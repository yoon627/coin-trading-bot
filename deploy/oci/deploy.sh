#!/usr/bin/env bash
set -euo pipefail

# Git Bash (MSYS2) 가 단일 슬래시 경로를 Windows path 로 자동 변환하는 동작 차단.
export MSYS_NO_PATHCONV=1

# ============================================================
# Coin Trading Bot - Oracle Cloud (OCI) 배포 스크립트
#
# VM.Standard.A1.Flex (2 OCPU ARM/Ampere, 12GB) + Docker Compose — Always Free 한도 내 운영 목표.
# 구성: caddy + app + PostgreSQL + Redis (AWS 판과 동일 스택·동일 메모리 제한).
# 이미지: GitHub Actions 가 multi-arch 로 GHCR 에 push → 인스턴스는 pull 만.
#
# AWS 판(deploy/aws/deploy.sh)과의 차이는 프로비저닝(setup/destroy)과 SSH 사용자(opc)뿐이며,
# 배포 로직(SHA 고정·자동 롤백·헬스체크)은 동일하다. 두 판의 공통 로직을 고칠 때는 양쪽에 함께 반영할 것.
#
# 사용법:
#   ./deploy/oci/deploy.sh setup    # 1회: VCN/subnet/NSG/버킷/IAM + A1.Flex 인스턴스 생성
#   ./deploy/oci/deploy.sh deploy   # GHCR 이미지 pull + compose 기동
#   ./deploy/oci/deploy.sh ssh      # 인스턴스 접속
#   ./deploy/oci/deploy.sh status   # 컨테이너 상태
#   ./deploy/oci/deploy.sh logs     # 앱 로그
#   ./deploy/oci/deploy.sh stop     # 전체 중지
#   ./deploy/oci/deploy.sh start    # 전체 시작
#   ./deploy/oci/deploy.sh destroy  # 전체 삭제
#
# setup 은 단계별 재진입 가능하다 — capacity 부족 등으로 중단되면 같은 명령을 다시 실행하면
# 이미 만들어진 리소스는 건너뛰고 남은 단계부터 이어서 진행한다.
# ============================================================

# 이 스크립트가 만드는 파일(.env 갱신·.state·임시 env)은 전부 시크릿을 담는다.
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="$SCRIPT_DIR/.state"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.prod.yml"

ENV_FILE="$SCRIPT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
    echo "ERROR: $ENV_FILE not found. Copy .env.example and fill in values:"
    echo "  install -m 600 $SCRIPT_DIR/.env.example $ENV_FILE"
    exit 1
fi
# .env 에는 DB/JWT/암호화 키와 GHCR PAT 가 들어간다. cp 로 만들면 umask 에 따라 0644 가 되므로 강제한다.
chmod 600 "$ENV_FILE" 2>/dev/null || true
source "$ENV_FILE"

OCI_REGION="${OCI_REGION:-ap-seoul-1}"
# OCI CLI 가 읽는 변수는 OCI_CLI_REGION 이다. .env 의 OCI_REGION 만으로는 CLI 가 로컬 config 의
# 기본 리전을 쓰게 되어, 의도와 다른 리전에 리소스가 생길 수 있다.
export OCI_CLI_REGION="$OCI_REGION"
APP_NAME="${APP_NAME:-coin-trading-bot}"
OCPUS="${OCPUS:-2}"
MEMORY_GB="${MEMORY_GB:-12}"
# Always Free 인스턴스는 최소 ~47GB 부트볼륨이 필요하고, boot+block 합계 무료 한도는 200GB.
BOOT_VOLUME_GB="${BOOT_VOLUME_GB:-50}"
SHAPE="${SHAPE:-VM.Standard.A1.Flex}"
GHCR_IMAGE="${GHCR_IMAGE:-ghcr.io/yoon627/coin-trading-bot}"
SSH_USER="${SSH_USER:-opc}"                      # Oracle Linux 기본 사용자 (Ubuntu 이미지면 ubuntu)
KEY_NAME="${APP_NAME}-key"
KEY_PEM="$SCRIPT_DIR/${KEY_NAME}.pem"
KEY_PUB="$SCRIPT_DIR/${KEY_NAME}.pub"

# capacity 재시도 상한 (무한 루프 금지 — bounded)
CAPACITY_MAX_ATTEMPTS="${CAPACITY_MAX_ATTEMPTS:-30}"
CAPACITY_MAX_SECONDS="${CAPACITY_MAX_SECONDS:-3600}"

save_state() { echo "$1=$2" >> "$STATE_FILE"; }
# 값이 바뀌는 키(LAST_GOOD_SHA 등) 갱신용 — 기존 라인 제거 후 재기록해 .state 증식을 막는다.
update_state() {
    local key="$1" val="$2"
    if [[ -f "$STATE_FILE" ]]; then
        grep -v "^${key}=" "$STATE_FILE" > "$STATE_FILE.tmp" || true
        mv "$STATE_FILE.tmp" "$STATE_FILE"
    fi
    echo "${key}=${val}" >> "$STATE_FILE"
}
load_state() { if [[ -f "$STATE_FILE" ]]; then source "$STATE_FILE"; fi; }
load_state
log() { echo -e "\n=== $1 ==="; }

require_oci() {
    command -v oci >/dev/null || { echo "ERROR: oci CLI 없음. 설치 후 'oci setup config'."; exit 1; }
    command -v jq  >/dev/null || { echo "ERROR: jq 필요 (OCI CLI 응답 파싱)."; exit 1; }
    oci iam region-subscription list >/dev/null 2>&1 || {
        echo "ERROR: OCI 자격증명 미설정/무효. 'oci setup config' 먼저 실행."; exit 1; }

    # 홈 리전 검증 — Always Free 컴퓨트는 **홈 리전에서만** 생성 가능하고, 홈 리전은 사후 변경이
    # 사실상 불가능하다. 여기서 걸러야 리소스를 만든 뒤 인스턴스 단계에서 실패하는 낭비를 막는다.
    local home
    home="$(oci iam region-subscription list \
        --query 'data[?"is-home-region"]."region-name" | [0]' --raw-output 2>/dev/null || true)"
    if [[ -z "$home" || "$home" == "null" ]]; then
        echo "ERROR: 홈 리전 조회 실패. OCI 자격증명/권한을 확인하세요."; exit 1
    fi
    if [[ "$home" != "$OCI_REGION" ]]; then
        echo "ERROR: 테넌시 홈 리전이 '$home' 인데 배포 대상은 '$OCI_REGION' 입니다."
        echo "  Always Free 컴퓨트는 홈 리전에서만 만들 수 있습니다."
        echo "  - 홈 리전이 ap-chuncheon-1(춘천)이면 Ampere A1 자체를 만들 수 없습니다(공식 문서 명시)."
        echo "  - .env 의 OCI_REGION 을 '$home' 로 바꾸거나, 홈 리전이 서울인 테넌시를 사용하세요."
        exit 1
    fi

    # tenancy OCID. region-subscription 응답에는 tenancy 가 들어있지 않으므로(필드는 region-key/
    # region-name/is-home-region/status 뿐) .env → 환경변수 → ~/.oci/config 순으로 찾는다.
    TENANCY_ID="${TENANCY_ID:-${OCI_CLI_TENANCY:-}}"
    if [[ -z "$TENANCY_ID" ]]; then
        local cfg="${OCI_CLI_CONFIG_FILE:-$HOME/.oci/config}"
        [[ -f "$cfg" ]] && TENANCY_ID="$(grep -m1 '^[[:space:]]*tenancy[[:space:]]*=' "$cfg" \
            | cut -d= -f2- | tr -d '[:space:]')"
    fi
    if [[ -z "$TENANCY_ID" ]]; then
        echo "ERROR: tenancy OCID 를 찾지 못했습니다."
        echo "  .env 에 TENANCY_ID=ocid1.tenancy... 를 적거나 OCI_CLI_TENANCY 를 export 하세요."
        exit 1
    fi
    # compartment 미지정이면 루트(=tenancy) 사용.
    COMPARTMENT_ID="${COMPARTMENT_ID:-$TENANCY_ID}"
}

