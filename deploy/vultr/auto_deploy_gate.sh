#!/usr/bin/env bash
# main push 의 자동 배포를 해도 되는지 판정한다. 첫 줄에 auto=true|false 를 쓴다.
# 서버의 `docker compose up -d` 는 정의가 바뀐 서비스를 다시 만든다. 운영 compose 에서 app 밖(postgres·caddy·볼륨·네트워크)이
# 바뀌었으면 DB·HTTPS 가 재시작되므로 사람이 시점을 정해 수동 실행으로 배포한다. 판정할 수 없으면 false 다(안전 쪽).
# 사용: auto_deploy_gate.sh <last-good-sha> <target-sha>  (전체 이력이 있는 체크아웃 안에서)
# 종료 코드: 0 판정함, 2 인자 오류. (안의 파이썬이 쓰는 10·3 은 이 스크립트 안에서만 쓰고 판정으로 바꿔 낸다.)
set -euo pipefail

usage() {
    echo "usage: $0 <last-good-sha> <target-sha>  (SHA 는 40자리)" >&2
    exit 2
}
sha_re='^[0-9a-f]{40}$'
[[ $# -eq 2 ]] || usage
last_good="$1"
target="$2"
[[ "$last_good" =~ $sha_re && "$target" =~ $sha_re ]] || usage
cd "$(git rev-parse --show-toplevel)"

compose=deploy/vultr/docker-compose.prod.yml
manual() {
    echo "auto=false"
    echo "$1"
    exit 0
}

if ! git cat-file -e "${last_good}^{commit}" 2>/dev/null; then
    manual "마지막 정상 배포 $last_good 가 이 체크아웃에 없어 compose 를 비교할 수 없다"
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git show "$last_good:$compose" > "$tmp/old.yml" 2>/dev/null || manual "마지막 정상 배포의 $compose 를 읽지 못했다"
git show "$target:$compose" > "$tmp/new.yml" 2>/dev/null || manual "배포 대상의 $compose 를 읽지 못했다"

python3 -c 'import yaml' 2>/dev/null || manual "자동 배포 판정에 필요한 python3·PyYAML 이 없다 — 수동 실행으로 배포한다"

# 파이썬의 잡히지 않은 예외는 exit 1 이라 "바뀜"과 섞이지 않게 바뀜은 10, 읽기 실패는 3 으로 낸다.
rc=0
changed="$(python3 - "$tmp/old.yml" "$tmp/new.yml" <<'PY'
import sys

import yaml


def outside_app(path):
    with open(path) as f:
        doc = yaml.safe_load(f) or {}
    if not isinstance(doc, dict):
        raise ValueError("compose 최상위가 맵이 아니다")
    parts = {}
    for key, value in doc.items():
        if key == "services":
            for name, service in (value or {}).items():
                if name != "app":
                    parts[f"services.{name}"] = service
        else:
            parts[key] = value
    return parts


try:
    old, new = outside_app(sys.argv[1]), outside_app(sys.argv[2])
except Exception as e:
    print(f"{type(e).__name__}: {e}".splitlines()[0])
    sys.exit(3)
diff = sorted(k for k in old.keys() | new.keys() if old.get(k) != new.get(k))
print(" ".join(diff))
sys.exit(10 if diff else 0)
PY
)" || rc=$?
case "$rc" in
    0) echo "auto=true"
       echo "운영 compose 의 app 밖 정의가 그대로다" ;;
    10) manual "운영 compose 의 app 밖 정의가 바뀌었다: $changed — 봇을 멈출지 정한 뒤 수동 실행으로 배포한다" ;;
    *) manual "운영 compose 를 읽지 못했다($changed) — 수동 실행으로 배포한다" ;;
esac
