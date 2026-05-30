#!/usr/bin/env bash
set -euo pipefail

# Git Bash (MSYS2) 가 '/dev/xvda' 같은 단일 슬래시 경로를 Windows path 로 자동 변환하는
# 동작 차단. macOS/Linux 에선 이 변수가 무시되므로 영향 없음.
export MSYS_NO_PATHCONV=1

# ============================================================
# Coin Trading Bot - AWS 배포 스크립트 (월 ~50,000 KRW 예산)
#
# EC2 t4g.medium (4GB, ARM/Graviton) + Docker Compose
# 구성: app + PostgreSQL + Redis  (Kafka/collector/모니터링 제외)
# 이미지: GitHub Actions 가 multi-arch 로 GHCR 에 push → 인스턴스는 pull 만.
#
# 사용법:
#   ./deploy/aws/deploy.sh setup    # 1회: 키페어 + VPC + SG + EC2(t4g.medium) 생성
#   ./deploy/aws/deploy.sh deploy   # GHCR 이미지 pull + compose 기동
#   ./deploy/aws/deploy.sh ssh      # EC2 접속
#   ./deploy/aws/deploy.sh status   # 컨테이너 상태
#   ./deploy/aws/deploy.sh logs     # 앱 로그
#   ./deploy/aws/deploy.sh stop     # 전체 중지
#   ./deploy/aws/deploy.sh start    # 전체 시작
#   ./deploy/aws/deploy.sh destroy  # 전체 삭제 (과금 중단)
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_FILE="$SCRIPT_DIR/.state"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.prod.yml"

ENV_FILE="$SCRIPT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
    echo "ERROR: $ENV_FILE not found. Copy .env.example and fill in values:"
    echo "  cp $SCRIPT_DIR/.env.example $ENV_FILE"
    exit 1
fi
source "$ENV_FILE"

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
APP_NAME="${APP_NAME:-coin-trading-bot}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t4g.medium}"   # ARM/Graviton, 4GB
EBS_SIZE_GB="${EBS_SIZE_GB:-20}"
GHCR_IMAGE="${GHCR_IMAGE:-ghcr.io/yoon627/coin-trading-bot}"
KEY_NAME="${APP_NAME}-key"
KEY_PEM="$SCRIPT_DIR/${KEY_NAME}.pem"

# t4g (ARM) 면 arm64, 그 외(t3 등)면 x86_64 AMI 선택.
if [[ "$INSTANCE_TYPE" == t4g.* || "$INSTANCE_TYPE" == t4gd.* || "$INSTANCE_TYPE" == *g.* ]]; then
    AMI_ARCH="arm64"
else
    AMI_ARCH="x86_64"
fi

save_state() { echo "$1=$2" >> "$STATE_FILE"; }
load_state() { if [[ -f "$STATE_FILE" ]]; then source "$STATE_FILE"; fi; }
load_state
log() { echo -e "\n=== $1 ==="; }

require_aws() {
    command -v aws >/dev/null || { echo "ERROR: aws CLI 없음. 설치 후 'aws configure'."; exit 1; }
    aws sts get-caller-identity --region "$AWS_REGION" >/dev/null 2>&1 || {
        echo "ERROR: AWS 자격증명 미설정. 'aws configure' 먼저 실행."; exit 1; }
}

# 비어있는 시크릿을 1회 생성해 .env 에 영속화. APP_ENCRYPTION_SECRET 은 절대 바뀌면 안 됨
# (저장된 Upbit API 키를 복호화하는 AES 키이므로 변경 시 키가 모두 무효화됨).
ensure_secrets() {
    command -v openssl >/dev/null || { echo "ERROR: openssl 필요"; exit 1; }
    local appended=""
    if [[ -z "${DB_PASSWORD:-}" ]]; then
        DB_PASSWORD="$(openssl rand -hex 16)"; appended+=$'\n'"DB_PASSWORD=$DB_PASSWORD"
    fi
    if [[ -z "${JWT_SECRET:-}" ]]; then
        JWT_SECRET="$(openssl rand -base64 48)"; appended+=$'\n'"JWT_SECRET=$JWT_SECRET"
    fi
    if [[ -z "${APP_ENCRYPTION_SECRET:-}" ]]; then
        APP_ENCRYPTION_SECRET="$(openssl rand -base64 48)"; appended+=$'\n'"APP_ENCRYPTION_SECRET=$APP_ENCRYPTION_SECRET"
    fi
    if [[ -n "$appended" ]]; then
        printf '%s\n' "$appended" >> "$ENV_FILE"
        log "시크릿 생성 → $ENV_FILE 에 저장 (백업 권장). APP_ENCRYPTION_SECRET 은 이후 변경 금지!"
    fi
}

