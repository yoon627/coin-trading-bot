---
title: pre-push codex 리뷰 게이트 — fail-closed, P0/P1 차단
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — .git/hooks/pre-push 실측(280줄, 차단 규칙·flock·timeout·bypass 경로 확인)
sources:
  - .git/hooks/pre-push
  - CLAUDE.md
---

# pre-push codex 리뷰 게이트

`.git/hooks/pre-push` 가 push 되는 커밋에 **`codex exec review --json` 을 high reasoning 으로** 돌리고 결과에 따라 push 를 막는다. 코드 스타일·보안·클린코드·테스트 체크의 실질 게이트는 이 hook 이다.

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

- **flock 직렬화** — 동시에 두 리뷰가 돌지 않는다. lock 대기 상한 600초, 죽은 pid 의 stale lock 은 제거한다. 그래서 여러 worktree 에서 **동시에 push 하면 뒤쪽이 대기**한다([[worktree-workflow]]).
- **escalation timeout** — codex 를 자기 process group 에서 실행하고 TERM → grace 10초 → KILL. 상한은 `CODEX_TIMEOUT` 기본 **480초**. 초과하면 "likely codegraph MCP hang" 메시지와 함께 차단된다.

## 실무 감각

- high reasoning 이라 **정상 통과에도 수 분** 걸린다. push 는 백그라운드로 돌리는 편이 낫다.
- 대용량 diff(수십 파일)에서 타임아웃이 재발한 사례가 있다. 그때 `CODEX_SKIP=1` 로 백업 push 를 하면 **그 브랜치는 리뷰를 받지 않은 상태**가 된다 — 재개할 때 정식 리뷰를 반드시 거쳐야 한다.
- 이 게이트는 push 시점의 최종 방어선이다. 개발 사이클 중간의 리뷰(구현 직후)와는 별개이며, 문서 동기화 규칙은 [[docs-code-sync]] 에 있다.
- 빌드 환경이 깨져 있으면 리뷰 이전에 로컬 검증부터 실패한다 — [[jdk-gradle-toolchain]] 참조.
