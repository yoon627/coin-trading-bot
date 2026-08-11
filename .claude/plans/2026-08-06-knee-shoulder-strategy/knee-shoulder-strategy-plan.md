---
title: knee-shoulder-strategy — 무릎 매수/어깨 매도 스윙 전략 2종 추가 (PR1)
status: in_progress
started: 2026-08-06
updated: 2026-08-11
---

# Goal

"무릎에서 사서 어깨에 판다"를 코드화한 스윙 진입 전략 2종(`knee_reversal` 반등확인형, `knee_pullback` 눌림목형)과
어깨 청산(`shouldSell` override)을 추가한다. PR1 은 **구조 변경 없이** 전략·지표·테스트·문서까지만.
전략별 리스크 프로파일(RiskProfile) 도입은 PR3 으로 분리한다.

⚠️ **PR1 의 `shouldSell`(어깨 청산)은 기본 설정에서 dead path 다.** 라이브는 `chartExitEnabled=false`
(`TradingEngine.kt:326`), 백테는 그에 더해 `maxHoldDays=1` 이라 매수 다음 봉에서 `atHoldLimit=true` 가 되어
`IntrabarExitModel.kt:55` 의 `!atHoldLimit` 게이트가 CHART_EXIT 자체를 차단한다. PR1 은 "옵션을 켜면 동작하는
코드"를 넣는 것이고, 실제 발동은 PR2(백테 파라미터)·PR3(프로파일)에서 검증한다.

# Progress

- 2026-08-06 — Explore 완료(백테/라이브 캔들 window 제약 실측), plan 작성 → plan-reviewer(+codex 0.146.0) 검토 →
  blocker 6건 반영해 plan 개정(조건식 2곳 변경, bean 등록 위치 제약 추가, Acceptance 재작성, rollback 절차 추가).
- 2026-08-11 — plan 개정본 저장. TDD Red 착수.
- 2026-08-12 — PR1 구현 완료. TDD Red(컴파일 실패) → 구현 → Green. code-reviewer(+codex 0.147.0) REQUEST CHANGES
  Major 2건 + Minor 7건을 fix loop 1회차로 반영. **mutation 검증 9/9 CAUGHT** 로 조건 고정 입증.
  전체 검증: `:bot:test` 623건 failures=0, wiki 3종 통과. Acceptance 11항목 전부 증거 충족.

- 2026-08-12 — push 완료(pre-push codex high-reasoning 리뷰 blocking issue 없음). **PR #95 생성**.

# Next

PR #95 리뷰·머지 대기. 머지 후 PR2(백테 검증)는 **별도 worktree**에서 — 착수 전 먼저
"백테 50봉 vs 라이브 60봉 RSI 불일치"(아래 Deferred)를 어떻게 다룰지 판단해야 캘리브레이션이 유효하다.

# Decisions

- **진입 2종 병행 구현** (사용자 선택): 같은 "무릎"이라도 반등확인형(역추세 전환)과 눌림목형(추세 중 조정)은
  전혀 다른 전략이라, 하나를 선험적으로 고르지 않고 둘 다 만들어 백테(PR2)로 비교한다.
- **어깨 청산은 `shouldSell` + 트레일링 확대 병행** (사용자 선택): 가격 안전망은 트레일링이, 과열 꺾임 판정은
  `shouldSell` 이 담당.
- **PR 3분할**: `StrategyController.backtest` 가 `maxHoldDays`·`chartExitEnabled`·트레일링을 요청 파라미터로
  받으므로 **구조 변경 없이 백테 검증이 가능**하다(단 **API 직접 호출 한정** — SPA 백테 화면은
  `strategy/ticker/days/chart_exit_enabled` 만 전송한다, `screens.jsx:383`). 값어치를 먼저 확인(PR2)하고
  구조(RiskProfile, PR3)는 뒤로 미뤄 헛수고 위험을 없앤다.
- **lookback 상한 40봉** (당초 50 → 40):
  - 백테가 전략에 넘기는 window 는 **정확히 50봉**(`BacktestEngine.kt:89`).
  - 라이브 store 경로는 **21~60봉 가변**(`TradingEngine.kt:367`).
  - **"계산 안전"과 "신호 유효"는 다르다** — 40봉 lookback 은 계산상 안전하지만, 라이브 warm-up 21~39봉
    구간에서는 두 전략 모두 **영구 false**(예외 없음, 신호도 없음)다. 의도된 동작이며 Acceptance 로 고정한다.
