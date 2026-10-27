#!/usr/bin/env bash
set -euo pipefail

export MSYS_NO_PATHCONV=1

# Vultr 인스턴스 생성과 caddy + app + postgres 배포.
#
# 사용법:
#   ./deploy/vultr/deploy.sh setup    # SSH 키 + 방화벽 + 인스턴스 생성. 다시 실행하면 만든 리소스는 건너뛴다
#   ./deploy/vultr/deploy.sh deploy   # GHCR 이미지 pull + compose 기동
#   ./deploy/vultr/deploy.sh ssh|status|logs|mem|stop|start
#   ./deploy/vultr/deploy.sh destroy

# .env·.state·임시 env 는 모두 시크릿을 담는다.
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
chmod 600 "$ENV_FILE" 2>/dev/null || true
source "$ENV_FILE"

VULTR_API="https://api.vultr.com/v2"
VULTR_REGION="${VULTR_REGION:-icn}"          # icn = 서울
VULTR_PLAN="${VULTR_PLAN:-vc2-1c-2gb}"       # 1 vCPU / 2GB / 55GB / 2TB
VULTR_OS_ID="${VULTR_OS_ID:-2284}"           # Ubuntu 24.04 LTS x64
APP_NAME="${APP_NAME:-coin-trading-bot}"
GHCR_IMAGE="${GHCR_IMAGE:-ghcr.io/yoon627/coin-trading-bot}"
SSH_USER="${SSH_USER:-root}"                 # Vultr 기본 사용자
KEY_NAME="${APP_NAME}-key"
KEY_PEM="$SCRIPT_DIR/${KEY_NAME}.pem"
KEY_PUB="$SCRIPT_DIR/${KEY_NAME}.pub"

if [[ -n "${SSH_KNOWN_HOSTS:-}" ]]; then
    [[ -r "$SSH_KNOWN_HOSTS" ]] || { echo "ERROR: SSH_KNOWN_HOSTS 파일을 읽을 수 없습니다: $SSH_KNOWN_HOSTS"; exit 1; }
    SSH_HOST_KEY_OPTIONS=(-o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$SSH_KNOWN_HOSTS")
else
    SSH_HOST_KEY_OPTIONS=(-o StrictHostKeyChecking=accept-new)
fi

save_state() { echo "$1=$2" >> "$STATE_FILE"; }
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

# 응답 본문은 전역 API_BODY 에 둔다(bash 3.2 에 nameref 가 없다).
# 2xx 가 아니면 실패시킨다. 넘기면 리소스가 반쯤 만들어진 채 진행된다.
API_BODY=""
api() {
    local method="$1" path="$2" body="${3:-}" out code
    local args=(-sS --max-time 60 -w $'\n%{http_code}' -X "$method" "${VULTR_API}${path}"
                -H "Authorization: Bearer ${VULTR_API_KEY}")
    if [[ -n "$body" ]]; then
        args+=(-H "Content-Type: application/json" -d "$body")
    fi
    out="$(curl "${args[@]}")" || { echo "ERROR: 네트워크 오류 ($method $path)" >&2; return 1; }
    code="${out##*$'\n'}"
    API_BODY="${out%$'\n'*}"
    case "$code" in
        2*) return 0 ;;
        401|403) echo "ERROR: Vultr 인증 실패(HTTP $code) — VULTR_API_KEY 와 허용 IP 설정을 확인하세요." >&2; return 1 ;;
        *) echo "ERROR: $method $path → HTTP $code" >&2
           printf '%s\n' "$API_BODY" | head -5 >&2
           return 1 ;;
    esac
}

require_vultr() {
    command -v curl >/dev/null || { echo "ERROR: curl 필요"; exit 1; }
    command -v jq   >/dev/null || { echo "ERROR: jq 필요"; exit 1; }
    [[ -n "${VULTR_API_KEY:-}" ]] || {
        echo "ERROR: VULTR_API_KEY 미설정. Vultr 콘솔 → Account → API 에서 발급 후 .env 에 넣으세요."
        echo "  ⚠️ Vultr API 는 기본적으로 호출 IP 를 화이트리스트로 제한합니다 —"
        echo "     같은 화면의 'Access Control' 에 현재 공인 IP 를 반드시 추가하세요."
        exit 1; }
    api GET /account || { echo "ERROR: Vultr API 접근 실패."; exit 1; }
    local email; email="$(printf '%s' "$API_BODY" | jq -r '.account.email // "unknown"')"
    echo "  Vultr 계정 확인: $email"
}

# 새 키를 만들면 앱은 정상 기동하면서 DB 에 저장된 Upbit 키만 복호화할 수 없게 되므로 생성하지 않는다.
require_encryption_secret() {
    if [[ -z "${APP_ENCRYPTION_SECRET:-}" ]]; then
        echo "ERROR: APP_ENCRYPTION_SECRET 이 비어 있습니다 — 자동 생성하지 않습니다."
        echo "  이 값은 저장된 Upbit API 키를 복호화하는 AES 키입니다. 새로 만들면 기존 키가 모두 무효화됩니다."
        echo "  운영 중인 값(서버 /opt/app/.env 또는 오프사이트 보관본)을 $ENV_FILE 에 그대로 복사하세요."
        exit 1
    fi
}

