#!/usr/bin/env bash
set -euo pipefail

# 운영 DB 를 서버 디스크(backups/)에 덤프한다. DB 를 손으로 되돌리기 전에 /opt/app 에서 돌린다:
#   ./dump_local.sh before-V40-down && docker compose exec -T postgres psql ... < /tmp/V40_down.sql
# S3 백업(backup.sh)과 달리 외부로 보내지 않는다 — 서버를 잃으면 함께 잃는다.
# 배포의 마이그레이션 전 덤프와 같이 pg_dump 가 끝까지 썼을 때만 남기고, 아니면 0 이 아닌 코드로 끝나 뒤의 명령을 막는다.
# 덤프에는 암호화된 거래소 키가 있어 root 전용으로 두고, 같은 이름표의 덤프는 최근 5개만 남긴다(다른 이름표의 덤프는 밀어내지 않는다).

label="${1:-}"
if [[ ! "$label" =~ ^[A-Za-z0-9._-]+$ || "$label" == *..* ]]; then
    echo "usage: $0 <label>  (영문·숫자·._- — 예: before-V40-down)" >&2
    exit 2
fi

# 어디서 부르든 compose 파일 옆(/opt/app)의 backups/ 에 쓴다. DUMP_LOCAL_APP_DIR 은 시험용이다.
cd "${DUMP_LOCAL_APP_DIR:-$(dirname "$0")}"
install -d -m 700 backups
out="backups/manual-${label}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
tmp="$out.partial"
trap 'rm -f "$tmp"' EXIT
# 바깥 timeout 은 docker CLI 만 죽이므로 컨테이너 안의 pg_dump 에도 시한을 건다.
(umask 077; timeout -k 5 300 docker compose exec -T postgres timeout 290 pg_dump -U trading -d trading --no-owner </dev/null | gzip -9 > "$tmp") || true
# pg_dump 는 정상 종료할 때만 끝 근처에 이 줄을 쓴다(17.x 는 그 뒤에 \unrestrict 줄이 붙는다).
# grep -q 는 찾자마자 끝나 pipefail 아래에서 tail 의 SIGPIPE 를 실패로 볼 수 있어 끝까지 읽게 한다.
if ! gzip -t "$tmp" 2>/dev/null || ! gzip -dc "$tmp" | tail -n 20 | grep 'PostgreSQL database dump complete' >/dev/null; then
    echo "ERROR: 덤프 실패 — 되돌리기를 시작하지 않는다" >&2
    exit 1
fi
mv "$tmp" "$out"
echo "덤프: $(pwd)/$out ($(du -h "$out" | cut -f1))"
# 이름표 바로 뒤가 시각이어야 한다 — 이름표 before 가 before-V40-down 의 덤프를 지우지 않게.
ls -1t "backups/manual-${label}-"[0-9]*Z.sql.gz | tail -n +6 | xargs -r rm -f --
