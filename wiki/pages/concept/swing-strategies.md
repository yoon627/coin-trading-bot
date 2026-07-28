---
title: 스윙 전략 7종과 TradingStrategy 인터페이스
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — TradingStrategy.kt 전문, CombinedStrategy.kt 전문, common/strategy/ 파일 목록
sources:
  - common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/
---

# 스윙 전략

전략은 전부 `common` 모듈에 있어 라이브 봇과 [[backtest-engine]] 이 같은 구현을 쓴다.

## 인터페이스

```kotlin
interface TradingStrategy {
    val name: String
    suspend fun shouldBuy(candles, currentPrice, config): Boolean
    suspend fun shouldSell(candles, currentPrice, config): Boolean  // default: 5/20 데드크로스
}
```

- `shouldSell` 의 **기본 구현은 5/20 MA 데드크로스**다. 전략별로 진입과 대칭인 청산을 원하면 override 한다.
- `*Normalized` 변형(`shouldBuyNormalized`/`shouldSellNormalized`)이 있고 기본 구현이 `NormalizedCandle` → `Candle` 로 변환해 위임한다. 엔진은 store 캔들이 충분하면 Normalized 경로를, 부족하면 REST 캔들로 legacy 경로를 탄다([[trading-engine-loop]]).

## 구현 7종

`VolatilityBreakout`, `RsiBounce`, `GoldenCross`, `MacdCross`, `BollingerBounce`, `MeanReversion`, `CombinedStrategy`. 지표 계산은 `Indicators` 에 모여 있다.

기본 전략은 **`combined`** (`TradingProperties.strategy` 기본값)이며 세 조건의 AND 다:

1. 변동성 돌파 — `currentPrice > calculateTargetPrice(candles, kValue)` (`kValue` 기본 0.5)
2. MA 상승추세 — `isMaUptrend(candles, 5, 20)`
3. RSI 건전 구간 — `calculateRsi(candles, 14) in 30.0..70.0`

캔들이 21개 미만이면 즉시 false. 그래서 엔진의 D1 최소 게이트도 21(`MIN_DAILY_CANDLES`)로 맞춰져 있다.

## 청산과의 관계

전략은 **진입 신호**가 주 역할이고, 실제 청산은 대부분 [[exit-gates]] 의 손익% 안전망이 담당한다. 차트 기반 청산(`shouldSell`)은 기본 off 이며, 켜더라도 손절·트레일링·익절 뒤에 평가된다.
