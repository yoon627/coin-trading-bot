---
title: trade-audit-atomicity — pending 해소와 감사 기록의 원자화 (#52)
status: in_progress
started: 2026-07-29
updated: 2026-07-29
---

# Goal

체결로 pending 이 해소될 때 `trading_states` durable 커밋과 감사 기록(`trade_executions`/`trade_records`) 저장을 **한 트랜잭션**으로 묶어, 감사 저장이 실패해도 재시도 근거(pending)가 남게 한다. Discord 알림은 커밋 후로 분리한다. Closes #52.

# Progress

- 2026-07-29: worktree 생성. Explore 완료 — `TransactionalOperator` 가 이미 배선돼 있고(`PersistenceConfig`, R2DBC suspend 라 `@Transactional` 대신) `saveAndNotify` 도 이미 `trade_records`+`trade_executions` 를 한 트랜잭션으로 묶는다. **누락된 것은 `trading_states` upsert 만 그 트랜잭션 밖이라는 점.** 사용자 결정: 1안(같은 트랜잭션).

# Next

체결 경로별 `persist`↔record 반환 지점을 확정하고 설계 후보 중 택1 → plan-review(codex) → TDD Red → 구현.

# Decisions

- **1안(같은 트랜잭션) 채택** (사용자 결정). 근거: `TransactionalOperator` 가 이미 있어 **인프라 추가가 0**이고, 유실 경로를 사후 복구가 아니라 원천 제거한다. outbox(2안)는 신규 테이블·워커라 이 이슈 대비 과하고, 보상 경로(3안)는 "되살린 pending" 의 의미론이 정상 pending 과 구분되지 않아 상태 해석이 복잡해진다.
- **트랜잭션 안에 외부 API 호출을 넣지 않는다**: `PositionManager` 호출 전체를 트랜잭션으로 감싸면(가장 적게 고치는 방법) `placeOrder`·`getOrder`·`getAccounts` 왕복 동안 DB 커넥션을 점유한다. 따라서 **체결이 확정돼 record 가 만들어진 뒤**의 DB 쓰기 두 건만 묶는다.
- **알림은 커밋 후**: Discord 는 롤백할 수 없다. 커밋 성공 후 발송하며, 알림 실패가 거래 기록을 되돌리지 않는다(로그만).
- **멱등은 유지**: `exchangeOrderId` + `uq_trade_executions_order_id` 기반 skip 은 그대로 둔다. 트랜잭션이 롤백돼 pending 이 살아남으면 다음 tick reconcile 이 같은 주문을 다시 처리하는데, 그때 중복 insert 를 막는 것이 이 멱등 키다.

## 설계 후보 (plan-review 대상)

대상 지점은 **체결 확정 후 record 가 생기는 3곳**: `completeBuy`(매수 체결·reconcile 공용), `sell()` 의 done 즉시경로, `applySellFillOutcome` 의 `executed>0` 경로.

| | 방식 | 장점 | 위험 |
|---|---|---|---|
| **A** | record 를 반환하는 경로에서 `persist` 를 빼고, 호출자(`TradingEngine`)가 트랜잭션 안에서 `persistState` + audit save | 트랜잭션 범위가 DB 쓰기 2건으로 최소 | **persist 누락 회귀** — 어느 경로를 빠뜨리면 상태가 durable 에 안 남는다. 테스트로 방어 필요 |
| **B** | `PositionManager` 에 `TradeExecutionService` 를 주입해 내부에서 함께 커밋 | 호출자 변경 없음 | 레이어 역전(포지션 관리가 감사·알림을 앎), 순환 의존 위험 |
| **C** | 반환 타입을 `TradeRecord?` → 명시적 커밋 오브젝트로 바꿔 "아직 persist 안 됨"을 타입으로 표현 | A 의 암묵 계약을 명시화 | 반환 타입 변경이 호출부·테스트 전반에 파급 |

현재 기울기: **A + 테스트 방어**. 단 "record 반환 = persist 미완료"가 암묵 계약이 되는 점이 A 의 약점이라 plan-review 에서 C 와 비교한다.

## ⚠️ 트랜잭션만으로는 부족하다 — 메모리 상태 롤백

`state.markBought(...)`/`markSold()` 는 **메모리 객체**를 변경하고, `TradingEngine.states`(`ConcurrentHashMap`)가 그 객체를 tick 간 유지한다. 따라서 DB 트랜잭션이 롤백돼도:

- DB: pending 이 살아남음 ✅
- **메모리 `TradingState`: 이미 pending 이 clear 된 상태** ❌

다음 tick 은 메모리 state 를 보고 판단하므로 `pendingBuyUuid == null` → reconcile 하지 않는다. **유실이 그대로 재현된다.** (재시작하면 DB 에서 복원돼 회복되지만, 그건 "재시작해야만 낫는다"는 뜻이라 해결이 아니다.)

따라서 원자화 단위는 **DB 트랜잭션 + 메모리 상태**여야 한다. 후보:

1. **전이를 트랜잭션 성공 후에 적용** — 체결 정보로 record 를 만들되 `markBought`/`markSold` 는 커밋 성공 뒤에 실행. 가장 깔끔하지만 `completeBuy` 가 전이 결과(평단·수량)로 record 를 만들므로 순서 재배치가 필요.
2. **롤백 시 메모리 되돌리기** — 전이 전 스냅샷을 떠두고 실패 시 복원. 되돌릴 필드가 많아(pendingBuyUuid·pendingBuyStrategy·avgBuyPrice·holdVolume·position·exitParams·reconcileFailureCount) 누락 위험.
3. **메모리 state 를 DB 에서 재로딩** — 실패 시 `tradingStateService.load` 로 덮어쓴다. 단순하지만 실패 경로에서 DB 재조회가 또 실패할 수 있다.

이 선택이 이번 작업의 핵심 설계점이다.

## 확정 설계 — "커밋 후 적용"(rollback-free)

위 3안 중 어느 것도 택하지 않고 **롤백 자체를 없앤다.** `TradingState` 가 `data class` 라 `copy()` 가 가능한 점을 이용한다.

```kotlin
val now = LocalDateTime.now(TradingDay.KST)
val applyTransition: (TradingState) -> Unit = { s ->
    s.markBought(fillPrice, volume, strategy, replace = true, now = now)
    s.exitParams = s.exitParams ?: snapshotExitParams()
}
val staged = state.copy().also(applyTransition)     // DB 에 쓸 전이본
val record = TradeRecord(...)                        // 전이 전에 계산된 값들로 구성 (이미 그렇다)

transactionalOperator.transactional(mono {
    tradingStateService.upsert(userId, staged)       // pending 해소
    tradeExecutionService.saveAudit(record)          // 감사 기록
}).awaitSingleOrNull()

applyTransition(state)                               // 커밋 성공 후에만 메모리 반영
```

- **실패 시 원본 `state` 는 건드려지지 않았다** → pending 이 살아 있고 다음 tick reconcile 이 재시도한다. 되돌리는 코드가 없으므로 필드 누락 위험도 없다(`markBought` 11개 필드 + `markSold` 의 `clearEntryMeta`/`clearPendingSell` 을 일일이 복원하는 2안의 약점 회피).
- **전이를 람다로 한 번만 정의**해 staged·원본에 각각 적용한다. `now` 를 고정 인자로 넘겨 두 적용이 결정론적으로 동일하다(`markBought` 는 `now` 파라미터를 받는다).
- 전이가 현재 state 에 의존하는 부분(`resuming = position`, 추가매수 평균단가)도 staged 와 원본의 출발점이 같으므로 결과가 같다.

## 트랜잭션을 여는 주체

`PositionManager` 에 `TradeExecutionService` + `TransactionalOperator` 를 주입한다.

- 레이어 역전이 아니다 — 둘 다 `engine` 패키지 동료이고, `PositionManager` 는 이미 `tradingStateService`(persistence)를 안다.
- **순환 의존 없음**: `TradeExecutionService` 생성자는 repository·notifier·transactionalOperator·tradingProperties 뿐이라 `PositionManager` 를 모른다(확인함).
- 대안(호출자인 `TradingEngine` 이 트랜잭션을 열기)은 전이 로직이 `PositionManager` 안에 있어 staged 를 만들 수 없다.

## 알림 분리

`saveAndNotify` 를 **저장(트랜잭션 내)** 과 **알림(커밋 후)** 으로 쪼갠다. Discord 는 롤백 불가이므로 커밋 성공 후에만 발송하고, 알림 실패가 거래 기록을 되돌리지 않는다. 멱등 skip(`existsByUserIdAndExchangeOrderId`)은 **저장 쪽**에 남긴다 — 알림까지 skip 되어야 재시도 시 중복 알림이 없다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `completeBuy:263`(persist), `sell:438`(persist), `applySellFillOutcome`
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — `onTrade:390`(감사 기록), 호출부 `:253 :264 :278 :300`
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — `saveAndNotify:169`(저장+알림 결합 — 분리 대상)
- `bot/src/main/kotlin/com/trading/bot/config/PersistenceConfig.kt` — `TransactionalOperator` 빈
- 테스트: `PositionManagerExtendedTest`(55K), `TradingEngineTest`(28K), `TradeExecutionServiceTest`(14K)

# Acceptance

- [ ] **TDD Red**: 감사 저장이 실패하는 상황에서 **pending 이 살아남아** 다음 tick reconcile 이 재시도함을 검증하는 테스트. 수정 전에는 실패(현재는 pending 이 지워져 유실)해야 하며, 그 실패가 의도한 이유인지 확인
- [ ] 매수 체결·매도 체결·reconcile 3경로 모두에서 상태 전이와 audit 이 원자적 — 각 경로에 테스트
- [ ] **알림 실패는 롤백하지 않는다**: Discord 실패 시에도 거래 기록이 커밋된 채 남는 테스트
- [ ] 트랜잭션 안에 외부 API 호출이 없음 (코드 확인 + 리뷰)
- [ ] 기존 테스트 전부 green — `./gradlew test` (JDK 21 필요, 로컬 기본이 25면 `JAVA_HOME` 지정)
- [ ] `./gradlew compileKotlin` 통과
- [ ] 문서 동기화: `wiki/pages/concept/trading-engine-loop.md` 의 "알려진 갭" 절이 이 수정으로 해소됨을 반영 (해당 페이지가 `TradingEngine.kt` 를 sources 로 가짐) + wiki 검증 3종
- [ ] `TradingEngine.kt:387-389` 주석("원자화는 별도 작업")을 현재 동작에 맞게 갱신

# Blockers

(없음)
