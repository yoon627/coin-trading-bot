---
title: precommit-hook-scope — 커밋 전 6패턴 agent hook 이 커밋 아닌 Bash(codex 리뷰 포함)를 차단하는 문제 수정
status: done
started: 2026-09-03
updated: 2026-09-03
---

# Goal

1. `.claude/settings.json` 의 `type: agent` PreToolUse hook(스테이징 .kt 6패턴 점검)이 `git commit` 이 아닌 Bash 명령까지 차단해 code-reviewer 의 codex 병행 리뷰(§9)가 막히는 문제를 고친다. 커밋 시 6패턴 점검은 그대로 유지한다.
2. 같은 그룹의 민감파일 검사 hook(`shell: powershell`)이 macOS 에서 실행 실패 후 통과(fail-open)하는 것을 bash·PowerShell 양쪽에서 도는 명령으로 교체한다(사용자 지시 2026-09-03 "니가 해줘").

# Progress

- 2026-09-03: 원인 확정(트랜스크립트 40건 차단 중 실제 `git commit` 은 3건, 나머지 37건은 오탐 — 2026-09-02 14:07 code-reviewer 의 `codex exec` 포함). 원인: (1) `if: "Bash(git commit*)"` 은 best-effort 필터라 명령명 이상을 지정한 패턴은 `$VAR`·`$()`·backtick 이 든 명령에서 보수적으로 발화(공식 docs hooks#if), (2) 발화 후 agent 프롬프트가 "커밋 아닌 명령은 통과" 규칙이 없어 절차 밖 명령을 `ok:false` 로 차단.

- 2026-09-03: 민감파일 hook 을 양쪽 셸 호환 git-alias 명령으로 교체(A6·A7 실측). Windows 는 미실측.
- 2026-09-03: code-reviewer 반영(`if` 되돌림·0단계 재배열·reason 한 줄) + A5·A1 재실측 통과.
- 2026-09-03: hook 수정(02f94a1) + headless `claude -p` 4케이스 실측 통과. baseline(수정 전) 단발 재현은 hook 판정이 비결정적이라 2회 모두 통과 — Red 근거는 트랜스크립트 37건 오탐.

# Next

- PR #165 머지로 종료(머지 거부 시 in_progress 복구).

# Decisions

- `if` 는 `Bash(git commit*)` 유지 — 처음 `Bash(git *)` 로 바꿨다가 code-reviewer 지적으로 되돌림(이유: 모든 git 명령마다 agent 가 떠 명령당 +6초·월 850회, hook agent 의 `git diff --cached` 가 자기 hook 을 재귀 발화. docs 표상 `cd x && git commit`·`$(…)` 안의 commit 은 `git commit*` 도 서브커맨드로 잡는다). 오탐 차단은 프롬프트 0단계로 막는다: 명령 텍스트에 `git commit` 서브커맨드가 있으면(heredoc·치환 포함) 점검, 없으면 즉시 `{"ok": true}`.
- 출력은 docs 규약대로 `{"ok": true}` / `{"ok": false, "reason": ...}` 로 명시(기존 "빈 응답" 규약 대체), reason 은 개행 없는 한 줄(`; ` 구분 — JSON 안 생 개행으로 파싱 실패 방지). `$ARGUMENTS` 로 hook 입력을 명시 전달.
- 민감파일 hook 은 `git -c alias.hookchk='!git diff --cached --quiet -- <pathspec…> || { echo … >&2; exit 2; }' hookchk` 한 줄로 교체. 이유: `shell` 기본값이 bash 이되 Windows 에서 Git Bash 미검출 시 PowerShell 로 돈다(docs) — 4a0d2f31 이 PowerShell 로 포팅한 원인. 명령 문자열을 두 셸에서 동일하게 파싱되는 형태(`$`·backtick 없음, 큰따옴표 1쌍)로 두고 실제 로직은 git 의 `!` alias 가 git 번들 sh 로 실행하게 해 플랫폼 분기를 없앤다. 매칭은 원래 regex(`\.env|\.pem|credentials|secret`)를 pathspec glob 7개로 옮김(디렉토리명 매치 포함). exit 2 + stderr 가 docs 상 PreToolUse 차단 규약. **⚠️ Windows PowerShell 경로는 이 세션에서 실측 못 함**(macOS 만) — Windows 첫 커밋에서 확인 필요.
- ~/.claude 의 `docs/codex-review.md`·wiki `worktree-isolation-bash-guard` 는 네이티브 격리 가드(메시지 "isolated in the worktree", 79건 실측) 대상이라 별개 — 이 hook 오탐과 혼동하지 않는다.

# Key Files

- `.claude/settings.json` — PreToolUse Bash agent hook.

# Blockers

# Acceptance

- ✅ A1 worktree 에서 `claude -p` 로 비git heredoc(backtick `git diff` 언급)+`$?` 명령 실행 → 차단 없이 `EXIT=0`(2026-09-03 실측).
- ✅ A2 `git status --short` → 차단 없이 출력(실측).
- ✅ A3 와일드카드 import `.kt` 스테이징 후 `git commit` → `Agent hook condition was not met: - [Wildcard imports] …HookProbeTest.kt:3` 로 차단, 커밋 미생성(실측).
- ✅ A4 plan 파일만 스테이징 후 `git commit` → 통과, 커밋 32971b2 생성(실측).
- ✅ A5 `git commit -F - <<'MSG'`(heredoc+`$(date)`) + 와일드카드 import `.kt` 2개 → 한 줄 reason 2건(`; ` 구분)으로 차단, 커밋 미생성(실측, `if` 되돌린 뒤).
- ✅ A1 재실측(`if` 되돌린 뒤) 통과.
- ✅ A6 `probe/test.pem` 을 `git add -f` 후 `git commit` → `Sensitive file(s) (.env, .pem, credentials, secret) detected in staged commit.` 로 차단, 커밋 미생성(실측, 교체 전엔 `no PowerShell executable … found on PATH` 로 통과했음). sh 직접 실행으로 `.pem`/`.env.local`/`credentials/` 차단·`plain.txt`/`creds/` 통과 확인.
- ✅ A7 교체 후 `.kt` 없는 커밋 → 통과(이 항목의 커밋 자체).
- ❌ A8 Windows(PowerShell 기본 셸)에서 같은 명령이 동작 — 미실측.

# Review Disposition

- code-reviewer(Claude 단독, codex 크레딧 소진): Major 4(CONFIRMED 2·PLAUSIBLE 2)·Minor 4. **fix** `if` 되돌림(발화 폭증·재귀), 0단계 문구 순서(commit 있으면 점검 우선), reason 한 줄, A5 추가. **defer** PowerShell hook fail-open → `# Deferred`. **no-op** statusMessage·시크릿 노출 표면(`if` 되돌려 해소), agent_id 재귀 차단(`if` 되돌려 불필요). open: agent hook 이 잘못된 JSON·timeout 일 때 통과/차단 여부 docs 미기재.

# Deferred

- (해소) 민감파일 hook fail-open → Goal 2 로 흡수해 이 브랜치에서 교체. 남은 것: Windows 실측(A8).
