---
title: worktree 작업 방식 — 분기·병렬 제약·머지 후 자동 정리
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — git worktree list 실측(main + 3개), 이슈 #49 본문, 글로벌 CLAUDE.md §8
sources:
  - https://github.com/yoon627/coin-trading-bot/issues/49
  - CLAUDE.md
---

# worktree 작업 방식

비trivial 작업은 `main` 에서 직접 하지 않고 **작업마다 별도 worktree**(`.claude/worktrees/<name>`, 같은 이름의 브랜치)에서 한다. `main` push 는 차단돼 있다.

## 왜 worktree 인가 (브랜치 전환이 아니라)

- 진행 중인 작업 위에 새 작업을 얹으면 base 가 섞이고 변경이 혼입된다.
- 브랜치를 checkout 으로 갈아타면 **미커밋 변경이 따라간다** — 실제로 그 사고가 있었다([[lesson-branch-checkout-drift]]).
- worktree 는 디렉토리가 분리돼 동시 편집이 안전하다.

## 병렬로 돌릴 때 걸리는 제약

1. **마이그레이션 번호 충돌** — 각 브랜치가 자기 분기 시점의 최신 번호만 안다([[migration-numbering]]).
2. **base 의존** — 한 작업이 다른 작업의 코드 위에 서면 동시 진행이 불가능하다(rebase 냐 stack 이냐를 먼저 정해야 한다).
3. **push 직렬화** — pre-push codex 리뷰가 직렬화되어 동시 push 는 대기가 길어진다([[prepush-codex-review]]).
4. **worklog 정확성** — worktree 작업은 그 worktree 세션에서 시작·마무리한다. 한 세션에서 여러 worktree 를 오가면 작업시간이 한 프로젝트 로그에 뭉친다.

착수 순서와 현재 병렬 제약은 **GitHub 이슈 #49 가 단일 소스**다 — worktree 를 매번 전수조사해 재판정하지 않는다.

## 삭제 시 주의

- `git worktree remove` 는 **gitignored 파일(`.env` 등)을 경고 없이 함께 지운다.** 삭제 전 `git status --porcelain --ignored` 로 확인한다.
- `.claude/plans/` 는 tracked 라 미커밋 plan 이 있으면 remove 가 거부한다 — 안전장치다([[plan-git-tracking]]).

## 머지 후 정리 (이 repo 규칙)

PR 이 머지되면 **worktree + 로컬 브랜치 + 원격 브랜치를 확인 없이 자동 정리**한다. 이 repo 는 글로벌 규칙(원격 삭제 전 확인)의 면제 대상이다 — 머지된 브랜치는 base history 로 복구 가능하므로. 정리 후 삭제한 원격 tip sha 를 보고에 남긴다.

머지 후 `main` worktree 로 복귀할 때는 clean 하고 fast-forward 가능한 경우에만 `git pull origin main --rebase` 를 돌린다. 로컬 전용 커밋이 있으면 히스토리 재작성을 피해 건너뛰고 알린다.
