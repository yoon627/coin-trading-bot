#!/usr/bin/env bash
# Claude Code PostToolUse 훅(.claude/settings.json): Edit·Write 한 이 repo 의 Kotlin 파일 하나를 Spotless(ktlint)로 포맷한다.
# 자동으로 못 고치는 lint 오류만 exit 2 로 Claude 에게 알린다. 그 외 실패(gradle·네트워크·timeout)는 편집을 막지 않는다 —
# CI 의 spotlessCheck 가 최종 검사다.
set -u

input=$(cat)
# jq 없이 첫 "file_path" 값을 뽑는다. Write 내용 안의 \"file_path\" 는 따옴표가 이스케이프돼 걸리지 않는다.
raw=$(printf '%s' "$input" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"([^"\\]|\\.)*"' | head -n 1)
[ -n "$raw" ] || exit 0
raw=${raw#*:}
raw=${raw#*\"}
raw=${raw%\"}
# Windows 경로는 \\ 로 온다. 그 쌍을 빼고도 \" 나 \u 가 남을 때만 경로를 추측하지 않는다(\\util 은 경로다).
case "${raw//'\\'/}" in
    *'\"'* | *'\u'*) exit 0 ;;
esac
# \\ 를 먼저 다른 문자로 바꿔 두어 \\/ 가 / 로 잘못 풀리지 않게 한다.
path=${raw//'\\'/$'\001'}
path=${path//'\/'/\/}
path=${path//$'\001'/\\}

case "$path" in
    *.kt | *.kts) ;;
    *) exit 0 ;;
esac
if command -v cygpath >/dev/null 2>&1; then
    path=$(cygpath -u "$path")
fi
[ -f "$path" ] || exit 0

dir=$(dirname "$path")
# 다른 repo 의 파일이면 그 repo 의 gradle 빌드 코드를 실행하지 않는다. worktree 는 common dir 이 같아 통과한다.
file_common=$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
project_common=$(git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
[ "$file_common" -ef "$project_common" ] || exit 0
root=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -f "$root/gradlew" ] || exit 0

hook_path=$path
if command -v cygpath >/dev/null 2>&1; then
    hook_path=$(cygpath -m "$path")
fi
err=$(cd "$root" && ./gradlew spotlessApply -PspotlessIdeHook="$hook_path" --quiet 2>&1 >/dev/null)
status=$?
[ "$status" -eq 0 ] && exit 0
if printf '%s\n' "$err" | grep -q 'found problem in'; then
    {
        echo "ktlint 가 자동으로 고칠 수 없는 문제가 있어 포맷하지 않았다: $path"
        printf '%s\n' "$err" | grep -E "^(Step '|L[0-9]+:)"
    } >&2
    exit 2
fi
exit 0
