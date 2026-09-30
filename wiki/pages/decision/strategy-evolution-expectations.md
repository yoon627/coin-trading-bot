---
title: 전략 개선 루프의 기대치 — 반자동이며 연 0~2건이 정상
category: decision
created: 2026-07-28
updated: 2026-10-01
claim_state: historical
verified: 2026-10-01 — 연구·백테 코드(`BacktestEngine`·`IntrabarExitModel`·M1 replay) 삭제 후 historical 로 전환, 삭제된 코드를 서술하던 "머지된 정합 개선" 절을 뺌 · 2026-07-28 — strategy-evolution-loop plan 의 설계 합의 절, 머지된 PR #42(main bfb237f)로 Phase 0 ①③ 반영 확인
sources:
  - .claude/plans/2026-07-10-strategy-evolution-loop/strategy-evolution-loop-plan.md
---

# 전략 개선 루프의 기대치

> 이 페이지는 진행 중 작업의 *상태*가 아니라 **확정된 설계 합의**만 담는다. 진행 상태는 해당 plan 이 소유한다.
>
> **historical** — 이 루프가 기대던 연구·백테 코드(D1 백테 엔진·intrabar 청산 모델·M1 replay·연구 fixture)는
> 2026-10-01 MVP 1단계에서 지웠다([[rightsizing-history]]). 아래 기대치·원칙은 연구 루프를 다시 둘 때의 출발점이다.

## 명문화된 기대치

이 파이프라인은 "완전 자동 자기성장"이 **아니다**. 자기 방어를 갖춘 **반자동 리서치 파이프라인**이다.

D1 스윙 · 사실상 단일 마켓 · 거래당 10만원 상한이라는 조건에서 **통계적으로 정당한 승격은 연 0~2건이 정상**이다. 더 빠른 성장을 원한다면 루프를 정교화할 게 아니라 **표본을 늘리는 방향**(전략 클래스·타임프레임·마켓 폭·투자 사이즈)을 바꿔야 하고, 그건 루프가 아니라 사람의 결정이다.

## 자동화 경계 = 리스크 방향

- **리스크를 줄이는 전이**(강등·safe-mode·kill)는 자동.
- **리스크를 키우는 전이**(승격·사이즈 복원)는 반드시 사람 승인.

자동 승격(champion-challenger auto-promote)과 별도 티커 실돈 파일럿은 **폐기**됐다. 전량매도 구조상 같은 마켓에 두 포지션을 못 두고, 다른 마켓에 배정하면 성과가 파라미터 효과가 아니라 마켓 효과가 되어 비교가 무의미해지기 때문이다 — 그 상태로 자동 승격을 걸면 복권이 실거래를 바꾸게 된다.

## 전략 선택에 쓰이는 통계 원칙

per-trade **net** pnl% 로 통일하고(all-in 복리 총수익 비교 금지 — [[swing-strategies]] 성과를 그 지표로 줄세우면 안 된다), 선택은 in-sample 에서만, 판정은 out-of-sample 에서만 한다. 임계값은 선험적으로 박지 않고 관찰 운영 후 캘리브레이션한다.
