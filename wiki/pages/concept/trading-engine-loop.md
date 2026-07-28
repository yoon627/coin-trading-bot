---
title: 매매 루프 — processTicker 의 게이트 순서
category: concept
created: 2026-07-28
updated: 2026-07-28
claim_state: current
verified: 2026-07-28 — TradingEngine.kt:173-334, PositionManager.kt:63-138 정독
sources:
  - bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt
  - bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt
  - common/src/main/kotlin/com/trading/common/config/TradingProperties.kt
---

# 매매 루프

`TradingEngine.runLoop()` 이 `intervalSeconds`(기본 10초)마다 활성 ticker 를 순회하며 `processTicker` 를 호출한다. 루프 진입 전 각 ticker 에 `syncPosition` 을 한 번 돌려 거래소 실잔고와 맞춘다.

## processTicker 의 순서 (TradingEngine.kt:231-308)

이 순서 자체가 안전장치다. 임의로 바꾸면 이중 매수·이중 매도가 열린다.

1. **가격 획득** — `MarketDataStore` 우선, **30초** 넘게 묵은 값이면 버리고 REST(`getTicker`) 폴백. 얼어붙은 가격으로 매매하지 않기 위함.
2. **`unsynced` 재동기화** — 부팅 시 `syncPosition` 이 실패했으면 여기서 재시도. 실패가 지속되면 `buy()` 초입 가드가 신규 진입을 막는다.
3. **`pendingPersistFailed` 재기록** — pending durable 기록 실패로 매수가 막힌 상태를 푸는 유일한 경로.
4. **미해소 매수 reconcile** — `pendingBuyUuid` 가 있으면 먼저 확정. 확정되면 그 tick 은 거기서 끝난다(막 산 포지션에 같은 tick 손절 평가 금지). 미해소면 이 tick 의 매수·매도 평가를 통째로 skip.
5. **미해소 매도 reconcile** — 같은 구조의 매도판.
6. **보유 중이면 청산 평가** — `updatePeakPrice`(오를 때만 durable flush) → `decideSell` → `sell`.
7. **당일 1회 가드** — `position || boughtToday` 면 매수 평가 자체를 생략.
8. **매수 평가** — D1 캔들이 store 에 21개 이상이면 store, 아니면 REST 60개로 폴백해 [[swing-strategies]] 의 `shouldBuy` 판정.

## 청산 사유 우선순위 (decideSell, :322-334)

```
STOP_LOSS  >  TRAILING_STOP  >  TAKE_PROFIT  >  CHART_EXIT  >  DAILY_RESET
```

`when` 의 short-circuit 이라 가격 안전망이 걸리면 차트 청산은 **평가조차 하지 않는다**(캔들 조회 비용 회피). 각 게이트의 판정식은 [[exit-gates]] 참조.

## 기본 리스크 파라미터 (TradingProperties.kt)

| 항목 | 기본값 |
|---|---|
| `takeProfitPct` | 2.0 (+2% 익절) |
| `maxLossPct` | 5.0 (−5% 손절) |
| `trailingStopPct` / `trailingArmPct` | 2.0 / 0.0 |
| `maxHoldDays` | 1 (KST 09:00 경계) |
| `chartExitEnabled` | **false** (기본 off) |
| `intervalSeconds` | 10 |
| `investRatio` / `maxInvestAmount` | 0.1 / 100,000 KRW |
| `reconcileHaltThreshold` | 20 |

> [!conflict] 과거 `PROJECT_ANALYSIS.md` 가 이 수치를 정반대로(손절 −3%/익절 +5%) 기재한 적이 있고 `docs-sync` 작업에서 교정됐다. **문서가 아니라 `TradingProperties.kt` 가 근거다.**

## 주문 유실 방지 구조

주문은 비멱등이라 자동 재시도하지 않는다. 대신 `placeOrder` 성공 직후 uuid 를 `pendingBuyUuid`/`pendingSellUuid` 로 잡고 **durable 로 먼저 기록**한 뒤(`NonCancellable` 안에서) 체결을 확인한다. 확인이 실패해도 uuid 가 남아 다음 tick 의 reconcile 이 이어받는다. 이 상태는 `trading_states` 테이블에 영속된다([[persistence-schema]]).

`getOrder` 와 잔고조회가 **둘 다** 실패하는 상황이 `reconcileHaltThreshold`(20회) 연속되면 해당 ticker 를 `halted` 로 두고 신규 진입만 막는다 — 매도·reconcile 은 계속 돌아야 잡힌 포지션이 갇히지 않는다.

## 알려진 갭

`onTrade`(감사 기록)는 pending 해소가 durable 에 커밋된 **뒤** 실행된다(TradingEngine.kt:387-389 주석이 명시). 여기서 예외가 나면 재시도 근거가 이미 지워져 그 거래 기록이 유실된다 — 실제 현금흐름은 발생했는데 기록만 없는 상태. 이슈 #52 로 추적 중이다.
