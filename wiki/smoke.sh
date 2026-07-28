#!/bin/bash
# query smoke test — "이 wiki 가 실제 질문에 답이 되는가" 를 확인한다.
# 구조 검사(check_links.py / verify.sh)는 링크와 형식만 보므로, 내용이 비어도 통과한다.
#
# 각 질문마다 (a) 답할 페이지가 index.md 에 `[[stem]]` 으로 등재됐고
#            (b) 답의 핵심 근거가 **본문**(frontmatter 제외)에 있는지 본다.
# frontmatter 를 제외하는 이유: sources·verified 에 우연히 걸리는 매칭을 근거로 세지 않기 위해.
#
# 마지막은 음성 검사 — 진행 중 작업의 '상태'가 wiki 로 새어들어오지 않았는지(WIKI.md §1).
# 특정 낱말(예: 브랜치명)이 아니라 상태 서술 어휘를 찾는다. 확정된 지식은 그 어휘를 쓰지 않는다.
#
# 사용: bash wiki/smoke.sh   (repo 루트에서)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
P=wiki/pages
pass=0; fail=0

# frontmatter 이후 본문만 출력
body() { awk 'BEGIN{n=0} /^---$/{n++; next} n>=2' "$1"; }

check() { # $1=질문  $2=페이지경로  $3=근거 정규식
  local q="$1" f="$2" re="$3" stem
  stem=$(basename "$f" .md)
  if [ ! -f "$f" ]; then
    echo "FAIL | $q → $f (파일 없음)"; fail=$((fail+1)); return
  fi
  if ! grep -qF "[[$stem]]" wiki/index.md; then
    echo "FAIL | $q → $stem (index.md 에 [[$stem]] 등재 없음)"; fail=$((fail+1)); return
  fi
  if ! body "$f" | grep -qE "$re"; then
    echo "FAIL | $q → $stem (본문에 근거 '$re' 없음)"; fail=$((fail+1)); return
  fi
  echo "PASS | $q → $stem"; pass=$((pass+1))
}

check "왜 collector 모듈이 없나"           "$P/decision/rightsizing-history.md"        "Kafka|메시지 버스"
check "다음 migration 번호를 어떻게 정하나" "$P/decision/migration-numbering.md"       "ls-tree|origin/main"
check "prod 에서 로그인이 안 되면"         "$P/decision/lesson-secure-cookie-http.md"  "Secure"
check "백테를 라이브와 같다고 볼 수 있나"  "$P/concept/backtest-engine.md"             "우선순위가 다르|낙관|한계"
check "worktree 를 지울 때 주의할 점"      "$P/decision/worktree-workflow.md"          "gitignored|경고 없이"
check "JDK 25 로 빌드가 깨지면"            "$P/entity/jdk-gradle-toolchain.md"         "JAVA_HOME"
check "청산 조건 우선순위"                 "$P/concept/trading-engine-loop.md"         "STOP_LOSS.*TRAILING"
check "push 가 막히면"                     "$P/decision/prepush-codex-review.md"       "CODEX_ACK|CODEX_SKIP"
check "보유 중 설정을 바꾸면 청산 기준은"  "$P/concept/exit-gates.md"                  "소비하지 않는|즉시 적용"

# 음성 검사: 진행 중 작업의 상태는 plan·이슈 소유다(WIKI.md §1).
#
# 어휘("미머지" 등)로 찾으면 일반 규칙 서술("미머지 브랜치가 번호를 선점할 수 있다")까지 걸린다.
# 대신 **실재하는 작업 브랜치 이름**을 찾는다 — 확정된 지식은 특정 브랜치를 지목할 이유가 없고,
# 특정 브랜치의 진행 상태를 적으면 반드시 그 이름이 등장한다. 브랜치 목록은 git 에서 가져오므로
# 브랜치가 바뀌어도 이 검사는 따라간다.
hits=""
for b in $(git branch -a --format='%(refname:short)' 2>/dev/null \
             | sed 's|^origin/||' | sort -u \
             | grep -E '^[a-z0-9]+(-[a-z0-9]+){1,}$'); do
  case "$b" in main|master|HEAD) continue ;; esac
  found=$(grep -rnF -- "$b" "$P" 2>/dev/null || true)
  [ -n "$found" ] && hits="$hits$found"$'\n'
done
# plan frontmatter 를 그대로 옮겨온 흔적도 본다.
inprog=$(grep -rnE "status:[[:space:]]*in_progress" "$P" 2>/dev/null || true)
[ -n "$inprog" ] && hits="$hits$inprog"$'\n'

if [ -n "$hits" ]; then
  echo "FAIL | 음성검사: 특정 작업 브랜치/진행 상태가 wiki 에 있음 (WIKI.md §1 위반)"
  printf '%s' "$hits" | sed '/^$/d; s/^/       /'
  fail=$((fail+1))
else
  echo "PASS | 음성검사: 특정 작업 브랜치·진행 상태 서술 없음 (plan·이슈가 소유)"
  pass=$((pass+1))
fi

echo "---"
echo "pass=$pass fail=$fail"
exit $fail
