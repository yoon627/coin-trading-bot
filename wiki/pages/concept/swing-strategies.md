---
title: TradingStrategy 인터페이스와 운영 전략 combined
category: concept
created: 2026-07-28
updated: 2026-10-01
claim_state: current
verified: 2026-10-01 — `Indicators` 에서 BB·MACD·EMA 삭제(main 소비자 `ChartController` 삭제) 뒤 main 호출자는 `CombinedStrategy` 하나(목표가·`isMaUptrend`·RSI, `calculateMa` 는 `isMaUptrend` 안에서)이고, `IndicatorsParityTest`(REST↔store 동등)·`IndicatorsExtendedTest` 통과 · 2026-10-01 — 차트 청산 제거(MVP 2단계): `shouldSell`·`shouldSellNormalized`·`Indicators.checkDeadCross` 삭제, `minCandles` 는 기본값 없는 선언(`CombinedStrategy` 21 — 가드와 같은 값)임을 `StrategyMinCandlesTest`·`CombinedStrategyTest` 로 확인 · 2026-10-01 — 등록 전략이 `combined` 하나임을 `StrategyConfigTest` 로, 엔진 초기 전략·상태 API 폴백이 첫 등록 전략임을 `TradingEngineTest`·`UserTradingManagerTest` 로, 세 조건(RSI 는 상한·하한 각각)·21봉 가드·store 경로(`shouldBuyNormalized`)를 `CombinedStrategyTest` 로 확인(변이 9종 검출 — 조건 3·RSI 하한·가드·시가 매핑·bean 추가·폴백 2) · 2026-09-16 — `calculateMacd` 의 TA-Lib 규칙은 `IndicatorsExtendedTest` 의 손계산 앵커(fast 2·slow 3·signal 2, 6봉, 1e-12)와 120봉 참조 루프 대조로 확인(#27); 창 길이 의존은 같은 테스트의 35봉 절단 대조(Δmacd > 1e-3)로 고정 · 2026-08-23 — TradingStrategy.minCandles 계약 도입, StrategyMinCandlesTest 로 선언·실제 대조 및 mutation CAUGHT 확인
sources:
  - common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/Indicators.kt
  - bot/src/main/kotlin/com/trading/bot/config/StrategyConfig.kt
  - common/src/main/kotlin/com/trading/common/strategy/
---

# TradingStrategy 와 `combined`

전략은 `common` 모듈에 있다. 운영 전략은 **`combined` 하나**다 — 나머지 등록 전략 8종·무릎 전략의 청산 헬퍼 `ShoulderExit`·연구 전략 10종은 2026-10-01 MVP 1단계에서 지웠다([[rightsizing-history]]).

## 인터페이스

```kotlin
interface TradingStrategy {
    val name: String
    val minCandles: Int  // 기본값 없음 — 구현체가 선언
    suspend fun shouldBuy(candles, currentPrice, config): Boolean
}
```

- `shouldBuyNormalized` 변형이 있고 기본 구현이 `NormalizedCandle` → `Candle` 로 변환해 위임한다. 엔진은 store 캔들이 충분하면 Normalized 경로를, 부족하면 REST 캔들로 legacy 경로를 탄다([[trading-engine-loop]]).
- 전략이 하나여도 인터페이스를 남긴 이유: 엔진이 전략 목록(`StrategyConfig` bean)을 받아 첫 전략을 기본으로 쓰고 `setStrategy` 로 바꾸며, 엔진 테스트가 이 자리에 스텁을 끼운다.

## `combined` — 세 조건의 AND

1. 변동성 돌파 — `currentPrice > calculateTargetPrice(candles, kValue)` = 당일 시가 + 전일 레인지 × `kValue`(기본 0.5). 같으면 사지 않는다.
2. MA 상승추세 — `isMaUptrend(candles, 5, 20)` (MA5 > MA20)
3. RSI 건전 구간 — `calculateRsi(candles, 14) in 30.0..70.0`

캔들이 `minCandles`(21)개 미만이면 즉시 false. 필요한 최소 봉수는 **전략이 `minCandles` 로 선언**하고(인터페이스에 기본값이 없다) 엔진이 `max(MIN_DAILY_CANDLES, minCandles)` 로 쓴다 — `combined` 는 21 을 선언하고 같은 값으로 가드하며, `StrategyMinCandlesTest` 가 선언과 실제를 대조한다.

- **RSI 는 넘긴 봉 전체로 계산한다.** `calculateRsi` 는 리스트 전체로 Wilder smoothing 을 돌아 **창 길이가 값에 들어간다** — 라이브 store 경로는 21~60봉 가변이라 같은 시점이어도 넘긴 봉 수에 따라 RSI 가 조금씩 다르다(무릎 전략 실측, 2026-08: 50↔60봉 최대 5.65, 21~60봉 가변이면 최대 21.43). `combined` 는 자르지 않는다 — 동작을 바꾸지 않으려는 것이다.

## 기본 전략 규칙

- 등록은 `StrategyConfig` 의 bean 이고, **엔진과 상태 API 는 첫 등록 bean 을 기본 전략으로 쓴다**(`TradingEngine` 초기화, 엔진·캐시가 없을 때의 `/api/bot/status` 전략). 둘이 같은 규칙이어야 정지 뒤 화면이 채운 전략으로 시작해도 전략이 바뀌지 않는다. `StrategyConfigTest` 가 등록 목록을 `["combined"]` 로 고정한다.
- 전략을 고르는 설정(`trading.strategy`·`TRADING_STRATEGY`)은 2026-10-01 에 없앴다. 운영 secret 에 남은 줄은 Vultr 배포가 전달하지 않고, 전달되더라도 Spring Boot 가 환경변수의 모르는 키로 바인딩을 실패시키지 않는다.
- 사용자별 선택(`/api/bot/strategy`, 시작 요청의 `strategy`)은 등록 이름만 받는다 — 전략이 하나라 고를 것이 없지만 경로는 남아 있다.

## 지표

지표 계산은 `Indicators` 에 모여 있고, `combined` 가 쓰는 목표가(`calculateTargetPrice`)·RSI(`calculateRsi`)·MA(`calculateMa`·`isMaUptrend`)만 남았다. MACD·볼린저 밴드·EMA 는 유일한 소비자였던 차트 API 와 함께 2026-10-01 지웠다(TA-Lib 규칙으로 맞춘 MACD 구현과 그 검증은 저장소 이력에 있다 — #27). 엔진은 store D1 과 REST 캔들을 오가므로 `IndicatorsParityTest` 가 같은 OHLC 를 두 형식으로 넣어 남은 지표가 같은 값을 내는지 고정한다.

## 청산과의 관계

전략은 **진입 신호**만 낸다. 청산은 전략과 무관하게 [[exit-gates]] 의 손익% 안전망과 보유상한이 맡는다 — 전략별 차트 청산(`shouldSell`, 기본 5/20 데드크로스)은 운영에서 꺼져 있다가 2026-10-01 MVP 2단계에서 지웠다.