# 이미 있는 인스턴스에서 새 DB_PASSWORD 는 기존 볼륨과 맞지 않아 앱과 롤백이 모두 뜨지 못한다.
require_app_secrets() {
    require_encryption_secret
    local key
    for key in DB_PASSWORD JWT_SECRET; do
        if [[ -z "${!key:-}" ]]; then
            echo "ERROR: $key 이 비어 있습니다 — 이미 있는 인스턴스에는 자동 생성하지 않습니다."
            echo "  새 값은 기존 DB 볼륨과 맞지 않아 앱과 자동 롤백이 함께 기동하지 못합니다."
            echo "  운영 중인 값(서버 /opt/app/.env 또는 오프사이트 보관본)을 $ENV_FILE 에 그대로 복사하세요."
            echo "  CI 배포라면 GitHub secret VULTR_DEPLOY_ENV 에 넣으세요."
            echo "  아직 한 번도 deploy 하지 않은 인스턴스라면 값을 직접 만들어 넣으세요(예: openssl rand -hex 16)."
            exit 1
        fi
    done
}

# DB 볼륨이 없는 새 인스턴스를 만들기 직전에만 부른다.
generate_missing_secrets() {
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

render_server_env() {
    local domain="${APP_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"
    cat > "$1" <<EOF
APP_VERSION=${APP_VERSION:-latest}
DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL:-}
DISCORD_ERROR_ALERT_ENABLED=${DISCORD_ERROR_ALERT_ENABLED:-false}
DISCORD_ERROR_WEBHOOK_URL=${DISCORD_ERROR_WEBHOOK_URL:-}
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
APP_ENCRYPTION_SECRET=${APP_ENCRYPTION_SECRET}
APP_AUTH_COOKIE_FORCE_INSECURE=${APP_AUTH_COOKIE_FORCE_INSECURE:-false}
APP_DOMAIN=${domain}
BACKUP_S3_BUCKET=${BACKUP_S3_BUCKET:-}
BACKUP_S3_PREFIX=${BACKUP_S3_PREFIX:-db-backups}
BACKUP_S3_ENDPOINT=${BACKUP_S3_ENDPOINT:-}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
AWS_ACCESS_KEY_ID=${BACKUP_ACCESS_KEY_ID:-}
AWS_SECRET_ACCESS_KEY=${BACKUP_SECRET_ACCESS_KEY:-}
AWS_DEFAULT_REGION=${BACKUP_REGION:-ap-northeast-2}
EOF
    append_app_overrides "$1"
    chmod 600 "$1"
}

# .env 에 값이 있는 키만 넘긴다. 폴백을 주면 앱 기본값과 갈리고, 빈 문자열은 Spring 숫자 바인딩이 실패한다.
APP_OVERRIDE_KEYS=(
    TRADING_TICKERS TRADING_INVEST_RATIO TRADING_MAX_INVEST_AMOUNT
    TRADING_AUTO_START TRADING_TAKE_PROFIT_PCT TRADING_MAX_LOSS_PCT TRADING_TRAILING_STOP_PCT
    TRADING_TRAILING_ARM_PCT TRADING_MAX_HOLD_DAYS
    TRADING_ROUND_TRIP_FEE_RATE TRADING_K_VALUE TRADING_INTERVAL_SECONDS
    TRADING_RECONCILE_HALT_THRESHOLD
    TRADING_SHADOW_EXIT_ENABLED TRADING_SHADOW_EXIT_TRAILING_STOP_PCT TRADING_SHADOW_EXIT_TRAILING_ARM_PCT
    WATCHLIST_TICKERS
    MARKETDATA_WATCHDOG_ENABLED MARKETDATA_WATCHDOG_STALE_MS MARKETDATA_WATCHDOG_INTERVAL_MS
    MARKETDATA_WATCHDOG_INITIAL_DELAY_MS MARKETDATA_WATCHDOG_RESTART_BACKOFF_MS
    KIS_TRADING_ENABLED KIS_TRADING_LIVE_ENABLED KIS_TRADING_INVEST_RATIO KIS_TRADING_MAX_INVEST_AMOUNT
    KIS_TRADING_MAX_ORDER_AMOUNT KIS_TRADING_TAKE_PROFIT_PCT KIS_TRADING_MAX_LOSS_PCT KIS_TRADING_TRAILING_STOP_PCT
    KIS_TRADING_TRAILING_ARM_PCT KIS_TRADING_MAX_HOLD_DAYS KIS_TRADING_INTERVAL_SECONDS
    KIS_TRADING_PAPER_MIN_REQUEST_INTERVAL_MS KIS_TRADING_LIVE_MIN_REQUEST_INTERVAL_MS
    KIS_TRADING_COMMISSION_RATE KIS_TRADING_SELL_TAX_RATE
)
# docker-compose.prod.yml 의 app environment 목록과 같게 유지한다(DeployEnvPassthroughTest 가 대조한다).
# 패턴을 따옴표로 감싸면 리터럴이 되므로 변수에 담는다.
OVERRIDE_VALUE_PATTERN='^[A-Za-z0-9._, -]+$'

