---
title: .claude/plans/ 는 git 추적하지 않는다 (2026-09-15 되돌림)
category: decision
created: 2026-07-28
updated: 2026-09-15
claim_state: current
verified: 2026-09-15 — .gitignore 의 `.claude/*` + `!.claude/tasks/` 만 남고 `!.claude/plans/` 제거, git check-ignore .claude/plans = ignored
sources:
  - .gitignore
  - CLAUDE.md
---

# `.claude/plans/` 는 gitignored 다

`.gitignore` 는 `.claude/*` 를 무시하고 `!.claude/tasks/` 만 negation 으로 되살린다. `.claude/plans/` 는 글로벌 규약(`~/.claude/CLAUDE.md` §10, `/e`·`/c`·`/wt` 스킬)의 기본 전제대로 **추적하지 않는다**.

## 이력

2026-07-28 ~ 2026-09-15 사이에는 `!.claude/plans/` 로 tracked 였다. 목적은 (1) `git worktree remove` 가 ignored 파일을 경고 없이 지우므로 plan 소실 방지, (2) 브랜치와 함께 push 해 다른 머신에서 이어받기.

2026-09-15 에 되돌렸다. 이유: plan 갱신(`chore(plan)`·`docs(plan): close …`)이 코드와 무관한 커밋으로 main 히스토리·PR diff 에 계속 쌓이는 것이 실익보다 컸다. 검토했다가 기각한 대안 — squash-merge 로 전환(코드 커밋 단위까지 뭉개짐), orphan `plans` 브랜치(worktree 마다 배선 필요), `~/.claude` repo 에 보관(스킬 경로 규약 변경 필요). 사용자는 "다른 머신에서 이어받기"를 포기하는 대신 글로벌 기본값을 택했다.

## 실무상 함의

- **worktree 삭제 전 plan 백업이 다시 필요하다.** 글로벌 `/e` 가 삭제 전 main 으로 백업하는 절차를 그대로 따른다. `git worktree remove` 는 미커밋 plan 을 거부하지 않으니 `git status --porcelain --ignored` 로 확인한다([[worktree-workflow]]).
- 다른 머신에서 이어가려면 plan 파일을 직접 옮기거나 PR·이슈·코드로 맥락을 복원한다.
- 되돌리기 이전에 커밋된 plan 들은 git 이력에 남아 있다(`git log --all -- .claude/plans/`). 제거된 KIS 봇의 설계 기록(`.claude/plans/2026-06-14-stock-bot-kis/`)도 이력에서 본다.

## plan 과 wiki 의 역할 분리

plan 은 **진행 중 작업 하나의 상태**를 소유하고 작업이 끝나면 닫힌다. 재사용 가능한 결정·교훈은 여기 wiki 로 승격한다 — 자세한 경계는 `wiki/WIKI.md` §1 과 [[docs-code-sync]] 참조.

plan 만 보면 진행 상황을 다 아는 것은 아니다. 중단된 작업은 stash·태그·미push 커밋에도 남으므로 [[lesson-resume-state-sources]] 의 6곳을 함께 확인한다.
