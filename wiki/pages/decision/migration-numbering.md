---
title: Flyway 번호 규약 — 미머지 브랜치의 번호 선점 문제
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — db/migration/ 최신 V14 확인, GitHub 이슈 #49 병렬 제약 절
sources:
  - bot/src/main/resources/db/migration/
  - https://github.com/yoon627/coin-trading-bot/issues/49
---

# 마이그레이션 번호 규약

Flyway 는 버전 번호가 유일해야 한다. **여러 worktree 가 동시에 진행되면 번호가 충돌**한다 — 각 브랜치는 자기가 분기한 시점의 최신 번호만 알기 때문이다.

## 규칙

1. **새 마이그레이션 번호는 `origin/main` 의 최신 번호 +1 로 잡는다.** 자기 브랜치 기준이 아니다.
2. **미머지 브랜치가 이미 그 번호를 쓰고 있는지 확인한다.** 확인하지 않으면 나중에 머지하는 쪽이 프로덕션에서 "이미 적용된 버전" 충돌을 맞는다.
3. **충돌하면 나중에 머지되는 쪽이 renumber** 한다. 파일명과 참조를 함께 바꾼다.
4. **destructive DDL(DROP 등)은 배포 순서까지 함께 정한다.** 롤백이 불가능하므로 언제 어떤 순서로 적용할지가 코드 리뷰 대상이다.

## 착수 전 확인 절차

`main` 의 최신 번호는 마이그레이션 디렉토리로 확인한다([[persistence-schema]]).

```bash
git fetch --all --prune          # origin main 만 받으면 다른 브랜치의 선점을 못 본다
git ls-tree --name-only origin/main bot/src/main/resources/db/migration/ | sort -V | tail -3
# 아직 머지되지 않은 브랜치가 잡아둔 번호까지 훑는다
git branch -a --format='%(refname:short)' | while read -r b; do
  git ls-tree --name-only "$b" bot/src/main/resources/db/migration/ 2>/dev/null
done | sort -u | sort -V | tail -5
```

`git fetch origin main` 만 돌리면 **다른 머신·worktree 가 방금 push 한 마이그레이션 브랜치가 로컬에 없어** 그 번호를 못 본다. 충돌 검사의 의미가 사라지므로 `--all` 로 받는다.

**어느 브랜치가 지금 어떤 번호를 잡고 있는지는 여기 적지 않는다** — 그건 진행 중 작업의 상태이고, 이 wiki 가 아니라 GitHub 이슈 #49 와 각 브랜치의 plan 이 소유한다([[github-issues-backlog]]). 여기 적으면 반드시 stale 해진다.

브랜치를 병렬로 돌릴 때의 일반 규칙은 [[worktree-workflow]] 에 있다.
