---
title: 청산 게이트 — 손절·트레일링·익절·차트·보유상한
category: concept
created: 2026-07-28
updated: 2026-09-23
claim_state: current
verified: 2026-09-14 — 보유상한 초과 WARN 은 `DailyResetManagerTest` 2건(초과 1회·정시 0건)으로 확인. 이전 확인분: 2026-09-08 — 재진입 문단의 경계 가드는 `TradingEngine.runSwing`(`isCurrentDay`·`pastBoundaryGrace`)과 `TradingEngineTest` 5건으로 확인. 이전 확인분: 2026-09-06 — 스냅샷 소비 도입(#177) 후 `ExitParamsSnapshotConsumptionTest` 6건 통과(게이트 4종 + 폴백 2종), `./gradlew build` 실행 990/skip 19/실패 0. 이전 확인분: 2026-07-28 — ExitGates.kt 전문, PositionManager.kt:591-612, TradingEngine.kt:320-334
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt
  - common/src/main/kotlin/com/trading/common/strategy/ExitGates.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
---

# 청산 게이트

이 페이지의 구현 인용은 Upbit `PositionManager` 기준이다(2026-09-16 KIS 경로 제거 후 유일한 청산 구현).

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

**청산 직후의 재진입.** 라이브는 09:00 경계에서 `boughtToday` 가 풀려 보유상한 청산 당일 돌파하면 다시 산다(목표가가 `당일시가 + k·전일레인지` 라 시가 즉시 재매수는 아니다). 백테는 2026-09-23 부터 기본 `LIVE_SAME_BAR` 로 이 기회를 모델링한다(#144, 그 전 기본 LEGACY 는 2봉 공백).
단 2026-09-08 부터 라이브는 **오늘 거래일 D1 이 있는 소스(store 또는 REST)로만** 재매수를 평가한다 — 그 전(약 60~120초)의 "공백 0" 재매수는 어제 window 위의 판정이었다([[trading-engine-loop]] 8번). `BacktestConfig.reentryMode` 가 이 축을 노브로 노출한다 — 배경과 측정 결과는 [[backtest-engine]]·[[reset-churn-measurement]].

## 비자명한 지점

- **적립 프로파일 티커에는 이 게이트가 하나도 적용되지 않는다.** `trading.accumulate.tickers` 에 든 티커는 `runAccumulate` 로 갈려 `decideSell` 자체를 타지 않는다 — 리스크 상한은 코인당 예산뿐이다([[accumulate-ladder]]). 적립을 끄면 남은 포지션이 즉시 이 게이트를 받는다.
- **트레일링은 수익 구간에서만 작동한다.** 손실 구간은 손절이 담당한다.
- **`trailingArmPct` 는 `trailingStopPct` 보다 클 때만 실효**하다. `pnl>0 ∧ drop≥trail` 이면 `peakPnl > trail/(1−trail/100)` 이 수학적으로 강제되므로, arm 이 trail 이하면 조건이 자동 충족돼 아무 효과가 없다. 엔진 기동 시 `warnIfExitConfigInert()` 가 이 무의미 조합과 "익절이 트레일링보다 낮아 트레일링이 dead 인" 조합을 WARN 으로 알린다.
- **`maxHoldDays` 는 0·음수를 1로 보정**한다(`effectiveMaxHoldDays`). env 오설정으로 0 이 들어오면 "매수 당일 즉시 청산" 루프가 돌기 때문이다.
- **NaN 안전**: 평단 0 등으로 `pnlPct` 가 NaN 이면 IEEE 비교 의미상 모든 조건이 false → 발동하지 않는다.
- **게이트는 gross, 기록은 net**: 청산 판정은 수수료를 빼지 않은 수익률로 하고, `TradeRecord.pnlPercent` 에만 왕복 수수료(`roundTripFeeRate`, 기본 0.1%)를 차감해 남긴다.
- **진입 전략으로 청산한다**: `resolveExitStrategy` 가 `entryStrategy` 를 복원해 그 전략의 `shouldSell` 을 쓴다. 전략이 목록에서 사라졌으면 활성 전략으로 폴백하며 WARN — 이때는 청산 기준이 진입과 달라진다([[swing-strategies]]). 같은 `entryStrategy` 가 **매도 기록의 전략 귀속**에도 쓰인다(`buildSellRecord` 가 `markSold` 이전에 읽는다 — [[persistence-schema]]).
- **차트 청산은 기본 off** (`chartExitEnabled=false`). 켜기 전 백테스트 검증이 전제다.

## 진입 시점 스냅샷을 따른다 (2026-09-06~, #177)

손절·익절·트레일링·보유상한은 **그 포지션이 진입할 때의 값**으로 판정한다. 보유 중 전역 설정을 바꿔도
이미 열린 포지션의 청산 기준은 바뀌지 않는다.

```kotlin
// PositionManager.exitParamsOf
private fun exitParamsOf(state: TradingState): ExitParamsSnapshot = state.exitParams ?: snapshotExitParams()
```

- 소비처: `checkTakeProfit` · `checkStopLoss` · `checkTrailingStop`(`PositionManager`), `shouldSellForDailyReset`(`DailyResetManager`).
- **늦은 보유상한 발동은 WARN 으로 드러난다**: `shouldSellForDailyReset` 이 경과 거래일 > 상한을 보면 프로세스 수명 동안 포지션(ticker·buyDate)당 1회 `Hold limit overrun` 을 남긴다 — 정상 발동(== 상한)은 침묵. 리셋이 밀린 구간(2026-07 3건, 원인 미확정, #131)의 재발 감지용이다. 한계: 손절·익절 등 앞선 게이트가 같은 tick 에 먼저 걸리면 이 판정에 도달하지 않아 남지 않고, 스윙 경로 전용이다(`runAccumulate` 는 `decideSell` 을 거치지 않는다).
- **스냅샷이 없으면 전역값으로 폴백**한다 — 이 변경 이전에 열린 포지션·복원 실패분의 동작을 보존한다.
- **`chartExitEnabled` 는 스냅샷에 없다.** 임계가 아니라 모드 스위치라 전역이 소유한다.
- 생명주기: 진입 시 기록(`markBought` 가 신규 진입에서 옛 값을 비우고 호출부가 다시 찍는다) → `exit_params_json` 으로 durable →
  청산 시 `markSold` 가 비운다. 재시작 복원은 durable 값을 그대로 쓴다.

> [!conflict] 2026-09-06 이전에는 소비되지 않았다
> 그전에는 스냅샷이 저장·복원만 되고 청산은 **현재** `tradingProperties` 를 읽었다. 그래서
> **보유 중 설정을 바꾸면 열린 포지션의 청산 기준까지 즉시 바뀌었다** — 2026-09-06 트레일링 승격
> ([[trailing-arm-finding-2026-09]])에서 실제로 발생했고, 그 거래들은 *진입은 옛 규칙, 청산은 새 규칙*이다.
> 그 기간의 성과를 어느 설정 몫으로 셀지는 정의되지 않는다. 이후 진입분부터는 이 문제가 없다.

