---
title: .claude/plans/ 를 git 추적한다 (이 repo 고유)
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — .gitignore:62-71 의 `.claude/*` + `!.claude/tasks/` + `!.claude/plans/` 실측, git check-ignore .claude/plans = not ignored
sources:
  - .gitignore
  - CLAUDE.md
---

# `.claude/plans/` 는 tracked 다

대부분의 repo 에서 `.claude/` 는 통째로 ignored 지만, 이 repo 는 `.gitignore` 에서 `.claude/*` 를 무시하되 **`!.claude/plans/` 와 `!.claude/tasks/` 를 negation** 으로 되살린다.

## 왜

작업 plan 이 worktree 안에 있는데 gitignored 면, **worktree 를 지우는 순간 plan 이 함께 사라진다**. `git worktree remove` 는 ignored 파일을 경고 없이 지운다. plan 은 세션·도구를 잇는 핸드오프 채널이라 유실되면 다음 세션이 맥락을 처음부터 복원해야 한다.

tracked 로 두면 부수 효과가 하나 더 있다: plan 을 브랜치와 함께 push 하면 **다른 머신·다른 세션이 pull 로 이어받는다**. "단일 진실 소스"가 머신 경계를 넘는다.

## 실무상 함의

- 작업 worktree 에서 plan 은 코드와 별도 커밋(`chore(plan): ...`)하거나 작업 커밋에 포함한다.
- **미커밋 plan 변경이 있으면 `git worktree remove` 가 거부한다** — tracked 라서 `git status` 에 뜨기 때문이다. 이건 안전장치다([[worktree-workflow]]).
- 글로벌 `/e`·`/c`·`/wt` skill 은 plans 가 gitignored 라고 전제하고 만들어졌다(worktree 삭제 전 main 에 백업하는 절차 등). 이 repo 에선 그 전제가 어긋나며, **plan 이 git 에 있으므로 별도 백업이 불필요**하다.
- plan 에 raw token·credential·PII 를 붙여넣지 않는다. tracked 라 그대로 원격에 올라간다. pre-commit 검사가 staged `plans/*.md` 를 스캔한다([[prepush-codex-review]]).

## plan 과 wiki 의 역할 분리

plan 은 **진행 중 작업 하나의 상태**를 소유하고 작업이 끝나면 닫힌다. 재사용 가능한 결정·교훈은 여기 wiki 로 승격한다 — 자세한 경계는 `wiki/WIKI.md` §1 과 [[docs-code-sync]] 참조.

plan 이 tracked 라고 해서 plan 만 보면 진행 상황을 다 아는 것은 아니다. 중단된 작업은 stash·태그·미push 커밋에도 남으므로 [[lesson-resume-state-sources]] 의 6곳을 함께 확인한다.