# 서버로 올릴 app 전용 .env 렌더 (AWS/GHCR 토큰 등은 제외).
render_server_env() {
    cat > "$1" <<EOF
APP_VERSION=${APP_VERSION:-latest}
UPBIT_ACCESS_KEY=${UPBIT_ACCESS_KEY:-}
UPBIT_SECRET_KEY=${UPBIT_SECRET_KEY:-}
TRADING_TICKERS=${TRADING_TICKERS:-KRW-BTC}
TRADING_STRATEGY=${TRADING_STRATEGY:-volatility_breakout}
TRADING_AUTO_START=${TRADING_AUTO_START:-false}
DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL:-}
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
APP_ENCRYPTION_SECRET=${APP_ENCRYPTION_SECRET}
APP_AUTH_COOKIE_FORCE_INSECURE=${APP_AUTH_COOKIE_FORCE_INSECURE:-false}
EOF
}

# ── setup ──
do_setup() {
    require_aws
    if [[ -f "$STATE_FILE" ]]; then
        echo "이미 setup 완료. deploy 또는 destroy 후 재시도."
        load_state; echo "EC2 IP: ${EC2_PUBLIC_IP:-unknown}"; return
    fi
    ensure_secrets

    # SSH 허용 CIDR: 미지정이면 현재 공인 IP/32 로 자동 제한 (0.0.0.0/0 지양).
    local ssh_cidr="${SSH_ALLOW_CIDR:-}"
    if [[ -z "$ssh_cidr" ]]; then
        local myip; myip="$(curl -s https://checkip.amazonaws.com || true)"
        [[ -n "$myip" ]] && ssh_cidr="${myip}/32" || { echo "ERROR: 공인 IP 감지 실패. .env 에 SSH_ALLOW_CIDR 설정."; exit 1; }
    fi
    local app_cidr="${APP_ALLOW_CIDR:-$ssh_cidr}"   # 기본: 대시보드도 본인 IP 만
    log "SSH 허용: $ssh_cidr / 앱(8080) 허용: $app_cidr"

    log "1/6 Key Pair"
    if aws ec2 describe-key-pairs --key-names "$KEY_NAME" --region "$AWS_REGION" &>/dev/null; then
        echo "기존 키 사용"
    else
        aws ec2 create-key-pair --key-name "$KEY_NAME" --region "$AWS_REGION" \
            --query 'KeyMaterial' --output text > "$KEY_PEM"
        chmod 400 "$KEY_PEM"
    fi

    log "2/6 VPC"
    VPC_ID=$(aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region "$AWS_REGION" --query 'Vpc.VpcId' --output text)
    aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames --region "$AWS_REGION"
    aws ec2 create-tags --resources "$VPC_ID" --tags Key=Name,Value="${APP_NAME}-vpc" --region "$AWS_REGION"
    save_state VPC_ID "$VPC_ID"

    log "3/6 Subnet + Internet Gateway"
    AZ1=$(aws ec2 describe-availability-zones --region "$AWS_REGION" --query 'AvailabilityZones[0].ZoneName' --output text)
    SUBNET_ID=$(aws ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block 10.0.1.0/24 --availability-zone "$AZ1" \
        --region "$AWS_REGION" --query 'Subnet.SubnetId' --output text)
    save_state SUBNET_ID "$SUBNET_ID"

    IGW_ID=$(aws ec2 create-internet-gateway --region "$AWS_REGION" --query 'InternetGateway.InternetGatewayId' --output text)
    aws ec2 attach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID" --region "$AWS_REGION"
    save_state IGW_ID "$IGW_ID"

    RTB_ID=$(aws ec2 describe-route-tables --filters Name=vpc-id,Values="$VPC_ID" --region "$AWS_REGION" \
        --query 'RouteTables[0].RouteTableId' --output text)
    aws ec2 create-route --route-table-id "$RTB_ID" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW_ID" --region "$AWS_REGION" > /dev/null
    save_state RTB_ID "$RTB_ID"

    log "4/6 Security Group (SSH/8080 제한, 그 외 비공개)"
    SG_ID=$(aws ec2 create-security-group --group-name "${APP_NAME}-sg" --description "Trading Bot" \
        --vpc-id "$VPC_ID" --region "$AWS_REGION" --query 'GroupId' --output text)
    aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 22   --cidr "$ssh_cidr" --region "$AWS_REGION" > /dev/null
    aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port 8080 --cidr "$app_cidr" --region "$AWS_REGION" > /dev/null
    save_state SG_ID "$SG_ID"

    log "5/6 EC2 ($INSTANCE_TYPE, $AMI_ARCH)"
    AMI_ID=$(aws ec2 describe-images --owners amazon \
        --filters "Name=name,Values=al2023-ami-2023.*-${AMI_ARCH}" "Name=state,Values=available" \
        --region "$AWS_REGION" --query 'sort_by(Images, &CreationDate)[-1].ImageId' --output text)

    USERDATA=$(cat <<'UDEOF'
#!/bin/bash
set -ex
dnf update -y
dnf install -y docker
systemctl enable docker && systemctl start docker
usermod -aG docker ec2-user
mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
mkdir -p /opt/app
chown -R ec2-user:ec2-user /opt/app
touch /opt/app/.userdata-done
UDEOF
)

    INSTANCE_ID=$(aws ec2 run-instances --image-id "$AMI_ID" --instance-type "$INSTANCE_TYPE" \
        --key-name "$KEY_NAME" --subnet-id "$SUBNET_ID" --security-group-ids "$SG_ID" \
        --associate-public-ip-address \
        --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=${EBS_SIZE_GB},VolumeType=gp3}" \
        --user-data "$USERDATA" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${APP_NAME}-ec2}]" \
        --region "$AWS_REGION" --query 'Instances[0].InstanceId' --output text)
    save_state INSTANCE_ID "$INSTANCE_ID"
    echo "인스턴스: $INSTANCE_ID"
    aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$AWS_REGION"

    log "6/6 Elastic IP"
    EIP_ALLOC=$(aws ec2 allocate-address --domain vpc --region "$AWS_REGION" --query 'AllocationId' --output text)
    aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$EIP_ALLOC" --region "$AWS_REGION" > /dev/null
    save_state EIP_ALLOC_ID "$EIP_ALLOC"
    EC2_PUBLIC_IP=$(aws ec2 describe-addresses --allocation-ids "$EIP_ALLOC" --region "$AWS_REGION" \
        --query 'Addresses[0].PublicIp' --output text)
    save_state EC2_PUBLIC_IP "$EC2_PUBLIC_IP"

    log "Setup 완료"
    echo "  EC2 IP: $EC2_PUBLIC_IP"
    echo "  SSH:    ssh -i $KEY_PEM ec2-user@$EC2_PUBLIC_IP"
    echo "  Docker 설치 완료까지 1~2분 대기 후: ./deploy/aws/deploy.sh deploy"
}