- **캔들 정렬은 index 0 = 최신** (`Indicators` 전체가 `take(period)` 관례, 백테 window 도 `.reversed()`,
  store 도 최신 openTime 우선 — `MarketDataStore.kt:73`).
- **[리뷰 반영 B4] `knee_reversal` 조건1 변경**: `(highestHigh(40) - price)/highestHigh(40) >= 0.15` →
  **`(highestHigh(40) - lowestLow(20))/highestHigh(40) >= 0.15`**.
  (이유: 기존 식은 조건2(저점 대비 +3~12%)와 얽혀 실제로는 낙폭 **17.48%** 이상을 암묵 요구했다.
  `1.03·L20 <= 0.85·H40 ⟺ L20 <= 0.8252·H40`. 새 식은 "하락이 있었음"을 직접 재고 조건2와 직교한다.)
- **[리뷰 반영 B5] "반등 확인"에서 `ma(5) > ma(5, drop(1))` 폐기**: `calculateMa` 가 단순평균이라
  `ma5(t) − ma5(t−1) = (c0 − c5)/5` — 즉 **`close[0] > close[5]` 와 정확히 동치**다. 눌림목(3~7봉 조정)과
  교집합이 거의 없어 신호가 안 나온다. → **`close[0] > close[1] && close[0] > open[0]`**(직전 봉 대비 상승 +
  당일 양봉)으로 대체. 반등의 최소 정의이며 동치 함정이 없다.
- **[리뷰 반영 B2] 신규 bean 은 `StrategyConfig` 목록 맨 끝에 등록**: `KisStockTradingEngine.kt:57` 과
  `TradingEngine.kt:66` 이 `strategies.firstOrNull()` 을 기본/폴백 전략으로 쓴다. 앞에 넣으면 KIS 국내주식 봇의
  기본 전략이 조용히 바뀐다. "첫 bean 은 volatility_breakout" 을 회귀 테스트로 고정한다.
- **[리뷰 반영] 볼린저 비교는 `prevBb` 로**: 현재 밴드와 직전 종가를 섞으면(epoch 혼용) 밴드가 확장/축소하는
  봉에서 판정이 뒤집힌다. 기존 관례(`BollingerBounce.kt:40-43`, `MeanReversion.kt:51`)대로
  `calculateBollingerBands(candles.drop(1), ...)` 를 따로 계산한다.
- **[리뷰 반영] `shouldSell` override 는 기본 데드크로스(5/20)를 대체하며, 병합하지 않는다**: 어깨 청산의 의도가
  "꼭지 전에 조기 이탈"인데 데드크로스를 OR 로 붙이면 늦은 청산이 섞여 의도가 흐려진다. 과열·밴드 이탈이 없었던
  트레이드는 차트 청산 경로가 없고 트레일링·maxHoldDays 가 처리한다. 코드에 이유를 주석 1줄로 남긴다.
- **[리뷰 반영] 0 값 방어를 명시 가드로**: `Candle` 의 O/H/L 기본값이 0.0 이라 분모 0 → Infinity/NaN 이 되고
  NaN 비교는 전부 false 라 **조용히 묻힌다**. `MeanReversion.kt:17`(`ma20 <= 0`) 관례대로 명시 가드를 둔다.
- ATR 은 이번 범위 제외 — 조건 수를 늘리면 자유도만 커져 과최적화 위험이 오른다.
- **[리뷰 반영] 신규 전략은 KIS 국내주식 선택지에도 자동 노출된다**(`StockUserTradingManager.kt:93,140` 이 같은
  `List<TradingStrategy>` 로 이름 검증). 코인용 설계지만 주식 목록에 섞이는 것은 기존 구조의 성질이며 PR1 에서
  차단하지 않는다(차단하려면 전략 스코프 개념이 필요 — PR3 후보).

# Key Files

- `common/src/main/kotlin/com/trading/common/strategy/Indicators.kt` — `lowestLow`/`highestHigh` 추가
- `common/src/main/kotlin/com/trading/common/strategy/KneeReversal.kt` — 신규(반등확인형)
- `common/src/main/kotlin/com/trading/common/strategy/KneePullback.kt` — 신규(눌림목형)
- `bot/src/main/kotlin/com/trading/bot/config/StrategyConfig.kt` — bean 2개를 **맨 끝에** 등록
- `bot/src/test/kotlin/com/trading/bot/strategy/KneeReversalTest.kt` / `KneePullbackTest.kt` — 신규
- `bot/src/test/kotlin/com/trading/bot/config/StrategyConfigTest.kt` — 신규(bean 목록·첫 bean 고정)
- `README.md:142` 전략 표 · `PROJECT_ANALYSIS.md`(7개 → 9개, 5곳) · `wiki/pages/concept/swing-strategies.md` ·
  `wiki/index.md:12`
