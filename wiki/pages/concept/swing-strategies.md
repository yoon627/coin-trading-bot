---
title: 스윙 전략 9종과 TradingStrategy 인터페이스
category: concept
created: 2026-07-28
updated: 2026-08-19
claim_state: current
verified: 2026-08-19 — RSI take(40) 슬라이스 적용 후 KneeRsiWindowTest 로 w50/w60/w41 판정 일치 확인(8마켓 fixture 1128 케이스), ShoulderExit.MIN_CANDLES 41 상향, :bot:test 통과
sources:
  - common/src/main/kotlin/com/trading/common/strategy/TradingStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/CombinedStrategy.kt
  - common/src/main/kotlin/com/trading/common/strategy/KneeReversal.kt
  - common/src/main/kotlin/com/trading/common/strategy/KneePullback.kt
  - common/src/main/kotlin/com/trading/common/strategy/ShoulderExit.kt
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

## 구현 9종

`VolatilityBreakout`, `RsiBounce`, `GoldenCross`, `MacdCross`, `BollingerBounce`, `MeanReversion`, `CombinedStrategy`, `KneeReversal`, `KneePullback`. 지표 계산은 `Indicators` 에 모여 있다.

### 무릎 매수 2종 (`knee_*`)

"무릎에서 사서 어깨에 판다"를 코드화한 것으로, 같은 이름이지만 **다른 국면**을 잡는다.

| 전략 | 국면 | 핵심 조건 |
|---|---|---|
| `knee_reversal` | 하락 추세 전환 | 40봉 고점 대비 20봉 저점 낙폭 ≥15% + 저점 대비 **3~12%** 반등 + RSI 35~55 + 거래량 |
| `knee_pullback` | 상승 추세 중 조정 | MA20 > MA40 + 현재가가 MA20 의 0.97~1.02배 + 직전봉 대비 상승 양봉 + RSI 40~60 |

- **lookback 상한은 40봉**이다. 백테는 전략에 정확히 50봉을 넘기고([[backtest-engine]]), 라이브 store 경로는 21~60봉 가변이라 그보다 긴 lookback 은 백테·라이브에서 다르게 동작한다. 대신 라이브 warm-up 21~39봉 구간에서 두 전략은 **영구 false**다(예외 없음, 신호도 없음).
- **RSI 도 `take(40)` 으로 잘라서 넘긴다.** `calculateRsi` 는 리스트 전체로 Wilder smoothing 을 돌아 **window 길이가 값에 들어간다** — 자르지 않으면 같은 시점인데 백테(50봉)와 라이브(21~60봉)의 판정이 갈린다 — 8마켓 1128 케이스 실측에서 진입 3건·청산 2건이 어긋났고, RSI 자체는 50봉↔60봉이 최대 5.65, 라이브 window 가 21~60봉으로 가변인 것까지 보면 최대 21.43 벌어진다. 자른 뒤에는 `max|ΔRSI| = 0.0000` 으로 완전히 일치한다. 같은 이유로 `ShoulderExit` 은 최소 **41봉**을 요구한다 — `drop(1)` 로 직전 epoch 를 만들기 때문에 40봉이면 그쪽이 39봉이 되어 어긋난다. 기존 7개 전략은 같은 성질을 갖지만 동작 변경을 피하려고 손대지 않았다.
- **`ma5` 상향 전환을 반등 확인으로 쓰지 않는다.** `calculateMa` 가 단순평균이라 `ma5(t) > ma5(t−1)` 는 `close[0] > close[5]` 와 **정확히 동치**이고, 눌림목 국면과는 교집합이 거의 없다. 그래서 `close[0] > close[1]`(+양봉)로 판정한다.
- 두 전략은 `ShoulderExit` 로 `shouldSell` 을 override 한다 — 과열 RSI 하향 돌파(70) 또는 볼린저 상단 이탈 후 복귀. 기본 데드크로스를 **OR 로 병합하지 않는** 이유는 조기 이탈이 목적이라 늦은 신호가 섞이면 의도가 사라지기 때문이다.
- ⚠️ 어깨 청산은 `chartExitEnabled` 가 꺼져 있으면 호출되지 않고, `maxHoldDays=1` 이면 백테에서도 `atHoldLimit` 게이트에 막혀 CHART_EXIT 대신 TIME_EXIT 이 난다([[exit-gates]]). 즉 **기본 설정에서는 dead path** 다.

기본 전략은 **`combined`** (`TradingProperties.strategy` 기본값)이며 세 조건의 AND 다:

1. 변동성 돌파 — `currentPrice > calculateTargetPrice(candles, kValue)` (`kValue` 기본 0.5)
2. MA 상승추세 — `isMaUptrend(candles, 5, 20)`
3. RSI 건전 구간 — `calculateRsi(candles, 14) in 30.0..70.0`

캔들이 21개 미만이면 즉시 false. 그래서 엔진의 D1 최소 게이트도 21(`MIN_DAILY_CANDLES`)로 맞춰져 있다.

## 청산과의 관계

전략은 **진입 신호**가 주 역할이고, 실제 청산은 대부분 [[exit-gates]] 의 손익% 안전망이 담당한다. 차트 기반 청산(`shouldSell`)은 기본 off 이며, 켜더라도 손절·트레일링·익절 뒤에 평가된다.
