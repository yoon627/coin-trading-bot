#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Coin Trading Bot - AWS CLI 배포 스크립트 (프리티어)
#
# 사전 준비:
#   1. AWS CLI 설치 + aws configure (리전: ap-northeast-2)
#   2. deploy/aws/.env 파일 작성 (.env.example 참고)
#
# 사용법:
#   ./deploy/aws/deploy.sh setup    # 1회: 키페어 + 인프라 생성
#   ./deploy/aws/deploy.sh deploy   # 앱 빌드 + EC2 배포
#   ./deploy/aws/deploy.sh ssh      # EC2 접속
#   ./deploy/aws/deploy.sh status   # 상태 확인
#   ./deploy/aws/deploy.sh logs     # 앱 로그
#   ./deploy/aws/deploy.sh stop     # 앱 중지
#   ./deploy/aws/deploy.sh start    # 앱 시작
#   ./deploy/aws/deploy.sh destroy  # 전체 삭제
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_FILE="$SCRIPT_DIR/.state"

# .env 로드
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

# 리소스 ID 저장/로드
save_state() { echo "$1=$2" >> "$STATE_FILE"; }
load_state() {
    if [[ -f "$STATE_FILE" ]]; then
        source "$STATE_FILE"
    fi
}
load_state

log() { echo -e "\n=== $1 ==="; }

