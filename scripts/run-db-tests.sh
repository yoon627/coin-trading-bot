#!/usr/bin/env bash
# 임시 Postgres 를 띄워 DB 통합테스트를 돌리고 끝나면 지운다.
# Testcontainers 는 Docker 29 가 요구하는 API 버전을 협상하지 못해 셸에서 컨테이너를 띄운다.
#
# 사용법:  ./scripts/run-db-tests.sh            (DB 통합테스트만)
#          ./scripts/run-db-tests.sh --all      (전체 테스트)
set -euo pipefail

cd "$(dirname "$0")/.."

# 동시 실행이 서로의 컨테이너를 지우지 않도록 이름·포트를 실행마다 다르게 잡는다.
CTR="coin-trading-bot-db-tests-$$"
IMAGE="postgres:17-alpine"   # 운영과 같은 이미지
# deploy.yml 의 같은 목록과 함께 고친다.
DB_TEST_CLASSES=(TradingStateRoundTripTest TradeRecordRoundTripTest UserSettingsUpdateRoundTripTest ExchangeDimensionRoundTripTest)
xml_of() { echo "bot/build/test-results/test/TEST-com.trading.bot.persistence.$1.xml"; }

cleanup() { [ -n "${CTR_STARTED:-}" ] && docker rm -f "$CTR" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== 임시 Postgres 기동 ($IMAGE)"
docker run -d --name "$CTR" \
    -e POSTGRES_DB=trading -e POSTGRES_USER=trading -e POSTGRES_PASSWORD=trading \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
CTR_STARTED=1
PORT="$(docker port "$CTR" 5432 | head -1 | sed 's/.*://')"
echo "   포트 $PORT (동적 할당)"

for _ in $(seq 1 60); do
    docker exec "$CTR" pg_isready -U trading -d trading >/dev/null 2>&1 && break
    sleep 1
done
docker exec "$CTR" pg_isready -U trading -d trading

if [ "${1:-}" = "--all" ]; then
    TARGET=(test --parallel)
else
    TARGET=(:bot:test)
    for c in "${DB_TEST_CLASSES[@]}"; do TARGET+=(--tests "*$c*"); done
fi

for c in "${DB_TEST_CLASSES[@]}"; do rm -f "$(xml_of "$c")"; done   # 이전 실행 결과를 그대로 통과시키지 않는다

# 재사용된 Gradle daemon 은 기동 시점의 환경변수를 써서 TEST_DB_* 가 보이지 않으므로 --no-daemon 으로 돌린다.
echo "== 테스트 실행"
TEST_DB_HOST=localhost \
TEST_DB_PORT="$PORT" \
TEST_DB_NAME=trading \
TEST_DB_USER=trading \
TEST_DB_PASSWORD=trading \
DB_TESTS_REQUIRED=true \
    ./gradlew "${TARGET[@]}" --rerun-tasks --no-daemon

# gradle 이 성공해도 DB 테스트가 skip 됐으면 실패로 처리한다.
for c in "${DB_TEST_CLASSES[@]}"; do
    XML="$(xml_of "$c")"
    if [ ! -f "$XML" ]; then
        echo "ERROR: 테스트 결과가 없다 — DB 통합테스트가 아예 실행되지 않았다 ($XML)" >&2
        exit 1
    fi
    attrs="$(head -3 "$XML")"
    ran="$(printf '%s' "$attrs" | grep -o 'tests="[0-9]*"' | head -1 | tr -dc '0-9')"
    skipped="$(printf '%s' "$attrs" | grep -o 'skipped="[0-9]*"' | head -1 | tr -dc '0-9')"
    echo "== DB 통합테스트 $c: 실행 ${ran:-?}건 / skip ${skipped:-?}건"
    if [ "${ran:-0}" = "0" ] || [ "${skipped:-1}" != "0" ]; then
        echo "ERROR: DB 통합테스트가 건너뛰어졌다 — 접속 정보가 테스트 JVM 에 전달되지 않았다." >&2
        exit 1
    fi
done
