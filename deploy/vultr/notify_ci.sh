#!/usr/bin/env bash
# Actions 에서 배포 webhook 으로 알린다. 언제나 0 으로 끝난다.
# 사용: VULTR_DEPLOY_ENV=<운영 .env 내용> notify_ci.sh <success|rollback|failure> <메시지>
# deploy.sh 와 같은 방식(source)으로 읽어야 그 배포가 알리는 webhook 과 같은 값이 된다. webhook 값은 출력하지 않는다.
set -uo pipefail

level="${1:-}"
message="${2:-}"
dir="$(cd "$(dirname "$0")" && pwd)"
(
    env_file="$(mktemp)"
    printf '%s\n' "${VULTR_DEPLOY_ENV:-}" > "$env_file"
    set +eu
    set -a
    # shellcheck disable=SC1090
    source "$env_file" > /dev/null 2>&1
    set +a
    rm -f "$env_file"
    bash "$dir/notify_deploy.sh" "$level" "$message"
) || true
exit 0