# ──────────────────────────────────────────────
# setup: 키페어 생성 + VPC/서브넷/SG/RDS/EC2 생성
# ──────────────────────────────────────────────
do_setup() {
    if [[ -f "$STATE_FILE" ]]; then
        echo "이미 setup이 완료되었습니다. destroy 후 다시 시도하세요."
        echo "또는 deploy 명령으로 앱만 배포하세요."
        load_state
        echo "EC2 IP: ${EC2_PUBLIC_IP:-unknown}"
        return
    fi

    log "1/8 Key Pair 생성"
    if aws ec2 describe-key-pairs --key-names "$KEY_NAME" --region "$AWS_REGION" &>/dev/null; then
        echo "Key pair '$KEY_NAME' 이미 존재. 기존 .pem 파일을 사용합니다."
    else
        aws ec2 create-key-pair \
            --key-name "$KEY_NAME" \
            --region "$AWS_REGION" \
            --query 'KeyMaterial' --output text > "$KEY_PEM"
        chmod 400 "$KEY_PEM"
        echo "생성 완료: $KEY_PEM"
    fi

    log "2/8 VPC 생성"
    VPC_ID=$(aws ec2 create-vpc \
        --cidr-block 10.0.0.0/16 \
        --region "$AWS_REGION" \
        --query 'Vpc.VpcId' --output text)
    aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames --region "$AWS_REGION"
    aws ec2 create-tags --resources "$VPC_ID" --tags Key=Name,Value="${APP_NAME}-vpc" --region "$AWS_REGION"
    save_state VPC_ID "$VPC_ID"
    echo "VPC: $VPC_ID"

    log "3/8 서브넷 생성"
    AZS=$(aws ec2 describe-availability-zones --region "$AWS_REGION" --query 'AvailabilityZones[0:2].ZoneName' --output text)
    AZ1=$(echo "$AZS" | awk '{print $1}')
    AZ2=$(echo "$AZS" | awk '{print $2}')

    SUBNET1_ID=$(aws ec2 create-subnet \
        --vpc-id "$VPC_ID" --cidr-block 10.0.1.0/24 \
        --availability-zone "$AZ1" \
        --region "$AWS_REGION" \
        --query 'Subnet.SubnetId' --output text)
    aws ec2 create-tags --resources "$SUBNET1_ID" --tags Key=Name,Value="${APP_NAME}-subnet-1" --region "$AWS_REGION"
    save_state SUBNET1_ID "$SUBNET1_ID"

    SUBNET2_ID=$(aws ec2 create-subnet \
        --vpc-id "$VPC_ID" --cidr-block 10.0.2.0/24 \
        --availability-zone "$AZ2" \
        --region "$AWS_REGION" \
        --query 'Subnet.SubnetId' --output text)
    aws ec2 create-tags --resources "$SUBNET2_ID" --tags Key=Name,Value="${APP_NAME}-subnet-2" --region "$AWS_REGION"
    save_state SUBNET2_ID "$SUBNET2_ID"
    echo "서브넷: $SUBNET1_ID, $SUBNET2_ID"

    log "4/8 인터넷 게이트웨이 + 라우팅"
    IGW_ID=$(aws ec2 create-internet-gateway \
        --region "$AWS_REGION" \
        --query 'InternetGateway.InternetGatewayId' --output text)
    aws ec2 attach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID" --region "$AWS_REGION"
    save_state IGW_ID "$IGW_ID"

    RTB_ID=$(aws ec2 describe-route-tables \
        --filters Name=vpc-id,Values="$VPC_ID" \
        --region "$AWS_REGION" \
        --query 'RouteTables[0].RouteTableId' --output text)
    aws ec2 create-route --route-table-id "$RTB_ID" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW_ID" --region "$AWS_REGION"
    save_state RTB_ID "$RTB_ID"
    echo "IGW: $IGW_ID"

    log "5/8 보안 그룹 생성"
    EC2_SG_ID=$(aws ec2 create-security-group \
        --group-name "${APP_NAME}-ec2-sg" \
        --description "EC2 SSH + App" \
        --vpc-id "$VPC_ID" \
        --region "$AWS_REGION" \
        --query 'GroupId' --output text)
    aws ec2 authorize-security-group-ingress --group-id "$EC2_SG_ID" --protocol tcp --port 22 --cidr "${SSH_ALLOW_CIDR:-0.0.0.0/0}" --region "$AWS_REGION"
    aws ec2 authorize-security-group-ingress --group-id "$EC2_SG_ID" --protocol tcp --port 8080 --cidr "0.0.0.0/0" --region "$AWS_REGION"
    save_state EC2_SG_ID "$EC2_SG_ID"

    DB_SG_ID=$(aws ec2 create-security-group \
        --group-name "${APP_NAME}-db-sg" \
        --description "RDS from EC2 only" \
        --vpc-id "$VPC_ID" \
        --region "$AWS_REGION" \
        --query 'GroupId' --output text)
    aws ec2 authorize-security-group-ingress --group-id "$DB_SG_ID" --protocol tcp --port 5432 --source-group "$EC2_SG_ID" --region "$AWS_REGION"
    save_state DB_SG_ID "$DB_SG_ID"
    echo "보안그룹 EC2: $EC2_SG_ID, DB: $DB_SG_ID"

    log "6/8 RDS PostgreSQL 생성 (프리티어: db.t3.micro)"
    aws rds create-db-subnet-group \
        --db-subnet-group-name "${APP_NAME}-db-subnet" \
        --db-subnet-group-description "Trading bot DB subnets" \
        --subnet-ids "$SUBNET1_ID" "$SUBNET2_ID" \
        --region "$AWS_REGION" > /dev/null
    save_state DB_SUBNET_GROUP "${APP_NAME}-db-subnet"

    aws rds create-db-instance \
        --db-instance-identifier "${APP_NAME}-db" \
        --engine postgres \
        --engine-version "17.4" \
        --db-instance-class db.t3.micro \
        --allocated-storage 20 \
        --storage-type gp2 \
        --db-name trading \
        --master-username trading \
        --master-user-password "$DB_MASTER_PASSWORD" \
        --vpc-security-group-ids "$DB_SG_ID" \
        --db-subnet-group-name "${APP_NAME}-db-subnet" \
        --no-publicly-accessible \
        --backup-retention-period 7 \
        --no-multi-az \
        --region "$AWS_REGION" > /dev/null
    save_state DB_INSTANCE_ID "${APP_NAME}-db"
    echo "RDS 생성 시작... (약 5-10분 소요)"

    echo "RDS가 available 될 때까지 대기 중..."
    aws rds wait db-instance-available \
        --db-instance-identifier "${APP_NAME}-db" \
        --region "$AWS_REGION"

    DB_ENDPOINT=$(aws rds describe-db-instances \
        --db-instance-identifier "${APP_NAME}-db" \
        --region "$AWS_REGION" \
        --query 'DBInstances[0].Endpoint.Address' --output text)
    save_state DB_ENDPOINT "$DB_ENDPOINT"
    echo "RDS 엔드포인트: $DB_ENDPOINT"

    log "7/8 EC2 인스턴스 생성 (프리티어: t2.micro)"
    # Amazon Linux 2023 AMI 조회
    AMI_ID=$(aws ec2 describe-images \
        --owners amazon \
        --filters "Name=name,Values=al2023-ami-2023.*-x86_64" "Name=state,Values=available" \
        --region "$AWS_REGION" \
        --query 'sort_by(Images, &CreationDate)[-1].ImageId' --output text)
    echo "AMI: $AMI_ID"

    # UserData 스크립트 생성
    USERDATA=$(cat <<UDEOF
#!/bin/bash
set -ex
dnf update -y
dnf install -y docker git java-21-amazon-corretto-headless
systemctl enable docker && systemctl start docker
usermod -aG docker ec2-user

mkdir -p /opt/app
cat > /opt/app/.env << 'ENVEOF'
SPRING_PROFILES_ACTIVE=prod
DB_HOST=${DB_ENDPOINT}
DB_PORT=5432
DB_NAME=trading
DB_USER=trading
DB_PASSWORD=${DB_MASTER_PASSWORD}
UPBIT_ACCESS_KEY=${UPBIT_ACCESS_KEY}
UPBIT_SECRET_KEY=${UPBIT_SECRET_KEY}
TRADING_TICKERS=${TRADING_TICKERS:-KRW-BTC}
TRADING_STRATEGY=${TRADING_STRATEGY:-volatility_breakout}
TRADING_AUTO_START=${TRADING_AUTO_START:-false}
DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL:-}
ENVEOF
chown -R ec2-user:ec2-user /opt/app
UDEOF
)

    INSTANCE_ID=$(aws ec2 run-instances \
        --image-id "$AMI_ID" \
        --instance-type t2.micro \
        --key-name "$KEY_NAME" \
        --subnet-id "$SUBNET1_ID" \
        --security-group-ids "$EC2_SG_ID" \
        --associate-public-ip-address \
        --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp2}' \
        --user-data "$USERDATA" \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${APP_NAME}-ec2}]" \
        --region "$AWS_REGION" \
        --query 'Instances[0].InstanceId' --output text)
    save_state INSTANCE_ID "$INSTANCE_ID"
    echo "EC2 인스턴스: $INSTANCE_ID (시작 중...)"

    aws ec2 wait instance-running --instance-ids "$INSTANCE_ID" --region "$AWS_REGION"

    EC2_PUBLIC_IP=$(aws ec2 describe-instances \
        --instance-ids "$INSTANCE_ID" \
        --region "$AWS_REGION" \
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
    save_state EC2_PUBLIC_IP "$EC2_PUBLIC_IP"

    log "8/8 Setup 완료!"
    echo ""
    echo "  EC2 IP:       $EC2_PUBLIC_IP"
    echo "  RDS Endpoint: $DB_ENDPOINT"
    echo "  SSH:          ssh -i $KEY_PEM ec2-user@$EC2_PUBLIC_IP"
    echo ""
    echo "  UserData(Docker 설치)가 완료될 때까지 2-3분 대기 후"
    echo "  ./deploy/aws/deploy.sh deploy 를 실행하세요."
}

