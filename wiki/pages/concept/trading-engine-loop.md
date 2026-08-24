---
title: 매매 루프 — processTicker 의 게이트 순서
category: concept
created: 2026-07-28
updated: 2026-08-23
claim_state: current
verified: 2026-08-23 — TradingProperties.kt 전 필드 대조(takeProfitPct 5.0·trailingArmPct 3.0 로 교정), BacktestEngine.run 가드 off-by-one 수정 확인. 같은 날 #56 로 확장된 `unsynced` 트리거를 PositionManager.syncPosition 실측 + :bot:test 실행. 21 은 게이트가 아니라 store/REST 소스 선택자임을 확인하고 전략 minCandles 계약(#109) 반영
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt
  - common/src/main/kotlin/com/trading/common/config/TradingProperties.kt
---

# 매매 루프

이 페이지는 Upbit `TradingEngine`의 `processTicker` 순서를 설명한다. KIS 국내주식 엔진은 별도 클래스와 주문 WAL을 사용하므로 [[kis-stock-trading-flow]]와 [[kis-order-lifecycle]]를 함께 읽어야 한다.

`TradingEngine.runLoop()` 이 `intervalSeconds`(기본 10초)마다 활성 ticker 를 순회하며 `processTicker` 를 호출한다. 루프 진입 전 각 ticker 에 `syncPosition` 을 한 번 돌려 거래소 실잔고와 맞춘다.

## processTicker 의 순서 (TradingEngine.kt:231-308)

이 순서 자체가 안전장치다. 임의로 바꾸면 이중 매수·이중 매도가 열린다.

1. **가격 획득** — `MarketDataStore` 우선, **30초** 넘게 묵은 값이면 버리고 REST(`getTicker`) 폴백. 얼어붙은 가격으로 매매하지 않기 위함.
2. **`unsynced` 재동기화** — 부팅 시 `syncPosition` 이 실패했거나, 조회는 됐지만 **우리 주문으로 설명되지 않는 `locked` 잔고**가 있어 그 코인이 우리 포지션인지 정하지 못한 경우 여기서 재시도. 어느 쪽이든 해소될 때까지 `buy()` 초입 가드가 신규 진입을 막는다([[upbit-api]] 의 locked 상한 규칙).
3. **`pendingPersistFailed` 재기록** — pending durable 기록 실패로 매수가 막힌 상태를 푸는 유일한 경로.
4. **미해소 매수 reconcile** — `pendingBuyUuid` 가 있으면 먼저 확정. 확정되면 그 tick 은 거기서 끝난다(막 산 포지션에 같은 tick 손절 평가 금지). 미해소면 이 tick 의 매수·매도 평가를 통째로 skip.
5. **미해소 매도 reconcile** — 같은 구조의 매도판.
6. **보유 중이면 청산 평가** — `updatePeakPrice`(오를 때만 durable flush) → `decideSell` → `sell`.
7. **당일 1회 가드** — `position || boughtToday` 면 매수 평가 자체를 생략.
8. **매수 평가** — D1 캔들이 store 에 전략이 요구하는 만큼(`max(MIN_DAILY_CANDLES, strategy.minCandles)`) store 에 있으면 store, 아니면 REST 60개로 폴백해 [[swing-strategies]] 의 `shouldBuy` 판정.

## 청산 사유 우선순위 (decideSell, :322-334)

```
STOP_LOSS  >  TRAILING_STOP  >  TAKE_PROFIT  >  CHART_EXIT  >  DAILY_RESET
```

`when` 의 short-circuit 이라 가격 안전망이 걸리면 차트 청산은 **평가조차 하지 않는다**(캔들 조회 비용 회피). 각 게이트의 판정식은 [[exit-gates]] 참조.

## 기본 리스크 파라미터 (TradingProperties.kt)

| 항목 | 기본값 |
|---|---|
| `takeProfitPct` | 5.0 (+5% 익절) |
| `maxLossPct` | 5.0 (−5% 손절) |
| `trailingStopPct` / `trailingArmPct` | 2.0 / 3.0 |
| `maxHoldDays` | 1 (KST 09:00 경계) |
| `chartExitEnabled` | **false** (기본 off) |
| `intervalSeconds` | 10 |
| `investRatio` / `maxInvestAmount` | 0.1 / 100,000 KRW |
| `reconcileHaltThreshold` | 20 |

> [!conflict] 이 표는 두 번 어긋난 적이 있다 — 과거 `PROJECT_ANALYSIS.md` 가 정반대로(손절 −3%/익절 +5%) 적었고, #75(리스크 기본값 단일화) 이후에는 이 페이지가 `takeProfitPct` 2.0 · `trailingArmPct` 0.0 인 옛 값을 들고 있었다(2026-08-23 교정).
> **문서가 아니라 `TradingProperties.kt` 가 근거다.** 기본값이 바뀌면 이 표를 같은 커밋에서 고친다.

## 주문 유실 방지 구조

주문은 비멱등이라 자동 재시도하지 않는다. 대신 `placeOrder` 성공 직후 uuid 를 `pendingBuyUuid`/`pendingSellUuid` 로 잡고 **durable 로 먼저 기록**한 뒤(`NonCancellable` 안에서) 체결을 확인한다. 확인이 실패해도 uuid 가 남아 다음 tick 의 reconcile 이 이어받는다. 이 상태는 `trading_states` 테이블에 영속된다([[persistence-schema]]).

`getOrder` 와 잔고조회가 **둘 다** 실패하는 상황이 `reconcileHaltThreshold`(20회) 연속되면 해당 ticker 를 `halted` 로 두고 신규 진입만 막는다 — 매도·reconcile 은 계속 돌아야 잡힌 포지션이 갇히지 않는다.

## 체결·감사 원자 커밋

체결이 확정되면 `PositionManager`가 전이 결과를 `TradingState.copy()`에 먼저 적용하고, `TradeExecutionService.commitFill` 안에서 `trading_states` upsert와 `trade_records`·`trade_executions` 저장을 한 `TransactionalOperator` 트랜잭션으로 커밋한다. 트랜잭션 성공 뒤에만 원본 메모리 상태를 적용하고 Discord를 알리므로, 감사 저장 실패 시 원본 pending이 남아 다음 tick reconcile의 재시도 근거가 유지된다.

`TradingEngine`은 `processTicker`의 게이트·순서만 조정하며 체결 기록을 별도로 저장하지 않는다. 주문 접수 직후의 pending durable 기록과 즉시 체결 후처리는 `NonCancellable` 구간에서 완주하고, 이후 tick의 reconcile도 같은 `commitFill` 원자 커밋을 사용한다. Discord 알림은 커밋 이후 외부 IO라 실패해도 이미 커밋된 거래 기록을 롤백하지 않는다.