ssh_ec2() { ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" "$@"; }

wait_for_docker() {
    log "인스턴스 준비 대기 (docker)"
    for i in $(seq 1 40); do
        if ssh_ec2 'test -f /opt/app/.userdata-done && docker info' >/dev/null 2>&1; then
            echo "준비 완료"; return 0
        fi
        sleep 10
    done
    echo "ERROR: docker 준비 타임아웃"; exit 1
}

# ── deploy ──
do_deploy() {
    require_aws
    load_state
    [[ -z "${EC2_PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }
    ensure_secrets
    wait_for_docker

    log "설정 업로드"
    local tmp_env; tmp_env="$(mktemp)"; render_server_env "$tmp_env"
    ssh_ec2 'mkdir -p /opt/app'
    scp -o StrictHostKeyChecking=no -i "$KEY_PEM" "$COMPOSE_FILE" ec2-user@"$EC2_PUBLIC_IP":/opt/app/docker-compose.yml
    scp -o StrictHostKeyChecking=no -i "$KEY_PEM" "$tmp_env"     ec2-user@"$EC2_PUBLIC_IP":/opt/app/.env
    rm -f "$tmp_env"

    log "컨테이너 배포 (GHCR pull)"
    GHCR_USERNAME="${GHCR_USERNAME:-}" GHCR_TOKEN="${GHCR_TOKEN:-}" \
    ssh_ec2 "GHCR_USERNAME='${GHCR_USERNAME:-}' GHCR_TOKEN='${GHCR_TOKEN:-}' bash -s" <<'REMOTE'
set -e
cd /opt/app
# private GHCR 패키지면 토큰으로 로그인. public 이면 생략 가능.
if [ -n "$GHCR_TOKEN" ]; then
    echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin
fi
docker compose pull
docker compose up -d --remove-orphans
echo "헬스체크 대기..."
healthy=false
for i in $(seq 1 36); do
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "App healthy!"; healthy=true; break
    fi
    sleep 5
done
docker compose ps
if [ "$healthy" = "false" ]; then
    echo "ERROR: 180s 내 헬스체크 실패"
    docker compose logs --tail=120 app || true
    exit 1
fi
docker image prune -f
REMOTE

    echo ""
    log "배포 완료"
    echo "  App: http://$EC2_PUBLIC_IP:8080  (security group 가 허용한 IP 에서만 접근)"
}

# ── utils ──
do_ssh() { load_state; ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP"; }
do_status() { load_state; echo "EC2 IP: ${EC2_PUBLIC_IP:-none}"; [[ -n "${EC2_PUBLIC_IP:-}" ]] && ssh_ec2 'cd /opt/app && docker compose ps' 2>/dev/null || echo "(unreachable)"; }
do_logs()   { load_state; ssh_ec2 'cd /opt/app && docker compose logs --tail=120 app'; }
do_stop()   { load_state; ssh_ec2 'cd /opt/app && docker compose down && echo 중지'; }
do_start()  { load_state; ssh_ec2 'cd /opt/app && docker compose up -d && echo 시작'; }

# ── destroy ──
do_destroy() {
    require_aws
    load_state
    echo "=== 모든 AWS 리소스 삭제 (과금 중단) ==="
    read -rp "'yes' 입력: " confirm; [[ "$confirm" != "yes" ]] && { echo "취소"; exit 0; }

    [[ -n "${INSTANCE_ID:-}" ]] && {
        log "EC2 종료"
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" > /dev/null 2>&1 || true
        aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" 2>/dev/null || true
    }
    [[ -n "${EIP_ALLOC_ID:-}" ]] && aws ec2 release-address --allocation-id "$EIP_ALLOC_ID" --region "$AWS_REGION" 2>/dev/null || true
    [[ -n "${SG_ID:-}" ]] && aws ec2 delete-security-group --group-id "$SG_ID" --region "$AWS_REGION" 2>/dev/null || true
    [[ -n "${SUBNET_ID:-}" ]] && aws ec2 delete-subnet --subnet-id "$SUBNET_ID" --region "$AWS_REGION" 2>/dev/null || true
    [[ -n "${IGW_ID:-}" && -n "${VPC_ID:-}" ]] && {
        aws ec2 detach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID" --region "$AWS_REGION" 2>/dev/null || true
        aws ec2 delete-internet-gateway --internet-gateway-id "$IGW_ID" --region "$AWS_REGION" 2>/dev/null || true
    }
    [[ -n "${VPC_ID:-}" ]] && aws ec2 delete-vpc --vpc-id "$VPC_ID" --region "$AWS_REGION" 2>/dev/null || true
    aws ec2 delete-key-pair --key-name "$KEY_NAME" --region "$AWS_REGION" 2>/dev/null || true
    rm -f "$KEY_PEM" "$STATE_FILE"
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
