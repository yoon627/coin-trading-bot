---
title: pre-push 가드 — deploy.yml 자기제외 검사 (codex 리뷰 게이트는 2026-09-03 제거)
category: decision
created: 2026-07-28
updated: 2026-09-03
claim_state: current
verified: 2026-09-03 — scripts/git-hooks/pre-push 슬림본 12케이스 실행(가드 차단/통과·inline fail-closed·다중 ref·삭제 ref·빈 stdin), 설치본 드리프트 실측
sources:
  - scripts/git-hooks/pre-push
  - scripts/git-hooks/README.md
  - coin-trading-bot PR #165 (agent hook 오탐 수정), ~/.claude PR #156 (code-reviewer codex 병행 강화)
---

# pre-push 가드

push 직전 git hook. **지금 하는 일은 하나** — push 되는 tip 의 `.github/workflows/deploy.yml` 에서 `paths-ignore` 가 워크플로 자신을 매칭하는 패턴(`**`, `.github/**`, `**.yml` …)을 가지면 push 를 막는다(#151). 그 패턴이 들어가면 `deploy.yml` 을 바꾸는 push 가 워크플로 실행을 만들지 않아 `DeployWorkflowPathListTest` 가 영영 돌지 않기 때문이다. inline 형식(`paths-ignore: [ … ]`)은 파서가 못 읽으므로 fail-closed 로 막는다. ref 삭제·새 커밋 없는 재-push 는 건너뛴다.

**정본은 tracked `scripts/git-hooks/pre-push`**, 실행본 `.git/hooks/pre-push` 는 복사 설치본(untracked, 이 clone 의 모든 worktree 공유). 정본을 고치면 재설치해야 한다 — 2026-07-28 설치본이 정본보다 뒤처져 이 가드가 5주간 로컬에서 돌지 않았던 것을 2026-09-03 에 발견했다. 빌드가 깨져도 push 는 막히지 않는다 — 검증 실패는 CI·배포에서 드러난다([[jdk-gradle-toolchain]]).

## 제거된 codex 리뷰 게이트 (2026-07-28 ~ 2026-09-03)

같은 hook 이 push 범위 전체 diff 에 `codex exec review --json`(high) 을 돌려 `[P0]`/`[P1]` 은 차단, `[P2]`/`[P3]` 는 `CODEX_ACK=1` 로만 통과, codex 에러·크레딧 소진·파싱 실패는 fail-closed(차단), docs-only 는 우회, `CODEX_SKIP=1` 은 전체 우회(감사 로그)였다. 머신 단위 lock 직렬화와 480초 escalation timeout(codegraph MCP hang 재발 방지, #45·#60)도 여기 있었다.

제거 이유:
- 구현 직후 리뷰 단계의 Claude `code-reviewer` 가 이미 codex 병행(글로벌 §9)을 규약으로 갖는데, repo 의 커밋 전 agent hook 오탐이 그 `codex exec` 를 막고 있었다(PR #165 에서 수정). 그 빈자리를 pre-push 가 push 마다 8라운드로 메우는 것은 가장 비싼 위치에서 나눠 잡는 셈이었다.
- fail-closed 라 크레딧 소진 시 코드 결함과 무관하게 모든 push 가 막혔다(PR #165 는 두 번 `CODEX_SKIP=1` 로 우회).
- 대신 구현 후 code-reviewer 의 codex 병행을 브랜치 전체 diff·high·P0/P1 우선으로 강화했다(~/.claude PR #156). 잃는 것은 "Claude 가 리뷰를 건너뛰어도 막는 강제성"이고, 이는 dlc 규율에 맡긴다.

되돌리려면 `ffd6ec9:scripts/git-hooks/pre-push` 를 재설치한다(README Rollback). 동시 push 가 직렬화되던 제약도 함께 사라졌다([[worktree-workflow]]).

## 실무 감각
- 이 가드에 걸리는 push 는 `deploy.yml` 을 바꾼 것뿐이다. 다른 push 가 막히면 hook 이 아니라 원격(브랜치 보호)이나 네트워크 쪽을 본다.
- 과거 codex 게이트가 잡은 P1 사례는 [[lesson-rollback-removal]]·[[lesson-single-point-verification]] 에 남아 있다 — 그 검토 관점은 이제 code-reviewer 프롬프트가 맡는다.
