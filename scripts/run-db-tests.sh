#!/usr/bin/env bash
# DB 통합테스트를 로컬에서 실행한다 — 임시 Postgres 를 띄우고, 끝나면 지운다.
#
# 왜 Testcontainers 를 쓰지 않나: docker-java 가 협상하는 Docker API 버전(1.32)이 Docker 29 의
# 최소 지원(1.40)보다 낮아 컨테이너를 띄우지 못한다(testcontainers-java#11212). 컨테이너를 셸에서
# 띄우고 접속 정보만 넘기면 Docker 버전과 무관해진다.
#
# 사용법:  ./scripts/run-db-tests.sh            (DB 통합테스트만)
#          ./scripts/run-db-tests.sh --all      (전체 테스트)
set -euo pipefail

cd "$(dirname "$0")/.."

# 이름·포트를 실행마다 다르게 잡는다 — 고정하면 동시 실행 시 서로의 컨테이너를 지우고 포트도 겹친다.
CTR="coin-trading-bot-db-tests-$$"
IMAGE="postgres:17-alpine"   # 운영과 같은 이미지
XML="bot/build/test-results/test/TEST-com.trading.bot.persistence.TradingStateRoundTripTest.xml"

# 내가 만든 컨테이너만 지운다(이름에 PID 가 있으므로 남의 것과 겹치지 않는다).
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
    TARGET=(:bot:test --tests "*TradingStateRoundTripTest*")
fi

rm -f "$XML"   # 이전 실행 결과를 그대로 통과시키지 않는다

# --no-daemon 이 필요한 이유: Gradle daemon 은 환경변수를 기동 시점에 고정한다. TEST_DB_* 없이
# ./gradlew test 를 돌린 적이 있으면 그 daemon 이 재사용되면서 여기서 세운 값이 테스트에 보이지
# 않고, DB_TESTS_REQUIRED 까지 안 보여 강제 실패 장치마저 무력화된다 — 3건이 조용히 skip 되는데
# 스크립트는 성공으로 끝난다.
echo "== 테스트 실행"
TEST_DB_HOST=localhost \
TEST_DB_PORT="$PORT" \
TEST_DB_NAME=trading \
TEST_DB_USER=trading \
TEST_DB_PASSWORD=trading \
DB_TESTS_REQUIRED=true \
    ./gradlew "${TARGET[@]}" --rerun-tasks --no-daemon

# 마지막 방어선: 어떤 이유로든(daemon 환경 고정·오타·설정 누락) 건너뛰어졌으면 실패로 만든다.
# gradle 이 성공으로 끝나도 검증이 안 됐으면 성공이 아니다.
if [ ! -f "$XML" ]; then
    echo "ERROR: 테스트 결과가 없다 — DB 통합테스트가 아예 실행되지 않았다 ($XML)" >&2
    exit 1
fi

attrs="$(head -3 "$XML")"
ran="$(printf '%s' "$attrs" | grep -o 'tests="[0-9]*"' | head -1 | tr -dc '0-9')"
skipped="$(printf '%s' "$attrs" | grep -o 'skipped="[0-9]*"' | head -1 | tr -dc '0-9')"
echo "== DB 통합테스트: 실행 ${ran:-?}건 / skip ${skipped:-?}건"

if [ "${ran:-0}" = "0" ] || [ "${skipped:-1}" != "0" ]; then
    echo "ERROR: DB 통합테스트가 건너뛰어졌다 — 접속 정보가 테스트 JVM 에 전달되지 않았다." >&2
    exit 1
fi
