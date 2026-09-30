---
title: TradingStrategy 인터페이스와 운영 전략 combined
category: concept
created: 2026-07-28
updated: 2026-10-01
claim_state: current
verified: 2026-10-01 — 등록 전략이 `combined` 하나임을 `StrategyConfigTest` 로, 엔진 초기 전략·상태 API 폴백이 첫 등록 전략임을 `TradingEngineTest`·`UserTradingManagerTest` 로, 세 조건(RSI 는 상한·하한 각각)·21봉 가드·store 경로(`shouldBuyNormalized`)를 `CombinedStrategyTest` 로 확인(변이 9종 검출 — 조건 3·RSI 하한·가드·시가 매핑·bean 추가·폴백 2) · 2026-09-16 — `calculateMacd` 의 TA-Lib 규칙은 `IndicatorsExtendedTest` 의 손계산 앵커(fast 2·slow 3·signal 2, 6봉, 1e-12)와 120봉 참조 루프 대조로 확인(#27); 창 길이 의존은 같은 테스트의 35봉 절단 대조(Δmacd > 1e-3)로 고정 · 2026-08-23 — TradingStrategy.minCandles 계약 도입, StrategyMinCandlesTest 로 선언·실제 대조 및 mutation CAUGHT 확인
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
    val minCandles: Int get() = 21
    suspend fun shouldBuy(candles, currentPrice, config): Boolean
    suspend fun shouldSell(candles, currentPrice, config): Boolean  // default: 5/20 데드크로스
}
```

- `shouldSell` 의 **기본 구현은 5/20 MA 데드크로스**다. `combined` 는 override 하지 않는다.
- `*Normalized` 변형(`shouldBuyNormalized`/`shouldSellNormalized`)이 있고 기본 구현이 `NormalizedCandle` → `Candle` 로 변환해 위임한다. 엔진은 store 캔들이 충분하면 Normalized 경로를, 부족하면 REST 캔들로 legacy 경로를 탄다([[trading-engine-loop]]).
- 전략이 하나여도 인터페이스를 남긴 이유: 엔진이 진입 전략으로 청산을 복원하고(`resolveExitStrategy`, [[exit-gates]]) 엔진 테스트가 이 자리에 스텁을 끼운다.

## `combined` — 세 조건의 AND

1. 변동성 돌파 — `currentPrice > calculateTargetPrice(candles, kValue)` = 당일 시가 + 전일 레인지 × `kValue`(기본 0.5). 같으면 사지 않는다.
2. MA 상승추세 — `isMaUptrend(candles, 5, 20)` (MA5 > MA20)
3. RSI 건전 구간 — `calculateRsi(candles, 14) in 30.0..70.0`

캔들이 21개 미만이면 즉시 false. 필요한 최소 봉수는 **전략이 `minCandles` 로 선언**하고 엔진이 `max(MIN_DAILY_CANDLES, minCandles)` 로 쓴다 — `combined` 는 기본값 21(= 기본 `shouldSell` 인 5/20 데드크로스 요구)이고 `StrategyMinCandlesTest` 가 선언과 실제를 대조한다.

- **RSI 는 넘긴 봉 전체로 계산한다.** `calculateRsi` 는 리스트 전체로 Wilder smoothing 을 돌아 **창 길이가 값에 들어간다** — 라이브 store 경로는 21~60봉 가변이라 같은 시점이어도 넘긴 봉 수에 따라 RSI 가 조금씩 다르다(무릎 전략 실측, 2026-08: 50↔60봉 최대 5.65, 21~60봉 가변이면 최대 21.43). `combined` 는 자르지 않는다 — 동작을 바꾸지 않으려는 것이다.

## 기본 전략 규칙

- 등록은 `StrategyConfig` 의 bean 이고, **엔진과 상태 API 는 첫 등록 bean 을 기본 전략으로 쓴다**(`TradingEngine` 초기화, 엔진·캐시가 없을 때의 `/api/bot/status` 전략). 둘이 같은 규칙이어야 정지 뒤 화면이 채운 전략으로 시작해도 전략이 바뀌지 않는다. `StrategyConfigTest` 가 등록 목록을 `["combined"]` 로 고정한다.
- 전략을 고르는 설정(`trading.strategy`·`TRADING_STRATEGY`)은 2026-10-01 에 없앴다. 운영 secret 에 남은 줄은 Vultr 배포가 전달하지 않고, 전달되더라도 Spring Boot 가 환경변수의 모르는 키로 바인딩을 실패시키지 않는다.
- 사용자별 선택(`/api/bot/strategy`, 시작 요청의 `strategy`)은 등록 이름만 받는다 — 전략이 하나라 고를 것이 없지만 경로는 남아 있다.

## 지표

지표 계산은 `Indicators` 에 모여 있다. `calculateMacd` 는 2026-09-16(#27)부터 TA-Lib 규칙 — 받은 히스토리 전체, EMA 는 첫 period 개 SMA 로 seed(fast 창은 slow 창의 꼬리에서 시작), 시그널은 MACD 선 전체에 EMA(9) — 이라 **충분한 히스토리(수백 봉)를 넘기면** 외부 차트의 MACD 와 일치한다. 지금 MACD 를 쓰는 곳은 차트 API(`ChartController`) 하나이고, 값은 넘긴 `count` 에 의존한다(이전 구현은 35봉 절단이라 표준값과 크게 달랐다). `calculateEma` 등 나머지 지표는 아직 이 파일 고유의 단순 방식이라 외부 값과 다르다.

## 청산과의 관계

전략은 **진입 신호**가 주 역할이고, 실제 청산은 대부분 [[exit-gates]] 의 손익% 안전망이 담당한다. 차트 기반 청산(`shouldSell`)은 기본 off 이며, 켜더라도 손절·트레일링·익절 뒤에 평가된다.