append_app_overrides() {
    local key value written=()
    for key in "${APP_OVERRIDE_KEYS[@]}"; do
        value="${!key:-}"
        [[ -z "$value" ]] && continue
        # 값을 그대로 쓰므로 개행·#·$·따옴표가 들어가면 compose 보간이 바뀐다. 숫자·boolean·티커 목록만 받는다.
        if [[ ! "$value" =~ $OVERRIDE_VALUE_PATTERN ]]; then
            echo "ERROR: $key 값에 허용되지 않은 문자가 있습니다(허용: 영숫자 . _ , - 공백)." >&2
            exit 1
        fi
        printf '%s=%s\n' "$key" "$value" >> "$1"
        written+=("$key")
    done
    # 배포 로그가 공개되므로 키 이름만 찍는다.
    if (( ${#written[@]} > 0 )); then log "서버 .env override 키: ${written[*]}"; else log "서버 .env override 키: 없음(앱 기본값)"; fi
    return 0
}

ensure_ssh_key() {
    if [[ ! -f "$KEY_PEM" && -f "$KEY_PUB" ]]; then
        echo "ERROR: 공개키($KEY_PUB)만 있고 개인키가 없습니다 — 접속 불가 상태입니다."
        echo "  두 파일을 모두 지운 뒤 새 인스턴스를 만드세요."
        exit 1
    fi
    if [[ -f "$KEY_PEM" && ! -f "$KEY_PUB" ]]; then
        log "공개키 재생성 (개인키로부터 파생)"
        ssh-keygen -y -f "$KEY_PEM" > "$KEY_PUB"
    elif [[ ! -f "$KEY_PEM" ]]; then
        log "SSH 키 생성"
        ssh-keygen -t ed25519 -N "" -f "$KEY_PEM" -C "$APP_NAME" >/dev/null
        mv "${KEY_PEM}.pub" "$KEY_PUB"
        chmod 400 "$KEY_PEM"
        echo "  생성: $KEY_PEM (백업 권장 — 분실 시 인스턴스 접속 불가)"
    else
        echo "  기존 키 사용: $KEY_PEM"
    fi

    [[ -n "${SSHKEY_ID:-}" ]] && { echo "  Vultr 등록 키 재사용: $SSHKEY_ID"; return; }

    api GET "/ssh-keys?per_page=500"
    local existing
    existing="$(printf '%s' "$API_BODY" | jq -r --arg n "$KEY_NAME" '.ssh_keys[]? | select(.name==$n) | .id' | head -1)"
    if [[ -n "$existing" ]]; then
        SSHKEY_ID="$existing"
    else
        log "Vultr 에 SSH 공개키 등록"
        local payload
        payload="$(jq -n --arg n "$KEY_NAME" --arg k "$(cat "$KEY_PUB")" '{name:$n, ssh_key:$k}')"
        api POST /ssh-keys "$payload"
        SSHKEY_ID="$(printf '%s' "$API_BODY" | jq -r '.ssh_key.id')"
    fi
    [[ -z "$SSHKEY_ID" || "$SSHKEY_ID" == "null" ]] && { echo "ERROR: SSH 키 등록 실패"; exit 1; }
    save_state SSHKEY_ID "$SSHKEY_ID"
}

# 규칙 없이 그룹만 붙이면 모든 인바운드가 막힌다.
setup_firewall() {
    if [[ -z "${FIREWALL_ID:-}" ]]; then
        log "방화벽 그룹 생성"
        api POST /firewalls "$(jq -n --arg d "${APP_NAME}" '{description:$d}')"
        FIREWALL_ID="$(printf '%s' "$API_BODY" | jq -r '.firewall_group.id')"
        [[ -z "$FIREWALL_ID" || "$FIREWALL_ID" == "null" ]] && { echo "ERROR: 방화벽 생성 실패"; exit 1; }
        save_state FIREWALL_ID "$FIREWALL_ID"
    fi

    local ssh_cidr="${SSH_ALLOW_CIDR:-}"
    if [[ -z "$ssh_cidr" ]]; then
        local myip; myip="$(curl -s --max-time 10 https://checkip.amazonaws.com || true)"
        myip="$(printf '%s' "$myip" | tr -d '[:space:]')"
        [[ -n "$myip" ]] && ssh_cidr="${myip}/32" || {
            echo "ERROR: 공인 IP 감지 실패. .env 에 SSH_ALLOW_CIDR 설정."; exit 1; }
    fi
    local ssh_subnet="${ssh_cidr%%/*}" ssh_size="${ssh_cidr##*/}"
    local app_cidr="${APP_ALLOW_CIDR:-0.0.0.0/0}"
    local app_subnet="${app_cidr%%/*}" app_size="${app_cidr##*/}"
    log "방화벽 규칙: SSH=$ssh_cidr / 443=$app_cidr / 80=0.0.0.0/0(ACME)"

    # notes 가 ctb- 로 시작하는 규칙만 지우고 다시 넣는다. ctb-ssh-github-actions 는 남긴다.
    api GET "/firewalls/${FIREWALL_ID}/rules?per_page=500"
    local old_ids rid stale=0
    local actions_rule_count actions_rule_invalid
    actions_rule_count="$(printf '%s' "$API_BODY" | jq '[.firewall_rules[]? | select((.notes // "") == "ctb-ssh-github-actions")] | length')"
    actions_rule_invalid="$(printf '%s' "$API_BODY" | jq '[.firewall_rules[]? | select((.notes // "") == "ctb-ssh-github-actions") | select((.ip_type // "") != "v4" or (.protocol // "") != "tcp" or (((.port // "") | tostring) != "22") or (.subnet // "") != "0.0.0.0" or (.subnet_size // -1) != 0)] | length')"
    if (( actions_rule_invalid > 0 )); then
        echo "ERROR: ctb-ssh-github-actions 규칙이 22/tcp, 0.0.0.0/0 형식이 아닙니다. Vultr 콘솔/API에서 수정 후 재실행하세요." >&2
        exit 1
    fi
    if (( actions_rule_count > 1 )); then
        echo "ERROR: ctb-ssh-github-actions 규칙이 중복됩니다. Vultr 콘솔/API에서 하나만 남긴 후 재실행하세요." >&2
        exit 1
    fi
    if (( actions_rule_count == 0 )); then
        echo "  WARN: ctb-ssh-github-actions 규칙이 없습니다 — GitHub Actions SSH 배포는 timeout됩니다." >&2
    fi
    old_ids="$(printf '%s' "$API_BODY" | jq -r '.firewall_rules[]? | select((.notes // "") | startswith("ctb-") and . != "ctb-ssh-github-actions") | .id')"
    for rid in $old_ids; do
        # 삭제에 실패하면 옛 SSH 대역이 열린 채 남는다.
        if ! api DELETE "/firewalls/${FIREWALL_ID}/rules/${rid}" >/dev/null; then
            echo "  WARN: 기존 규칙 $rid 삭제 실패 — 콘솔에서 직접 확인하세요." >&2
            stale=1
        fi
    done
    [[ $stale -eq 1 ]] && echo "  ⚠️ 남은 옛 규칙이 있을 수 있습니다(접근 범위가 넓어질 수 있음)." >&2

    add_rule() {  # port subnet size notes
        api POST "/firewalls/${FIREWALL_ID}/rules" \
            "$(jq -n --arg p "$1" --arg s "$2" --argjson z "$3" --arg n "$4" \
                '{ip_type:"v4", protocol:"tcp", subnet:$s, subnet_size:$z, port:$p, notes:$n}')" >/dev/null
    }
    add_rule 22  "$ssh_subnet" "$ssh_size" "ctb-ssh"
    add_rule 80  "0.0.0.0"     0           "ctb-acme-http01"
    add_rule 443 "$app_subnet" "$app_size" "ctb-https"
    echo "  방화벽 규칙 적용 완료"
}

write_userdata() {
    cat > "$1" <<'UDEOF'
#!/bin/bash
set -ex
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl gnupg jq unzip

install_aws_cli() {
    local deb_arch aws_arch aws_version tmp
    if command -v aws >/dev/null 2>&1; then
        aws_version="$(aws --version 2>&1)"
        case "$aws_version" in
            aws-cli/2*) printf '%s\n' "$aws_version"; return ;;
            *) echo "FATAL: AWS CLI v2가 아닌 aws가 이미 설치되어 있습니다"; exit 1 ;;
        esac
    fi

    deb_arch="$(dpkg --print-architecture)"
    case "$deb_arch" in
        amd64) aws_arch="x86_64" ;;
        arm64) aws_arch="aarch64" ;;
        *) echo "FATAL: AWS CLI 미지원 아키텍처: $deb_arch"; exit 1 ;;
    esac

    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${aws_arch}.zip" -o "$tmp/awscliv2.zip"
    unzip -q "$tmp/awscliv2.zip" -d "$tmp"
    "$tmp/aws/install"
    trap - EXIT
    rm -rf "$tmp"
    command -v aws >/dev/null 2>&1 || { echo "FATAL: AWS CLI 설치 실패"; exit 1; }
    aws_version="$(aws --version 2>&1)"
    case "$aws_version" in
        aws-cli/2*) printf '%s\n' "$aws_version" ;;
        *) echo "FATAL: AWS CLI v2 설치 확인 실패"; exit 1 ;;
    esac
}

