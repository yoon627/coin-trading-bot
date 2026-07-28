#!/bin/bash
# query smoke test — "이 wiki 가 실제 질문에 답이 되는가" 를 확인한다.
# 구조 검사(check_links.py / verify.sh)는 링크와 형식만 보므로, 내용이 비어도 통과한다.
# 여기서는 대표 질문마다 (a) 답할 페이지가 index 에 등재돼 있고 (b) 답의 핵심 근거가 본문에 있는지 본다.
# 마지막 항목은 음성 질의 — 진행 중 작업의 상태가 wiki 로 새어들어오지 않았는지 검사한다.
# 사용: bash wiki/smoke.sh   (repo 루트에서)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
P=wiki/pages
pass=0; fail=0

check() { # $1=질문  $2=페이지경로  $3=근거 정규식
  if grep -qE "$3" "$2" 2>/dev/null && grep -q "$(basename "$2" .md)" wiki/index.md; then
    echo "PASS | $1 → $(basename "$2" .md)"; pass=$((pass+1))
  else
    echo "FAIL | $1 → $2 (근거 '$3' 없음 또는 index 미등재)"; fail=$((fail+1))
  fi
}

check "왜 collector 모듈이 없나"          "$P/decision/rightsizing-history.md"       "Kafka|메시지 버스"
check "다음 migration 번호를 어떻게 정하나" "$P/decision/migration-numbering.md"      "ls-tree|origin/main"
check "prod 에서 로그인이 안 되면"        "$P/decision/lesson-secure-cookie-http.md" "Secure"
check "백테를 라이브와 같다고 볼 수 있나" "$P/concept/backtest-engine.md"           "useMarketFilter|낙관|한계"
check "worktree 를 지울 때 주의할 점"     "$P/decision/worktree-workflow.md"         "gitignored|경고 없이"
check "JDK 25 로 빌드가 깨지면"           "$P/entity/jdk-gradle-toolchain.md"        "JAVA_HOME"
check "청산 조건 우선순위"                "$P/concept/trading-engine-loop.md"        "STOP_LOSS.*TRAILING"
check "push 가 막히면"                    "$P/decision/prepush-codex-review.md"      "CODEX_ACK|CODEX_SKIP"

# 음성 질의: 진행 중 작업(주식 봇 등)의 상태는 이슈·plan 소유이며 wiki 에 있으면 안 된다.
if grep -rqi "kis" wiki/pages/ 2>/dev/null; then
  echo "FAIL | 음성질의: 진행 중 작업(KIS) 서술이 wiki 에 있음 — 백로그 침범 (WIKI.md §1)"; fail=$((fail+1))
else
  echo "PASS | 음성질의: '주식(KIS) 봇 현재 상태' → wiki 에 없음 (의도된 결과)"; pass=$((pass+1))
fi

echo "---"
echo "pass=$pass fail=$fail"
exit $fail
