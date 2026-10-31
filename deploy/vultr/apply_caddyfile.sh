#!/usr/bin/env bash
# 서버(/opt/app)에서 caddy/Caddyfile 이 마지막으로 적용한 것과 다르면 실행 중인 caddy 에 reload 로 적용한다.
# reload 는 새 설정이 틀리면 이전 설정을 그대로 두고(중단 없음) 같은 설정이면 아무것도 하지 않는다.
# 종료코드 0=변경 없음 10=적용 그 밖=실패. 실패하면 적용 기록을 두지 않아 다음 배포가 다시 시도한다.
# 적용 뒤 HTTPS 를 확인하지 못하면 배포가 기록을 지워 다음 배포가 다시 적용하고 다시 확인한다.
set -uo pipefail

file=caddy/Caddyfile
marker=.caddyfile-applied

hash="$(sha256sum "$file" 2>/dev/null | cut -d' ' -f1)"
if [[ -z $hash ]]; then
    echo "ERROR: $file 을 읽을 수 없다"
    exit 1
fi
if [[ -f $marker && "$(cat "$marker")" == "$hash" ]]; then
    echo "Caddyfile 변경 없음 (${hash:0:12})"
    exit 0
fi

echo "Caddyfile 적용 (${hash:0:12}) — caddy reload"
# 배포의 원격 스크립트는 bash -s 의 표준입력으로 들어와 exec 가 그 나머지를 먹지 않게 끊는다.
rc=0
timeout -k 5 30 docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile </dev/null || rc=$?
if (( rc != 0 )); then
    echo "ERROR: caddy reload 실패(rc=$rc) — 실행 중인 caddy 는 이전 설정이거나, 시간 초과(124·137)면 적용 여부를 확인하지 못했다"
    docker compose logs --tail=60 caddy </dev/null || true
    exit 1
fi
printf '%s\n' "$hash" > "$marker"
exit 10
