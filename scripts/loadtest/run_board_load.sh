#!/usr/bin/env bash
# 시세판 SSE 부하 측정. 운영 이미지를 운영과 같은 설정(prod 프로필, JAVA_OPTS, 메모리 832MB)에 CPU 1개 제한을 더해 로컬에
# 띄우고, 접속 수 단계별로 sse_load.py 를 돌려 앱 메모리·CPU·스레드, 전달 지연, /actuator/health 왕복을 표 줄로 찍는다.
# 운영 호스트에서 돌려도 운영 앱·DB 에는 손대지 않는다(컨테이너 이름 lt*, 포트는 127.0.0.1 에만) — 운영 앱은 상태와 ERROR 로그만 읽는다.
#
# usage: scripts/loadtest/run_board_load.sh <app image> [duration_s] [levels...]
#   예: ... ghcr.io/yoon627/coin-trading-bot:<운영 SHA> 120 0 100 300 500 600
set -euo pipefail
IMAGE=$1; shift
DURATION=${1:-120}
[ $# -gt 0 ] && shift
if [ $# -gt 0 ]; then LEVELS=("$@"); else LEVELS=(0 100 300 500 600); fi
HERE=$(cd "$(dirname "$0")" && pwd)
PORT=18090
PW=$(openssl rand -hex 12)
PROD_APP=${PROD_APP:-app-app-1}
WORK=$(mktemp -d)
SAMPLER=

cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  rm -rf "$WORK"
  docker rm -fv ltapp ltdb >/dev/null 2>&1 || true
  docker network rm ltnet >/dev/null 2>&1 || true
}
trap cleanup EXIT
wait_for() {  # <시도 수> <설명> <명령…> — 끝내 안 되면 측정을 멈춘다
  local tries=$1 what=$2; shift 2
  for _ in $(seq 1 "$tries"); do "$@" >/dev/null 2>&1 && return 0; sleep 2; done
  echo "ERROR: $what 를 기다리다 멈췄다" >&2; exit 1
}
board_connected() { docker logs ltapp 2>&1 | grep -q "\[board\] Upbit WebSocket connected"; }

docker rm -fv ltapp ltdb >/dev/null 2>&1 || true
docker network rm ltnet >/dev/null 2>&1 || true
docker network create ltnet >/dev/null
docker run -d --name ltdb --network ltnet --memory 256m -e POSTGRES_DB=trading -e POSTGRES_USER=trading -e POSTGRES_PASSWORD=trading \
  postgres:17.11-alpine@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b >/dev/null
wait_for 30 "측정 DB" docker exec ltdb pg_isready -U trading -d trading
# 운영 compose(deploy/vultr/docker-compose.prod.yml)의 app 과 같은 프로필·JVM 옵션·메모리. CPU 제한은 운영 앱을 지키려고 더한다.
# prod 프로필은 JWT·암호화 secret 을 요구한다 — 이 측정에만 쓰는 무작위 값이다.
docker run -d --name ltapp --network ltnet --memory 832m --cpus 1 -p 127.0.0.1:$PORT:8080 \
  -e SPRING_PROFILES_ACTIVE=prod -e "JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError" \
  -e "JWT_SECRET=$(openssl rand -hex 32)" -e "APP_ENCRYPTION_SECRET=$(openssl rand -hex 32)" \
  -e DB_HOST=ltdb -e DB_PORT=5432 -e DB_NAME=trading -e DB_USER=trading -e DB_PASSWORD=trading \
  "$IMAGE" >/dev/null
wait_for 90 "측정 앱 health" curl -fsS http://127.0.0.1:$PORT/actuator/health
wait_for 60 "시세판 업비트 연결" board_connected

# 가입은 첫 계정만 받는다 — 첫 계정을 만들고 같은 비밀번호 해시로 나머지를 넣는다.
curl -fsS -o /dev/null -X POST http://127.0.0.1:$PORT/api/auth/register -H 'Content-Type: application/json' -d "{\"username\":\"load0\",\"password\":\"$PW\"}"
docker exec ltdb psql -q -U trading -d trading -c \
  "INSERT INTO users (username, password) SELECT 'load' || g, (SELECT password FROM users WHERE username = 'load0') FROM generate_series(1, 299) g"
sleep 20  # 시세판이 KRW 목록 전체의 첫 스냅샷을 받는다

prod_health() { docker inspect -f '{{.State.Health.Status}}' "$PROD_APP" 2>/dev/null || echo missing; }

# sse_load.py 가 로그인을 마친(ready) 뒤부터 duration 동안 5초마다 앱 메모리·CPU·스레드 수와 운영 앱 상태를 남긴다.
sample() {
  local ready=$1 out=$2 end
  while [ ! -e "$ready" ]; do sleep 0.5; done
  end=$((SECONDS + DURATION))
  while [ $SECONDS -lt "$end" ]; do
    printf '%s %s %s\n' "$(docker stats --no-stream --format '{{.MemUsage}} {{.CPUPerc}}' ltapp)" \
      "$(docker exec ltapp sh -c 'ls /proc/1/task | wc -l')" "$(prod_health)" >> "$out"
    sleep 5
  done
}

echo "| 접속 | 열림 | 느린 연결 | 503 | 실패 (health·끊김·오류) | 앱 메모리 최대 (첫 단계 대비) | CPU 평균 | 스레드 최대 | 지연 p50 / p99 | 도착 간격 p99 | health p50 / p99 | 운영 앱 healthy · ERROR |"
echo "|---|---|---|---|---|---|---|---|---|---|---|---|"
BASE_MEM=
for N in "${LEVELS[@]}"; do
  USERS=$(( (N + 2) / 3 )); [ "$USERS" -lt 1 ] && USERS=1
  READY="$WORK/ready-$N"; STATS="$WORK/stats-$N"
  sample "$READY" "$STATS" &
  SAMPLER=$!
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  RESULT=$(nice -n 19 uv run --no-project -q --with aiohttp python -I "$HERE/sse_load.py" --base http://127.0.0.1:$PORT \
    --streams "$N" --users "$USERS" --password "$PW" --duration "$DURATION" --slow-fraction 0.1 --ready-file "$READY" | tail -1)
  wait "$SAMPLER" || true
  SAMPLER=
  prod_errors=$(docker logs --since "$since" "$PROD_APP" 2>&1 | grep -c ' ERROR ' || true)
  if ! ROW=$(python3 - "$RESULT" "$STATS" "$prod_errors" "${BASE_MEM:-}" 2> "$WORK/row-err" <<'EOF'
import json, re, sys
r = json.loads(sys.argv[1]); prod_errors = sys.argv[3]; base = sys.argv[4]
mem, cpu, threads, healthy, samples = [], [], [], 0, 0
for line in open(sys.argv[2]).read().split("\n"):
    m = re.match(r"([\d.]+)(MiB|GiB)\s*/\s*\S+\s+([\d.]+)%\s+(\d+)\s+(\S+)", line.strip())
    if m:
        mem.append(float(m.group(1)) * (1024 if m.group(2) == "GiB" else 1)); cpu.append(float(m.group(3)))
        threads.append(int(m.group(4))); samples += 1; healthy += m.group(5) == "healthy"
f = lambda v: "-" if v is None else f"{v:.0f}"
peak = max(mem) if mem else 0
delta = "" if not base else f" ({peak - float(base):+.0f})"
fails = f"{r['health_fail']} · {r['closed_by_server']} · {r['errors']}"
print(f"| {r['streams']} | {r['opened']} | {r['slow_opened']} | {r['status'].get('503', 0)} | {fails} | {peak:.0f} MiB{delta} "
      f"| {sum(cpu)/len(cpu) if cpu else 0:.0f}% | {max(threads) if threads else '-'} "
      f"| {f(r['lag_p50_ms'])} / {f(r['lag_p99_ms'])} ms | {f(r['gap_p99_ms'])} ms | {f(r['health_p50_ms'])} / {f(r['health_p99_ms'])} ms "
      f"| {healthy}/{samples} · +{prod_errors} |")
print(f"{peak:.0f}", file=sys.stderr)
print("raw:", json.dumps(r), file=sys.stderr)
EOF
  ); then
    cat "$WORK/row-err" >&2
    exit 1
  fi
  echo "$ROW"
  grep '^raw:' "$WORK/row-err" >&2
  [ -z "$BASE_MEM" ] && BASE_MEM=$(head -1 "$WORK/row-err")
  sleep 10  # 닫힌 접속이 정리되고 다음 단계 자리가 빈다
done