# 비어있는 시크릿을 1회 생성해 .env 에 영속화.
#
# ⚠️ APP_ENCRYPTION_SECRET 은 **생성하지 않고 실패시킨다**. AWS 에서 이전해 오는 DB 에는 이 키로
# 암호화된 Upbit API 키가 들어있어, 여기서 새 키를 만들면 앱은 정상 기동하면서 저장된 거래소 키만
# 조용히 복호화 불능이 된다(가장 위험한 실패 모드). 반드시 AWS 쪽 값을 그대로 옮겨 적어야 한다.
ensure_secrets() {
    command -v openssl >/dev/null || { echo "ERROR: openssl 필요"; exit 1; }
    if [[ -z "${APP_ENCRYPTION_SECRET:-}" ]]; then
        echo "ERROR: APP_ENCRYPTION_SECRET 이 비어 있습니다 — 자동 생성하지 않습니다."
        echo "  이 값은 저장된 Upbit API 키를 복호화하는 AES 키입니다. 새로 만들면 기존 키가 모두 무효화됩니다."
        echo "  AWS 쪽 deploy/aws/.env 의 APP_ENCRYPTION_SECRET 값을 $ENV_FILE 에 그대로 복사하세요."
        echo "  (신규 구축이라 이전할 데이터가 없다면 openssl rand -base64 48 로 만든 값을 직접 넣으세요.)"
        exit 1
    fi
    local appended=""
    if [[ -z "${DB_PASSWORD:-}" ]]; then
        DB_PASSWORD="$(openssl rand -hex 16)"; appended+=$'\n'"DB_PASSWORD=$DB_PASSWORD"
    fi
    if [[ -z "${JWT_SECRET:-}" ]]; then
        JWT_SECRET="$(openssl rand -base64 48)"; appended+=$'\n'"JWT_SECRET=$JWT_SECRET"
    fi
    if [[ -n "$appended" ]]; then
        printf '%s\n' "$appended" >> "$ENV_FILE"
        log "시크릿 생성 → $ENV_FILE 에 저장 (백업 권장)"
    fi
}

# AWS 판과 동일 계약: 서버로 올릴 app 전용 .env 렌더 (OCI 자격증명 등은 제외).
render_server_env() {
    local domain="${APP_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
    cat > "$1" <<EOF
APP_VERSION=${APP_VERSION:-latest}
UPBIT_ACCESS_KEY=${UPBIT_ACCESS_KEY:-}
UPBIT_SECRET_KEY=${UPBIT_SECRET_KEY:-}
TRADING_TICKERS=${TRADING_TICKERS:-KRW-BTC}
TRADING_STRATEGY=${TRADING_STRATEGY:-combined}
TRADING_AUTO_START=${TRADING_AUTO_START:-false}
TRADING_TAKE_PROFIT_PCT=${TRADING_TAKE_PROFIT_PCT:-2.0}
TRADING_MAX_LOSS_PCT=${TRADING_MAX_LOSS_PCT:-5.0}
TRADING_TRAILING_STOP_PCT=${TRADING_TRAILING_STOP_PCT:-2.0}
TRADING_TRAILING_ARM_PCT=${TRADING_TRAILING_ARM_PCT:-0.0}
TRADING_MAX_HOLD_DAYS=${TRADING_MAX_HOLD_DAYS:-1}
TRADING_CHART_EXIT_ENABLED=${TRADING_CHART_EXIT_ENABLED:-false}
TRADING_ROUND_TRIP_FEE_RATE=${TRADING_ROUND_TRIP_FEE_RATE:-0.001}
DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL:-}
DISCORD_ERROR_ALERT_ENABLED=${DISCORD_ERROR_ALERT_ENABLED:-false}
DISCORD_ERROR_WEBHOOK_URL=${DISCORD_ERROR_WEBHOOK_URL:-}
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
APP_ENCRYPTION_SECRET=${APP_ENCRYPTION_SECRET}
APP_AUTH_COOKIE_FORCE_INSECURE=${APP_AUTH_COOKIE_FORCE_INSECURE:-false}
APP_DOMAIN=${domain}
BACKUP_BUCKET=${BACKUP_BUCKET:-}
BACKUP_PREFIX=${BACKUP_PREFIX:-db-backups}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
OCI_REGION=${OCI_REGION}
EOF
    chmod 600 "$1"
}

# ── SSH 키 ──
# OCI 는 AWS 처럼 키페어를 클라우드 리소스로 만들지 않는다. 로컬에서 만들어 공개키를 인스턴스
# 메타데이터로 넣는다.
ensure_ssh_key() {
    if [[ -f "$KEY_PEM" && -f "$KEY_PUB" ]]; then
        echo "  기존 키 사용: $KEY_PEM"; return
    fi
    # 개인키만 남아 있으면(공개키 분실) 공개키를 파생한다 — 같은 경로로 ssh-keygen 을 다시 돌리면
    # overwrite 프롬프트에 걸리거나 기존 키를 덮어써 인스턴스 접속이 영구히 막힌다.
    if [[ -f "$KEY_PEM" ]]; then
        log "공개키 재생성 (개인키로부터 파생)"
        ssh-keygen -y -f "$KEY_PEM" > "$KEY_PUB"
        echo "  복구: $KEY_PUB"; return
    fi
    if [[ -f "$KEY_PUB" ]]; then
        echo "ERROR: 공개키($KEY_PUB)만 있고 개인키($KEY_PEM)가 없습니다 — 접속 불가 상태입니다."
        echo "  개인키를 복구하거나, 두 파일을 모두 지운 뒤 새 인스턴스를 만드세요."
        exit 1
    fi
    log "SSH 키 생성"
    ssh-keygen -t ed25519 -N "" -f "$KEY_PEM" -C "${APP_NAME}" >/dev/null
    # ssh-keygen 은 <file> / <file>.pub 로 만든다 — .pem/.pub 규약에 맞춰 이름 정리.
    mv "${KEY_PEM}.pub" "$KEY_PUB"
    chmod 400 "$KEY_PEM"
    echo "  생성: $KEY_PEM (백업 권장 — 분실 시 인스턴스 접속 불가)"
}

# ── 프로비저닝 헬퍼 ──
# 모든 단계는 .state 에 OCID 가 이미 있으면 건너뛴다(재진입 가능).

