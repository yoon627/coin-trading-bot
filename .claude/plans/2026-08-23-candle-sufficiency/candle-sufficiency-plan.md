---
title: candle-sufficiency — 최소 캔들 계약을 전략이 선언하게 한다 (#109)
status: in_progress
started: 2026-08-23
updated: 2026-08-23
---

# Goal

"신호를 내려면 캔들이 몇 개 필요한가"를 **전략이 선언**하고 엔진이 그 값을 쓰게 한다.

⚠️ **범위를 정확히**: plan-reviewer 검토로 최초 문제 정의가 틀렸음이 드러났다(아래 Decisions 첫 항목).
이 작업이 실제로 고치는 것은 **store 가 21~minCandles 구간일 때 더 긴 REST 를 쓰게 하는 것**과
**조용한 실패의 가시화**, 그리고 **새 전략이 계약을 선언하도록 강제**하는 것이다.
REST 자체가 부족한 신규 마켓은 이 변경으로 고쳐지지 않는다.

# Progress

- 2026-08-23 — 전 전략 요구 수집, plan 작성 → plan-reviewer(+codex 0.147.0) **CONDITIONAL**.
  문제 정의 오류 포함 blocker 7건을 반영해 개정.

## 실측 — 전략별 최소 봉수 (buy / sell)

| 전략 | buy | sell | 선언값 | 비고 |
|---|---:|---:|---:|---|
| `volatility_breakout` | 2 | 21 | 21 | **매수 하한이 2→21 로 바뀐다(동작 변경)** |
| `golden_cross` | 21 | 21 | 21 | 그대로 |
| `bollinger_bounce` | 21 | 21 | 21 | 그대로 |
| `mean_reversion` | 21 | 21 | 21 | 그대로 |
| `combined` | 21 | 21 | 21 | 그대로 |
| `rsi_bounce` | 16 | 16 | **21** | 선언은 16 이지만 엔진 하한 21 이 우선(아래 이유) |
| `macd_cross` | 36 | 36 | 36 | store 21~35 에서 REST 로 전환됨 |
| `knee_reversal` | 41 | 41 | 41 | store 21~40 에서 REST 로 전환됨 |
| `knee_pullback` | 41 | 41 | 41 | 〃 |

# Next

TDD(계약 테스트 먼저) → 구현 → code-reviewer.

# Decisions

## [정정] 매수 경로에 게이트는 없었다 — 21 은 소스 선택자다

`TradingEngine.kt:282-288` 은 store 가 21 미만이면 `getDayCandles(ticker, 60)` 으로 폴백하고
**크기 검사 없이** `shouldBuy` 를 호출한다. 즉 오늘 `volatility_breakout` 은 2봉으로도 매수 평가된다.

→ 최초 plan 의 대표 예시("상장 25일차 + `macd_cross` 가 조용히 false")는 **이 변경으로 고쳐지지 않는다**.
REST 도 25봉이라 여전히 false 이고, 달라지는 건 로그가 남는다는 것뿐이다.

**실제로 고치는 범위**: store ∈ [21, minCandles) 이면서 REST 가 더 많은 구간
(`macd_cross` 21~35, `knee_*` 21~40). 이 구간에서 지금은 짧은 store 를 쓰고 전략이 false 를 낸다.

## 근본 원인은 따로 있다 (3 Whys)

왜 store 가 21~40봉인가 → 부팅 seed 가 200봉을 넣으므로 정상 경로에선 즉시 60봉이다
(`MarketDataIngestionService.kt:70`). 왜 그래도 그 구간이 생기나 → **seed 실패 시 재시도가 없고**(`:241-245`
warn 만) 이후 D1 은 집계로 하루 1봉씩만 쌓인다. 왜 아무도 모르나 → 로그가 debug/무로그.

→ 이 작업은 **증상의 가시화 + 소스 선택 교정**이고, 데이터 결손 자체는 남는다. seed 재시도는 별건(Deferred).

## 계약 설계

- **`TradingStrategy.minCandles` = `max(buy 요구, sell 요구)`.** 진입·청산을 나누면 호출부가 두 배가 되는데
  실제로 갈리는 전략은 `volatility_breakout` 하나뿐이다. 큰 쪽으로 통일한다.
  Kotlin 인터페이스는 initializer 를 못 쓰므로 `val minCandles: Int get() = 21`.
- **엔진은 `max(MIN_DAILY_CANDLES, strategy.minCandles)` 로 하한 21 을 유지한다.**
  `rsi_bounce` 를 16 으로 낮추면 store 16~20봉이 "충분"으로 판정돼 **REST 60봉 대신 16봉으로 RSI 를 계산**한다.
  `calculateRsi` 는 리스트 전체로 Wilder 평활을 돌아 window 길이가 값에 들어가고(라이브 21~60봉이면
  최대 ΔRSI 21.43 — wiki `swing-strategies` 실측), `RsiBounce.kt:24-25` 주석도 `운영 N≥21` 을 전제한다.
  → 하향은 금지. `MIN_DAILY_CANDLES` 는 죽은 상수가 아니라 **floor** 로 역할을 재정의한다.
- **청산은 `resolveExitStrategy(...).minCandles`, 매수는 activeStrategy 값.**
  activeStrategy 를 양쪽에 쓰면 `knee_reversal` 로 산 포지션 + 활성 `volatility_breakout`(21) 조합에서
  store 21~40 이 "충분"으로 통과해 `ShoulderExit`(41)이 영구 false → **차트청산이 안 걸리는 포지션**이 된다.
- **[구현 중 재결정] 매수 REST 경로는 막지 않고 알리기만 한다 — 동작 변경 없음.**
  처음엔 `volatility_breakout` 매수 하한을 2→21 로 올리는 동작 변경을 감수하려 했으나, 구현해 보니
  **차단은 결과를 바꾸지 않는다** — 캔들이 부족하면 전략이 자기 가드로 어차피 false 를 낸다. 얻는 것은
  로그뿐인데 잃는 것은 짧은 이력으로도 매매하던 전략의 계약이다.
  그리고 기존 `TradingEngineTest` 8건이 `getDayCandles → emptyList()` + 전략 mock 으로 매수 흐름을
  검증하고 있어, 게이트를 넣으면 `buyEntered.await()` 가 영원히 대기해 **테스트가 hang** 했다.
  이 신호를 "테스트를 고치자"가 아니라 "설계가 과하다"로 읽었다.
  → 매수는 `warn` 만, **차단은 청산 경로에만**(그쪽은 원래 게이트가 있었고 값만 21→전략값으로 바뀐다).

## 테스트

- **전략 목록을 하드코딩하지 않는다.** `StrategyConfigTest` 의 `AnnotationConfigApplicationContext`
  패턴을 재사용해 **새 전략이 자동으로 계약 검증 대상**이 되게 한다. 기본값 21 을 두는 이상 41 이 필요한
  새 전략이 override 를 잊으면 같은 버그가 재발하고, 열거 테스트가 유일한 방어선이다.
- 계약 테스트는 **`minCandles - 1` 에서 false** 를 전 전략에 강제한다(값싸고 확실). "그 값에서 신호가 난다"는
  전략마다 fixture 설계가 필요해 가능한 전략만 하고 나머지는 사유를 남긴다.
- **기존 strict mockk 가 깨진다.** `TradingEngineTest`·`KisStockTradingEngineTest` 의 `strategy = mockk()`
  는 `name` 만 stub 한다. `every { strategy.minCandles } returns N` 을 추가한다.
  **`relaxed = true` 로 바꾸지 않는다** — Int 를 0 으로 반환해 게이트를 항상 통과시켜 검증이 공동화된다.
- 상한은 리터럴 50 이 아니라 `BacktestEngine.MIN_CANDLES` 를 참조한다.

## 로그

기존 `staleWarnAtMs` 패턴(ticker 키 + 60초 간격, `warn`)을 따르되 키를 `ticker:strategy:경로` 로 둔다 —
런타임 `setStrategy` 로 전략이 바뀌면 새로 알려야 하고, 매수/청산 두 경로가 중복 발화하면 안 된다.
메시지에 `userId` 를 포함한다(엔진이 사용자별 인스턴스).

## 범위 밖 (명시)

- `StrategyController.listStrategies` 응답에 `minCandles` 를 넣지 않는다(API 계약 변경).
- **백테는 `minCandles` 를 적용하지 않는다.** 백테 window 는 항상 정확히 50봉이라 라이브(21/36/41)와
  판정 창이 다른 문제는 **이번 범위에서 해소되지 않는다**(#109 가 언급한 "백테↔라이브 파생 차이" 과대주장 금지).

# Key Files

- `common/.../strategy/TradingStrategy.kt` — `minCandles` 선언
- `common/.../strategy/{MacdCross,KneeReversal,KneePullback}.kt` — override (rsi_bounce 는 하지 않음)
- `bot/.../engine/TradingEngine.kt` — 소스 선택·청산 게이트를 전략 값으로, 부족 로그
- `bot/.../kis/engine/KisStockTradingEngine.kt` — 동일 + 무로그 경로에 로그
- `bot/src/test/.../strategy/StrategyMinCandlesTest.kt` — 신규(전략 열거 계약 테스트)
- wiki: `swing-strategies.md:69`, `trading-engine-loop.md:32`, `marketdata-pipeline.md:40`,
  `kis-stock-trading-flow.md`(sources 에 KisStockTradingEngine 포함)

# Acceptance

| # | 충족 기준 | 검증 방법 | 통과 조건 |
|---|---|---|---|
| 1 | 전 전략이 `minCandles - 1` 에서 매수 false | `StrategyMinCandlesTest`(Spring 컨텍스트 열거) | green |
| 2 | 모든 `minCandles <= BacktestEngine.MIN_CANDLES` | 같은 테스트 | green |
| 3 | store 21~40 + `knee_reversal` 이면 REST 폴백 | `TradingEngineTest` | green |
| 4 | 캔들 부족 시 로그가 남는다 (매수·청산 각각) | 게이트 경로 단언 | green |
| 5 | 청산이 **진입 전략**의 `minCandles` 를 쓴다 | 진입≠활성 조합 테스트 | green |
| 6 | **매수 동작 불변** — 캔들이 부족해도 전략 호출은 그대로(경고만) | 기존 `TradingEngineTest` 통과 | green |
| 7 | KIS 경로도 같은 계약 + 부족 로그 | KIS 테스트 | green |
| 8 | 기존 회귀 없음 (strict mockk stub 추가로 통과) | 전체 스위트 | green |
| 9 | 문서 동기화 4곳 + 검증 3종 | `check_links.py`·`verify.sh`·`smoke.sh` | 통과 |
| 10 | 전체 검증 | `./gradlew :bot:test compileKotlin` (`:common:test` 는 소스셋 부재라 무의미) | 통과 |

# Blockers

없음.

# Deferred

- **부팅 D1 seed 실패에 재시도가 없다**(`MarketDataIngestionService.kt:241-245`) — store 가 21~40봉에
  머무는 진짜 원인. 이 작업은 증상만 가시화한다. 심각도 중.
- **KIS 청산이 진입 전략을 복원하지 않는다**(`KisStockTradingEngine.kt:182` 는 activeStrategy 사용,
  Upbit `:302` 는 `resolveExitStrategy`). 두 거래소의 청산 계약이 갈린다. 심각도 중.
- 백테(50봉 고정) ↔ 라이브(21~60 가변) 판정 창 차이는 그대로 남는다.

# Review Disposition

| finding | 처분 |
|---|---|
| **B1** 매수 경로에 게이트 없음 — 문제 정의 오류 | **fix** — Goal·Decisions 재작성, 실제 범위 명시 |
| **B2** 근본 원인은 seed 무재시도 | **fix(기록)** — 3 Whys 를 Decisions 에, 수정은 Deferred |
| **B3** `rsi_bounce` 하향 위험 | **fix** — 엔진 하한 21 유지(`max(MIN_DAILY_CANDLES, minCandles)`) |
| **B4** `volatility_breakout` 동작 변경 미명시 | **fix** — Acceptance #6 |
| **B5** `MIN_DAILY_CANDLES` 가 죽은 상수 | **fix** — floor 로 역할 재정의 |
| **B6** 청산의 minCandles 소유자 미명시 | **fix** — `resolveExitStrategy` 값 사용, Acceptance #5 |
| **B7** strict mockk 파손 + relaxed 함정 | **fix** — stub 추가, relaxed 금지를 Decisions 에 |
| 전략 목록 하드코딩 금지 | **fix** — Spring 컨텍스트 열거 |
| 문서 4곳 | **fix** — Acceptance #9 |
| `:common:test` 무의미 | **fix** — 검증 명령에서 제외 |
| 로그 억제 패턴 실제 형태 | **fix** — 60초·warn·키 설계 반영 |
| `StrategyController` 응답 | **wontfix** — API 계약 변경, 범위 밖 명시 |
| KIS 100일 백필이 41 거래일 보장? | **확인 후 판단** — 통상 66~69 거래일이나 신규상장·거래정지는 미달 가능 → 로그로 드러나게 |

# Workflow Findings

- **문제 정의를 코드로 검증하지 않고 plan 을 썼다.** "엔진이 21로 게이트한다"를 상수 존재만 보고 단정했는데,
  실제로는 소스 선택자였다. 이슈 #109 본문에도 같은 오류가 들어갔다(등록 시 함께 검증했어야 했다).
  plan 의 문제 정의 문장은 **호출 경로를 따라 확인한 뒤** 쓴다.
