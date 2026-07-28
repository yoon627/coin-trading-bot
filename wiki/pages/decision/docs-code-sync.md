---
title: 문서 동기화 규칙 — 어떤 변경이 어떤 문서를 갱신시키는가
category: decision
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — repo CLAUDE.md "문서 동기화 대상" 표 대조
sources:
  - CLAUDE.md
  - PROJECT_ANALYSIS.md
  - README.md
---

# 문서 동기화 규칙

변경 종류별로 갱신 대상 문서가 정해져 있다. **같은 브랜치에서** 갱신하는 것이 원칙이다 — 나중에 하겠다고 미루면 문서가 코드와 어긋난다.

| 변경 종류 | 갱신 대상 |
|---|---|
| 외부 visible behavior / public API / CLI | `README.md` |
| 모듈 구조·의존성·아키텍처 | `PROJECT_ANALYSIS.md` + `README.md` 해당 절 |
| 설계 결정·신규 서브시스템 | `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` 신규 |
| 운영/배포 절차 | `docs/runbook/`(있으면) 또는 README 운영 절 |
| 보안 정책 | `SECURITY.md`(있으면) |
| 이 wiki 의 페이지 추가·변경 | `wiki/index.md` 동기화 |

내부 리팩터·테스트 전용·invisible 버그 수정은 문서 갱신이 불필요하다.

## 문서를 근거로 삼지 말 것

문서가 코드와 어긋난 전례가 실제로 있다. `PROJECT_ANALYSIS.md` 가 실거래 리스크 파라미터를 **정반대로**(손절 −3%/익절 +5% — 실제는 익절 +2%/손절 −5%) 기재하고, 라이브에 없는 MA50 매수 차단을 있다고 서술한 건이 `docs-sync` 작업에서 잡혔다. 운영자의 리스크 오인으로 직결되는 종류의 오류다.

그래서 이 wiki 의 규약은 **"문서가 아니라 코드를 확인하고, 확인한 것만 쓴다"** 이다(`wiki/WIKI.md` §4). 실제 파라미터는 [[architecture-overview]] 가 가리키는 코드 경로에서 확인한다.

## 이 wiki 의 자리

- `plans/` — 진행 중 작업 하나의 상태([[plan-git-tracking]])
- GitHub Issues — 백로그([[github-issues-backlog]])
- `wiki/` — 작업을 가로지르는 누적 지식
- `docs/superpowers/specs/` — 특정 작업의 설계 스펙(작성 시점 고정)

wiki 페이지를 건드리면 `wiki/index.md` 등재를 함께 맞춰야 한다. 링크 검사기가 index 누락·잉여를 둘 다 위반으로 잡는다.