setup_network() {
    if [[ -z "${VCN_ID:-}" ]]; then
        log "VCN 생성"
        VCN_ID="$(oci network vcn create -c "$COMPARTMENT_ID" \
            --cidr-blocks '["10.0.0.0/16"]' --display-name "${APP_NAME}-vcn" --dns-label "ctbvcn" \
            --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
        save_state VCN_ID "$VCN_ID"
    fi

    if [[ -z "${IGW_ID:-}" ]]; then
        log "Internet Gateway 생성"
        IGW_ID="$(oci network internet-gateway create -c "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
            --is-enabled true --display-name "${APP_NAME}-igw" \
            --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
        save_state IGW_ID "$IGW_ID"
    fi

    if [[ -z "${RT_ID:-}" ]]; then
        log "기본 라우트 테이블에 0.0.0.0/0 → IGW 추가"
        RT_ID="$(oci network vcn get --vcn-id "$VCN_ID" --query 'data."default-route-table-id"' --raw-output)"
        oci network route-table update --rt-id "$RT_ID" --force \
            --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$IGW_ID\"}]" \
            >/dev/null
        save_state RT_ID "$RT_ID"
    fi

    # ⚠️ 전용 security list 를 반드시 만들어 subnet 에 지정한다.
    # 지정하지 않으면 VCN **기본** security list 가 붙는데, 기본 list 에는 0.0.0.0/0 → TCP/22 가 들어있다.
    # security list 와 NSG 는 교집합이 아니라 **합집합**이라, NSG 에서 SSH 를 /32 로 좁혀도
    # 기본 list 때문에 SSH 가 전 세계에 열린 채로 남는다. 여기서는 규칙이 빈 list 를 쓰고
    # 모든 허용은 NSG 에만 둔다.
    if [[ -z "${SECLIST_ID:-}" ]]; then
        log "전용 Security List 생성 (빈 규칙 — 허용은 NSG 로만)"
        SECLIST_ID="$(oci network security-list create -c "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
            --display-name "${APP_NAME}-seclist" \
            --ingress-security-rules '[]' --egress-security-rules '[]' \
            --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
        save_state SECLIST_ID "$SECLIST_ID"
    fi

    if [[ -z "${SUBNET_ID:-}" ]]; then
        log "Subnet 생성 (regional)"
        SUBNET_ID="$(oci network subnet create -c "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
            --cidr-block "10.0.1.0/24" --display-name "${APP_NAME}-subnet" --dns-label "ctbsub" \
            --route-table-id "$RT_ID" --security-list-ids "[\"$SECLIST_ID\"]" \
            --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
        save_state SUBNET_ID "$SUBNET_ID"
    fi
}

# NSG 를 쓰는 이유(security list 대신): security list 는 subnet 의 모든 VNIC 에 적용되고 VCN 기본
# security list 와 **합집합**으로 동작해, 여기서 SSH 를 좁혀도 기본 list 의 넓은 규칙이 남으면
# 제한이 무력화된다. NSG 는 AWS security group 과 같은 "리소스에 붙는" 의미를 가진다.
setup_nsg() {
    if [[ -z "${NSG_ID:-}" ]]; then
        log "NSG 생성"
        NSG_ID="$(oci network nsg create -c "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
            --display-name "${APP_NAME}-nsg" \
            --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
        save_state NSG_ID "$NSG_ID"
    fi

    # SSH 허용 CIDR: 미지정이면 현재 공인 IP/32 로 자동 제한 (0.0.0.0/0 지양).
    local ssh_cidr="${SSH_ALLOW_CIDR:-}"
    if [[ -z "$ssh_cidr" ]]; then
        local myip; myip="$(curl -s --max-time 10 https://checkip.amazonaws.com || true)"
        myip="$(printf '%s' "$myip" | tr -d '[:space:]')"
        [[ -n "$myip" ]] && ssh_cidr="${myip}/32" || {
            echo "ERROR: 공인 IP 감지 실패. .env 에 SSH_ALLOW_CIDR 설정."; exit 1; }
    fi
    local app_cidr="${APP_ALLOW_CIDR:-0.0.0.0/0}"
    log "NSG 규칙: SSH=$ssh_cidr / 443=$app_cidr / 80=0.0.0.0/0(ACME)"

    # `nsg rules add` 는 규칙을 **추가**한다. "규칙이 하나라도 있으면 통과" 로 판정하면 SSH CIDR 이
    # 바뀌었을 때(집↔카페 등) 옛 규칙이 남아 접근 범위가 넓어진 채 유지된다. 그래서 이 스크립트가
    # 관리하는 규칙(description 이 "ctb-" 로 시작)만 걷어내고 매번 다시 넣어 멱등성을 보장한다.
    # 사람이 직접 추가한 규칙은 prefix 가 다르므로 건드리지 않는다.
    local old_ids
    old_ids="$(oci network nsg rules list --nsg-id "$NSG_ID" 2>/dev/null \
        | jq -r '[.data[] | select((.description // "") | startswith("ctb-")) | .id] | @json' 2>/dev/null || echo '[]')"
    if [[ -n "$old_ids" && "$old_ids" != "[]" && "$old_ids" != "null" ]]; then
        echo "  기존 관리 규칙 교체"
        oci network nsg rules remove --nsg-id "$NSG_ID" --security-rule-ids "$old_ids" >/dev/null
    fi

    # egress 전체 허용은 필수: GHCR pull, Let's Encrypt(ACME), 업비트 API, Object Storage 백업이 모두 아웃바운드.
    oci network nsg rules add --nsg-id "$NSG_ID" --security-rules "$(cat <<JSON
[
  {"direction":"INGRESS","protocol":"6","source":"$ssh_cidr","sourceType":"CIDR_BLOCK","isStateless":false,
   "tcpOptions":{"destinationPortRange":{"min":22,"max":22}},"description":"ctb-ssh"},
  {"direction":"INGRESS","protocol":"6","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK","isStateless":false,
   "tcpOptions":{"destinationPortRange":{"min":80,"max":80}},"description":"ctb-acme-http01"},
  {"direction":"INGRESS","protocol":"6","source":"$app_cidr","sourceType":"CIDR_BLOCK","isStateless":false,
   "tcpOptions":{"destinationPortRange":{"min":443,"max":443}},"description":"ctb-https"},
  {"direction":"EGRESS","protocol":"all","destination":"0.0.0.0/0","destinationType":"CIDR_BLOCK","isStateless":false,
   "description":"ctb-egress"}
]
JSON
)" >/dev/null
    echo "  NSG 규칙 적용 완료"
}

