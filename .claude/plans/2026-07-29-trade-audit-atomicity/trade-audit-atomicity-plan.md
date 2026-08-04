---
title: trade-audit-atomicity — pending 해소와 감사 기록의 원자화 (#52)
status: done
started: 2026-07-29
updated: 2026-08-04
---

# Goal

체결로 pending 이 해소될 때 `trading_states` durable 커밋과 감사 기록(`trade_executions`/`trade_records`) 저장을 **한 트랜잭션**으로 묶어, 감사 저장이 실패해도 재시도 근거(pending)가 남게 한다. Discord 알림은 커밋 후로 분리한다. Closes #52.

# Progress

- 2026-07-29: worktree 생성. Explore 완료 — `TransactionalOperator` 가 이미 배선돼 있고(`PersistenceConfig`, R2DBC suspend 라 `@Transactional` 대신) `saveAndNotify` 도 이미 `trade_records`+`trade_executions` 를 한 트랜잭션으로 묶는다. **누락된 것은 `trading_states` upsert 만 그 트랜잭션 밖이라는 점.** 사용자 결정: 1안(같은 트랜잭션).
- 2026-07-30: `c37591a` 구현 커밋 생성. `PositionManager` 의 4개 체결 경로를 staged `TradingState` + audit 저장 트랜잭션으로 원자화하고, 커밋 성공 후에만 원본 메모리 상태와 알림을 적용하도록 배선했다. 커밋 메시지 기준 `compileKotlin`·`compileTestKotlin`은 통과했으며 테스트 스위트는 미실행이다.
- 2026-08-01: Codex가 기존 worktree를 재개했다. 작업 전 `git status --short`는 plan 파일만 modified였고 HEAD는 `c37591a`다. `origin/main`은 별도 후속 커밋 `2d125f8`(`#76`)까지 진행되어 현재 브랜치보다 앞서며, `gh` API 연결 실패로 PR 및 사람 리뷰 intake는 확인하지 못했다. 저장소에 `plan-lint.js`는 없다.
- 2026-08-01: JDK 21과 Gradle 8.12로 관련 테스트를 실행했다. `TradeAuditAtomicityTest`는 4개 중 1개가 `stagedStates`의 사전 pending 저장까지 포함하지 않은 기대값(`1`, 실제 `2`) 때문에 실패했다. 통합 지정 실행은 `TradingEngineTest`의 기존 `saveAndNotify` 대기 테스트가 현재 구조에서 호출되지 않아 hang 되었고, thread dump로 `TradingEngineTest.kt:135`의 stale 계약임을 확인한 뒤 중단했다.
- 2026-08-01: stale 테스트 계약과 callback 순서를 수정한 뒤 관련 4개 테스트, 전체 `test`, `compileKotlin`, wiki 검증 3종이 JDK 21에서 통과했다. 구현 후 Codex 재리뷰는 원자 커밋 순서는 타당하다고 확인했지만 실제 `TradeExecutionService` 배선 및 `CancellationException` 전파를 테스트로 고정하라고 Major finding을 남겼다.
- 2026-08-01: `UserTradingManager.createEngine`의 실제 `TradeExecutionService` 배선 테스트와 취소 전파 assertion을 추가했다. 관련 5개 테스트(`TradeAuditAtomicityTest`, `PositionManagerExtendedTest`, `TradingEngineTest`, `TradeExecutionServiceTest`, `UserTradingManagerTest`), 전체 `test`, `compileKotlin`이 JDK 21에서 다시 통과했다.
- 2026-08-01: simplify 점검에서 `commitFillAndApply` 공통화와 callback 실패 격리·취소 재전파의 필요성을 확인했다. `wiki/verify.sh`, `wiki/smoke.sh`(10/10), link check가 최종 문서 상태에서 모두 통과했다.
- 2026-08-02: 사용자가 변경사항 커밋과 PR 생성을 승인했다. merge는 사용자가 직접 진행한다.
- 2026-08-02: 검증된 코드·테스트·문서·plan 변경을 `065eb15` (`fix(engine): 체결 감사 커밋 원자성 보장 (#52)`)로 커밋했다. 현재 작업 브랜치는 clean이다.
- 2026-08-02: pre-push Codex review가 P2를 발견해 push가 차단됐다. 공통 `notifyTrade`가 수동 주문의 `recorded=false` 후처리 계약까지 삼키는 경로이며, 엔진에서는 `PositionManager`가 이미 알림 실패를 격리한다. 수동 경로 회귀 테스트를 추가하고 `notifyTrade` 예외 전파를 복원한다.
- 2026-08-02: 새 수동 알림 실패 테스트가 기존 코드에서 Red임을 확인한 뒤, `notifyTrade`의 Discord 예외 전파와 `recordOrder`의 `recorded=false` 계약을 복원했다. 관련 테스트·전체 `test`·`compileKotlin`이 JDK 21에서 통과했다.
- 2026-08-02: P2 fix를 `b80e1f2`로 커밋하고 pre-push Codex review `OK` 후 `origin/trade-audit-atomicity`에 push했다. PR [#78](https://github.com/yoon627/coin-trading-bot/pull/78)을 생성했으며 merge는 사용자 대기 상태다.
- 2026-08-02: PR #78이 squash merge 됐다(main `2e7a01c`). 당시 worktree 충돌로 `--delete-branch`의 정리 단계가 실패해 브랜치가 남았다.
- 2026-08-04: 잔재 정리 — 브랜치 코드가 main 과 동일함을 확인(diff 는 plan·wiki 문서뿐이며 모두 main 이 더 최신)한 뒤 로컬·원격 `trade-audit-atomicity`를 삭제했다(삭제 tip `91643e1`, 복구 가능). status 를 `done`으로 정정.

# Next

없음 — PR #78 머지 완료, 브랜치 정리 완료.

# Decisions

- **1안(같은 트랜잭션) 채택** (사용자 결정). 근거: `TransactionalOperator` 가 이미 있어 **인프라 추가가 0**이고, 유실 경로를 사후 복구가 아니라 원천 제거한다. outbox(2안)는 신규 테이블·워커라 이 이슈 대비 과하고, 보상 경로(3안)는 "되살린 pending" 의 의미론이 정상 pending 과 구분되지 않아 상태 해석이 복잡해진다.
- **트랜잭션 안에 외부 API 호출을 넣지 않는다**: `PositionManager` 호출 전체를 트랜잭션으로 감싸면(가장 적게 고치는 방법) `placeOrder`·`getOrder`·`getAccounts` 왕복 동안 DB 커넥션을 점유한다. 따라서 **체결이 확정돼 record 가 만들어진 뒤**의 DB 쓰기 두 건만 묶는다.
- **알림은 커밋 후**: Discord 는 롤백할 수 없다. 커밋 성공 후 발송하며, 알림 실패가 거래 기록을 되돌리지 않는다(로그만).
- **멱등은 유지**: `exchangeOrderId` + `uq_trade_executions_order_id` 기반 skip 은 그대로 둔다. 트랜잭션이 롤백돼 pending 이 살아남으면 다음 tick reconcile 이 같은 주문을 다시 처리하는데, 그때 중복 insert 를 막는 것이 이 멱등 키다.
- **후속 검증은 책임 경계에 맞춘다**: `TradingEngine`은 체결 기록을 직접 저장하지 않고 `PositionManager`의 `commitFill` 배선이 원자 커밋과 커밋 후 알림을 담당한다. 따라서 기존 `TradingEngineTest`의 `saveAndNotify` 대기는 stale 계약으로 보고, 현재 소유자인 commit 경로를 검증한다(실패 실행·thread dump 근거).

## 설계 후보 (plan-review 대상)

대상 지점은 **체결 확정 후 record 가 생기는 3곳**: `completeBuy`(매수 체결·reconcile 공용), `sell()` 의 done 즉시경로, `applySellFillOutcome` 의 `executed>0` 경로.

| | 방식 | 장점 | 위험 |
|---|---|---|---|
| **A** | record 를 반환하는 경로에서 `persist` 를 빼고, 호출자(`TradingEngine`)가 트랜잭션 안에서 `persistState` + audit save | 트랜잭션 범위가 DB 쓰기 2건으로 최소 | **persist 누락 회귀** — 어느 경로를 빠뜨리면 상태가 durable 에 안 남는다. 테스트로 방어 필요 |
| **B** | `PositionManager` 에 `TradeExecutionService` 를 주입해 내부에서 함께 커밋 | 호출자 변경 없음 | 레이어 역전(포지션 관리가 감사·알림을 앎), 순환 의존 위험 |
| **C** | 반환 타입을 `TradeRecord?` → 명시적 커밋 오브젝트로 바꿔 "아직 persist 안 됨"을 타입으로 표현 | A 의 암묵 계약을 명시화 | 반환 타입 변경이 호출부·테스트 전반에 파급 |

최종 결정: **PositionManager 내부의 staged 전이 + callback 경계**. `PositionManager`는 상태 사본과 원본 적용 순서를 소유하고, 프로덕션 callback은 `UserTradingManager`가 `TradeExecutionService.commitFill`과 `notifyTrade`로 배선한다. 테스트는 기본 callback과 실제 서비스 배선을 모두 검증한다.

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
val record = TradeRecord(...)                        // 전이 전에 계산된 값들로 구성 (이미 그렇다)

val recorded = commitFill(
    { tradingStateService.upsert(userId, state.copy().also(applyTransition)) },
    record,
)                                                    // DB 상태 + 감사 기록 커밋
applyTransition(state)                                // 커밋 성공 후에만 메모리 반영
if (recorded) notifyTrade(record)                     // 커밋 후 알림
```

- **실패 시 원본 `state` 는 건드려지지 않았다** → pending 이 살아 있고 다음 tick reconcile 이 재시도한다. 되돌리는 코드가 없으므로 필드 누락 위험도 없다(`markBought` 11개 필드 + `markSold` 의 `clearEntryMeta`/`clearPendingSell` 을 일일이 복원하는 2안의 약점 회피).
- **전이를 람다로 한 번만 정의**해 staged·원본에 각각 적용한다. `now` 를 고정 인자로 넘겨 두 적용이 결정론적으로 동일하다(`markBought` 는 `now` 파라미터를 받는다).
- 전이가 현재 state 에 의존하는 부분(`resuming = position`, 추가매수 평균단가)도 staged 와 원본의 출발점이 같으므로 결과가 같다.

## 커밋과 알림의 배선

`PositionManager`는 `commitFill`(DB transaction)과 `notifyTrade`(post-commit) callback을 주입받고, `UserTradingManager`가 두 callback을 `TradeExecutionService`에 연결한다. `PositionManager`는 DB 트랜잭션 성공 뒤 원본 메모리 상태를 적용하고 그 다음 알림을 호출한다. 이 경계는 실제 서비스 객체를 연결한 회귀 테스트로 고정한다.

- 레이어 역전이 아니다 — 둘 다 `engine` 패키지 동료이고, `PositionManager`는 이미 `tradingStateService`(persistence)를 안다.
- **순환 의존 없음**: `TradeExecutionService` 생성자는 repository·notifier·transactionalOperator·tradingProperties 뿐이라 `PositionManager`를 모른다(확인함).
- 대안(호출자인 `TradingEngine`이 트랜잭션을 열기)은 전이 로직이 `PositionManager` 안에 있어 staged를 만들 수 없다.

## 알림 분리

엔진 경로는 `TradeExecutionService.commitFill`(상태 upsert + audit 저장)과 `notifyTrade`(커밋 후 외부 IO)로 나눈다. 수동 주문의 `saveAndNotify`는 기존 책임을 유지한다. Discord는 롤백 불가이므로 커밋 성공 후에만 발송하고, 알림 실패가 거래 기록을 되돌리지 않는다. 멱등 skip(`existsByUserIdAndExchangeOrderId`)은 **저장 쪽**에 남긴다 — skip이면 후속 알림도 보내지 않아 재시도 중복을 막는다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `commitFillAndApply:297`, 매수·매도·reconcile 체결 경로의 staged 커밋/메모리 적용/후속 알림 공통화
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — 체결 게이트·주문 루프만 조정하며 기록 저장은 하지 않음
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — `createEngine:319`에서 실제 commit/notify callback 배선
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — `commitFill:241` DB 원자 커밋, `notifyTrade:208` 커밋 후 알림
- `bot/src/main/kotlin/com/trading/bot/config/PersistenceConfig.kt` — `TransactionalOperator` 빈
- 테스트: `TradeAuditAtomicityTest`, `TradeExecutionServiceTest`, `UserTradingManagerTest`, `PositionManagerExtendedTest`, `TradingEngineTest`

# Acceptance

- [x] 감사 저장 실패 시 pending이 살아남고 정상 재시도 근거가 유지되는 회귀 테스트를 추가했고, `TradeAuditAtomicityTest`에서 통과를 확인했다. 기존 커밋을 보존하기 위해 이전 구현으로 reset해 Red를 재현하지는 않았다.
- [x] 매수 체결·매도 체결·reconcile 경로의 상태 전이와 audit 원자성을 `TradeAuditAtomicityTest` 및 기존 `PositionManagerExtendedTest`로 검증했다.
- [x] **알림 실패는 롤백하지 않는다**: 실제 `UserTradingManager.createEngine` 배선과 `TradeExecutionService.notifyTrade` 실패 격리에서 커밋 후 메모리 전이를 검증한다.
- [x] 트랜잭션 안에 외부 API 호출이 없음 — 구현 확인 및 Codex 리뷰에서 확인했다.
- [x] 기존 테스트 전부 green — JDK 21 `./gradlew test` 통과.
- [x] `./gradlew compileKotlin` 통과 — JDK 21.
- [x] 문서 동기화 — `wiki/pages/concept/trading-engine-loop.md`에 원자 커밋 동작을 반영하고 wiki `verify.sh`, `smoke.sh`, link check 3종 통과.
- [x] `TradingEngine.kt`의 이전 감사 저장 책임 주석을 현재 PositionManager callback 책임으로 갱신했다.

# Review Disposition

- `fix`: 첫 코드 리뷰의 Major — 알림 실패 시 메모리 전이가 누락될 수 있던 순서를 `commitFill` → 메모리 적용 → `notifyTrade`로 분리했고, 알림 실패 격리 테스트를 추가했다.
- `fix`: 재리뷰의 Major — 실제 `UserTradingManager.createEngine`의 `TradeExecutionService` callback 배선과 `CancellationException` 호출자 전파 assertion을 추가했고 관련 테스트가 통과했다.
- `fix`: pre-push review의 P2 — 엔진 알림 실패 격리를 위해 `notifyTrade`가 수동 `recordOrder`의 `recorded=false` 계약을 깨던 문제를 수동 알림 실패 회귀 테스트와 예외 전파로 수정했고, 관련 테스트가 통과했다.

# Deferred

- `defer`: ⚠️ `TradingStateService`의 read-then-write upsert 동시 충돌 가능성은 별도 범위이며 현재 직접 재현되지 않았다 (`bot/src/main/kotlin/com/trading/bot/persistence/TradingStateService.kt`).

# Blockers

(없음)
