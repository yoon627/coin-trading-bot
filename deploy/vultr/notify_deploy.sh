#!/usr/bin/env bash
# 배포 결과를 Discord 로 알린다. 배포 종료 코드를 바꾸지 않도록 언제나 0 으로 끝낸다.
# 사용: DISCORD_WEBHOOK_URL=… DISCORD_ERROR_WEBHOOK_URL=… notify_deploy.sh <success|rollback|failure> <메시지>
#   성공은 일반 webhook, 롤백·실패는 에러 webhook(없으면 일반 webhook)으로 보낸다.
# 배포 로그는 공개 repo 의 CI 로그라, webhook URL 은 env 로만 받고 curl 에도 표준입력 설정으로만 넘기며 curl 의 오류
# 문구(URL 이 들어갈 수 있다)는 버린다.
set -uo pipefail

level="${1:-}"
message="${2:-}"

case "$level" in
    success) url="${DISCORD_WEBHOOK_URL:-}" ;;
    rollback|failure) url="${DISCORD_ERROR_WEBHOOK_URL:-}"; [[ -n "$url" ]] || url="${DISCORD_WEBHOOK_URL:-}" ;;
    *) echo "배포 알림: 알 수 없는 결과 '$level' — 보내지 않는다"; exit 0 ;;
esac
if [[ -z "$url" ]]; then
    echo "배포 알림 건너뜀: webhook 이 설정되지 않았다"
    exit 0
fi

json_escape() {
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\"/\\\"}
    s=${s//$'\n'/\\n}
    s=${s//$'\r'/\\r}
    s=${s//$'\t'/\\t}
    # 남은 제어 문자는 JSON 문자열에 그대로 넣을 수 없다.
    printf '%s' "$s" | LC_ALL=C tr -d '\000-\037'
}

payload="{\"content\":\"$(json_escape "$message")\",\"allowed_mentions\":{\"parse\":[]}}"
config_url=${url//\\/\\\\}
config_url=${config_url//\"/\\\"}
# -q 가 맨 앞이어야 ~/.curlrc(trace 설정이 URL 을 찍을 수 있다)를 읽지 않는다.
code="$(printf 'url = "%s"\n' "$config_url" | curl -q -sS -K - -o /dev/null -w '%{http_code}' --max-time 10 \
    -H 'Content-Type: application/json' --data-binary "$payload" 2>/dev/null)"
rc=$?
if [[ $rc -ne 0 ]]; then
    echo "배포 알림 실패: curl exit $rc"
else
    echo "배포 알림: HTTP $code"
fi
exit 0