# 백업용 Object Storage 버킷 + instance principal 권한.
#
# instance principal 은 옵션 하나로 권한이 생기지 않는다 — 인스턴스를 dynamic group 에 넣고,
# 그 그룹에 policy 로 권한을 줘야 한다. 이 둘은 **테넌시(루트) 레벨** 리소스라 루트 권한이 필요하다.
# 권한이 없으면 백업만 비활성화되고 배포 자체는 계속된다(백업은 선택 기능).
#
# ⚠️ 인스턴스 생성 **뒤에** 호출해야 한다 — matching rule 을 이 인스턴스 하나로 좁히기 위해
# INSTANCE_ID 가 필요하다. compartment 단위로 매칭하면 (기본이 루트 compartment 이므로) 테넌시의
# 모든 인스턴스가 백업 버킷을 읽고 지울 수 있게 된다.
setup_backup_iam() {
    [[ -z "${BACKUP_BUCKET:-}" ]] && { echo "  BACKUP_BUCKET 미설정 → 백업 리소스 생성 생략"; return; }

    local dg_name="${APP_NAME}-dg" pol_name="${APP_NAME}-backup-policy"

    if [[ -z "${BUCKET_CREATED:-}" ]]; then
        log "Object Storage 버킷: $BACKUP_BUCKET"
        # 버전닝은 Disabled 로 둔다. 켜면 backup.sh 의 보존 정리가 delete marker 만 남기고 이전 버전이
        # 계속 용량을 차지해, 14일 정책이 Always Free 20GB 한도를 전혀 회수하지 못한다.
        # (백업 키는 타임스탬프로 고유하고 --no-overwrite 를 쓰므로 덮어쓰기 사고 위험도 없다.)
        if oci os bucket create -c "$COMPARTMENT_ID" --name "$BACKUP_BUCKET" \
             --public-access-type NoPublicAccess --versioning Disabled >/dev/null 2>&1; then
            echo "  생성 완료"
        else
            if oci os bucket get --name "$BACKUP_BUCKET" >/dev/null 2>&1; then
                echo "  기존 버킷 사용"
            else
                echo "WARN: 버킷 생성/조회 실패 — 백업이 동작하지 않습니다. 권한을 확인하세요."
                return
            fi
        fi
        save_state BUCKET_CREATED "1"
    fi

    # ── dynamic group (policy 와 독립 단계) ──
    if [[ -z "${DYNAMIC_GROUP_ID:-}" ]]; then
        log "Dynamic Group (이 인스턴스만 매칭)"
        local dg_id=""
        dg_id="$(oci iam dynamic-group create --name "$dg_name" \
            --description "Instance allowed to write ${APP_NAME} DB backups" \
            --matching-rule "ALL {instance.id = '$INSTANCE_ID'}" \
            --query 'data.id' --raw-output 2>/dev/null || true)"
        if [[ -z "$dg_id" || "$dg_id" == "null" ]]; then
            dg_id="$(oci iam dynamic-group list --all \
                --query "data[?name=='$dg_name'].id | [0]" --raw-output 2>/dev/null || true)"
        fi
        if [[ -z "$dg_id" || "$dg_id" == "null" ]]; then
            echo "WARN: dynamic group 생성/조회 실패 — 백업이 동작하지 않습니다."
            echo "  루트 권한이 없거나 Identity Domains 테넌시라 레거시 IAM API 가 막혔을 수 있습니다."
            echo "  콘솔에서 아래를 수동 생성하세요:"
            echo "    dynamic group '$dg_name' : ALL {instance.id = '$INSTANCE_ID'}"
            echo "    Allow dynamic-group $dg_name to read buckets in tenancy where target.bucket.name='$BACKUP_BUCKET'"
            echo "    Allow dynamic-group $dg_name to manage objects in tenancy where target.bucket.name='$BACKUP_BUCKET'"
            return
        fi
        save_state DYNAMIC_GROUP_ID "$dg_id"
    fi

    # ── policy (별도 단계 — dynamic group 은 만들어졌는데 policy 만 실패한 상태로 재실행해도 복구된다) ──
    if [[ -z "${POLICY_ID:-}" ]]; then
        log "Policy (버킷 한정 최소권한)"
        local pol_id=""
        pol_id="$(oci iam policy create -c "$TENANCY_ID" --name "$pol_name" \
            --description "Allow ${APP_NAME} instance to write DB backups" \
            --statements "[\"Allow dynamic-group $dg_name to read buckets in tenancy where target.bucket.name='$BACKUP_BUCKET'\",\"Allow dynamic-group $dg_name to manage objects in tenancy where target.bucket.name='$BACKUP_BUCKET'\"]" \
            --query 'data.id' --raw-output 2>/dev/null || true)"
        if [[ -z "$pol_id" || "$pol_id" == "null" ]]; then
            pol_id="$(oci iam policy list -c "$TENANCY_ID" --all \
                --query "data[?name=='$pol_name'].id | [0]" --raw-output 2>/dev/null || true)"
        fi
        if [[ -n "$pol_id" && "$pol_id" != "null" ]]; then
            save_state POLICY_ID "$pol_id"
            echo "  policy 확보 완료"
        else
            echo "WARN: policy 생성/조회 실패 — 백업이 권한 없이 남습니다(위 수동 생성 안내 참고)."
        fi
    fi
}

# Oracle Linux 9 (ARM) 최신 플랫폼 이미지 OCID. 이름 패턴이 아니라 shape 호환 조회 결과를 쓴다.
resolve_image() {
    if [[ -n "${IMAGE_ID:-}" ]]; then return; fi
    log "이미지 조회 (Oracle Linux 9, $SHAPE)"
    IMAGE_ID="$(oci compute image list -c "$COMPARTMENT_ID" \
        --operating-system "Oracle Linux" --operating-system-version "9" \
        --shape "$SHAPE" --lifecycle-state AVAILABLE \
        --sort-by TIMECREATED --sort-order DESC --limit 1 \
        --query 'data[0].id' --raw-output)"
    [[ -z "$IMAGE_ID" || "$IMAGE_ID" == "null" ]] && {
        echo "ERROR: $SHAPE 호환 Oracle Linux 9 이미지를 찾지 못했습니다."; exit 1; }
    save_state IMAGE_ID "$IMAGE_ID"
    echo "  $IMAGE_ID"
}

write_userdata() {
    cat > "$1" <<'UDEOF'
#!/bin/bash
set -ex
# Oracle Linux 9 은 기본 repo 에 docker 가 없다(podman 계열) → docker CE repo 를 추가한다.
dnf install -y dnf-plugins-core
dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable docker && systemctl start docker
usermod -aG docker opc

# OCI 의 Oracle Linux 이미지는 호스트 방화벽이 켜져 있어 NSG 만 열어도 트래픽이 막힌다.
# 80/443 을 호스트에서도 열어야 Caddy 의 ACME 발급과 HTTPS 접속이 동작한다.
if systemctl is-active --quiet firewalld; then
    firewall-cmd --permanent --add-port=80/tcp
    firewall-cmd --permanent --add-port=443/tcp
    firewall-cmd --reload
else
    # firewalld 미사용 이미지 대비 — iptables 규칙이 기본 적용된 경우를 함께 처리.
    iptables -I INPUT -p tcp --dport 80  -j ACCEPT || true
    iptables -I INPUT -p tcp --dport 443 -j ACCEPT || true
    (command -v netfilter-persistent >/dev/null && netfilter-persistent save) || \
      (command -v service >/dev/null && service iptables save) || true
fi

# 백업 스크립트가 쓰는 OCI CLI(instance principal 인증 — 키 배포 불필요)와 jq.
# Oracle Linux 9 공식 경로는 developer repo 의 python39-oci-cli 다.
dnf install -y oraclelinux-developer-release-el9 || true
dnf install -y python39-oci-cli || dnf install -y python3-oci-cli || true
dnf install -y jq || true

mkdir -p /opt/app
chown -R opc:opc /opt/app

# 마커는 **의존성이 실제로 준비된 뒤에만** 남긴다. 마커가 곧 "배포해도 좋다" 는 신호인데,
# docker 없이 마커가 생기면 deploy 가 준비된 줄 알고 진행해 엉뚱한 곳에서 실패한다.
command -v docker >/dev/null || { echo "FATAL: docker 설치 실패"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "FATAL: docker compose plugin 없음"; exit 1; }
# oci/jq 는 백업 전용이라 없으면 경고만 남긴다(배포 자체는 가능). backup.sh 가 자체적으로 재확인한다.
command -v oci >/dev/null || echo "WARN: oci CLI 미설치 — DB 백업이 동작하지 않습니다."
command -v jq  >/dev/null || echo "WARN: jq 미설치 — 백업 보존 정리가 동작하지 않습니다."

touch /opt/app/.userdata-done
UDEOF
}

# capacity 부족은 Always Free ARM 에서 상시 발생한다. 다만 재시도는 반드시 상한이 있어야 하고,
# capacity 이외의 오류(인증·quota·shape/image 불일치)는 재시도해도 낭비이므로 즉시 실패시킨다.
is_capacity_error() {
    printf '%s' "$1" | grep -qiE "out of host capacity|outofcapacity|out of capacity|insufficient (host )?capacity"
}

