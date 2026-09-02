---
title: prepush-gate-logdir-lesson — "pre-push codex 게이트가 조용히 skip 됐다" 는 오진이었다 (원인·교훈 적립)
status: in_progress
started: 2026-09-02
updated: 2026-09-02
---

# Goal

2026-09-01 세션(#133 engine-buy-fee-basis)에서 **"pre-push codex 게이트가 조용히 건너뛰어졌다"** 고
기록한 Workflow Finding 을 **오진으로 정정**하고, 같은 오판을 반복하지 않도록 교훈을 적립한다.

이 plan 은 그 세션의 **핸드오프 기록**이기도 하다 — 미결로 남긴 판단을 다음 세션이 근거째 이어받는다.

# Progress

- 2026-09-02: 유보됐던 Finding 을 재조사해 **오진으로 확정**(아래 `# Decisions` 1). 게이트는 두 push
  모두에서 정상 실행됐다. 이 worktree·plan 생성으로 기록을 떼어냈다. 코드 변경 없음.

# Next

**후속 3건의 적용 여부를 사용자가 판단해야 한다** — 셋 다 운영 자산/wiki 변경이라 승인 후 착수(§1).

1. **`engine-buy-fee-basis` plan 의 `# Workflow Findings` 정정.**
   그 plan 은 PR #155 로 열려 있고 **머지되면 틀린 기록이 main 에 남는다.** 정정은 그 브랜치에서 해야
   하며(별도 브랜치로 하면 충돌), 머지 전에 하는 편이 낫다. 파일:
   `.claude/plans/2026-08-31-engine-buy-fee-basis/engine-buy-fee-basis-plan.md`
   — `# Workflow Findings` 첫 항목 + `## codex 최종 diff 재검토 (2026-09-01)` 도입부 2곳.

2. **wiki 교훈 페이지** `wiki/pages/decision/lesson-worktree-gitdir-logs.md` 신규 여부.
   내용: linked worktree 에서 `git rev-parse --git-dir` 는 `.git/worktrees/<name>` 이다 → **`.git/` 하위
   산출물(로그·캐시)은 worktree 마다 갈린다.** "로그가 없다 = 안 돌았다" 추론은 **디렉토리를 먼저
   확정한 뒤에만** 성립한다. `wiki/index.md` 등재 동기화 필요.

3. **memory `project_prepush_codex_slow` 정정은 불필요**하다는 판정을 확정.
   "`git push` 는 run_in_background 로" 조언은 **게이트를 무력화하지 않는다**(이번 근거로 반증됨).
   다만 백그라운드 실행은 훅 출력을 세션에서 안 보이게 만들어 *오진을 유발*했다 — 한 줄 덧붙일지는 판단 대상.

# Decisions

## 1. Finding "게이트가 조용히 skip 됐다" 는 **오진**이다 (2026-09-02, ✅확실)

**원래 주장**: 브랜치 첫 push(`04dfd60`)에서 codex 리뷰가 실행되지 않았다. 근거는 (a) 훅 출력 0줄,
(b) `.git/codex-pre-push/` 의 최신 로그가 `20260831-230325.jsonl`(그 push 이전).

**반증 — 근거 (b) 를 잘못된 디렉토리에서 봤다.**

훅 `:20` 이 로그 위치를 이렇게 잡는다:

```bash
LOG_DIR="$(git rev-parse --git-dir)/codex-pre-push"
```

linked worktree 안에서 `git rev-parse --git-dir` 는 **공용 `.git` 이 아니라 그 worktree 전용 디렉토리**를
반환한다 (실측):

```
$ cd .claude/worktrees/engine-buy-fee-basis
git-dir: /Users/…/coin-trading-bot/.git/worktrees/engine-buy-fee-basis
common:  /Users/…/coin-trading-bot/.git
```

→ 그 push 들의 로그는 `.git/codex-pre-push/` 가 아니라
`.git/worktrees/engine-buy-fee-basis/codex-pre-push/` 에 있다. 실제로 **거기 두 개가 있다**:

| 로그 | mtime | 대응 push | 커밋 시각 |
|---|---|---|---|
| `20260901-000526.jsonl` (461KB) | 09-01 00:14 | `04dfd60` | 00:05:18 (**push 8초 후 로그 생성**) |
| `20260901-002534.jsonl` (235KB) | 09-01 00:29 | `7728cf8` | 00:21:59 |

로그 본문도 리뷰 세션 그 자체다 — 첫 로그 시작부가 `thread.started` → `git status` · `wiki/index.md` ·
`git diff --stat 9376452…`(= 직전 커밋 base) 실행이다.

**결론: 두 push 모두 게이트가 정상 실행됐다.** 건너뛴 push 는 없었다.

**근거 (a)"출력 0줄" 의 진짜 원인**: `run_in_background` 로 push 를 돌리면 훅의 stderr 가 백그라운드
태스크 출력으로 가고 세션 본문에 안 보인다. **출력 부재는 미실행의 증거가 아니다.**

**기각된 가설**: 훅 `:46-48` 의 "stdin 에서 ref 를 못 읽으면 조용히 `exit 0`" 경로. 그 코드는 실재하지만
이번 건의 원인이 아니다. 당시에도 "백그라운드 3건 중 2건만 실패"라 가설이 안 맞는 게 보였는데,
**틀린 관측(b)를 버리는 대신 가설을 유보하는 쪽을 택한 것**이 오판이었다.

## 2. 교훈 — "산출물이 없다"로 "실행이 없었다"를 추론하지 않는다

3 Whys:

1. 왜 게이트가 안 돌았다고 판단했나 → 로그 디렉토리에 새 파일이 없어서.
2. 왜 없었나 → **다른 worktree 의 디렉토리를 봤다.** linked worktree 는 `.git/worktrees/<name>` 이 git-dir 이다.
3. 왜 그걸 몰랐나 → `core.hooksPath` 가 절대경로(`…/.git/hooks`)로 **전 worktree 공유**라
   (`2026-07-18-prepush-codex-hardening` plan 에 그렇게 적혀 있다) **훅이 공유되면 산출물도 공유될
   것이라 유추**했다. 훅 스크립트는 공유되지만 `LOG_DIR` 은 실행 시점 `git rev-parse --git-dir` 로
   정해져 **worktree 별로 갈린다.** 공유되는 것과 갈리는 것을 구분하지 않았다.

**규칙**: 부재(negative evidence)로 결론 내기 전에 **경로를 실측으로 확정**한다.
worktree 안에서는 `git rev-parse --git-dir` ≠ `git rev-parse --git-common-dir`.

## 3. 이번 오진의 실질 피해는 없다

#133 의 리뷰 커버리지 자체는 무관하게 충분했다 — code-reviewer + codex 가 돌았고, 반영 후 최종 diff 로
codex 를 한 번 더 돌렸다(Critical 0 · Major 0). 오진의 비용은 **잘못된 기록이 plan 에 남은 것**뿐이며
`# Next` 1 이 그걸 걷어낸다.

# Key Files

- `scripts/git-hooks/pre-push` — `LOG_DIR="$(git rev-parse --git-dir)/codex-pre-push"` (`:20`).
  stdin 무읽음 시 조용한 `exit 0` 은 `:46-48` (이번 건과 무관하나 별개 UX 이슈 후보).
- `.claude/plans/2026-08-31-engine-buy-fee-basis/engine-buy-fee-basis-plan.md` — **정정 대상**
  (브랜치 `engine-buy-fee-basis`, PR #155 오픈 중. main 에는 아직 없다).
- `.claude/plans/2026-07-18-prepush-codex-hardening/prepush-codex-hardening-plan.md` — 훅 구조·
  `core.hooksPath` 공유 사실의 출처.

# Blockers

없음. 후속 3건은 막힌 게 아니라 **승인 대기**다(`# Next`).

# Acceptance

- [ ] `engine-buy-fee-basis` plan 의 Workflow Finding 2곳이 오진 정정으로 교체됐다 (승인 시).
- [ ] wiki 교훈 페이지 신규 여부가 판정됐고, 만들었다면 `wiki/index.md` 등재 + 검증 3종 통과 (승인 시).
- [x] 오진의 반증 근거가 실측으로 확정됐다 — git-dir 실측 · 로그 2건 · 커밋↔로그 시각 대조.

# Deferred

- **훅이 stdin 부재 시 조용히 `exit 0` 하는 것**(`:46-48`)은 이번 오진과 무관하지만 여전히 **관측 불가능한
  성공/실패**를 만든다. 별개 개선 후보(경고 1줄 출력). 심각도: 하.
- **백그라운드 push 는 훅 출력을 세션에서 감춘다.** 게이트 자체는 정상이나 이번처럼 오진을 유발한다.
  `git push 2>&1 | tee` 류로 출력을 남길지 판단. 심각도: 하.

# Workflow Findings

- **유보한 Finding 을 유보한 채 세션을 닫으면, 다음 세션이 그걸 사실로 읽는다** (2026-09-02).
  2026-09-01 세션은 원인을 "⚠️미확정"으로 정직하게 표시했지만, **관측 자체를 재확인하지 않았다.**
  가설이 관측과 안 맞을 때(백그라운드 3건 중 2건만 실패) 가설을 유보하기 전에 **관측을 먼저 의심**해야
  했다 — 재확인 비용은 `ls` 한 번이었다.
