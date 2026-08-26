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
- 2026-08-26 researcher 가 세션 한도로 2회 죽어 **메인이 Upbit 공개 API 를 직접 호출해 확정**(아래 Decisions 3).
- 2026-08-26 시점 유니버스 실측 완료 — 30일 평균 거래대금 기준 상위 8 확정, 사용자 결정 2건 수신.

# Next

1. 수집 스크립트 작성 — 시점 유니버스 선정 + 200봉 수집 + 정규화(재현 가능해야 함)
2. fixture 교체 → `BacktestFixtures.kt`·`BacktestFixturesTest.kt` 갱신
3. 다운스트림 재생성: `legacy-golden.txt` → #128 측정 재실행 → `reset-churn-measurement` 갱신 → 무릎 비교 수치
4. README·wiki 동기화 → plan-reviewer/code-review → 검증

# Decisions

3. **✅ Upbit API 실측 (2026-08-26, 메인 직접 호출)** — 문서 추정이 아니라 실제 응답 근거다.
   - `GET /v1/market/all?isDetails=true` 스키마 = `market`·`korean_name`·`english_name`·`market_event`.
     **상장일 필드 없음** → 과거 시점 목록을 직접 주는 엔드포인트는 없다.
   - **폐지 종목은 404**: `KRW-LUNA`·`BTC-LUNA`·`USDT-LUNA`·`KRW-LUNC` 전부
     `{"error":{"name":404,"message":"Code not found"}}` — 존재하지 않는 코드와 **동일 응답**.
     LUNA 는 2022-05 업비트 상장폐지가 확인된 실제 사례다.
     → **생존편향 제거는 원리적으로 불가능**하고, 크기 측정조차 불가능하다(그때 뭐가 있었는지 알 방법이 없다).
   - **"현재 상장 + 그때 미상장" 은 200 + 빈 배열** — 404 와 구별된다.
     → **신규상장 배제 편향은 정확히 판정·교정 가능**하다. 이게 이번 작업의 실질 범위다.
   - `candle_acc_trade_price`(누적 거래대금)가 일봉 응답에 있어 **시점 기준 순위를 계산할 수 있다**.
   - rate limit `group=candles; min=600; sec=9` → KRW 284개 전수 조회가 ~40초. 현실적이다.

4. **선정 규칙 = 구간 시작 시점의 30일 평균 거래대금 상위 8** (사용자 확정 2026-08-26).
   같은 "거래대금 상위" 규칙을 시점만 옮기되, 창을 30일로 늘려 단기 펌핑 영향을 줄인다.
   순위 계산 창은 구간 시작 **이전** 30봉이라 look-ahead 가 없다.
   (1일·7일 기준도 계산해 봤고 BLUR·MLK·LSK·ZRX 같은 당시 펌핑주가 상위를 채웠다 — 30일이 그걸 완화한다.)

5. **fixture 교체 + 다운스트림 전면 재생성** (사용자 확정 2026-08-26).
   편향 있는 fixture 를 남겨두면 계속 인용되므로 교체한다. `legacy-golden.txt`·#128 측정치
   (`reset-churn-measurement`)·무릎 전략 비교 수치를 모두 재생성한다. **#128 결론이 바뀔 수 있다.**

6. **paired 교집합이 4 → 3 으로 줄어드는 것을 수용한다.**
   실측 유니버스: bear = XRP·BTC·ETH·AXS·DATA·ENSO·SOL·BERA / bull = GAS·XRP·BTC·SOL·ARK·MINA·BLUR·POLYX.
   교집합은 BTC·SOL·XRP 3개다(기존 4개: XRP·BTC·ETH·DOGE).
   유니버스가 실제로 회전하기 때문이고, **겹침을 억지로 늘리면 선택 편향을 다시 넣는 것**이라 그대로 둔다.
   대신 bull 국면 내 N 이 4 → 8 로 두 배가 된다(#128 이 BULL N=4·한 팔 N=2 로 쪼그라들었던 제약이 풀린다).
   ⚠️ #128 의 A5d(국면 간 부호 비교)는 paired 3개라 더 약해진다 — 재실행 시 판정이 유보로 바뀔 수 있다.

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

없음. (가정 A 는 Decisions 3 으로 해소 — 폐지 종목 조회 불가 확정, Goal 을 아래로 축소)

**Goal 축소 (Decisions 3 의 귀결)**: "생존편향 제거"는 이 데이터 소스로 달성 불가다.
이번 작업이 실제로 없애는 것은 **신규상장 배제 편향 + '오늘의 승자를 과거에 소급 적용하는' look-ahead** 이고,
생존편향(폐지 종목 부재)은 **제거도 측정도 불가능한 잔여 한계**로 명시한다.

# Acceptance

(researcher 결과 + Decisions 2 확정 후 항목화)

# Review Disposition

# Deferred

# Workflow Findings
