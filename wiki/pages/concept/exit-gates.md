---
title: 청산 게이트 — 손절·트레일링·익절·차트·보유상한
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — ExitGates.kt 전문, PositionManager.kt:591-612, TradingEngine.kt:320-334
sources:
  - common/src/main/kotlin/com/trading/common/strategy/ExitGates.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
---

# 청산 게이트

청산 판정식은 `common` 의 `ExitGates` 에 공용화돼 있다. 목적은 **라이브(`PositionManager`)와 백테스트(`BacktestEngine`)가 같은 조건식을 쓰게** 하는 것이다 — 두 곳에 각각 구현하면 백테 결과가 라이브를 대변하지 못한다([[backtest-engine]]).

## 판정식

| 게이트 | 조건 | 구현 |
|---|---|---|
| 손절 | `pnlPct <= -maxLossPct` | `PositionManager.checkStopLoss` |
| 트레일링 | `pnlPct > 0 && peakPnlPct >= trailingArmPct && dropFromPeakPct >= trailingStopPct` | `ExitGates.isTrailingStopTriggered` |
| 익절 | `pnlPct >= takeProfitPct` | `PositionManager.checkTakeProfit` |
| 차트 청산 | 전략의 `shouldSell` (기본 = 5/20 MA 데드크로스) | `TradingEngine.evaluateChartExit` |
| 보유 상한 | `maxHoldDays` 경과 (KST 09:00 경계) | `DailyResetManager` |

평가 순서는 [[trading-engine-loop]] 참조 — 손절이 가장 먼저다.

## 비자명한 지점

- **트레일링은 수익 구간에서만 작동한다.** 손실 구간은 손절이 담당한다.
- **`trailingArmPct` 는 `trailingStopPct` 보다 클 때만 실효**하다. `pnl>0 ∧ drop≥trail` 이면 `peakPnl > trail/(1−trail/100)` 이 수학적으로 강제되므로, arm 이 trail 이하면 조건이 자동 충족돼 아무 효과가 없다. 엔진 기동 시 `warnIfExitConfigInert()` 가 이 무의미 조합과 "익절이 트레일링보다 낮아 트레일링이 dead 인" 조합을 WARN 으로 알린다.
- **`maxHoldDays` 는 0·음수를 1로 보정**한다(`effectiveMaxHoldDays`). env 오설정으로 0 이 들어오면 "매수 당일 즉시 청산" 루프가 돌기 때문이다.
- **NaN 안전**: 평단 0 등으로 `pnlPct` 가 NaN 이면 IEEE 비교 의미상 모든 조건이 false → 발동하지 않는다.
- **게이트는 gross, 기록은 net**: 청산 판정은 수수료를 빼지 않은 수익률로 하고, `TradeRecord.pnlPercent` 에만 왕복 수수료(`roundTripFeeRate`, 기본 0.1%)를 차감해 남긴다.
- **진입 전략으로 청산한다**: `resolveExitStrategy` 가 `entryStrategy` 를 복원해 그 전략의 `shouldSell` 을 쓴다. 전략이 목록에서 사라졌으면 활성 전략으로 폴백하며 WARN — 이때는 청산 기준이 진입과 달라진다([[swing-strategies]]).
- **차트 청산은 기본 off** (`chartExitEnabled=false`). 켜기 전 백테스트 검증이 전제다.
- **진입 시점 파라미터를 스냅샷**한다(`ExitParamsSnapshot`). 보유 중 설정을 바꿔도 그 포지션은 진입 당시 기준으로 청산된다.