# backup.sh 가 쓴다.
install_aws_cli

# compose plugin 이 필요해 Ubuntu 기본 docker.io 대신 docker-ce 를 쓴다.
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

systemctl enable --now docker

# 이미지에 ufw 가 켜져 있으면 클라우드 방화벽과 별개로 막히므로 함께 연다.
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 22/tcp
    ufw allow 80/tcp
    ufw allow 443/tcp
fi

mkdir -p /opt/app

# deploy 는 이 마커를 보고 진행하므로 의존성이 준비된 뒤에만 남긴다.
command -v docker >/dev/null || { echo "FATAL: docker 설치 실패"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "FATAL: docker compose plugin 없음"; exit 1; }
touch /opt/app/.userdata-done
UDEOF
}

launch_instance() {
    if [[ -n "${INSTANCE_ID:-}" ]]; then echo "  기존 인스턴스 사용: $INSTANCE_ID"; return; fi

    # 응답이 유실돼 이미 만들어졌을 수 있으니 label 로 먼저 찾는다.
    api GET "/instances?per_page=500"
    local existing
    existing="$(printf '%s' "$API_BODY" | jq -r --arg l "$APP_NAME" '.instances[]? | select(.label==$l) | .id' | head -1)"
    if [[ -n "$existing" ]]; then
        echo "  같은 label 의 기존 인스턴스 재사용: $existing"
        INSTANCE_ID="$existing"; save_state INSTANCE_ID "$INSTANCE_ID"; return
    fi

    # 생성 요청 전에 .env 에 적어 두어야 요청이 끊겨 다시 실행할 때 같은 값을 쓴다.
    generate_missing_secrets

    local ud; ud="$(mktemp)"; write_userdata "$ud"
    # shellcheck disable=SC2064
    trap "rm -f '$ud'" EXIT

    log "인스턴스 생성 ($VULTR_PLAN, $VULTR_REGION, os_id=$VULTR_OS_ID)"
    local payload
    payload="$(jq -n \
        --arg region "$VULTR_REGION" --arg plan "$VULTR_PLAN" --argjson os "$VULTR_OS_ID" \
        --arg label "$APP_NAME" --arg host "$APP_NAME" \
        --arg fw "$FIREWALL_ID" --arg key "$SSHKEY_ID" \
        --arg ud "$(base64 < "$ud" | tr -d '\n')" \
        '{region:$region, plan:$plan, os_id:$os, label:$label, hostname:$host,
          firewall_group_id:$fw, sshkey_id:[$key], user_data:$ud,
          backups:"disabled", enable_ipv6:false, ddos_protection:false}')"
    api POST /instances "$payload"
    INSTANCE_ID="$(printf '%s' "$API_BODY" | jq -r '.instance.id')"
    [[ -z "$INSTANCE_ID" || "$INSTANCE_ID" == "null" ]] && { echo "ERROR: 인스턴스 생성 실패"; exit 1; }
    save_state INSTANCE_ID "$INSTANCE_ID"
    rm -f "$ud"; trap - EXIT
    echo "  생성됨: $INSTANCE_ID"
}

