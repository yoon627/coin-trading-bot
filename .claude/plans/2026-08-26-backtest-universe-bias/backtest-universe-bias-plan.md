---
title: backtest-universe-bias — #112 백테 fixture 유니버스의 look-ahead/생존편향 제거
status: in_progress
started: 2026-08-26
updated: 2026-08-26
---

# Goal

백테 fixture 의 마켓 선정에서 look-ahead 를 없앤다. 현재는 **수집 시점(2026-08)의 24h 거래대금 상위**로
골라 구간 *끝* 정보를 쓴 것이라 ① 생존편향(그 사이 폐지·급락한 종목 배제) ② 신규상장 배제 편향이 동시에 있다.
각 구간 *시작* 시점 기준 유니버스로 바꾸는 것이 목표다.

# Progress

- 2026-08-26 worktree 생성(base `main@d21a3eb`), baseline `:bot:test`+`:common:test` BUILD SUCCESSFUL(사전 실패 없음).
- 2026-08-26 Explore — fixture README·`BacktestFixtures`·`BacktestFixturesTest` 확인, 영향 범위 6곳 식별.
- 2026-08-26 researcher 호출(Upbit 과거 상장 목록 API 가용성) — **결과 대기 중**.

# Next

1. researcher 결과 수신 → **실현 가능성 판정**(아래 Blockers 의 가정 A)
2. 그 결과로 범위 확정 질문(교체 vs 병행 — Decisions 2) 후 draft plan 완성
3. plan-reviewer → 구현

# Decisions

1. **먼저 확정할 외부 사실**: Upbit 공개 API 로 (a) 과거 시점 상장 목록을 직접 얻을 수 있는가
   (b) **폐지된 마켓의 캔들을 조회할 수 있는가**. (b) 가 불가면 생존편향 제거는 **원리적으로 불가능**하고
   작업은 "신규상장 배제 편향만 교정 + 생존편향은 한계로 명시"로 축소된다. 근사안은 각 마켓의 최초 캔들
   날짜로 상장일을 역산하는 것.
2. **미결 — 사용자 확인 필요**: fixture 를 **교체**할지 **새 세트를 병행**할지.
   - 교체: 기존 측정치(오늘 발행한 #128 `reset-churn-measurement`, 무릎 전략 비교)가 전부 무효 → 재생성 필요.
   - 병행: 기존 결과는 인용 가능한 채 남고 새 분석만 새 세트를 쓴다. fixture 용량·유지 비용 증가.
   이 결정은 researcher 결과와 무관하게 필요하다.

# Key Files

- `bot/src/test/resources/backtest/README.md` — 수집 조건·정규화·한계. **look-ahead 를 이미 스스로 문서화**하고 있다(이 작업의 출발점).
- `bot/src/test/resources/backtest/{bear,bull}/*.json` — 실데이터 fixture 12개(BEAR 8 / BULL 4).
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixtures.kt` — 로더. `MARKETS_BY_REGIME`·`PAIRED_MARKETS`·`Regime`.
- `bot/src/test/kotlin/com/trading/bot/engine/BacktestFixturesTest.kt:49-70` — 마켓 로스터를 **구현 상수를 쓰지 않고 독립 하드코딩**해 고정. 유니버스가 바뀌면 의도적으로 실패한다(좋은 설계 — 무음 통과 방지).

영향 받는 소비자(유니버스 변경 시 전부 재검토):
- `BacktestLegacyGoldenTest` + `resources/backtest/legacy-golden.txt` — 현 fixture 12개 기준 골든.
- `DailyResetCounterfactualTest` — #128 측정 하네스. `PAIRED_MARKETS` 의존.
- `BacktestReentryEquivalenceTest` — 전 fixture 순회.
- `KneeStrategyComparisonTest` · `KneeRsiWindowTest` — 무릎 전략 비교 수치.
- `wiki/pages/query/reset-churn-measurement.md` — 오늘 발행한 #128 결과.
- plan 4개: `2026-08-12-knee-backtest-calibration` · `2026-08-20-knee-bull-market-sample` · `2026-08-22-knee-review-followup` · `2026-08-24-daily-reset-counterfactual`.

# Blockers

- **가정 A (미확정)**: 폐지된 Upbit 마켓의 일봉을 공개 API 로 조회할 수 있는가. researcher 결과 대기 중.
  불가하면 Goal 의 "생존편향 제거"는 달성 불가 → Goal 축소가 선행돼야 한다.

# Acceptance

(researcher 결과 + Decisions 2 확정 후 항목화)

# Review Disposition

# Deferred

# Workflow Findings
