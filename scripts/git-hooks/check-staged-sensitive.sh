#!/usr/bin/env bash
# 커밋 전 민감 경로 검사(#299). 스테이징된 변경 중 삭제를 뺀 모든 파일을 본다 — 비밀 파일을 빼는 커밋까지 막을 이유가 없다.
#
# - 비밀 파일 경로(.env*·*.env·*.pem·*credentials*·*secret*, 대소문자 무시)는 막는다.
# - dotenv 템플릿(.env.example·*.env.sample 등)만 경로가 아니라 내용으로 본다: 비밀처럼 보이는 키에 placeholder 가 아닌
#   값이 들어 있을 때만 막는다. 값이 없는 템플릿을 고치지 못하면 사람이 손으로 커밋해야 했다. 다른 형식의 템플릿
#   (JSON·YAML·PEM)은 줄 단위로 판정할 수 없어 경로로 막는다.
#
# Claude Code 의 PreToolUse 훅(.claude/settings.json)과 git pre-commit 훅(scripts/git-hooks/pre-commit)이 함께 부른다.
# 막을 때 exit 2 — PreToolUse 는 2 만 차단으로 읽고, git 은 0 이 아니면 커밋을 멈춘다.
set -uo pipefail
shopt -s nocasematch

top="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "민감 경로 검사: git 저장소가 아니다" >&2; exit 2; }
cd "$top" || exit 2

SENSITIVE='(^|/)\.env[^/]*$|\.env$|\.pem$|credentials|secret'
DOTENV_TEMPLATE='(^|/)(\.env[^/]*|[^/]*\.env)\.(example|sample|template)$'
SECRET_KEY='(KEY|SECRET|PASSWORD|PASSWD|TOKEN|WEBHOOK|PRIVATE|CREDENTIAL)'
# 값이 아니라 자리 표시인 것만 — 접두사만 맞는 값(your-real-secret…)은 통과시키지 않는다.
PLACEHOLDER='^(your[_-][a-z0-9_-]*here|<[^>]*>|changeme|change[_-]me|x+|example|placeholder)$'

# 따옴표 값은 따옴표 안이 값이고, 따옴표 없는 값은 공백 뒤 # 부터가 주석이다(docker compose·bash 와 같다).
# 여는 따옴표만 있으면 여러 줄 값이다 — 뒤 줄을 판정할 수 없으니 막는다.
template_violations() {
  local path="$1" content line key raw value
  content="$(git show ":$path")" || { echo "$path: 스테이징된 내용을 읽지 못했다"; return; }
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    # 로컬 개발 기본값처럼 의도된 값은 줄에 표시한다 — 리뷰에서 보이는 예외라 조용한 우회가 아니다.
    [[ "$line" == *"# not-a-secret"* ]] && continue
    [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_.-]*)[[:space:]]*=[[:space:]]*(.*)$ ]] || continue
    key="${BASH_REMATCH[2]}"
    raw="${BASH_REMATCH[3]}"
    if [[ "$raw" =~ ^\"([^\"]*)\" || "$raw" =~ ^\'([^\']*)\' ]]; then
      value="${BASH_REMATCH[1]}"
    elif [[ "$raw" == \"* || "$raw" == \'* ]]; then
      echo "$path: $key 의 따옴표가 한 줄에서 닫히지 않는다(여러 줄 값은 판정할 수 없다)"
      continue
    else
      value="$(printf '%s' "$raw" | sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//')"
    fi
    [[ -z "$value" ]] && continue
    if [[ "$key" =~ $SECRET_KEY ]] && [[ ! "$value" =~ $PLACEHOLDER ]]; then
      echo "$path: $key 에 값이 들어 있다"
    fi
  done <<< "$content"
}

violations=()
staged="$(git diff --cached --name-only -z --diff-filter=d | tr '\0' '\n')" || { echo "민감 경로 검사: 스테이징 목록을 읽지 못했다" >&2; exit 2; }
while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  [[ "$path" =~ $SENSITIVE ]] || continue
  if [[ "$path" =~ $DOTENV_TEMPLATE ]]; then
    while IFS= read -r v; do [[ -n "$v" ]] && violations+=("$v"); done < <(template_violations "$path")
    continue
  fi
  violations+=("$path")
done <<< "$staged"

if (( ${#violations[@]} > 0 )); then
  echo "Sensitive file(s) (.env, .pem, credentials, secret) detected in staged commit:" >&2
  printf '  %s\n' "${violations[@]}" >&2
  echo "dotenv 템플릿(.env.example 등)은 비밀 키의 값을 비우거나 placeholder(your_..._here, <...>)로 두면 통과한다." >&2
  exit 2
fi
exit 0