wait_for_active() {
    if [[ -n "${PUBLIC_IP:-}" ]]; then echo "  기존 IP: $PUBLIC_IP"; return; fi
    log "인스턴스 active 대기"
    local i status ip
    for i in $(seq 1 60); do
        api GET "/instances/${INSTANCE_ID}" || { sleep 10; continue; }
        status="$(printf '%s' "$API_BODY" | jq -r '.instance.status')"
        ip="$(printf '%s' "$API_BODY" | jq -r '.instance.main_ip')"
        if [[ "$status" == "active" && -n "$ip" && "$ip" != "0.0.0.0" && "$ip" != "null" ]]; then
            PUBLIC_IP="$ip"; save_state PUBLIC_IP "$PUBLIC_IP"
            echo "  active — IP: $PUBLIC_IP"; return
        fi
        sleep 10
    done
    echo "ERROR: 인스턴스가 active 상태가 되지 않았습니다. Vultr 콘솔에서 확인하세요."; exit 1
}

do_setup() {
    require_vultr
    command -v openssl >/dev/null || { echo "ERROR: openssl 필요"; exit 1; }
    require_encryption_secret
    ensure_ssh_key
    setup_firewall
    launch_instance
    require_app_secrets
    wait_for_active

    log "Setup 완료"
    echo "  공인 IP: $PUBLIC_IP"
    echo "  SSH:     ssh -i $KEY_PEM ${SSH_USER}@${PUBLIC_IP}"
    echo "  cloud-init(Docker·AWS CLI 설치) 완료까지 2~4분 대기 후: ./deploy/vultr/deploy.sh deploy"
}

# CI 는 SSH_KNOWN_HOSTS 로 호스트 키를 고정한다. 수동 실행은 처음 보는 키를 받아 두고 이후 변경을 거부한다.
ssh_inst() {
    ssh "${SSH_HOST_KEY_OPTIONS[@]}" -o ConnectTimeout=10 -i "$KEY_PEM" \
        "${SSH_USER}@${PUBLIC_IP}" "$@"
}

wait_for_docker() {
    log "인스턴스 준비 대기 (cloud-init + Docker·AWS CLI)"
    local i
    for i in $(seq 1 40); do
        if ssh_inst 'test -f /opt/app/.userdata-done && docker info' >/dev/null 2>&1; then
            echo "준비 완료"; return 0
        fi
        sleep 10
    done
    echo "ERROR: cloud-init 의존성 준비 타임아웃. 로그 확인:"
    echo "  ./deploy/vultr/deploy.sh ssh   후  cat /var/log/cloud-init-output.log"
    exit 1
}

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
        echo "WARN: $domain resolve 실패. ACME 발급 실패 가능."
    elif [[ "$resolved" != "$PUBLIC_IP" ]]; then
        echo "WARN: $domain → $resolved (인스턴스 IP=$PUBLIC_IP 와 불일치). ACME 발급 실패 가능."
    else
        echo "  도메인 preflight OK: $domain → $resolved"
    fi
}

# 이 마이그레이션이 없는 이미지는 거래소 축을 몰라 KIS 행을 Upbit 상태로 읽으므로 자동 롤백 대상이 될 수 없다.
ROLLBACK_FLOOR_MIGRATION="bot/src/main/resources/db/migration/V30__exchange_dimension.sql"

