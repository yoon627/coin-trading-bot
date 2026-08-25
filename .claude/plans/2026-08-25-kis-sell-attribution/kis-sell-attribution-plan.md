---
title: kis-sell-attribution — KIS 체결 기록의 전략·사유 귀속 복구 (#130)
status: in_progress
started: 2026-08-25
updated: 2026-08-25
---

# Goal

KIS 주식 경로의 체결 기록(`trade_executions`)에 진입 전략과 매도 사유가 안 남아 **전략별 손익 귀속이 불가능한 결함**을 고친다(#117 Upbit 판의 KIS 대응). 값을 주문 WAL 에 실어 reconcile 시점의 경합을 원천 차단한다.

# Progress

- 2026-08-25: Explore 완료 — 결함과 그 원인을 코드로 확정.
  - `StockOrderReconciler.buildExecution`(`:197-216`)이 `strategy`·`reason`·`pnlPercent`·`pnlAmount` 를 **전부** 안 넘긴다. `TradeExecutionEntity` 의 해당 필드가 모두 nullable 기본값이라 컴파일이 통과한다(Upbit `buildSellRecord` 와 동일한 실패 형태).
  - **이슈가 열어둔 "수명 제약" 질문의 답: 경합이 실재한다.** 엔진 루프가 매 tick `syncFromHoldings` 를 부르고(`KisStockTradingEngine.kt:128`), 청산이면 `StockPosition.kt:82` 가 `entryStrategy = null` 로 지운다. `StockOrderReconciler` 는 `@Scheduled(fixedDelay 15s)`(`:60`)로 **완전히 독립**해 돌고 `positions`·상태 리포지토리에 의존조차 없다. 따라서 매도 체결 후 엔진 tick 이 먼저 돌면 `stock_position_state.entry_strategy` 는 이미 NULL 이다 → 이슈 원안(reconcile 시점 조회)은 고치려는 결함과 같은 증상을 남긴다.
  - 반면 **주문 접수 시점엔 양쪽 다 전략을 안다**: `submitBuy(…, strategyName, …)` 는 인자로 받고, `submitSell` 은 `pos.entryStrategy` 가 아직 살아 있다(청산 반영은 "다음 패스 getHoldings" 라고 코드 주석이 명시).
  - **백필 불필요**(⚠️추정, 배포 전 실측 예정): `DRY_RUN` 은 `terminal=true`(`StockOrderStatus.kt:22`)라 reconcile 대상에서 빠져 `buildExecution` 이 아예 안 불린다. 앱 기본 `kis.live-enabled=false`(`application.yml:53`)이고 현재 배포 계층 `deploy/vultr/` 에는 `KIS_LIVE_ENABLED` 설정이 **없다**(grep 0건) — #75 결정("배포 계층은 앱 기본값을 갖지 않는다")대로 앱 기본값이 적용된다.
  - **V22 사용 가능** — `origin/main`(V21 최신) + 로컬 3개 브랜치(`daily-reset-counterfactual` V21, `maxholddays-sweep` V19) 전수 확인. 원격 미머지 브랜치 없음([[migration-numbering]] 절차).

- 2026-08-25: **plan 자체 검토에서 진입점 누락 발견** — `SubmitOrderCommand` 생성부가 둘이다: 엔진(`StockPositionManager:99`)과 **수동 REST 주문**(`KisTradeController:65`). 초안은 엔진 경로만 상정했다. Upbit 에서 `executeSellAll`/`executeSellVolume` 이 `strategy="manual"` 을 하드코딩한 것과 대응되는 지점이라 같은 시맨틱으로 맞춘다(아래 Decisions).
- 2026-08-25: **TDD Red→Green→구현 완료.** Red 를 두 경로에서 각각 실제로 관찰했다 — reconcile 경로는 `expected: <rsi_bounce> but was: <null>`, 주문 접수 경로는 구현을 일시 되돌려 `expected: <knee> but was: <null>`. 명시값 fixture 라 vacuous 통과가 아니다.
- 2026-08-25: **V22 를 격리 컨테이너에서 실측** — 로컬 개발 DB 를 건드리지 않도록 임시 `postgres:17-alpine` 에 V1~V22 를 번호순 적용(22/22 ok), `strategy varchar(64) null=YES` · `reason varchar(32) null=YES` 확인.
- 2026-08-25: **자체 code-review(subagent 미사용 — 세션 제약)에서 2건 발견·반영.** ① 수동 매도에 `reason` 을 안 실어 Upbit `TradeExecutionService:117,163`(`SellReason.MANUAL`)과 어긋났다 → `side == SELL` 이면 `MANUAL` 을 싣도록 수정. ② `StockOrderService` 가 command 값을 실제 WAL 행에 넣는지 검증하는 테스트가 없었다(`StockPositionManagerTest` 는 command 까지만 본다) → `StockOrderServiceTest` 에 추가. 수동 경로 테스트도 `KisTradeControllerTest` 에 추가.
- 2026-08-25: **최종 검증 통과** — `compileKotlin`, `test --parallel`(CI 와 동일 명령), `build -x test` 전부 성공. wiki 검증 3종(link/verify/smoke 10-0) 통과.
- 2026-08-25: **운영 DB 실측(read-only)으로 백필 불필요 확정.** `trade_executions` 의 KIS 행 **0건**(Upbit 66건), `stock_order_intent` **0행**, 비terminal 주문 **0건** — V22 적용 시 영향받을 미체결 주문도 없다. flyway 는 **V21 까지 21건 적용·실패 0**(첫 쿼리의 `max(version)=9` 는 version 이 varchar 라 사전순이었던 내 쿼리 결함이었고, 숫자 정렬로 재확인했다).

# Next

push → PR → 머지. Acceptance 9/9 충족. 머지 시 자동 배포가 돌고 V22 가 적용된다 — 활성 주문 0건이라 마이그레이션이 건드릴 미체결 주문은 없다.

# Decisions

- **값은 주문 WAL 에 싣는다(사용자 결정 2026-08-25).** `stock_order_intent` 에 `strategy`·`reason` 컬럼을 V22 로 추가하고, 주문 접수 시점의 값을 박아 reconcile 이 언제 돌든 그대로 읽는다. 대안 2개를 기각한 이유:
  - *reconcile 시점 상태 조회*(이슈 원안) — 위 Progress 의 경합 때문에 매도 귀속이 그대로 샌다. `buildExecution` 이 도는 시점에 `entry_strategy` 가 살아 있다는 보장이 없다.
  - *WAL 우선 + 상태 폴백* — 경합 경로를 남기면서 분기만 늘린다. 구버전 WAL 행을 살리는 게 이점인데, 백필 대상이 0건이라 살릴 행 자체가 없다.
- **범위는 `strategy` + `reason` 까지(사용자 결정 2026-08-25).** 둘 다 주문 접수 시점에 이미 손에 있어 같은 컬럼 추가 작업에 묶으면 비용이 거의 안 든다. `pnlPercent`/`pnlAmount` 는 제외 — 매수평단·부분체결·수수료 입력이 더 필요해 설계가 커진다. `# Deferred` 에 남긴다.
- **컬럼은 additive nullable** — `VARCHAR(64)`(기존 `stock_position_state.entry_strategy` 와 동일 폭). R2DBC 는 엔티티 선언 컬럼만 SELECT/INSERT 하므로 구버전 앱 + V22 공존이 안전하다(V21 때 확인한 성질).
- **수동 주문은 `strategy = "manual"`** — `KisTradeController` 경로는 전략이 없다. Upbit 이 `trade_records.strategy='manual'` 로 엔진/수동을 구분하고 그 구분에 집계가 의존하므로([[trade-record-volume-semantics]]), KIS 도 같은 리터럴을 쓴다. null 로 두면 "귀속 실패"와 "수동이라 전략 없음"이 구분되지 않는다 — 지금 고치는 결함이 정확히 그 혼동이다. *수동 매도가 엔진 포지션을 청산했을 때 진입 전략을 크레딧하는 문제는 #129 범위이며 여기서 다루지 않는다.*
- **`reason` 은 BUY 에서 null** — 매수는 사유 개념이 없다. `SellReason` enum 의 `name` 을 문자열로 싣는다.

# Key Files

- `bot/src/main/resources/db/migration/V22__add_stock_order_intent_strategy.sql` — 신규. 컬럼 2개 추가
- `bot/src/main/kotlin/com/trading/bot/persistence/entity/StockOrderIntentEntity.kt` — 필드 2개 추가
- `bot/src/main/kotlin/com/trading/bot/kis/order/StockOrderService.kt` — `SubmitOrderCommand` 확장 + WAL INSERT 전달(`:52-90`)
- `bot/src/main/kotlin/com/trading/bot/kis/engine/StockPositionManager.kt` — `submit()` 이 값을 받아 넘김(`:40-100`)
- `bot/src/main/kotlin/com/trading/bot/kis/order/StockOrderReconciler.kt` — `buildExecution` 이 `row.strategy`/`row.reason` 사용(`:197-216`)
- `bot/src/main/kotlin/com/trading/bot/api/KisTradeController.kt` — 수동 주문 진입점(`:65`), `strategy="manual"` 고정
- `bot/src/test/kotlin/com/trading/bot/kis/order/StockOrderReconcilerTest.kt` — Red 테스트
- `wiki/pages/concept/kis-order-lifecycle.md` — 두 소스 파일을 sources 로 가짐 → 갱신 의무

# Blockers

없음.

# Acceptance

- [x] **V22 가 실제 Postgres 에 적용된다** — 컨테이너에 V1~V22 순차 적용, `stock_order_intent` 에 두 컬럼 존재 확인
- [x] **BUY 귀속** — WAL 에 `strategy` 가 박힌 intent 를 FILLED 로 reconcile 하면 `trade_executions.strategy` 가 그 값과 일치. 검증: `StockOrderReconcilerTest` 통과
- [x] **SELL 귀속** — `strategy` = 진입 전략, `reason` = `SellReason.name` 이 체결 기록에 남는다
- [x] **경합 무관성(이 설계의 핵심 이점)** — 값이 WAL 에서만 오므로 포지션 상태와 무관하다. **구조로 보장**: `StockOrderReconciler` 생성자에 포지션 상태 리포지토리가 아예 없어 조회 자체가 불가능하다(별도 fixture 보다 강한 증거)
- [x] **Red 가 vacuous 하지 않다** — fixture 가 `strategy` 를 명시적 non-null 로 세우고, 수정 전 테스트가 `expected "rsi_bounce" but was null` 로 실패함을 눈으로 확인
- [x] **수동 주문 구분** — `KisTradeController` 로 낸 주문의 체결 기록은 `strategy = "manual"` 로 남아 엔진 체결과 구분된다
- [x] **문서 동기화** — `wiki/pages/concept/kis-order-lifecycle.md` 갱신(WAL 이 무엇을 싣는지), `wiki/index.md` 는 설명 변경 시에만
- [x] **검증 통과** — `./gradlew compileKotlin` + `./gradlew test`
- [x] **백필 불필요 실측** — 운영 DB 실측: KIS 체결 행 0건, `stock_order_intent` 0행, 비terminal 0건. 추정이 아니라 관측으로 확정됐다

# Review Disposition

| finding | 처분 | 근거 |
|---|---|---|
| **자체리뷰 1** 수동 매도에 `reason` 미기록 — Upbit(`SellReason.MANUAL`)과 불일치 | **fix** | `KisTradeController` 에서 `side == SELL` 일 때 `MANUAL` 을 싣도록 수정. 집계에서 두 거래소가 갈리면 안 된다 |
| **자체리뷰 2** WAL 행이 command 값을 담는지 미검증 | **fix** | `StockOrderServiceTest` 에 추가. command 까지만 실려서는 reconcile 이 읽을 수 없어 실제 결함이 통과할 수 있었다 |
| **자체리뷰 3** `"manual"` 리터럴이 5곳으로 늘어남 | **defer** | 상수 추출은 이번 변경 범위 밖(§3-4). `TradeRoundTrip.kt:45` 의 `MANUAL_STRATEGY` 가 private 이라 공용화가 별도 작업이다. `# Deferred` 기록 |
| **자체리뷰 4** `buildExecution` 이 `pnl*` 도 안 채움 | **defer** | 사용자가 범위에서 제외 결정. `# Deferred` 기록 |

# Deferred

- **`buildExecution` 이 `pnlPercent`/`pnlAmount` 도 안 채운다** — Upbit 은 V21 에서 `pnl_amount` 를 채웠으나 KIS 는 공백. 매수평단·부분체결·수수료 입력 설계가 필요해 이번 범위에서 뺐다(사용자 결정). 심각도: 중(전략별 *금액* 손익 집계가 KIS 에서 불가). 파일: `StockOrderReconciler.kt:197-216`

- **`"manual"` 리터럴이 5곳** — `TradeRoundTrip.kt:45`(private `MANUAL_STRATEGY`), `ManualTradeController.kt:41,74,87`, 이번에 추가한 `KisTradeController`. 공용 상수로 올릴 후보. 심각도: 낮음(오타 시 집계가 조용히 갈릴 수 있음)
- **CLAUDE.md 의 검증 명령 서술이 stale** — "이 repo 의 검증 명령은 `.github/workflows/lint.yml`" 이라고 적혀 있으나 그 파일은 없다. 실제 CI 게이트는 `deploy.yml:27` 의 `./gradlew test --parallel`. CLAUDE.md 는 운영 자산이라 자가수정하지 않았다(§1) — 승인 시 별도 작업

# Workflow Findings

(없음)
