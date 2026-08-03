---
title: KIS 주문 수명주기 — 검증·WAL·송신·체결 reconcile
category: concept
created: 2026-08-02
updated: 2026-08-02
claim_state: current
verified: 2026-08-02 — KisTradeController.kt, StockOrderService.kt, KisClientImpl.kt, KisTokenProvider.kt, StockOrderReconciler.kt, V15~V18 migration 실측
sources:
  - bot/src/main/kotlin/com/trading/bot/api/KisTradeController.kt
  - bot/src/main/kotlin/com/trading/bot/kis/order/StockOrderService.kt
  - bot/src/main/kotlin/com/trading/bot/kis/order/StockOrderReconciler.kt
  - bot/src/main/kotlin/com/trading/bot/kis/client/KisClientImpl.kt
  - bot/src/main/kotlin/com/trading/bot/kis/client/KisTokenProvider.kt
  - bot/src/main/kotlin/com/trading/bot/persistence/StockOrderIntentRepository.kt
  - bot/src/main/kotlin/com/trading/bot/persistence/entity/StockOrderIntentEntity.kt
  - bot/src/main/resources/db/migration/V15__create_stock_order_intent.sql
  - bot/src/main/resources/db/migration/V17__bot_state_exchange_and_wal_side.sql
---

# KIS 주문 수명주기

KIS 주문은 자동매매와 수동주문이 같은 `StockOrderService`를 통과한다. 자동 경로는 [[kis-stock-trading-flow]]의 `StockPositionManager.submitBuy/submitSell`에서, 수동 경로는 `POST /api/kis/order`에서 시작한다.

```text
자동 엔진 / 수동 REST
        ↓
공통 StockOrderService.validate
        ↓
활성 주문 중복 가드
        ↓
tx1: stock_order_intent 기록·커밋
        ↓
dry-run 반환 또는 KIS placeOrder 송신
        ↓
tx2: 상태·ODNO 조건부 전이
        ↓
StockOrderReconciler의 당일 체결조회
        ↓
PLACED / PARTIAL / FILLED / CANCELLED / NEEDS_REVIEW 확정
```

## 송신 전 검증

공통 검증은 수량이 양수인지, 지정가 가격이 양수인지, 주문 명목금액이 `KIS_MAX_ORDER_AMOUNT`를 넘지 않는지 확인한다. 시장가의 명목금액은 현재가에 1.1 버퍼를 곱해 계산한다. 실주문 모드에서는 송신 직전에 장이 열려 있는지 다시 확인하고, BUY는 KIS `inquire-psbl-order`의 매수가능수량을 초과하지 못한다.

같은 사용자·계좌·종목·side에 활성 주문이 있으면 애플리케이션 가드와 DB partial unique index가 모두 막는다. V17부터 BUY와 SELL은 각각 하나씩 활성화할 수 있지만 같은 side 중복은 허용하지 않는다.

## WAL을 먼저 커밋하는 이유

`StockOrderService`는 다음을 하나의 DB 트랜잭션으로 묶지 않는다.

1. **tx1** — 주문 의도를 `stock_order_intent`에 `SUBMITTING`으로 저장하고 커밋한다.
2. **네트워크 송신** — 트랜잭션 밖에서 KIS `placeOrder`를 호출한다. 자동 재시도하지 않는다.
3. **tx2** — 응답에 따라 `PLACED`, `FAILED`, `UNKNOWN`으로 조건부 전이한다.

KIS 호출 뒤 프로세스가 죽을 수 있으므로 송신과 DB 기록을 한 트랜잭션으로 묶으면 안 된다. 그렇게 하면 송신은 됐는데 DB INSERT가 롤백되어 추적할 수 없는 주문이 생긴다. 반대로 `UNKNOWN`은 접수됐을 가능성을 보존하고 reconcile이 확정하도록 한다.

## dry-run과 실제 KIS 송신

`KIS_LIVE_ENABLED=false`(기본)이면 `stock_order_intent`에 `DRY_RUN`을 기록하고 `KisClient.placeOrder()`를 호출하지 않는다. KIS 모의계정으로 API 주문을 시험하려면 live gate를 켜야 하지만, 사용자의 `kis_paper=true`를 유지하면 KIS 모의 도메인과 모의 tr_id를 사용한다. 실전계정 주문은 live gate와 `kis_paper=false`가 모두 필요하다.

실제 송신 시 `KisTokenProvider`가 access token을 캐시하고, `KisClientImpl`이 paper/real에 따라 다음 tr_id를 선택한다.

| 경로 | BUY | SELL |
|---|---|---|
| 모의투자 | `VTTC0012U` | `VTTC0011U` |
| 실전 | `TTTC0012U` | `TTTC0011U` |

주문은 국내주식 `/uapi/domestic-stock/v1/trading/order-cash`로 전송하고, 성공 응답의 `ODNO`를 WAL에 저장한다. 명시적 업무 거절은 `FAILED`, timeout·연결오류처럼 접수 여부를 모르는 오류는 `UNKNOWN`이다.

## 체결 reconcile

`StockOrderReconciler`는 부팅 시와 기본 15초 주기로 비종료 주문을 모아 KIS `inquireDailyConclusions`를 조회한다. `ODNO`가 있으면 직접 매칭하고, `SUBMITTING/UNKNOWN`처럼 ODNO가 없으면 종목·side·수량으로 단일 후보를 찾는다. 조회 API의 연속 페이지도 이어 받아 당일 주문 전체를 확인한다.

- 실행수량 0이고 잔여수량이 있으면 `PLACED`
- 일부 체결이면 `PARTIAL`
- 잔여수량 0이고 실행수량이 있으면 `FILLED`
- 취소면 `CANCELLED`
- 일시적인 조회 누락은 바로 실패로 만들지 않는다
- 기본 grace 120초 또는 ODNO 누락 stale 1,800초가 지나면 `NEEDS_REVIEW`로 올리고 오류 로그를 남긴다

`FILLED`·`CANCELLED` 같은 terminal 전이는 체결 audit 기록과 함께 트랜잭션으로 처리하고, `trade_executions.exchange_order_id`의 unique 제약으로 같은 체결의 audit 중복 기록을 막는다([[persistence-schema]]). 이후 자동 엔진의 보유수량·평단은 다음 KIS holdings 조회로 확정한다.

이 구조의 운영 목적은 주문을 자동 재전송하는 것이 아니라, **송신 여부가 불명확한 주문을 잃지 않고 수동 검토 가능한 상태로 보존하는 것**이다. 전체 시스템에서 이 주문 경계가 어디에 놓이는지는 [[architecture-overview]]에서, 엔진이 어떤 조건으로 BUY/SELL을 호출하는지는 [[kis-stock-trading-flow]]에서 확인한다.
