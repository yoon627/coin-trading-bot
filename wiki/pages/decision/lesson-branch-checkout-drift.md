---
title: lesson — 긴 작업 중 checkout 하면 미커밋 변경이 따라간다
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-06-01) 이관, 원본 커밋 331426f
sources:
  - docs/lessons.md
---

# lesson: checkout 이 미커밋 변경을 끌고 간다

**언제**: 2026-06-01

## 증상

여러 파일을 고치는 긴 작업 중에 `feat/*` → `main` 으로 checkout 이 일어났고, **미커밋 변경 전부가 main 의 working tree 로 따라갔다.** 의도한 feature 브랜치가 아니라 main 에 작업이 쌓였고, 커밋 직전까지 아무도 눈치채지 못했다.

여기에 더해 로컬 `main` 이 `origin/main` 보다 2커밋 behind 였다. 그대로 커밋했다면 그 2커밋(배포 관련 변경)이 빠진 **stale base** 위에 작업이 얹혀졌을 것이다.

## 원인

git 의 정상 동작이다 — checkout 은 충돌하지 않는 미커밋 변경을 그대로 들고 브랜치를 옮긴다. 문제는 **세션 초기에 확인한 브랜치를 끝까지 믿은 것**이다.

## 지금 어떻게 하나

1. **긴 세션은 커밋 직전에 `git branch --show-current` + `git status` 를 다시 확인한다.** 세션 초기 확인만 믿지 않는다.
2. 애초에 작업마다 worktree 를 분리하면 이 사고 자체가 성립하지 않는다 — 디렉토리가 다르므로 checkout 으로 변경이 옮겨가지 않는다([[worktree-workflow]]).

## 복구 방법 (실제로 통했던 절차)

```bash
git stash -u
git worktree add <dir> -b <branch> origin/main
git -C <dir> stash pop     # 겹친 커밋과 자동 3-way 병합 (당시 충돌 0)
```

이때 plan 파일도 함께 옮겨야 맥락이 끊기지 않는다 — 이 repo 는 plan 이 tracked 라 stash 대상에 포함된다([[plan-git-tracking]]).
