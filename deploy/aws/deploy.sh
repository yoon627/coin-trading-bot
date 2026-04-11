#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Coin Trading Bot - AWS CLI 배포 스크립트 (프리티어)
#
# EC2 t2.micro + Docker Compose (전체 컨테이너화)
# 구성: App + PostgreSQL + Redis + Prometheus + Loki + Grafana
#
# 사용법:
#   ./deploy/aws/deploy.sh setup    # 1회: 키페어 + VPC + EC2 생성
#   ./deploy/aws/deploy.sh deploy   # docker-compose 배포
#   ./deploy/aws/deploy.sh ssh      # EC2 접속
#   ./deploy/aws/deploy.sh status   # 상태 확인
#   ./deploy/aws/deploy.sh logs     # 앱 로그
#   ./deploy/aws/deploy.sh stop     # 전체 중지
#   ./deploy/aws/deploy.sh start    # 전체 시작
#   ./deploy/aws/deploy.sh destroy  # 전체 삭제
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_FILE="$SCRIPT_DIR/.state"

ENV_FILE="$SCRIPT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
    echo "ERROR: $ENV_FILE not found. Copy .env.example and fill in values."
    exit 1
fi
source "$ENV_FILE"

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
APP_NAME="${APP_NAME:-coin-trading-bot}"
KEY_NAME="${APP_NAME}-key"
KEY_PEM="$SCRIPT_DIR/${KEY_NAME}.pem"

save_state() { echo "$1=$2" >> "$STATE_FILE"; }
load_state() { [[ -f "$STATE_FILE" ]] && source "$STATE_FILE"; }
load_state
log() { echo -e "\n=== $1 ==="; }

# ── setup ──
do_setup() {
    if [[ -f "$STATE_FILE" ]]; then
        echo "이미 setup 완료. deploy 또는 destroy 후 다시 시도."
        load_state; echo "EC2 IP: ${EC2_PUBLIC_IP:-unknown}"; return
    fi

    log "1/6 Key Pair 생성"
    if aws ec2 describe-key-pairs --key-names "$KEY_NAME" --region "$AWS_REGION" &>/dev/null; then
        echo "기존 키 사용"
    else
        aws ec2 create-key-pair --key-name "$KEY_NAME" --region "$AWS_REGION" \
            --query 'KeyMaterial' --output text > "$KEY_PEM"
        chmod 400 "$KEY_PEM"
    fi

    log "2/6 VPC 생성"
    VPC_ID=$(aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region "$AWS_REGION" --query 'Vpc.VpcId' --output text)
    aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames --region "$AWS_REGION"
    aws ec2 create-tags --resources "$VPC_ID" --tags Key=Name,Value="${APP_NAME}-vpc" --region "$AWS_REGION"
    save_state VPC_ID "$VPC_ID"

    log "3/6 서브넷 + 인터넷 게이트웨이"
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

    log "4/6 보안 그룹"
    SG_ID=$(aws ec2 create-security-group --group-name "${APP_NAME}-sg" --description "Trading Bot" \
        --vpc-id "$VPC_ID" --region "$AWS_REGION" --query 'GroupId' --output text)
    for port in 22 80 443 8080 3000 9090; do
        aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port "$port" --cidr "0.0.0.0/0" --region "$AWS_REGION" > /dev/null
    done
    save_state SG_ID "$SG_ID"

    log "5/6 EC2 인스턴스 (t2.micro)"
    AMI_ID=$(aws ec2 describe-images --owners amazon \
        --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
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
mkdir -p /opt/app/monitoring
chown -R ec2-user:ec2-user /opt/app
UDEOF
)

    INSTANCE_ID=$(aws ec2 run-instances --image-id "$AMI_ID" --instance-type t2.micro \
        --key-name "$KEY_NAME" --subnet-id "$SUBNET_ID" --security-group-ids "$SG_ID" \
        --associate-public-ip-address --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp2}' \
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
    sed -i '' "s/^EC2_PUBLIC_IP=.*/EC2_PUBLIC_IP=$EC2_PUBLIC_IP/" "$STATE_FILE" 2>/dev/null || \
        save_state EC2_PUBLIC_IP "$EC2_PUBLIC_IP"

    log "Setup 완료!"
    echo "  EC2 IP: $EC2_PUBLIC_IP"
    echo "  SSH:    ssh -i $KEY_PEM ec2-user@$EC2_PUBLIC_IP"
    echo ""
    echo "  Docker 설치 완료 대기 후 ./deploy/aws/deploy.sh deploy"
}

# ── deploy ──
do_deploy() {
    load_state
    [[ -z "${EC2_PUBLIC_IP:-}" ]] && { echo "ERROR: setup 먼저 실행"; exit 1; }

    log "Docker 이미지 빌드 & 푸시"
    cd "$PROJECT_DIR"
    ./gradlew bootJar -x test
    docker build -t ghcr.io/yoon627/coin-trading-bot:latest .
    docker push ghcr.io/yoon627/coin-trading-bot:latest

    log "설정 파일 업로드"
    scp -o StrictHostKeyChecking=no -i "$KEY_PEM" \
        docker-compose.yml ec2-user@"$EC2_PUBLIC_IP":/opt/app/docker-compose.yml
    scp -o StrictHostKeyChecking=no -i "$KEY_PEM" \
        -r monitoring ec2-user@"$EC2_PUBLIC_IP":/opt/app/monitoring

    log "컨테이너 배포"
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" bash <<'REMOTE'
cd /opt/app
docker compose pull app
docker compose up -d --remove-orphans
echo "Waiting for health check..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "App is healthy!"
        break
    fi
    sleep 5
done
docker compose ps
docker image prune -f
REMOTE

    echo ""
    log "배포 완료!"
    echo "  App:      http://$EC2_PUBLIC_IP:8080"
    echo "  Grafana:  http://$EC2_PUBLIC_IP:3000"
}

# ── utils ──
do_ssh() { load_state; ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP"; }

do_status() {
    load_state
    echo "EC2 IP: ${EC2_PUBLIC_IP:-none}"
    [[ -n "${EC2_PUBLIC_IP:-}" ]] && {
        ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" 'cd /opt/app && docker compose ps' 2>/dev/null || echo "(not reachable)"
    }
}

do_logs() {
    load_state
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" \
        'cd /opt/app && docker compose logs --tail=100 app'
}

do_stop() {
    load_state
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" \
        'cd /opt/app && docker compose down && echo "중지 완료"'
}

do_start() {
    load_state
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" \
        'cd /opt/app && docker compose up -d && echo "시작 완료"'
}

# ── destroy ──
do_destroy() {
    load_state
    echo "=== 모든 AWS 리소스 삭제 ==="
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

    log "삭제 완료!"
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
    *)
        echo "사용법: $0 {setup|deploy|ssh|status|logs|stop|start|destroy}"
        exit 1 ;;
esac