# ──────────────────────────────────────────────
# deploy: JAR 빌드 + EC2로 전송 + 실행
# ──────────────────────────────────────────────
do_deploy() {
    load_state
    if [[ -z "${EC2_PUBLIC_IP:-}" ]]; then
        echo "ERROR: setup을 먼저 실행하세요: ./deploy/aws/deploy.sh setup"
        exit 1
    fi

    log "JAR 빌드"
    cd "$PROJECT_DIR"
    ./gradlew bootJar -x test
    JAR_PATH=$(ls -t "$PROJECT_DIR/build/libs"/*.jar | grep -v plain | head -1)
    echo "빌드 완료: $JAR_PATH"

    log "SSH 대기"
    for i in $(seq 1 20); do
        if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" "echo ok" &>/dev/null; then
            break
        fi
        echo "  연결 대기 ($i/20)..."
        sleep 10
    done

    log "JAR 업로드 → EC2 ($EC2_PUBLIC_IP)"
    scp -o StrictHostKeyChecking=no -i "$KEY_PEM" \
        "$JAR_PATH" ec2-user@"$EC2_PUBLIC_IP":/opt/app/app.jar

    log "앱 실행"
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" bash <<'REMOTE'
set -e
cd /opt/app
# 기존 프로세스 종료
pkill -f 'java.*app.jar' 2>/dev/null || true
sleep 2
# 환경변수 로드 + 백그라운드 실행
set -a; source .env; set +a
nohup java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -jar app.jar \
    > /opt/app/app.log 2>&1 &
echo "PID: $!"
sleep 3
echo "=== 최근 로그 ==="
tail -20 /opt/app/app.log
REMOTE

    echo ""
    log "배포 완료!"
    echo ""
    echo "  API:    http://$EC2_PUBLIC_IP:8080/api/bot/status"
    echo "  Health: http://$EC2_PUBLIC_IP:8080/actuator/health"
    echo "  로그:   ./deploy/aws/deploy.sh logs"
    echo "  SSH:    ./deploy/aws/deploy.sh ssh"
}

# ──────────────────────────────────────────────
# 유틸리티 명령
# ──────────────────────────────────────────────
do_ssh() {
    load_state
    echo "접속: ec2-user@$EC2_PUBLIC_IP"
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP"
}

do_status() {
    load_state
    echo "=== 리소스 상태 ==="
    echo "EC2 IP:       ${EC2_PUBLIC_IP:-not created}"
    echo "RDS Endpoint: ${DB_ENDPOINT:-not created}"
    echo "Instance ID:  ${INSTANCE_ID:-not created}"

    if [[ -n "${INSTANCE_ID:-}" ]]; then
        echo ""
        STATE=$(aws ec2 describe-instances \
            --instance-ids "$INSTANCE_ID" \
            --region "$AWS_REGION" \
            --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo "unknown")
        echo "EC2 State: $STATE"
    fi

    if [[ -n "${EC2_PUBLIC_IP:-}" ]]; then
        echo ""
        echo "=== 앱 상태 ==="
        curl -s --connect-timeout 5 "http://$EC2_PUBLIC_IP:8080/api/bot/status" 2>/dev/null \
            | python3 -m json.tool 2>/dev/null \
            || echo "(응답 없음 - 앱이 실행 중이 아닐 수 있습니다)"
    fi
}

do_logs() {
    load_state
    echo "=== 앱 로그 (최근 100줄) ==="
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" \
        'tail -100 /opt/app/app.log'
}

do_stop() {
    load_state
    echo "앱 중지 중..."
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" \
        'pkill -f "java.*app.jar" 2>/dev/null && echo "중지 완료" || echo "실행 중인 앱 없음"'
}

do_start() {
    load_state
    echo "앱 시작 중..."
    ssh -o StrictHostKeyChecking=no -i "$KEY_PEM" ec2-user@"$EC2_PUBLIC_IP" bash <<'REMOTE'
cd /opt/app
set -a; source .env; set +a
nohup java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -jar app.jar \
    > /opt/app/app.log 2>&1 &
echo "시작 완료 (PID: $!)"
REMOTE
}

# ──────────────────────────────────────────────
# destroy: 모든 리소스 삭제
# ──────────────────────────────────────────────
do_destroy() {
    load_state
    echo "=== 경고: 모든 AWS 리소스를 삭제합니다 ==="
    echo "  EC2: ${INSTANCE_ID:-없음}"
    echo "  RDS: ${DB_INSTANCE_ID:-없음}"
    echo "  VPC: ${VPC_ID:-없음}"
    echo ""
    read -rp "'yes'를 입력하여 확인: " confirm
    if [[ "$confirm" != "yes" ]]; then
        echo "취소됨."
        exit 0
    fi

    # 1. EC2 종료
    if [[ -n "${INSTANCE_ID:-}" ]]; then
        log "EC2 종료: $INSTANCE_ID"
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" > /dev/null 2>&1 || true
        echo "EC2 종료 대기 중..."
        aws ec2 wait instance-terminated --instance-ids "$INSTANCE_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 2. RDS 삭제
    if [[ -n "${DB_INSTANCE_ID:-}" ]]; then
        log "RDS 삭제: $DB_INSTANCE_ID"
        aws rds delete-db-instance \
            --db-instance-identifier "$DB_INSTANCE_ID" \
            --skip-final-snapshot \
            --region "$AWS_REGION" > /dev/null 2>&1 || true
        echo "RDS 삭제 대기 중... (약 3-5분)"
        aws rds wait db-instance-deleted --db-instance-identifier "$DB_INSTANCE_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 3. DB 서브넷 그룹 삭제
    if [[ -n "${DB_SUBNET_GROUP:-}" ]]; then
        aws rds delete-db-subnet-group --db-subnet-group-name "$DB_SUBNET_GROUP" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 4. 보안 그룹 삭제
    if [[ -n "${EC2_SG_ID:-}" ]]; then
        aws ec2 delete-security-group --group-id "$EC2_SG_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi
    if [[ -n "${DB_SG_ID:-}" ]]; then
        aws ec2 delete-security-group --group-id "$DB_SG_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 5. 서브넷 삭제
    if [[ -n "${SUBNET1_ID:-}" ]]; then
        aws ec2 delete-subnet --subnet-id "$SUBNET1_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi
    if [[ -n "${SUBNET2_ID:-}" ]]; then
        aws ec2 delete-subnet --subnet-id "$SUBNET2_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 6. IGW 분리 + 삭제
    if [[ -n "${IGW_ID:-}" && -n "${VPC_ID:-}" ]]; then
        aws ec2 detach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID" --region "$AWS_REGION" 2>/dev/null || true
        aws ec2 delete-internet-gateway --internet-gateway-id "$IGW_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 7. VPC 삭제
    if [[ -n "${VPC_ID:-}" ]]; then
        aws ec2 delete-vpc --vpc-id "$VPC_ID" --region "$AWS_REGION" 2>/dev/null || true
    fi

    # 8. Key Pair 삭제
    aws ec2 delete-key-pair --key-name "$KEY_NAME" --region "$AWS_REGION" 2>/dev/null || true
    rm -f "$KEY_PEM"

    # 상태 파일 삭제
    rm -f "$STATE_FILE"

    log "모든 리소스 삭제 완료!"
}

# ---- Main ----

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
        echo "Coin Trading Bot - AWS CLI 배포"
        echo ""
        echo "사용법: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  setup    최초 1회: 키페어 + VPC + RDS + EC2 생성"
        echo "  deploy   앱 빌드 + EC2 배포"
        echo "  ssh      EC2 SSH 접속"
        echo "  status   리소스 + 앱 상태 확인"
        echo "  logs     앱 로그 확인"
        echo "  stop     앱 중지"
        echo "  start    앱 시작"
        echo "  destroy  모든 리소스 삭제"
        exit 1
        ;;
esac
