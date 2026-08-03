---
title: 백로그는 GitHub Issues 단일 소스 (TODO 파일 금지)
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — repo CLAUDE.md "TODO / 백로그 = GitHub Issues" 절 대조, gh issue list 로 이슈가 실제 백로그로 쓰이는지 확인 (이슈 개수는 변하므로 기록하지 않는다)
sources:
  - CLAUDE.md
  - https://github.com/yoon627/coin-trading-bot/issues
---

# 백로그 = GitHub Issues

할 일·백로그·미룬 항목은 **GitHub Issues 에만** 둔다. `TODO.md` 같은 추적 파일을 새로 만들지 않는다.

- 작업 아이디어·버그·후속 작업은 이슈로 만들고, PR/커밋에서 `Closes #N` 으로 연결한다.
- `docs/superpowers/specs/` 의 설계 스펙과 `.claude/plans/` 의 plan 은 **진행 중 작업의 설계·상태 기록 전용**이며 백로그 저장소가 아니다([[plan-git-tracking]]).

## wiki 도 백로그가 아니다

이 wiki 에는 **열린 이슈의 상태를 옮기지 않는다.** 옮기는 순간 제2 백로그가 생기고 두 곳이 어긋난다. 종결된 이슈에서 나온 *결정·교훈*만 페이지로 승격한다(`wiki/WIKI.md` §1).

## 인덱스 이슈 패턴

여러 작업의 **순서·의존 관계**처럼 특정 plan 하나가 소유할 수 없는 정보는 인덱스 이슈로 관리한다. 현재 #49 가 worktree 착수 순서와 병렬 제약을 소유한다 — 각 작업의 상세는 해당 브랜치 plan 이 소유하고 이슈는 중복 서술하지 않는다([[worktree-workflow]]).

조건부 작업도 이슈에 조건을 명시한다. 예: 분산/다중 인스턴스 확장(#26)은 "부하 테스트로 단일 인스턴스 한계를 입증한 후 착수"라는 선행 조건이 이슈에 적혀 있다.

문서 쪽 동기화 규칙은 [[docs-code-sync]] 참조.
