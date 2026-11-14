#!/usr/bin/env bash
# 직전 정상 이미지가 새 이미지의 마이그레이션을 적용한 스키마 위에서 뜨고 봇 복원 조회까지 하는지 본다(#323). 통과한
# 배포는 deploy.sh 가 마이그레이션이 있어도 자동 롤백을 허용한다. 빈 DB 라 쓰기 경로와 데이터 수준 비호환(옛 코드가 잘못
# 읽는 새 행)은 잡지 못한다.
#
# 사용법: rollback_compat.sh <이미지 저장소> <직전 정상 SHA> <대상 SHA>
# 종료 코드: 0 호환, 1 비호환(직전 이미지가 healthy 가 되지 못했다), 2 검사 불가(입력·postgres·새 이미지 실패)
set -uo pipefail

image="${1:-}" last_good="${2:-}" target="${3:-}"
[[ -n "$image" && "$last_good" =~ ^[0-9a-f]{40}$ && "$target" =~ ^[0-9a-f]{40}$ ]] \
    || { echo "사용법: $0 <이미지 저장소> <직전 정상 SHA 40자> <대상 SHA 40자>" >&2; exit 2; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 운영과 같은 postgres 이미지(digest 고정)를 쓴다.
pg_image="$(awk '/^  postgres:/ {p=1} p && /image:/ {print $2; exit}' "$script_dir/docker-compose.prod.yml")"
[[ -n "$pg_image" ]] || { echo "ERROR: docker-compose.prod.yml 에서 postgres 이미지를 찾지 못했다" >&2; exit 2; }

name="ctb-rollback-compat-$$"
HEALTH_DEADLINE_SECONDS="${HEALTH_DEADLINE_SECONDS:-240}"
cleanup() { docker rm -f "$name-app" "$name-pg" >/dev/null 2>&1; docker network rm "$name" >/dev/null 2>&1; }
trap cleanup EXIT

# 받는 시간이 health 시한을 먹지 않게, 받지 못한 것을 비호환(1)으로 잘못 알리지 않게 먼저 받는다.
for sha in "$target" "$last_good"; do
    docker pull -q "$image:$sha" >/dev/null || { echo "ERROR: $image:$sha 를 받지 못했다 — 검사 불가" >&2; exit 2; }
done

db_password="$(openssl rand -hex 16)"
docker network create "$name" >/dev/null || { echo "ERROR: docker network 생성 실패" >&2; exit 2; }
docker run -d --name "$name-pg" --network "$name" \
    -e POSTGRES_DB=trading -e POSTGRES_USER=trading -e POSTGRES_PASSWORD="$db_password" \
    --health-cmd 'pg_isready -h 127.0.0.1 -U trading' --health-interval 2s --health-retries 30 \
    "$pg_image" >/dev/null || { echo "ERROR: postgres 기동 실패" >&2; exit 2; }

pg_ready=false
for _ in $(seq 1 60); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$name-pg" 2>/dev/null)" == healthy ]] && { pg_ready=true; break; }
    sleep 2
done
[[ "$pg_ready" == true ]] || { echo "ERROR: postgres 가 healthy 가 되지 않았다" >&2; exit 2; }

# 더미 시크릿·알림 끔으로 운영과 같은 prod 프로파일을 띄운다. 운영 시크릿은 쓰지 않는다. 자동 시작을 켜야 복원이
# bot_state 를 조회하고 health 가 그 결과를 반영한다 — 빈 DB 라 복원할 봇이 없어 주문은 나가지 않는다.
app_healthy() {
    local sha="$1" deadline
    docker rm -f "$name-app" >/dev/null 2>&1
    docker run -d --name "$name-app" --network "$name" \
        -e SPRING_PROFILES_ACTIVE=prod \
        -e DB_HOST="$name-pg" -e DB_PORT=5432 -e DB_NAME=trading -e DB_USER=trading -e DB_PASSWORD="$db_password" \
        -e JWT_SECRET="$(openssl rand -hex 32)" -e APP_ENCRYPTION_SECRET="$(openssl rand -hex 32)" \
        -e TRADING_AUTO_START=true -e DISCORD_ERROR_ALERT_ENABLED=false \
        "$image:$sha" >/dev/null || { echo "ERROR: $image:$sha 를 띄우지 못했다" >&2; return 1; }
    deadline=$((SECONDS + HEALTH_DEADLINE_SECONDS))
    while (( SECONDS < deadline )); do
        if timeout -k 5 15 docker exec "$name-app" curl -fsS --max-time 5 http://localhost:8080/actuator/health </dev/null >/dev/null 2>&1; then
            return 0
        fi
        [[ "$(docker inspect -f '{{.State.Running}}' "$name-app" 2>/dev/null)" == true ]] || break
        sleep 5
    done
    echo "--- ${sha:0:12} 로그 끝 ---" >&2
    docker logs --tail 60 "$name-app" >&2 2>&1
    return 1
}

echo "새 이미지 ${target:0:12} 로 마이그레이션 적용"
app_healthy "$target" || { echo "ERROR: 새 이미지가 빈 DB 에서 healthy 가 되지 않았다 — 검사 불가" >&2; exit 2; }
docker stop -t 30 "$name-app" >/dev/null 2>&1

echo "직전 정상 이미지 ${last_good:0:12} 를 새 스키마 위에서 기동"
if app_healthy "$last_good"; then
    echo "롤백 호환: ${last_good:0:12} 가 ${target:0:12} 의 스키마에서 healthy"
    exit 0
fi
echo "롤백 비호환: ${last_good:0:12} 가 ${target:0:12} 의 스키마에서 healthy 가 되지 않았다" >&2
exit 1