- (참고) `bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt:89` — window 50봉 제약의 근거
- (참고) `bot/src/main/kotlin/com/trading/bot/engine/IntrabarExitModel.kt:55` — `!atHoldLimit` 이 CHART_EXIT 차단

# 설계 — 조건식 (초기값, PR2 에서 캘리브레이션)

## `knee_reversal` (하락 추세 전환형) — 최소 40봉
| # | 조건 | 식 |
|---|---|---|
| 0 | 데이터·0 가드 | `size >= 40`, `h40 > 0`, `l20 > 0` |
| 1 | 하락이 있었음 | `(highestHigh(40) - lowestLow(20)) / highestHigh(40) >= 0.15` |
| 2 | **무릎 구간** | `(price - lowestLow(20)) / lowestLow(20)` 가 `0.03..0.12` |
| 3 | 반등 확인 | `close[0] > close[1]` && `rsi(14) in 35.0..55.0` |
| 4 | 거래량 | `avgVolume(10) <= 0 || vol[0] >= avgVolume(10)` |

2번이 핵심 — 아래면 떨어지는 칼, 위면 이미 허리.

## `knee_pullback` (상승추세 중 조정형) — 최소 41봉
| # | 조건 | 식 |
|---|---|---|
| 0 | 데이터·0 가드 | `size >= 41`, `ma20 > 0`, `ma40 > 0` |
| 1 | 상승추세 | `ma(20) > ma(40)` |
| 2 | **눌림** | `price` 가 `ma20*0.97 .. ma20*1.02` |
| 3 | 반등 확인 | `close[0] > close[1]` && `close[0] > open[0]` |
| 4 | 과열 아님 | `rsi(14) in 40.0..60.0` |

최소 41봉인 이유: `calculateMa(candles, 40)` 는 `size < 40` 이면 **조용히 0.0** 을 반환한다
(`checkGoldenCross` 가 `longPeriod + 1` 로 가드하는 것과 같은 이유 — `Indicators.kt:52`). 40 봉 정확히면
계산은 되지만 여유가 없어 41 로 둔다.

## 어깨 청산 `shouldSell` (두 전략 공통, 각자 override)
```
(rsiPrev >= 70 && rsi < 70)                        // 과열 꺾임
|| (prevClose > prevBb.upper && close <= bb.upper) // 볼밴 상단 이탈 후 복귀
```
`rsiPrev` = `calculateRsi(candles.drop(1), 14)`, `prevBb` = `calculateBollingerBands(candles.drop(1), 20, 2.0)`.
꼭지를 맞히지 않고 꺾임을 확인하고 나온다 = 어깨.

# Acceptance

| # | 충족 기준 | 검증 방법 | 통과 조건 |
|---|---|---|---|
| 1 | `lowestLow`/`highestHigh` 가 최신 N봉의 `low`/`high` 필드 기준으로 동작, `size < period` 면 0.0 반환 | `IndicatorsTest` 신규 케이스 | green |
| 2 | 두 전략의 각 조건이 **단독으로 깨지면 매수 false** (조건별 1케이스씩) | `KneeReversalTest`/`KneePullbackTest` | green |
| 3 | 모든 조건 충족 시 매수 true (O/H/L/C 를 **모두 채운** fixture) | 각 테스트 positive 케이스 | green |
| 4 | `shouldSell` 이 과열 꺾임·밴드 복귀에서 true, 그 외 false | 각 테스트 sell 케이스 | green |
| 5 | 40봉 미만 입력에서 **예외 없이 false** (라이브 warm-up 21~39봉 구간 동작 고정) | 부족 데이터 케이스 | green |
| 6 | O/H/L 이 0 인 불량 캔들에서 NaN/Infinity 없이 false | 0-가드 케이스 | green |
| 7 | 두 전략이 bean 으로 등록되고, **첫 bean 은 여전히 `volatility_breakout`** | `StrategyConfigTest`(신규 단위 테스트 — repo 에 `@SpringBootTest` 가 없어 컨텍스트 로드로는 검증 불가) | green |
| 8 | 백테 엔진이 두 전략을 50봉 window 에서 예외 없이 실행 | `BacktestEngineTest` 스타일 신규 케이스 | green |
| 9 | **어깨 청산이 실제로 발동한다** — `chartExitEnabled=true` + `maxHoldDays>1` 백테에서 `reason == "CHART_EXIT"` 트레이드가 1건 이상 | `BacktestEngine.run` 결과의 trades assert | green |
| 10 | 문서 동기화 — `README.md` 전략표 2행 · `PROJECT_ANALYSIS.md` 7→9(5곳) · wiki `swing-strategies.md` · `wiki/index.md:12` | wiki 검증 3종(`check_links.py`, `verify.sh`, `smoke.sh`) | 통과 |
| 11 | 전체 검증 | `./gradlew :common:test :bot:test compileKotlin` | 통과 |