# migration 이 끼면 자동 롤백하지 않는다. 옛 앱이 새 스키마 위에서 뜰 수 있다.
do_deploy() {
    load_state
    [[ -z "${PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    require_app_secrets
    local domain="${APP_DOMAIN:-${PUBLIC_IP//./-}.sslip.io}"

    local repo_root; repo_root="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
    [[ -z "$repo_root" ]] && { echo "ERROR: git 저장소가 아님 — repo 체크아웃 안에서 실행하세요."; exit 1; }
    git -C "$repo_root" fetch origin main --quiet 2>/dev/null || true
    local target_sha="${APP_VERSION:-}"
    if [[ -z "$target_sha" || "$target_sha" == "latest" ]]; then
        target_sha="$(git -C "$repo_root" rev-parse origin/main 2>/dev/null || true)"
        [[ -z "$target_sha" ]] && { echo "ERROR: 대상 SHA 확인 실패. APP_VERSION=<sha> 로 지정하세요."; exit 1; }
    fi
    APP_VERSION="$target_sha"

    local last_good="${LAST_GOOD_SHA:-}"
    local migration_gate="rollback-ok"
    if [[ -z "$last_good" ]]; then
        migration_gate="blocked"
    elif [[ "$last_good" == "$target_sha" ]]; then
        migration_gate="same-sha"
    elif ! git -C "$repo_root" cat-file -e "${last_good}^{commit}" 2>/dev/null \
      || ! git -C "$repo_root" cat-file -e "${target_sha}^{commit}" 2>/dev/null; then
        migration_gate="blocked"
    elif ! git -C "$repo_root" cat-file -e "${last_good}:${ROLLBACK_FLOOR_MIGRATION}" 2>/dev/null; then
        migration_gate="below-floor"
        log "자동 롤백 제외: 직전 정상 SHA(${last_good:0:12}) 트리에 ${ROLLBACK_FLOOR_MIGRATION##*/} 가 없다 — V30 이전 이미지로는 롤백하지 않는다"
    elif [[ -n "$(git -C "$repo_root" diff --name-only "$last_good" "$target_sha" -- bot/src/main/resources/db/migration/)" ]]; then
        migration_gate="blocked"
    fi
    log "대상 SHA=${target_sha:0:12}  LAST_GOOD=${last_good:0:12}  자동롤백=$migration_gate"

    preflight_domain
    wait_for_docker

    log "설정 업로드"
    local tmp_env; tmp_env="$(mktemp)"
    trap "rm -f '$tmp_env'" EXIT
    render_server_env "$tmp_env"
    # 잘못된 .env 는 롤백으로 되돌려지지 않으므로 업로드 전에 검사한다.
    bash "$SCRIPT_DIR/preflight_exit_params.sh" "$tmp_env"
    ssh_inst 'mkdir -p /opt/app'
    local scp_opts=("${SSH_HOST_KEY_OPTIONS[@]}" -o ConnectTimeout=10 -i "$KEY_PEM")
    scp "${scp_opts[@]}" "$COMPOSE_FILE"          "${SSH_USER}@${PUBLIC_IP}":/opt/app/docker-compose.yml
    scp "${scp_opts[@]}" "$SCRIPT_DIR/Caddyfile"  "${SSH_USER}@${PUBLIC_IP}":/opt/app/Caddyfile
    scp "${scp_opts[@]}" "$SCRIPT_DIR/backup.sh"  "${SSH_USER}@${PUBLIC_IP}":/opt/app/backup.sh
    scp "${scp_opts[@]}" "$tmp_env"               "${SSH_USER}@${PUBLIC_IP}":/opt/app/.env
    rm -f "$tmp_env"; trap - EXIT
    ssh_inst 'chmod +x /opt/app/backup.sh && chmod 600 /opt/app/.env'

    # argv 에 두면 ps 로 보이므로 stdin 으로 넘긴다.
    if [[ -n "${GHCR_TOKEN:-}" ]]; then
        log "GHCR 로그인 (토큰은 stdin 으로만 전달)"
        printf '%s' "$GHCR_TOKEN" | ssh_inst "docker login ghcr.io -u '${GHCR_USERNAME:-}' --password-stdin" >/dev/null
    fi

    log "컨테이너 배포 (GHCR pull, SHA=${target_sha:0:12})"
    local deploy_rc=0
    ssh_inst "APP_DOMAIN='$domain' GHCR_IMAGE='$GHCR_IMAGE' TARGET_SHA='$target_sha' LAST_GOOD_SHA='$last_good' MIGRATION_GATE='$migration_gate' bash -s" <<'REMOTE' || deploy_rc=$?
set -e
# 원격 명령이 set -e 로 멈추면 그 명령의 종료 코드가 나가 2(자동 롤백)·3(롤백 실패)과 섞인다. 실패는 1 로 모은다.
trap 'exit 1' ERR
cd /opt/app
: "${TARGET_SHA:?TARGET_SHA 필요}"

# 이 스크립트는 bash -s 의 stdin 으로 들어온다. exec -T 가 stdin 을 붙잡아 남은 스크립트를 먹지 않게 끊는다.
# health 는 실행 중이던 봇의 복원이 끝나야 200 이다. 시도 횟수가 아니라 마감으로 묶는다 — exec·curl 이 걸려도 180초를 넘지 않는다.
health_ok() {
    local deadline=$((SECONDS + 180))
    while (( SECONDS < deadline )); do
        if timeout -k 5 15 docker compose exec -T app curl -fsS --max-time 5 http://localhost:8080/actuator/health </dev/null > /dev/null 2>&1; then
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
    printf '%s\n' "$TARGET_SHA" > /opt/app/.last-good-sha
    chmod 600 /opt/app/.last-good-sha
    echo "App healthy! ($TARGET_SHA)"
    docker compose ps
    tls_ok=false
    for _ in $(seq 1 18); do
        if curl -fsS --max-time 5 --resolve "${APP_DOMAIN}:443:127.0.0.1" \
            "https://${APP_DOMAIN}/actuator/health" > /dev/null 2>&1; then
            echo "HTTPS e2e OK: https://${APP_DOMAIN}"; tls_ok=true; break
        fi
        sleep 5
    done
    [ "$tls_ok" = "false" ] && { echo "WARN: HTTPS e2e 미확인 (인증서 발급 지연 가능). caddy 로그:"; docker compose logs --tail=60 caddy || true; }
    docker images "$GHCR_IMAGE" --format '{{.Repository}}:{{.Tag}}' \
        | grep -vE ":(${TARGET_SHA}|latest)\$" | xargs -r docker rmi > /dev/null 2>&1 || true
    docker image prune -f > /dev/null 2>&1 || true
    exit 0
fi

echo "ERROR: 180s 내 헬스체크 실패 (SHA=$TARGET_SHA)"
docker compose ps || true
docker compose logs --tail=120 app || true
# 503 이 복원 중(OUT_OF_SERVICE)인지 실패(DOWN)인지 가른다. 본문은 상태 한 줄뿐이다.
echo "--- health 응답 ---"
timeout -k 5 15 docker compose exec -T app curl -sS --max-time 5 http://localhost:8080/actuator/health </dev/null || true
echo ""
# 봇 복원 실패는 기동 직후에 찍혀 위 꼬리 로그에서 밀려날 수 있다.
echo "--- 봇 복원 실패 로그 ---"
docker compose logs app 2>/dev/null | grep -F '봇 미복원' | tail -n 5 || true

if [ "$MIGRATION_GATE" != "rollback-ok" ]; then
    if [ -z "$LAST_GOOD_SHA" ]; then
        echo "자동 롤백 제외: 직전 정상 배포(LAST_GOOD) 없음."
    elif [ "$MIGRATION_GATE" = "same-sha" ]; then
        echo "자동 롤백 제외: 마지막 정상 배포와 같은 커밋이다 — 이미지·설정이 같으면 compose 가 컨테이너를 다시 만들지 않는다."
        echo "  봇 복원 실패라면 그 사용자의 봇을 멈추거나 서버에서 'cd /opt/app && docker compose restart app' 으로 다시 띄운다."
    elif [ "$MIGRATION_GATE" = "below-floor" ]; then
        echo "자동 롤백 제외: 직전 정상 SHA 에 V30(거래소 축) 마이그레이션이 없다 — V30 이전 이미지는 KIS 행을 Upbit 상태로 읽는다."
    else
        echo "자동 롤백 제외: DB migration 포함 배포 — 신규 스키마가 이미 적용됐을 수 있어 구버전 앱 자동 복귀는 위험."
    fi
    echo "--- 수동 개입 필요 ---"
    echo "  1) docker compose logs app  로 원인 확인"
    echo "  2) DB migration 적용 여부 점검, 필요 시 백업 복원"
    echo "  3) 수동 롤백: cd /opt/app && APP_VERSION=<이전정상SHA> docker compose up -d --pull missing"
    echo "     V30 이후에는 V30 이전 이미지로 롤백하지 않는다 — 불가피하면 README 의 절차로 KIS 행을 먼저 지운다."
    echo "     마이그레이션이 적용되지 않았으면(flyway_schema_history 에 30 없음) 직전 이미지로 수동 롤백해도 된다."
    exit 1
fi

echo "자동 롤백 → LAST_GOOD_SHA=$LAST_GOOD_SHA"
if ! APP_VERSION="$LAST_GOOD_SHA" docker compose up -d --remove-orphans --pull missing; then
    echo "ERROR: 롤백 기동 실패. 수동 개입 필요."
    exit 3
fi
if health_ok; then
    printf '%s\n' "$LAST_GOOD_SHA" > /opt/app/.last-good-sha
    chmod 600 /opt/app/.last-good-sha
    sed -i "s|^APP_VERSION=.*|APP_VERSION=${LAST_GOOD_SHA}|" .env || true
    echo "롤백 성공: $LAST_GOOD_SHA 로 복구됨."
    docker compose ps || true
    exit 2
fi
echo "ERROR: 롤백 후에도 unhealthy. 수동 개입 필요."
docker compose logs --tail=120 app || true
exit 3
REMOTE

    echo ""
    local run_url=""
    [[ -n "${GITHUB_RUN_ID:-}" ]] && run_url=" — ${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID}"
    case "$deploy_rc" in
        0)
            update_state LAST_GOOD_SHA "$target_sha"
            log "배포 완료 (SHA=${target_sha:0:12} — LAST_GOOD 갱신)"
            echo "  App: https://$domain  (Caddy 가 Let's Encrypt 인증서 발급까지 최대 ~30초)"
            echo "  ⚠️ 2GB 박스이므로 기동 후 실사용량을 확인하세요:  ./deploy/vultr/deploy.sh mem"
            notify_deploy success "운영 배포 성공: ${target_sha:0:12}${run_url}"
            ;;
        2)
            log "자동 롤백됨 → 이전 정상 SHA(${last_good:0:12}) 로 복구 (LAST_GOOD 유지)"
            echo "  원인 조사 후 재배포하세요. App: https://$domain"
            notify_deploy rollback "운영 배포 자동 롤백: ${target_sha:0:12} 이 헬스체크(봇 복원 포함)를 통과하지 못해 ${last_good:0:12} 로 되돌렸다${run_url}"
            exit 2
            ;;
        *)
            echo "ERROR: 배포 실패 (rc=$deploy_rc) — 위 원격 로그/안내 참조. LAST_GOOD 미변경."
            notify_deploy failure "$(deploy_failure_message "$deploy_rc" "$target_sha" "$last_good")${run_url}"
            exit 1
            ;;
    esac
}

