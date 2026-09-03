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

# Next

- hook 수정 → headless `claude -p` 로 4케이스 실측 → 커밋 → PR.

# Decisions

- `if` 를 `Bash(git *)`(명령명만 지정)로 바꿔 `$`·backtick 보수 발화를 줄이고, 프롬프트 0단계에 "`tool_input.command` 가 `git commit` 이 아니면 즉시 `{"ok": true}`" 를 넣어 필터가 넓게 발화해도 차단하지 않게 한다(이중 방어 — `if` 는 docs 상 hard gate 가 아니다).
- 출력은 docs 규약대로 `{"ok": true}` / `{"ok": false, "reason": ...}` 로 명시(기존 "빈 응답" 규약 대체). `$ARGUMENTS` 로 hook 입력을 명시 전달.
- PowerShell `shell` 민감파일 hook 은 범위 밖(별도).
- ~/.claude 의 `docs/codex-review.md`·wiki `worktree-isolation-bash-guard` 는 네이티브 격리 가드(메시지 "isolated in the worktree", 79건 실측) 대상이라 별개 — 이 hook 오탐과 혼동하지 않는다.

# Key Files

- `.claude/settings.json` — PreToolUse Bash agent hook.

# Blockers

# Acceptance

- A1 worktree 에서 `claude -p` 로 비git heredoc+`$VAR` 명령 실행 → 차단 없이 출력 `hello 1`.
- A2 `git status` 실행 → 차단 없음(agent 발화 시 0단계 통과).
- A3 와일드카드 import 가 든 `.kt` 스테이징 후 `git commit` → `[Wildcard imports]` 사유로 차단.
- A4 `.kt` 없는 스테이징(plan 파일) `git commit` → 통과, 커밋 생성.

# Deferred

- 민감파일 검사 hook 이 `"shell": "powershell"` 이라 macOS 에서 동작 여부 미확인(중, `.claude/settings.json`).