# Blockers

없음 (진행 가능).

## Rollback 절차 (순서 중요 — 뒤집으면 조용한 전략 스왑 발생)

코드 revert 만으로 깨끗하지 않다. 전략 이름이 DB 에 문자열로 남는다
(`bot_state.strategy`, `trading_states.entry_strategy`, `stock_position_state.entry_strategy`).
`UserTradingManager.kt:182-184` 는 `engine.setStrategy(...)` 의 **반환값을 무시하고** 복원 로그를 찍으므로,
revert 후에도 로그·UI 는 `knee_reversal` 이라 보고하면서 엔진은 `strategies.firstOrNull()` 로 도는 상태가 된다.

1. 각 사용자 전략을 기존 전략으로 원복 (API `setStrategy`)
2. 보유 포지션 청산 또는 수용 여부 판단 (`resolveExitStrategy` 가 WARN 후 활성 전략으로 폴백 → 청산 기준이 진입과 달라짐)
3. 배포 revert

DB migration 이 없고 기존 테스트가 명시적 전략 리스트를 쓰므로(`BacktestEngineTest.kt:16`) 그 외 회귀는 없다.

# Deferred

- wiki `trading-engine-loop.md` 기본 리스크 파라미터 표가 stale — `takeProfitPct` 2.0/`trailingArmPct` 0.0 로
  적혀 있으나 실제는 5.0/3.0(#75 이후 미반영). 심각도 중. 이번 브랜치 범위 밖.
- `UserTradingManager.kt:182-184` — `setStrategy` 반환값을 무시하고 성공 로그를 찍어 **복원 실패를 은폐**한다
  (잘못된 전략명이 DB 에 있으면 로그·UI 와 실제 엔진이 어긋남). 심각도 중, 별도 이슈 후보.
- PR2 백테는 `useMarketFilter=false` 로 고정할 것 — `BacktestEngine.kt:151` 의 MA50 필터는
  `knee_reversal`(정의상 MA50 아래)을 거의 전부 제거해 trade 0 으로 오판하게 만든다.
- **백테(50봉)와 라이브(60봉)의 RSI 가 다르다** — `calculateRsi` 가 리스트 전체를 쓰므로 window 길이가
  값에 들어간다. 리뷰어 실측(일변동 3% 랜덤워크 320봉): `|ΔRSI|` 평균 0.53 / p90 1.13 / 최대 2.08,
  `knee_reversal` 매수 신호는 16건 중 2건(12.5%)이 달라진다. 기존 전략도 같은 성질이라 신규 회귀는
  아니지만, **PR2 가 백테로 RSI 밴드를 캘리브레이션하면 그 값이 라이브로 그대로 옮겨가지 않는다.**
  심각도 중 — PR2 착수 시 먼저 판단할 것(고정 길이 슬라이스로 RSI 계산 vs 밴드를 넓게 두기).

# Review Disposition

| finding | 처분 |
|---|---|
| B1 Acceptance #6 검증 수단 부재(`@SpringBootTest` 없음) | **fix** — `StrategyConfigTest` 단위 테스트로 교체 (Acceptance #7) |
| B2 bean 순서가 KIS 기본 전략 변경 | **fix** — 목록 끝 등록 + 첫 bean 고정 테스트 |
| B3 shouldSell dead path 미명시 | **fix** — Goal 에 경고 명시 + Acceptance #9(CHART_EXIT assert) 추가 |
| B4 조건1↔2 얽힘(암묵 17.48%) | **fix** — 조건1을 `(H40−L20)/H40` 로 변경 |
| B5 `ma5` 상향 ⟺ `c0 > c5` 동치 | **fix** — `close[0] > close[1]` (+ 양봉)으로 대체 |
| B6 fixture O/H/L 기본 0.0 → NaN 은폐 | **fix** — 명시 가드(Acceptance #6) + fixture 전 필드 채움(Acceptance #3) |
| 문서 동기화 대상 누락 | **fix** — `PROJECT_ANALYSIS.md`·`wiki/index.md` 추가 (Acceptance #10) |
| prevBb epoch 혼용 | **fix** — `drop(1)` 밴드 별도 계산 |
| shouldSell 이 데드크로스 폴백 상실 | **wontfix(의도)** — 조기 청산이 목적. Decisions 에 이유 명시 + 코드 주석 |
| rollback 절차 부재 | **fix** — Blockers 절에 3단계 절차 명시 |
| `useMarketFilter` 충돌 | **defer(PR2)** — Deferred 에 "PR2 는 false 고정" 기록 |
| KIS 주식 목록 노출 | **wontfix(PR1 범위)** — Decisions 에 명시, 전략 스코프는 PR3 후보 |
| 라이브 `candles[0]` 미완성 봉 시각 편향 | **defer** — 기존 전략도 동일 성질(신규 회귀 아님) |
| 전략 간 신호 중복 매트릭스 부재 | **defer(PR2)** — 비교 방법론 항목 |
| `UserTradingManager` 복원 로그 은폐 | **defer** — 범위 밖, Deferred 기록 |

## code-reviewer (+codex 0.147.0) — REQUEST CHANGES, fix loop 1회차

| finding | 처분 |
|---|---|
| **M1** `ShoulderExit` 볼린저 분기가 상승봉에서 오발동 (밴드가 가격보다 빨리 확장). Acceptance #9 의 CHART_EXIT 증거가 잘못된 청산(+10.6%, 진짜 어깨는 +27.3%)을 인증하고 있었다 | **fix** — `candles[0].close >= candles[1].close` 면 조기 return(`BollingerBounce` 의 falling-knife 가드의 거울상). 오발동 지점(i=107 window)을 회귀 테스트로 고정 |
| **M2** 음성 fixture 가 조건을 동시에 여러 개 깨뜨려 `knee`·`decline`·`dip`·`trend` 를 삭제해도 전 테스트 green | **fix** — 12개 fixture 를 조건별 격리형으로 재설계(아래 "격리 축" 참조) + 각 음성 테스트에 "나머지 조건은 통과" 단언 추가. **mutation 검증 9/9 CAUGHT** 로 입증 |
| `stays dormant` 가 `chartExitEnabled` 를 고정 못함(maxHoldDays=1 이 먼저 차단) | **fix** — 두 메커니즘 분리(maxHoldDays=50 고정, 플래그만 토글) + 매수 발생 단언 추가 |
| `knee_pullback` 백테 진입 미검증 | **fix** — `tailBars` 추가(신호가 마지막 봉이면 체결 불가) + 진입 발생 테스트 |
| `currentPrice` ↔ `candles[0].tradePrice` 혼용 | **fix** — 가격 비교를 `currentPrice` 로 통일(백테 결과 불변, 라이브 정합 개선) |
| NaN 이 0 가드를 우회해 fail-open | **fix** — `isFinite()` 가드 추가(매수 게이트는 fail-safe 여야) |
| `KneePullbackTest` 의 zeroOhlc 가 0 가드를 실행하지 않음 | **fix** — 해당 테스트 제거(`KneePullback` 은 나눗셈이 없어 NaN 경로 자체가 없다) |
| `IndicatorsTest` 가 `period<=0` 미커버 | **fix** — 테스트 추가(가드가 없으면 `NoSuchElementException`) |
| BB multiplier 미명시 · shouldSell 관례 주석 누락 · 자명한 주석 3줄 | **fix** |
| 백테 50봉 vs 라이브 60봉 RSI 차이로 신호 12.5% 불일치 | **defer(PR2)** — 기존 전략도 같은 성질(신규 회귀 아님). PR2 캘리브레이션 시 고려 필요 → Deferred |
| `ShoulderExit` 의 `?: return false` 죽은 분기 | **wontfix** — Kotlin 타입 시스템상 필수(nullable 반환) |
| 두 테스트의 `assertRejectedOnlyBy` 중복(16줄) | **wontfix** — 각 테스트가 독립적으로 읽히는 이점이 더 크다고 판단. 조건 맵이 서로 달라 공통화 이득이 작다 |

**격리 축** — 조건끼리 상관(반등을 키우면 RSI 도 오름)이 있어 단순 파라미터 조정으로는 격리가 안 됐다. 두 축을 새로 도입해 해결:
- `troughWickFactor` — 하락 마지막 봉의 **저가만** 눌러 20봉 저점을 낮춘다. 종가 계열 불변 → RSI 고정한 채 "무릎 위치"만 파손.
- `sawPct`/`sawPeriod` — 상승 구간에 주기적 되밀림을 넣어 추세·눌림 구조를 유지한 채 RSI 만 하향.

# Workflow Findings

(없음)
