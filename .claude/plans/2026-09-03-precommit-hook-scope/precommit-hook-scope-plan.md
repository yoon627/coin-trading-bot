---
title: precommit-hook-scope — 커밋 전 6패턴 agent hook 이 커밋 아닌 Bash(codex 리뷰 포함)를 차단하는 문제 수정
status: in_progress
started: 2026-09-03
updated: 2026-09-03
---

# Goal

`.claude/settings.json` 의 `type: agent` PreToolUse hook(스테이징 .kt 6패턴 점검)이 `git commit` 이 아닌 Bash 명령까지 차단해 code-reviewer 의 codex 병행 리뷰(§9)가 막히는 문제를 고친다. 커밋 시 6패턴 점검은 그대로 유지한다.

# Progress

- 2026-09-03: 원인 확정(트랜스크립트 40건 차단 중 실제 `git commit` 은 3건, 나머지 37건은 오탐 — 2026-09-02 14:07 code-reviewer 의 `codex exec` 포함). 원인: (1) `if: "Bash(git commit*)"` 은 best-effort 필터라 명령명 이상을 지정한 패턴은 `$VAR`·`$()`·backtick 이 든 명령에서 보수적으로 발화(공식 docs hooks#if), (2) 발화 후 agent 프롬프트가 "커밋 아닌 명령은 통과" 규칙이 없어 절차 밖 명령을 `ok:false` 로 차단.

- 2026-09-03: code-reviewer 반영(`if` 되돌림·0단계 재배열·reason 한 줄) + A5·A1 재실측 통과.
- 2026-09-03: hook 수정(02f94a1) + headless `claude -p` 4케이스 실측 통과. baseline(수정 전) 단발 재현은 hook 판정이 비결정적이라 2회 모두 통과 — Red 근거는 트랜스크립트 37건 오탐.

# Next

- code-reviewer 결과 처분 → push → PR → 머지(`/e merge`).

# Decisions

- `if` 는 `Bash(git commit*)` 유지 — 처음 `Bash(git *)` 로 바꿨다가 code-reviewer 지적으로 되돌림(이유: 모든 git 명령마다 agent 가 떠 명령당 +6초·월 850회, hook agent 의 `git diff --cached` 가 자기 hook 을 재귀 발화. docs 표상 `cd x && git commit`·`$(…)` 안의 commit 은 `git commit*` 도 서브커맨드로 잡는다). 오탐 차단은 프롬프트 0단계로 막는다: 명령 텍스트에 `git commit` 서브커맨드가 있으면(heredoc·치환 포함) 점검, 없으면 즉시 `{"ok": true}`.
- 출력은 docs 규약대로 `{"ok": true}` / `{"ok": false, "reason": ...}` 로 명시(기존 "빈 응답" 규약 대체), reason 은 개행 없는 한 줄(`; ` 구분 — JSON 안 생 개행으로 파싱 실패 방지). `$ARGUMENTS` 로 hook 입력을 명시 전달.
- PowerShell `shell` 민감파일 hook 은 범위 밖(별도).
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

# Review Disposition

- code-reviewer(Claude 단독, codex 크레딧 소진): Major 4(CONFIRMED 2·PLAUSIBLE 2)·Minor 4. **fix** `if` 되돌림(발화 폭증·재귀), 0단계 문구 순서(commit 있으면 점검 우선), reason 한 줄, A5 추가. **defer** PowerShell hook fail-open → `# Deferred`. **no-op** statusMessage·시크릿 노출 표면(`if` 되돌려 해소), agent_id 재귀 차단(`if` 되돌려 불필요). open: agent hook 이 잘못된 JSON·timeout 일 때 통과/차단 여부 docs 미기재.

# Deferred

- 민감파일 검사 hook(`"shell": "powershell"`)이 macOS 에서 **fail-open 확인됨**: headless 검증 세션 로그에 `no PowerShell executable (pwsh or powershell) was found on PATH` exit 1 → non-blocking 으로 통과. 로컬 pre-commit git hook 도 없어 `.env`/`.pem` staged 커밋을 아무것도 막지 않는다(높음, `.claude/settings.json` — bash 로 재작성하는 별도 작업).
