#!/usr/bin/env bash
set -euo pipefail

export MSYS_NO_PATHCONV=1

# ============================================================
# Coin Trading Bot - Vultr 배포 스크립트
#
# Vultr 서울(icn) vc2-1c-2gb (1 vCPU x86_64, 2GB, 55GB SSD) + Docker Compose — $10/월.
# 구성: caddy + app + PostgreSQL + Redis.
#
# AWS(4GB, $39.29/월 실측) 대비 -75%. 2GB 로 낮춘 근거는 운영 59일차 EC2 실측이다
# (app 420MiB / postgres 380MiB / redis 3.4MiB / caddy 14MiB = 818MiB, load average 0.00).
#
# deploy/aws · deploy/oci 판과 배포 로직(대상 SHA 고정·migration 게이트·자동 롤백·헬스체크)이
# 동일한 계약이다. 한쪽을 고치면 다른 쪽도 함께 고쳐야 한다.
#
# 사용법:
#   ./deploy/vultr/deploy.sh setup    # 1회: SSH 키 + 방화벽 + 인스턴스 생성
#   ./deploy/vultr/deploy.sh deploy   # GHCR 이미지 pull + compose 기동
#   ./deploy/vultr/deploy.sh ssh|status|logs|stop|start
#   ./deploy/vultr/deploy.sh destroy  # 삭제 (과금 중단)
#
# setup 은 단계별 재진입 가능하다 — 중간 실패 후 같은 명령을 다시 실행하면 이미 만들어진
# 리소스는 건너뛰고 남은 단계부터 이어서 진행한다.
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

# ── Vultr REST 호출 ──
# 응답 본문은 전역 API_BODY 로 돌려준다(bash 3.2 호환 — 로컬 nameref 안 씀).
# 2xx 가 아니면 즉시 실패시킨다. API 오류를 조용히 넘기면 리소스가 반쯤 만들어진 채 진행된다.
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

# ⚠️ APP_ENCRYPTION_SECRET 은 생성하지 않고 실패시킨다 — AWS 에서 이전해 오는 DB 에는 이 키로
# 암호화된 Upbit 키가 들어있어, 새 키를 만들면 앱은 정상 기동하면서 거래소 키만 조용히
# 복호화 불능이 된다(가장 위험한 실패 모드).
ensure_secrets() {
    command -v openssl >/dev/null || { echo "ERROR: openssl 필요"; exit 1; }
    if [[ -z "${APP_ENCRYPTION_SECRET:-}" ]]; then
        echo "ERROR: APP_ENCRYPTION_SECRET 이 비어 있습니다 — 자동 생성하지 않습니다."
        echo "  이 값은 저장된 Upbit API 키를 복호화하는 AES 키입니다. 새로 만들면 기존 키가 모두 무효화됩니다."
        echo "  AWS 쪽 deploy/aws/.env 의 APP_ENCRYPTION_SECRET 값을 $ENV_FILE 에 그대로 복사하세요."
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
BACKUP_S3_BUCKET=${BACKUP_S3_BUCKET:-}
BACKUP_S3_PREFIX=${BACKUP_S3_PREFIX:-db-backups}
BACKUP_S3_ENDPOINT=${BACKUP_S3_ENDPOINT:-}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
AWS_ACCESS_KEY_ID=${BACKUP_ACCESS_KEY_ID:-}
AWS_SECRET_ACCESS_KEY=${BACKUP_SECRET_ACCESS_KEY:-}
AWS_DEFAULT_REGION=${BACKUP_REGION:-ap-northeast-2}
EOF
    chmod 600 "$1"
}

# ── SSH 키 ──
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

    # 같은 이름이 이미 등록돼 있으면 재사용한다(재실행 시 중복 등록 방지).
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

# ── 방화벽 ──
# Vultr 클라우드 방화벽은 리소스에 붙는 형태라 AWS security group 과 의미가 같다.
# 규칙 없이 그룹만 붙이면 **모든 인바운드가 차단**되므로 필요한 포트를 반드시 넣는다.
setup_firewall() {
    if [[ -z "${FIREWALL_ID:-}" ]]; then
        log "방화벽 그룹 생성"
        api POST /firewalls "$(jq -n --arg d "${APP_NAME}" '{description:$d}')"
        FIREWALL_ID="$(printf '%s' "$API_BODY" | jq -r '.firewall_group.id')"
        [[ -z "$FIREWALL_ID" || "$FIREWALL_ID" == "null" ]] && { echo "ERROR: 방화벽 생성 실패"; exit 1; }
        save_state FIREWALL_ID "$FIREWALL_ID"
    fi

    # SSH 허용 대역: 미지정이면 현재 공인 IP/32 로 자동 제한.
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

    # 이 스크립트가 관리하는 규칙(notes 가 ctb- 로 시작)만 지우고 다시 넣어 멱등성을 보장한다.
    # "규칙이 하나라도 있으면 통과" 로 판정하면 SSH 대역이 바뀌었을 때 옛 규칙이 남아 위험하다.
    api GET "/firewalls/${FIREWALL_ID}/rules?per_page=500"
    local old_ids rid stale=0
    old_ids="$(printf '%s' "$API_BODY" | jq -r '.firewall_rules[]? | select((.notes // "") | startswith("ctb-")) | .id')"
    for rid in $old_ids; do
        # 삭제 실패를 삼키면 옛 규칙(예: 이전 집 IP 로 열린 SSH)이 남은 채 새 규칙이 더해져
        # 접근 범위가 의도보다 넓어진다 — 조용히 넘기지 않고 경고한다.
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
apt-get install -y ca-certificates curl gnupg jq

# Docker 공식 저장소 (Ubuntu 는 기본 repo 의 docker.io 대신 docker-ce 를 쓴다 — compose plugin 포함)
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

systemctl enable --now docker

# DB 백업이 S3 호환 스토리지로 업로드할 때 쓴다(백업을 안 쓰면 그냥 남아있을 뿐).
apt-get install -y awscli || true

# Vultr 클라우드 방화벽이 앞단에서 막지만, 이미지에 ufw 가 켜져 있는 경우를 대비해 함께 연다.
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 22/tcp
    ufw allow 80/tcp
    ufw allow 443/tcp
fi

mkdir -p /opt/app

# 마커는 의존성이 실제로 준비된 뒤에만 남긴다 — 마커가 곧 "배포해도 좋다" 는 신호다.
command -v docker >/dev/null || { echo "FATAL: docker 설치 실패"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "FATAL: docker compose plugin 없음"; exit 1; }
touch /opt/app/.userdata-done
UDEOF
}