# 원격 스크립트의 종료 코드: 1 수동 개입(자동 롤백 대상 아님·원격 명령 실패), 3 롤백까지 실패. 그 밖은 SSH 가 끊긴 경우 등.
deploy_failure_message() {
    local rc="$1" target="${2:0:12}" last_good="${3:0:12}"
    case "$rc" in
        1) echo "운영 배포 실패(수동 개입 필요): $target — 자동 롤백 대상이 아니거나 원격 명령이 실패했다. 새 컨테이너가 떠 있을 수 있다" ;;
        3) echo "운영 배포 실패(앱 중단 가능): $target 이 헬스체크를 통과하지 못했고 ${last_good:-?} 로의 롤백도 기동이나 헬스체크에 실패했다" ;;
        *) echo "운영 배포 결과 불명(rc=$rc): $target — 원격 실행이 끊겼을 수 있다. 서버 상태를 확인한다" ;;
    esac
}

# .env 를 source 한 셸 변수라 export 되지 않으므로 알림 스크립트에 명시해 넘긴다. 알림 실패가 배포 결과를 바꾸지 않는다.
notify_deploy() {
    DISCORD_WEBHOOK_URL="${DISCORD_WEBHOOK_URL:-}" DISCORD_ERROR_WEBHOOK_URL="${DISCORD_ERROR_WEBHOOK_URL:-}" \
        bash "$SCRIPT_DIR/notify_deploy.sh" "$@" || true
}

