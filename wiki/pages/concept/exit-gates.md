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

일부 청산 판정식이 `common` 의 `ExitGates` 에 공용화돼 있다 — 라이브(`PositionManager`)와 백테스트(`BacktestEngine`)가 각각 구현하면 백테 결과가 라이브를 대변하지 못하기 때문이다([[backtest-engine]]).

**단, 공용화된 것은 트레일링 판정과 `maxHoldDays` 보정뿐이고 평가 순서는 두 곳이 다르다** — 아래 "라이브와 백테의 순서 차이" 참조.

## 판정식

| 게이트 | 조건 | 구현 |
|---|---|---|
| 손절 | `pnlPct <= -maxLossPct` | `PositionManager.checkStopLoss` |
| 트레일링 | `pnlPct > 0 && peakPnlPct >= trailingArmPct && dropFromPeakPct >= trailingStopPct` | `ExitGates.isTrailingStopTriggered` |
| 익절 | `pnlPct >= takeProfitPct` | `PositionManager.checkTakeProfit` |
| 차트 청산 | 전략의 `shouldSell` (기본 = 5/20 MA 데드크로스) | `TradingEngine.evaluateChartExit` |
| 보유 상한 | `maxHoldDays` 경과 (KST 09:00 경계) | `DailyResetManager` |

## 라이브와 백테의 순서 차이

| | 순서 |
|---|---|
| 라이브 (`TradingEngine.decideSell`) | **손절 → 트레일링** → 익절 → 차트 → 일일리셋 |
| 백테 (`IntrabarExitModel.evaluate`) | **트레일링 → 손절** → 익절 → 차트 → TIME_EXIT |

의도된 차이다. 라이브는 10초 tick 이라 두 조건이 상호배타적으로 도달하지만, 백테는 봉 하나를 붕괴시켜 판정하므로 "하강 경로에서 라이브가 먼저 닿는 순서"(트레일링선 > 진입가 > 손절선)를 따라야 한다. 라이브 순서를 그대로 옮기면 **트레일링으로 이익 실현했을 거래가 −maxLoss 손절로 오기록**된다.

따라서 백테의 청산 사유(reason) 분포를 라이브와 1:1로 비교하면 안 된다.

## 비자명한 지점

- **트레일링은 수익 구간에서만 작동한다.** 손실 구간은 손절이 담당한다.
- **`trailingArmPct` 는 `trailingStopPct` 보다 클 때만 실효**하다. `pnl>0 ∧ drop≥trail` 이면 `peakPnl > trail/(1−trail/100)` 이 수학적으로 강제되므로, arm 이 trail 이하면 조건이 자동 충족돼 아무 효과가 없다. 엔진 기동 시 `warnIfExitConfigInert()` 가 이 무의미 조합과 "익절이 트레일링보다 낮아 트레일링이 dead 인" 조합을 WARN 으로 알린다.
- **`maxHoldDays` 는 0·음수를 1로 보정**한다(`effectiveMaxHoldDays`). env 오설정으로 0 이 들어오면 "매수 당일 즉시 청산" 루프가 돌기 때문이다.
- **NaN 안전**: 평단 0 등으로 `pnlPct` 가 NaN 이면 IEEE 비교 의미상 모든 조건이 false → 발동하지 않는다.
- **게이트는 gross, 기록은 net**: 청산 판정은 수수료를 빼지 않은 수익률로 하고, `TradeRecord.pnlPercent` 에만 왕복 수수료(`roundTripFeeRate`, 기본 0.1%)를 차감해 남긴다.
- **진입 전략으로 청산한다**: `resolveExitStrategy` 가 `entryStrategy` 를 복원해 그 전략의 `shouldSell` 을 쓴다. 전략이 목록에서 사라졌으면 활성 전략으로 폴백하며 WARN — 이때는 청산 기준이 진입과 달라진다([[swing-strategies]]).
- **차트 청산은 기본 off** (`chartExitEnabled=false`). 켜기 전 백테스트 검증이 전제다.

## ⚠️ 진입 시점 스냅샷은 아직 소비되지 않는다

`ExitParamsSnapshot` 이 진입 시점에 기록되고 재시작 시 복원되지만, **청산 판정은 그 값을 읽지 않는다.** 손절·익절·트레일링·보유상한 모두 **현재** `tradingProperties` 를 읽는다(`PositionManager` 의 게이트 함수들, `DailyResetManager`).

```
TradingState.kt:41
// 진입 시점 청산 파라미터 스냅샷. 저장·복원 전용 —
// 소비(진입 시점 값으로 청산)는 strategy-evolution Phase 2.
```

실무상 의미: **보유 중에 설정을 바꾸면 이미 열려 있는 포지션의 청산 기준까지 즉시 적용된다.** 운영 중 파라미터를 조정할 때는 열린 포지션이 있는지 먼저 확인해야 한다. 스냅샷이 있으니 안전하다고 가정하면 안 된다.