# 같은 표시이름의 인스턴스가 이미 있으면 재사용한다. CLI 응답만 유실된 경우(네트워크 오류 등)
# 재시도가 인스턴스를 중복 생성하는 것을 막는다.
find_existing_instance() {
    oci compute instance list -c "$COMPARTMENT_ID" --display-name "${APP_NAME}" --all \
        --query "data[?\"lifecycle-state\"=='RUNNING' || \"lifecycle-state\"=='PROVISIONING' || \"lifecycle-state\"=='STARTING'].id | [0]" \
        --raw-output 2>/dev/null || true
}

launch_instance() {
    if [[ -n "${INSTANCE_ID:-}" ]]; then echo "  기존 인스턴스 사용: $INSTANCE_ID"; return; fi

    local existing; existing="$(find_existing_instance)"
    if [[ -n "$existing" && "$existing" != "null" ]]; then
        echo "  같은 이름의 기존 인스턴스 발견 — 재사용: $existing"
        INSTANCE_ID="$existing"; save_state INSTANCE_ID "$INSTANCE_ID"; return
    fi

    # 이 함수는 실패 시 return 이 아니라 exit 하는 경로가 있어 RETURN trap 으로는 정리되지 않는다.
    # EXIT trap 으로 걸어 어느 경로로 끝나도 임시 userdata 가 남지 않게 한다.
    local ud; ud="$(mktemp)"; write_userdata "$ud"
    # shellcheck disable=SC2064  # 지금 값으로 고정해 정리하는 것이 의도
    trap "rm -f '$ud'" EXIT

    # 서울 리전의 실제 AD 목록을 조회해 순환한다(AD 마다 용량이 다르다).
    # mapfile 은 bash 4+ 전용이라 쓰지 않는다 — macOS 기본 bash 는 3.2 라 런타임에 깨진다.
    local ads=() line
    while IFS= read -r line; do
        [[ -n "$line" ]] && ads+=("$line")
    done < <(oci iam availability-domain list -c "$COMPARTMENT_ID" 2>/dev/null | jq -r '.data[].name')
    [[ ${#ads[@]} -eq 0 ]] && { echo "ERROR: availability domain 조회 실패"; exit 1; }

    log "인스턴스 생성 ($SHAPE, ${OCPUS} OCPU / ${MEMORY_GB}GB, boot ${BOOT_VOLUME_GB}GB)"
    echo "  AD ${#ads[@]}개 순환 · 최대 ${CAPACITY_MAX_ATTEMPTS}회 / ${CAPACITY_MAX_SECONDS}초"

    local start_ts attempt=0 delay=30 out rc ad
    start_ts="$(date +%s)"
    while (( attempt < CAPACITY_MAX_ATTEMPTS )); do
        # `(( attempt++ ))` 는 증가 **전** 값을 반환한다 — 첫 회차엔 0 이라 산술식의 exit status 가 1 이
        # 되고 `set -e` 가 여기서 스크립트를 죽인다(launch 를 한 번도 호출하지 못한다).
        attempt=$(( attempt + 1 ))
        ad="${ads[$(( (attempt - 1) % ${#ads[@]} ))]}"
        set +e
        out="$(oci compute instance launch -c "$COMPARTMENT_ID" \
            --availability-domain "$ad" \
            --shape "$SHAPE" \
            --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY_GB}" \
            --image-id "$IMAGE_ID" \
            --subnet-id "$SUBNET_ID" \
            --nsg-ids "[\"$NSG_ID\"]" \
            --assign-public-ip false \
            --boot-volume-size-in-gbs "$BOOT_VOLUME_GB" \
            --display-name "$APP_NAME" \
            --ssh-authorized-keys-file "$KEY_PUB" \
            --user-data-file "$ud" \
            --wait-for-state RUNNING \
            --query 'data.id' --raw-output 2>&1)"
        rc=$?
        set -e

        if [[ $rc -eq 0 ]]; then
            INSTANCE_ID="$(printf '%s' "$out" | tail -1 | tr -d '[:space:]')"
            save_state INSTANCE_ID "$INSTANCE_ID"
            echo "  생성 완료: $INSTANCE_ID (AD=$ad, 시도 ${attempt}회)"
            return
        fi

        if ! is_capacity_error "$out"; then
            echo "ERROR: 인스턴스 생성 실패 (capacity 문제가 아님 — 재시도하지 않음):" >&2
            printf '%s\n' "$out" | tail -20 >&2
            exit 1
        fi

        # 응답 유실 후 실제로는 생성됐을 수 있으므로 재시도 전에 확인한다.
        existing="$(find_existing_instance)"
        if [[ -n "$existing" && "$existing" != "null" ]]; then
            echo "  재시도 전 확인: 인스턴스가 이미 생성돼 있음 — 재사용: $existing"
            INSTANCE_ID="$existing"; save_state INSTANCE_ID "$INSTANCE_ID"; return
        fi

        local elapsed=$(( $(date +%s) - start_ts ))
        if (( elapsed >= CAPACITY_MAX_SECONDS )); then break; fi

        # exponential backoff + jitter (상한 300초). 동시 재시도 쏠림을 흩는다.
        local jitter=$(( RANDOM % 15 ))
        echo "  [${attempt}/${CAPACITY_MAX_ATTEMPTS}] AD=$ad 용량 부족 — $(( delay + jitter ))초 후 재시도 (경과 ${elapsed}s)"
        sleep $(( delay + jitter ))
        delay=$(( delay * 2 )); (( delay > 300 )) && delay=300
    done

    echo "ERROR: Always Free 용량을 확보하지 못했습니다 (시도 ${attempt}회)."
    echo "  - 시간대를 바꿔 재시도하거나(용량은 수시로 바뀝니다), 다른 AD/리전을 고려하세요."
    echo "  - 이미 만들어진 네트워크 리소스는 유지됩니다. 같은 명령을 다시 실행하면 인스턴스 단계부터 이어집니다."
    exit 1
}

# 공인 IP: reserved 우선(인스턴스를 다시 만들어도 IP 유지 → sslip.io 도메인·업비트 IP 화이트리스트·
# TLS 인증서가 그대로 산다). 실패하면 ephemeral 로 폴백해 최소한 접속은 되게 한다.
assign_public_ip() {
    if [[ -n "${PUBLIC_IP:-}" ]]; then echo "  기존 공인 IP: $PUBLIC_IP"; return; fi

    local vnic_id priv_ip_id
    vnic_id="$(oci compute instance list-vnics --instance-id "$INSTANCE_ID" --query 'data[0].id' --raw-output)"
    priv_ip_id="$(oci network private-ip list --vnic-id "$vnic_id" --query 'data[0].id' --raw-output)"
    [[ -z "$priv_ip_id" || "$priv_ip_id" == "null" ]] && { echo "ERROR: private IP OCID 확인 실패"; exit 1; }
    save_state VNIC_ID "$vnic_id"

    # 이전 실행이 IP 를 만든 뒤 주소 조회 단계에서 죽었을 수 있다. 그대로 새로 만들려 하면
    # "이미 공인 IP 가 할당됨" 으로 거부되므로, 기존 할당을 먼저 찾아 재사용한다.
    if [[ -n "${PUBLIC_IP_ID:-}" ]]; then
        local existing_ip
        existing_ip="$(oci network public-ip get --public-ip-id "$PUBLIC_IP_ID" \
            --query 'data."ip-address"' --raw-output 2>/dev/null || true)"
        if [[ -n "$existing_ip" && "$existing_ip" != "null" ]]; then
            PUBLIC_IP="$existing_ip"; update_state PUBLIC_IP "$PUBLIC_IP"
            echo "  기존 공인 IP 재사용: $PUBLIC_IP"; return
        fi
    fi
    local assigned
    assigned="$(oci network public-ip get --private-ip-id "$priv_ip_id" \
        --query 'data."ip-address"' --raw-output 2>/dev/null || true)"
    if [[ -n "$assigned" && "$assigned" != "null" ]]; then
        PUBLIC_IP="$assigned"; update_state PUBLIC_IP "$PUBLIC_IP"
        echo "  이미 할당된 공인 IP 사용: $PUBLIC_IP"; return
    fi

    log "공인 IP 할당 (reserved 우선)"
    local ip="" pid=""
    set +e
    pid="$(oci network public-ip create -c "$COMPARTMENT_ID" --lifetime RESERVED \
        --private-ip-id "$priv_ip_id" --display-name "${APP_NAME}-ip" \
        --wait-for-state ASSIGNED --query 'data.id' --raw-output 2>/dev/null)"
    set -e
    if [[ -n "$pid" && "$pid" != "null" ]]; then
        save_state PUBLIC_IP_ID "$pid"
        ip="$(oci network public-ip get --public-ip-id "$pid" --query 'data."ip-address"' --raw-output)"
        echo "  reserved IP: $ip"
    else
        echo "  WARN: reserved 실패 → ephemeral 로 폴백"
        set +e
        pid="$(oci network public-ip create -c "$COMPARTMENT_ID" --lifetime EPHEMERAL \
            --private-ip-id "$priv_ip_id" --display-name "${APP_NAME}-ip" \
            --wait-for-state ASSIGNED --query 'data.id' --raw-output 2>/dev/null)"
        set -e
        [[ -n "$pid" && "$pid" != "null" ]] && {
            save_state PUBLIC_IP_ID "$pid"
            ip="$(oci network public-ip get --public-ip-id "$pid" --query 'data."ip-address"' --raw-output)"
            echo "  ephemeral IP: $ip"
        }
    fi
    [[ -z "$ip" || "$ip" == "null" ]] && { echo "ERROR: 공인 IP 할당 실패"; exit 1; }
    PUBLIC_IP="$ip"; save_state PUBLIC_IP "$PUBLIC_IP"

    # 부트볼륨 OCID 기록 — destroy 시 보존/삭제 판단에 쓴다.
    local bv
    bv="$(oci compute boot-volume-attachment list -c "$COMPARTMENT_ID" \
        --availability-domain "$(oci compute instance get --instance-id "$INSTANCE_ID" --query 'data."availability-domain"' --raw-output)" \
        --instance-id "$INSTANCE_ID" --query 'data[0]."boot-volume-id"' --raw-output 2>/dev/null || true)"
    [[ -n "$bv" && "$bv" != "null" ]] && save_state BOOT_VOLUME_ID "$bv"
}

# ── setup ──
do_setup() {
    require_oci
    ensure_secrets
    ensure_ssh_key
    setup_network
    setup_nsg
    resolve_image
    launch_instance
    assign_public_ip
    setup_backup_iam   # 인스턴스 뒤 — dynamic group 을 이 인스턴스 하나로 좁히려면 INSTANCE_ID 가 필요

    log "Setup 완료"
    echo "  공인 IP: $PUBLIC_IP"
    echo "  SSH:     ssh -i $KEY_PEM ${SSH_USER}@${PUBLIC_IP}"
    echo "  cloud-init(도커 설치) 완료까지 2~4분 대기 후: ./deploy/oci/deploy.sh deploy"
}

# StrictHostKeyChecking=accept-new: 최초 접속만 자동 수용하고, 이후 호스트키가 바뀌면 거부한다
# (=no 는 MITM 을 영구히 무시하므로 쓰지 않는다).
ssh_inst() {
    ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -i "$KEY_PEM" \
        "${SSH_USER}@${PUBLIC_IP}" "$@"
}

wait_for_docker() {
    log "인스턴스 준비 대기 (cloud-init + docker)"
    for _ in $(seq 1 40); do
        if ssh_inst 'test -f /opt/app/.userdata-done && docker info' >/dev/null 2>&1; then
            echo "준비 완료"; return 0
        fi
        sleep 10
    done
    echo "ERROR: docker 준비 타임아웃. cloud-init 로그 확인:"
    echo "  ./deploy/oci/deploy.sh ssh   후  sudo cat /var/log/cloud-init-output.log"
    exit 1
}

# sslip.io 도메인이 실제 공인 IP 로 resolve 되는지 확인. 불일치면 ACME 가 엉뚱한 호스트로 가 발급 실패.
preflight_domain() {
    local domain="${APP_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
    local resolved=""
    if command -v dig >/dev/null 2>&1; then
        resolved="$(dig +short A "$domain" 2>/dev/null | tail -1)"
    elif command -v nslookup >/dev/null 2>&1; then
        resolved="$(nslookup "$domain" 2>/dev/null | awk '/^Address/{a=$NF} END{print a}')"
    else
        echo "  도메인 preflight 스킵: dig/nslookup 없음 — $domain 의 A 레코드가 $PUBLIC_IP 인지 직접 확인"
        return
    fi
    if [[ -z "$resolved" ]]; then
        echo "WARN: $domain resolve 실패 (A 레코드 미설정?). ACME 발급 실패 가능."
    elif [[ "$resolved" != "$PUBLIC_IP" ]]; then
        echo "WARN: $domain → $resolved (인스턴스 IP=$PUBLIC_IP 와 불일치). ACME 발급 실패 가능."
    else
        echo "  도메인 preflight OK: $domain → $resolved"
    fi
}

# ── deploy ──
# 아래 배포 로직은 AWS 판과 동일한 계약이다(대상 SHA 고정 · migration 게이트 · 자동 롤백 · 헬스체크).
# 한쪽을 고치면 다른 쪽도 함께 고쳐야 한다.
do_deploy() {
    require_oci
    load_state
    [[ -z "${PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    ensure_secrets
    local domain="${APP_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"

    # CI 는 :latest 와 :<full-sha> 를 push. latest 대신 SHA 로 고정해 무엇을 배포/롤백하는지 재현 가능하게 한다.
    local repo_root; repo_root="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
    [[ -z "$repo_root" ]] && { echo "ERROR: git 저장소가 아님 — deploy.sh 는 repo 체크아웃 안에서 실행하세요."; exit 1; }
    git -C "$repo_root" fetch origin main --quiet 2>/dev/null || true
    local target_sha="${APP_VERSION:-}"
    if [[ -z "$target_sha" || "$target_sha" == "latest" ]]; then
        target_sha="$(git -C "$repo_root" rev-parse origin/main 2>/dev/null || true)"
        [[ -z "$target_sha" ]] && { echo "ERROR: 대상 SHA 확인 실패(git rev-parse origin/main). APP_VERSION=<sha> 로 지정하세요."; exit 1; }
    fi
    APP_VERSION="$target_sha"

    # 자동 롤백 게이트: 롤백 대상 유무 + DB migration 포함 여부.
    # migration 포함 배포가 실패하면 신규 스키마가 이미 적용됐을 수 있어 구버전 앱 자동 복귀는 위험 → 제외(수동).
    local last_good="${LAST_GOOD_SHA:-}"
    local migration_gate="rollback-ok"
    if [[ -z "$last_good" || "$last_good" == "$target_sha" ]]; then
        migration_gate="blocked"
    elif ! git -C "$repo_root" cat-file -e "${last_good}^{commit}" 2>/dev/null \
      || ! git -C "$repo_root" cat-file -e "${target_sha}^{commit}" 2>/dev/null; then
        migration_gate="blocked"
    elif [[ -n "$(git -C "$repo_root" diff --name-only "$last_good" "$target_sha" -- bot/src/main/resources/db/migration/)" ]]; then
        migration_gate="blocked"
    fi
    log "대상 SHA=${target_sha:0:12}  LAST_GOOD=${last_good:0:12}  자동롤백=$migration_gate"

    preflight_domain
    wait_for_docker

    log "설정 업로드"
    local tmp_env; tmp_env="$(mktemp)"
    # tmp_env 엔 시크릿 평문이 담긴다. scp 실패로 set -e 종료돼도 반드시 삭제되도록 EXIT trap.
    trap "rm -f '$tmp_env'" EXIT
    render_server_env "$tmp_env"
    ssh_inst 'mkdir -p /opt/app'
    local scp_opts=(-o StrictHostKeyChecking=accept-new -i "$KEY_PEM")
    scp "${scp_opts[@]}" "$COMPOSE_FILE"          "${SSH_USER}@${PUBLIC_IP}":/opt/app/docker-compose.yml
    scp "${scp_opts[@]}" "$SCRIPT_DIR/Caddyfile"  "${SSH_USER}@${PUBLIC_IP}":/opt/app/Caddyfile
    scp "${scp_opts[@]}" "$SCRIPT_DIR/backup.sh"  "${SSH_USER}@${PUBLIC_IP}":/opt/app/backup.sh
    scp "${scp_opts[@]}" "$tmp_env"               "${SSH_USER}@${PUBLIC_IP}":/opt/app/.env
    rm -f "$tmp_env"; trap - EXIT
    ssh_inst 'chmod +x /opt/app/backup.sh && chmod 600 /opt/app/.env'

    # GHCR 토큰은 배포 명령의 환경변수로 넘기지 않는다 — 로컬 ssh argv 와 원격 커맨드라인에 평문으로
    # 남아 같은 호스트의 다른 사용자가 `ps` 로 읽을 수 있다. 별도 호출의 stdin 으로만 흘린다.
    if [[ -n "${GHCR_TOKEN:-}" ]]; then
        log "GHCR 로그인 (토큰은 stdin 으로만 전달)"
        printf '%s' "$GHCR_TOKEN" | ssh_inst "docker login ghcr.io -u '${GHCR_USERNAME:-}' --password-stdin" >/dev/null
    fi

    log "컨테이너 배포 (GHCR pull, SHA=${target_sha:0:12})"
    local deploy_rc=0
    ssh_inst "APP_DOMAIN='$domain' GHCR_IMAGE='$GHCR_IMAGE' TARGET_SHA='$target_sha' LAST_GOOD_SHA='$last_good' MIGRATION_GATE='$migration_gate' bash -s" <<'REMOTE' || deploy_rc=$?
set -e
cd /opt/app
: "${TARGET_SHA:?TARGET_SHA 필요}"

# app 은 호스트에 8080 을 노출하지 않으므로(Caddy 경유) 컨테이너 내부에서 헬스 확인. 최대 36×5s=180s.
# `</dev/null` 필수: 이 스크립트는 ssh 가 `bash -s` 의 stdin(파이프)로 흘려보낸다. `exec -T` 는 TTY 만
# 끄고 stdin 은 attach 하므로, </dev/null 이 없으면 exec 가 파이프에 남은 나머지 스크립트를 drain 해
# 실패/롤백 경로가 사라진다.
health_ok() {
    for _ in $(seq 1 36); do
        if docker compose exec -T app curl -fsS http://localhost:8080/actuator/health </dev/null > /dev/null 2>&1; then
            return 0
        fi
        sleep 5
    done
    return 1
}

echo "배포: SHA=$TARGET_SHA"
docker compose pull
docker compose up -d --remove-orphans
echo "헬스체크 대기 (~180s)..."
if health_ok; then
    echo "App healthy! ($TARGET_SHA)"
    docker compose ps
    # Caddy TLS 종단 e2e: 도메인 SNI 로 로컬 Caddy(127.0.0.1:443)에 HTTPS 요청이 app 까지 닿는지 확인.
    tls_ok=false
    for _ in $(seq 1 18); do
        if curl -fsS --max-time 5 --resolve "${APP_DOMAIN}:443:127.0.0.1" \
            "https://${APP_DOMAIN}/actuator/health" > /dev/null 2>&1; then
            echo "HTTPS e2e OK: https://${APP_DOMAIN}"; tls_ok=true; break
        fi
        sleep 5
    done
    [ "$tls_ok" = "false" ] && { echo "WARN: HTTPS e2e 미확인 (인증서 발급 지연 가능). caddy 로그:"; docker compose logs --tail=60 caddy || true; }
    # 성공 정리: 현재 SHA(=다음 배포의 롤백 대상)와 :latest 만 남기고 이전 app 이미지 제거.
    docker images "$GHCR_IMAGE" --format '{{.Repository}}:{{.Tag}}' \
        | grep -vE ":(${TARGET_SHA}|latest)\$" | xargs -r docker rmi > /dev/null 2>&1 || true
    docker image prune -f > /dev/null 2>&1 || true
    exit 0
fi

echo "ERROR: 180s 내 헬스체크 실패 (SHA=$TARGET_SHA)"
docker compose ps || true
docker compose logs --tail=120 app || true

if [ "$MIGRATION_GATE" != "rollback-ok" ]; then
    if [ -z "$LAST_GOOD_SHA" ]; then
        echo "자동 롤백 제외: 직전 정상 배포(LAST_GOOD) 없음."
    else
        echo "자동 롤백 제외: DB migration 포함 배포 — 신규 스키마가 이미 적용됐을 수 있어 구버전 앱 자동 복귀는 위험."
    fi
    echo "--- 수동 개입 필요 ---"
    echo "  1) docker compose logs app  로 원인 확인"
    echo "  2) DB migration 적용 여부 점검, 필요 시 백업 복원"
    echo "  3) 수동 롤백: cd /opt/app && APP_VERSION=<이전정상SHA> docker compose up -d --pull missing"
    exit 1
fi

echo "자동 롤백 → LAST_GOOD_SHA=$LAST_GOOD_SHA"
if ! APP_VERSION="$LAST_GOOD_SHA" docker compose up -d --remove-orphans --pull missing; then
    echo "ERROR: 롤백 기동 실패 (LAST_GOOD 이미지 확보 불가). 수동 개입 필요."
    exit 3
fi
if health_ok; then
    sed -i "s|^APP_VERSION=.*|APP_VERSION=${LAST_GOOD_SHA}|" .env || true
    echo "롤백 성공: $LAST_GOOD_SHA 로 복구됨. 새 SHA($TARGET_SHA) 배포 실패 원인 조사 필요."
    docker compose ps || true
    exit 2
fi
echo "ERROR: 롤백 후에도 unhealthy. 수동 개입 필요."
docker compose logs --tail=120 app || true
exit 3
REMOTE

    echo ""
    case "$deploy_rc" in
        0)
            update_state LAST_GOOD_SHA "$target_sha"
            log "배포 완료 (SHA=${target_sha:0:12} — LAST_GOOD 갱신)"
            echo "  App: https://$domain  (Caddy 가 Let's Encrypt 인증서 발급까지 최대 ~30초)"
            ;;
        2)
            log "자동 롤백됨 → 이전 정상 SHA(${last_good:0:12}) 로 복구 (LAST_GOOD 유지)"
            echo "  원인 조사 후 재배포하세요. App: https://$domain"
            exit 2
            ;;
        *)
            echo "ERROR: 배포 실패 (rc=$deploy_rc) — 위 원격 로그/안내 참조. LAST_GOOD 미변경."
            exit 1
            ;;
    esac
}

# ── utils ──
do_ssh()    { load_state; ssh -o StrictHostKeyChecking=accept-new -i "$KEY_PEM" "${SSH_USER}@${PUBLIC_IP}"; }
do_status() { load_state; echo "공인 IP: ${PUBLIC_IP:-none}"; [[ -n "${PUBLIC_IP:-}" ]] && ssh_inst 'cd /opt/app && docker compose ps' 2>/dev/null || echo "(unreachable)"; }
do_logs()   { load_state; ssh_inst 'cd /opt/app && docker compose logs --tail=120 app'; }
do_stop()   { load_state; ssh_inst 'cd /opt/app && docker compose down && echo 중지'; }
do_start()  { load_state; ssh_inst 'cd /opt/app && docker compose up -d && echo 시작'; }

# ── destroy ──
do_destroy() {
    require_oci
    load_state
    echo "=== 모든 OCI 리소스 삭제 ==="
    echo "  인스턴스: ${INSTANCE_ID:-none}"
    echo "  부트볼륨: ${BOOT_VOLUME_ID:-none}"
    echo ""
    echo "⚠️  거래 이력과 (암호화된) Upbit 키는 부트볼륨의 Postgres 볼륨에 있습니다."
    echo "    삭제 전 백업을 확보했는지 확인하세요 — 부트볼륨까지 지우면 복구 불가입니다."
    read -rp "'yes' 입력: " confirm; [[ "$confirm" != "yes" ]] && { echo "취소"; exit 0; }

    local preserve_bv=true
    read -rp "부트볼륨도 함께 삭제할까요? (데이터 영구 소멸) [y/N]: " del_bv
    [[ "$del_bv" == "y" || "$del_bv" == "Y" ]] && preserve_bv=false
    echo "  부트볼륨 보존: $preserve_bv"

    # 삭제 전 최종 DB 백업 (AWS 판과 동일한 데이터 보호 계약).
    # 거래이력·암호화 Upbit 키가 볼륨과 함께 소멸할 수 있으므로 마지막 스냅샷을 남긴다.
    # 백업을 명시 설정(BACKUP_BUCKET)했는데 실패하면 destroy 를 중단한다.
    if [[ -n "${PUBLIC_IP:-}" && -n "${BACKUP_BUCKET:-}" ]]; then
        log "최종 DB 백업 → Object Storage"
        if ! ssh_inst "cd /opt/app && ./backup.sh"; then
            echo "ERROR: 최종 백업 실패 — 데이터 영구 소멸을 막기 위해 destroy 를 중단합니다."
            echo "  원인(백업 권한/DB/스크립트 부재) 해결 후 재시도하거나,"
            echo "  백업 없이 삭제하려면 deploy/oci/.env 의 BACKUP_BUCKET 을 비우고 다시 실행하세요."
            exit 1
        fi
    elif [[ -n "${PUBLIC_IP:-}" && "$preserve_bv" == "false" ]]; then
        echo "WARN: BACKUP_BUCKET 미설정 → 최종 DB 백업 생략. 거래이력·암호화키가 소멸됩니다."
        read -rp "그래도 진행하려면 'delete-my-data' 입력: " c2
        [[ "$c2" != "delete-my-data" ]] && { echo "취소"; exit 0; }
    fi

    local failed=0
    del() {  # label, then oci args...
        local label="$1"; shift
        if "$@" >/dev/null 2>&1; then echo "  삭제: $label"; else echo "  실패: $label" >&2; failed=1; fi
    }

    if [[ -n "${INSTANCE_ID:-}" ]]; then
        log "인스턴스 종료"
        del "instance" oci compute instance terminate --instance-id "$INSTANCE_ID" --force \
            --preserve-boot-volume "$preserve_bv" --wait-for-state TERMINATED
    fi
    # public IP: reserved 는 인스턴스와 독립 수명이라 명시 삭제해야 한다(ephemeral 은 VNIC 과 함께 소멸).
    [[ -n "${PUBLIC_IP_ID:-}" ]] && del "public-ip" oci network public-ip delete --public-ip-id "$PUBLIC_IP_ID" --force
    [[ -n "${NSG_ID:-}" ]]       && del "nsg"       oci network nsg delete --nsg-id "$NSG_ID" --force --wait-for-state TERMINATED
    [[ -n "${SUBNET_ID:-}" ]]    && del "subnet"    oci network subnet delete --subnet-id "$SUBNET_ID" --force --wait-for-state TERMINATED
    [[ -n "${SECLIST_ID:-}" ]]   && del "seclist"   oci network security-list delete --security-list-id "$SECLIST_ID" --force --wait-for-state TERMINATED

    # IGW 는 이를 가리키는 route rule 이 남아 있으면 삭제가 거부된다 — 먼저 route table 을 비운다.
    if [[ -n "${RT_ID:-}" ]]; then
        del "route-rules(비우기)" oci network route-table update --rt-id "$RT_ID" --force --route-rules '[]'
    fi
    [[ -n "${IGW_ID:-}" ]] && del "internet-gateway" oci network internet-gateway delete --ig-id "$IGW_ID" --force --wait-for-state TERMINATED
    [[ -n "${VCN_ID:-}" ]] && del "vcn"              oci network vcn delete --vcn-id "$VCN_ID" --force --wait-for-state TERMINATED

    if [[ "$preserve_bv" == "true" && -n "${BOOT_VOLUME_ID:-}" ]]; then
        echo ""
        echo "부트볼륨은 보존됐습니다: $BOOT_VOLUME_ID"
        echo "  ⚠️ 보존된 볼륨은 Always Free 200GB 한도를 계속 차지합니다. 불필요하면 삭제하세요:"
        echo "     oci bv boot-volume delete --boot-volume-id $BOOT_VOLUME_ID --force"
    fi

    # 백업 버킷·IAM 은 의도적으로 남긴다 — 백업 데이터가 destroy 로 함께 사라지면 안 된다.
    [[ -n "${BACKUP_BUCKET:-}" ]] && echo "백업 버킷 '$BACKUP_BUCKET' 과 IAM 리소스는 보존됐습니다(수동 삭제)."

    if [[ $failed -ne 0 ]]; then
        echo ""
        echo "ERROR: 일부 리소스를 삭제하지 못했습니다 — .state 를 **보존**합니다($STATE_FILE)."
        echo "  남은 OCID 를 확인하고 원인을 해결한 뒤 destroy 를 다시 실행하세요."
        echo "  (state 를 지우면 남은 리소스를 추적할 수 없게 됩니다.)"
        exit 1
    fi
    rm -f "$STATE_FILE"
    log "삭제 완료"
}

COMMAND="${1:-}"
case "$COMMAND" in
    setup)   do_setup ;;
    deploy)  do_deploy ;;
    ssh)     do_ssh ;;
    status)  do_status ;;
    logs)    do_logs ;;
    stop)    do_stop ;;
    start)   do_start ;;
    destroy) do_destroy ;;
    *) echo "사용법: $0 {setup|deploy|ssh|status|logs|stop|start|destroy}"; exit 1 ;;
esac