launch_instance() {
    if [[ -n "${INSTANCE_ID:-}" ]]; then echo "  기존 인스턴스 사용: $INSTANCE_ID"; return; fi

    # 응답 유실 등으로 이미 만들어졌을 수 있으니 label 로 먼저 확인한다(중복 생성·중복 과금 방지).
    api GET "/instances?per_page=500"
    local existing
    existing="$(printf '%s' "$API_BODY" | jq -r --arg l "$APP_NAME" '.instances[]? | select(.label==$l) | .id' | head -1)"
    if [[ -n "$existing" ]]; then
        echo "  같은 label 의 기존 인스턴스 재사용: $existing"
        INSTANCE_ID="$existing"; save_state INSTANCE_ID "$INSTANCE_ID"; return
    fi

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
    ensure_secrets
    ensure_ssh_key
    setup_firewall
    launch_instance
    wait_for_active

    log "Setup 완료"
    echo "  공인 IP: $PUBLIC_IP"
    echo "  SSH:     ssh -i $KEY_PEM ${SSH_USER}@${PUBLIC_IP}"
    echo "  cloud-init(도커 설치) 완료까지 2~4분 대기 후: ./deploy/vultr/deploy.sh deploy"
}

# StrictHostKeyChecking=accept-new: 최초 접속만 자동 수용, 이후 호스트키 변경은 거부.
ssh_inst() {
    ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -i "$KEY_PEM" \
        "${SSH_USER}@${PUBLIC_IP}" "$@"
}

wait_for_docker() {
    log "인스턴스 준비 대기 (cloud-init + docker)"
    local i
    for i in $(seq 1 40); do
        if ssh_inst 'test -f /opt/app/.userdata-done && docker info' >/dev/null 2>&1; then
            echo "준비 완료"; return 0
        fi
        sleep 10
    done
    echo "ERROR: docker 준비 타임아웃. cloud-init 로그 확인:"
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

# ── deploy ──
# AWS/OCI 판과 동일한 계약(대상 SHA 고정 · migration 게이트 · 자동 롤백 · 헬스체크).
do_deploy() {
    load_state
    [[ -z "${PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    ensure_secrets
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

    # GHCR 토큰은 argv 에 남기지 않는다(같은 호스트의 다른 사용자가 ps 로 읽을 수 있다).
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

# `</dev/null` 필수: 이 스크립트는 ssh 가 `bash -s` 의 stdin(파이프)로 흘려보낸다. exec -T 는 TTY 만
# 끄고 stdin 은 attach 하므로, </dev/null 이 없으면 exec 가 남은 스크립트를 drain 해 실패 경로가 사라진다.
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
    echo "ERROR: 롤백 기동 실패. 수동 개입 필요."
    exit 3
fi
if health_ok; then
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
    case "$deploy_rc" in
        0)
            update_state LAST_GOOD_SHA "$target_sha"
            log "배포 완료 (SHA=${target_sha:0:12} — LAST_GOOD 갱신)"
            echo "  App: https://$domain  (Caddy 가 Let's Encrypt 인증서 발급까지 최대 ~30초)"
            echo "  ⚠️ 2GB 박스이므로 기동 후 실사용량을 확인하세요:  ./deploy/vultr/deploy.sh mem"
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

# 2GB 로 낮췄으므로 실사용량 확인이 중요하다. 제한 대비 여유를 눈으로 보게 한다.
do_mem() {
    load_state
    [[ -z "${PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    ssh_inst 'free -m; echo; docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"'
}

# ── destroy ──
do_destroy() {
    require_vultr
    load_state
    echo "=== Vultr 리소스 삭제 (과금 중단) ==="
    echo "  인스턴스: ${INSTANCE_ID:-none} (${PUBLIC_IP:-none})"
    echo ""
    echo "⚠️  거래 이력과 (암호화된) Upbit 키가 이 인스턴스 디스크에 있습니다."
    echo "    Vultr 인스턴스 삭제는 디스크까지 즉시 소멸시킵니다 — 백업을 먼저 확보하세요."
    read -rp "'yes' 입력: " confirm; [[ "$confirm" != "yes" ]] && { echo "취소"; exit 0; }

    # 삭제 전 최종 백업 (설정돼 있을 때만). 실패하면 중단한다 — 데이터 영구 소멸을 막는다.
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
        # 인스턴스가 참조 중이면 거부될 수 있어 인스턴스 삭제 뒤에 지운다.
        sleep 5
        api DELETE "/firewalls/${FIREWALL_ID}" && echo "  삭제: firewall" || { echo "  실패: firewall" >&2; failed=1; }
    fi
    # SSH 키는 다른 인스턴스에서 재사용할 수 있으므로 남긴다(계정 자산, 과금 없음).
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