do_ssh()    { load_state; ssh "${SSH_HOST_KEY_OPTIONS[@]}" -i "$KEY_PEM" "${SSH_USER}@${PUBLIC_IP}"; }
do_status() { load_state; echo "공인 IP: ${PUBLIC_IP:-none}"; [[ -n "${PUBLIC_IP:-}" ]] && ssh_inst 'cd /opt/app && docker compose ps' 2>/dev/null || echo "(unreachable)"; }
do_logs()   { load_state; ssh_inst 'cd /opt/app && docker compose logs --tail=120 app'; }
do_stop()   { load_state; ssh_inst 'cd /opt/app && docker compose down && echo 중지'; }
do_start()  { load_state; ssh_inst 'cd /opt/app && docker compose up -d && echo 시작'; }

do_mem() {
    load_state
    [[ -z "${PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    ssh_inst 'free -m; echo; docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"'
}

do_destroy() {
    require_vultr
    load_state
    echo "=== Vultr 리소스 삭제 (과금 중단) ==="
    echo "  인스턴스: ${INSTANCE_ID:-none} (${PUBLIC_IP:-none})"
    echo ""
    echo "⚠️  거래 이력과 (암호화된) Upbit 키가 이 인스턴스 디스크에 있습니다."
    echo "    Vultr 인스턴스 삭제는 디스크까지 즉시 소멸시킵니다 — 백업을 먼저 확보하세요."
    read -rp "'yes' 입력: " confirm; [[ "$confirm" != "yes" ]] && { echo "취소"; exit 0; }

    # 인스턴스를 지우면 디스크도 사라지므로 최종 백업이 실패하면 멈춘다.
    if [[ -n "${PUBLIC_IP:-}" && -n "${BACKUP_S3_BUCKET:-}" ]]; then
        log "최종 DB 백업"
        if ! ssh_inst "cd /opt/app && ./backup.sh"; then
            echo "ERROR: 최종 백업 실패 — destroy 를 중단합니다."
            echo "  백업 없이 삭제하려면 .env 의 BACKUP_S3_BUCKET 을 비우고 다시 실행하세요."
            exit 1
        fi
    elif [[ -n "${PUBLIC_IP:-}" ]]; then
        echo "WARN: BACKUP_S3_BUCKET 미설정 → 최종 백업 생략. 거래이력·암호화키가 소멸됩니다."
        read -rp "그래도 진행하려면 'delete-my-data' 입력: " c2
        [[ "$c2" != "delete-my-data" ]] && { echo "취소"; exit 0; }
    fi

    local failed=0
    if [[ -n "${INSTANCE_ID:-}" ]]; then
        log "인스턴스 삭제"
        api DELETE "/instances/${INSTANCE_ID}" && echo "  삭제: instance" || { echo "  실패: instance" >&2; failed=1; }
    fi
    if [[ -n "${FIREWALL_ID:-}" ]]; then
        # 인스턴스가 참조 중이면 삭제가 거부된다.
        sleep 5
        api DELETE "/firewalls/${FIREWALL_ID}" && echo "  삭제: firewall" || { echo "  실패: firewall" >&2; failed=1; }
    fi
    [[ -n "${SSHKEY_ID:-}" ]] && echo "  보존: SSH 키($SSHKEY_ID) — 필요 없으면 콘솔에서 삭제"

    if [[ $failed -ne 0 ]]; then
        echo ""
        echo "ERROR: 일부 리소스를 삭제하지 못했습니다 — .state 를 보존합니다($STATE_FILE)."
        echo "  Vultr 콘솔에서 남은 리소스를 확인하세요(과금이 계속될 수 있습니다)."
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
    mem)     do_mem ;;
    stop)    do_stop ;;
    start)   do_start ;;
    destroy) do_destroy ;;
    *) echo "사용법: $0 {setup|deploy|ssh|status|logs|mem|stop|start|destroy}"; exit 1 ;;
esac
