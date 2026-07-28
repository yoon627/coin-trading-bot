---
title: lesson — "진행하던 작업" 을 찾을 땐 6곳을 모두 본다
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — docs/lessons.md 원문 항목(2026-05-25) 이관, 원본 커밋 331426f
sources:
  - docs/lessons.md
---

# lesson: resume 질문엔 6곳을 확인한다

**언제**: 2026-05-25

## 증상

새 세션에서 "진행하던 작업 기억해?" 라는 질문에 **"없음" 이라고 잘못 단언**했다. 실제로는 이전 세션이 stash 하나(`claude-pre-rebase-wt`), 태그 하나(`claude-backup-before-rebase`), **로컬 커밋 6개**를 남겨놨는데도 찾지 못했다.

## 원인

`memory/` 와 `.claude/plans/` 만 확인하고 **git 에 영속된 상태**(stash·tag·아직 push 되지 않은 커밋)를 보지 않았다. 작업의 흔적은 대화 기록에만 남는 게 아니라 저장소에도 남는다.

## 지금 어떻게 하나

resume 류 질문에는 아래 여섯 곳을 **모두** 확인한 뒤 답한다:

| # | 확인 대상 | 명령 |
|---|---|---|
| ① | 세션 메모리 | `memory/` |
| ② | 작업 plan | `.claude/plans/` ([[plan-git-tracking]]) |
| ③ | stash | `git stash list` |
| ④ | 백업 태그 | `git tag --list "claude-*"` |
| ⑤ | 미push 커밋 | `git log --oneline @{u}..HEAD` |
| ⑥ | 작업 트리 | `git status --short` |

이 repo 에서는 여기에 **worktree 목록**이 추가된다 — 진행 중 작업이 다른 worktree 에 있을 수 있고, 그 순서는 이슈 #49 가 소유한다([[worktree-workflow]]).

## 일반화

"기억이 없다"와 "저장소에 흔적이 없다"는 다른 명제다. 후자를 확인하지 않고 전자로 답하면 **작업을 통째로 잃을 수 있다**.
