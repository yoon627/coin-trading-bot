---
title: pre-push codex 리뷰 게이트 — fail-closed, P0/P1 차단
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — scripts/git-hooks/pre-push 실측(331줄, 차단 규칙·mkdir lock·escalation timeout·bypass 경로)
sources:
  - scripts/git-hooks/pre-push
  - scripts/git-hooks/README.md
  - CLAUDE.md
---

# pre-push codex 리뷰 게이트

push 되는 커밋에 **`codex exec review --json` 을 high reasoning 으로** 돌리고 결과에 따라 push 를 막는다. 코드 스타일·보안·클린코드·테스트 체크의 실질 게이트는 이 hook 이다.

**정본은 tracked 파일 `scripts/git-hooks/pre-push`** 다. 실행되는 `.git/hooks/pre-push` 는 그것을 복사한 **설치본**이며 untracked 다 — fresh clone 이나 새 worktree 에는 없으므로 README 절차대로 재설치해야 한다. 훅 동작을 고칠 때는 반드시 tracked 쪽을 고치고 재설치한다.

## 차단 규칙

| 결과 | 동작 |
|---|---|
| `- [P0]` / `- [P1]` 발견 | **차단.** 고쳐야 push 된다 |
| `- [P2]` / `- [P3]` 발견 | **차단**, 단 `CODEX_ACK=1 git push` 로 "검토했고 이대로 받는다" 명시하면 통과 |
| codex 에러·출력 파싱 실패·CLI 부재 | **fail-closed** (차단) |
| docs-only push | 리뷰 우회 |
| `CODEX_SKIP=1 git push` | 전체 우회 — audit 로그에 기록되며 권장하지 않음 |

`python3` 이 JSONL 파싱에 필요하므로 없으면 역시 차단된다.

## hang 방지 장치

과거에 codegraph MCP 가 매달려 push 가 무한 대기하는 사고가 있었고(이슈 #45), 지금은 두 겹으로 막혀 있다:

- **`mkdir` atomic lock 직렬화** — 동시에 두 리뷰가 돌지 않는다. lock 대기 상한 600초이며, stale lock 은 소유 pid 가 죽었거나 age 초과일 때만 제거한다(갓 생성된 live lock 을 뺏지 않으려고). 그래서 여러 worktree 에서 **동시에 push 하면 뒤쪽이 대기**한다([[worktree-workflow]]).
- **escalation timeout** — codex 를 자기 process group 에서 실행하고 TERM → grace 10초 → KILL. 상한은 `CODEX_TIMEOUT` 기본 **480초**. 초과하면 "likely codegraph MCP hang" 메시지와 함께 차단된다.

## 실무 감각

- high reasoning 이라 **정상 통과에도 수 분** 걸린다. push 는 백그라운드로 돌리는 편이 낫다.
- **타임아웃이 나면 `CODEX_SKIP=1` 로 넘기기 전에 원인을 본다.** 그렇게 올린 브랜치는 *리뷰를 받지 않은 상태*가 되고, 실제로 그런 브랜치가 쌓인 전례가 있다.
- 재현되는 타임아웃 원인 하나가 **codegraph MCP 다중 인스턴스 경합**이다: codex 는 리뷰마다 `codegraph serve --mcp` 를 새로 띄우는데, Claude Code 세션도 각자 하나씩 띄운다. 세션이 여러 개 열려 있으면 `codegraph_explore` 호출이 응답하지 않는다. hook 의 lock 은 **hook 끼리만** 직렬화하므로 이 경합을 막지 못한다. `codegraph` 를 끄고 같은 리뷰를 돌리면 정상 완료된다(실측). 추적: 이슈 #60.
- 이 게이트는 push 시점의 최종 방어선이다. 개발 사이클 중간의 리뷰(구현 직후)와는 별개이며, 문서 동기화 규칙은 [[docs-code-sync]] 에 있다.
- 빌드 환경이 깨져 있으면 리뷰 이전에 로컬 검증부터 실패한다 — [[jdk-gradle-toolchain]] 참조.
