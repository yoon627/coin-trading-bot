---
title: peak-flush-retry — peakPrice flush 1회 실패가 재시도되지 않는 문제 (#54)
status: done
started: 2026-08-20
updated: 2026-08-20
---

# Goal

신고점 durable flush 가 실패하면 **다음 tick 에서 재시도**하게 한다. 지금은 갱신 tick 에만 flush 하는데(write 증폭 회피) `persistState` 가 best-effort 라, 고점 직후 1회 실패하고 하락 전환되면 다시 갱신될 일이 없어 재시작 시 낮은 옛 peak 이 복원된다 → 이미 발동했어야 할 트레일링 스톱이 안 걸린다.

main 은 peak 영속 자체가 없었으므로 회귀가 아니라 **PR #50 개선의 미완**이다.

# Progress

- 2026-08-20: Explore — `TradingEngine.kt:264` 가 `updatePeakPrice()` true 일 때만 `persistState`, 그 `persist` 는 warn 만 남기고 삼킨다. 선례로 `pendingPersistFailed`(비영속 dirty + `retryPendingPersistIfNeeded`)가 이미 있어 같은 형태를 택했다.
- 2026-08-22: PR #100 머지(main `032e49a`). pre-push codex 가 P3 2건·P2 1건을 추가 검출해 전량 반영, 659 tests green.
- 2026-08-20: TDD Red(3 테스트) → 구현 → Green. 기존 테스트 2건이 `persistState` 호출을 검증하고 있어 `persistPeak` 으로 갱신했고, 재시도 경로 테스트를 추가했다. 654 tests green.

# Next

없음 — PR [#100](https://github.com/yoon627/coin-trading-bot/pull/100) 머지(main `032e49a`), 이슈 #54 종결.

# Decisions

## 비영속 dirty 플래그 (`pendingPersistFailed` 와 동형)

`peakPersistFailed` 는 DB 에 저장하지 않는다. 재시작하면 peak 자체가 durable 에서 복원되고, 이 플래그는 **런타임 재시도 신호**일 뿐이다. `pendingPersistFailed` 도 같은 이유로 비영속이다.

## 매수 게이트는 걸지 않는다

`pendingPersistFailed` 는 신규 진입을 막는다 — pending 주문이 유실되면 크래시 시 주문을 잃기 때문이다. peak 은 다르다: 고점 유실은 **청산 정확도** 문제이지 주문 유실 위험이 아니다. 매수까지 막으면 장애 시 과잉 차단이 된다(이슈 본문도 이를 명시).

## flush 지점을 pending reconcile **앞**으로, 재시도와 갱신을 **한 곳**에서

```kotlin
if (state.position) {
    val newHigh = state.updatePeakPrice(currentPrice)
    if (newHigh || state.peakPersistFailed) positionManager.persistPeak(state)
}
```

- **앞에 둔 이유**: 원래 flush 지점(pending 분기 뒤)에 재시도를 두면, 매수·매도 미해소 tick 이 `return` 해버려 미해소가 길어지는 동안 dirty 가 안 풀린다. (구현 중 직접 발견)
- **한 곳에 합친 이유**: 재시도와 갱신 flush 를 분리하면 dirty 상태에서 신고점이 나온 tick 에 upsert 가 2회 난다(codex P2-b).
- `updatePeakPrice` 를 **먼저 평가**해야 가격 갱신이 dirty 여부와 무관하게 일어난다(short-circuit 순서).

# Key Files

- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt` — `peakPersistFailed` 비영속 플래그
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `persistPeak()` 신설
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:261-268` — flush 지점
- `bot/src/test/.../PositionManagerExtendedTest.kt`, `TradingEngineTest.kt` — 계약 고정

# Blockers

(없음)

# Acceptance

- [x] **실패 표시**: upsert 실패 시 `peakPersistFailed = true` — 테스트
- [x] **성공 시 해제**: 재시도가 성공하면 flag 해제 + upsert 1회 — 테스트
- [x] **매수 차단 없음**: peak flush 실패가 `pendingPersistFailed` 를 건드리지 않음 — 테스트
- [x] **재시도 발화**: 신고점이 아닌 tick 에서도 dirty 면 `persistPeak` 호출 — 테스트
- [x] **write 증폭 유지**: 갱신 없고 dirty 도 없으면 flush 0회 — 기존 테스트 갱신해 유지
- [x] **빌드·테스트**: JDK 21 `./gradlew build` — 654 tests, 0 failures
- [x] **리뷰**: codex code-review(high) P0/P1 0 · P2 2 · P3 1 전량 처분
- [x] **중복 upsert 없음**: dirty + 신고점 tick 에서 `persistPeak` 정확히 1회 — 테스트
- [x] **일반 persist 가 dirty 해제**: 같은 스냅샷이 저장됐으면 재시도 불필요 — 테스트
- [x] **매수 게이트 실검증**: `buy()` 를 실제로 호출해 진입이 막히지 않음을 확인(P3 반영)

# Review Disposition

codex code-review (2026-08-20, effort=high) — **P0/P1 0** / P2 2 / P3 1, 미해결 0.

| # | finding | 처분 |
|---|---|---|
| P2-a | 일반 `persist()` 성공이 `peakPersistFailed` 를 해제하지 않아, 다른 경로로 저장된 뒤에도 매 tick 재시도가 돌고 `markSold()` 후에도 dirty 가 남는다 | **fix** — `persist()` 의 upsert 성공 직후 해제. 같은 스냅샷이 저장됐으면 peak 도 durable 이다 |
| P2-b | dirty 상태에서 신고점이 나오면 같은 tick 에 upsert 2회. 지속 장애 시 backoff 없음 | **fix(전자)** — 재시도와 갱신 flush 를 한 지점으로 합쳐 1회로. **backoff 는 defer** — DB 장애 시 매 tick 실패 로그는 `persistPending` 등 기존 경로도 동일해 이 변경만의 문제가 아니다(아래 Deferred) |
| P3 | "매수 차단 없음" 테스트가 `pendingPersistFailed` 만 확인해 실제 게이트를 검증 못 함 | **fix** — `buy()` 를 실제로 호출해 진입이 성공하는지 확인 |

codex 가 함께 확인해 준 것: `peakPersistFailed` 는 DB 매핑에 없어 재시작 시 `false` 로 초기화됨 ✅ / `runLoop` 가 순차라 flag 동시 접근 없음 ✅ / KIS 는 `StockPosition.durableDirty` + `finally` 재시도로 같은 결함 없음 ✅.

## pre-push codex review (2026-08-22) — P3 1건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P3(1차) | `persist()` 에만 해제를 넣어 `persistPending`·`persistStateOrThrow` 성공 시 dirty 가 남는다 | **fix** — 개별 호출자마다 넣는 대신 **`upsertState()` 단일 통로**를 만들어 모든 성공 경로가 함께 해소하게 했다 |
| P3(2차) | 체결 커밋(`commitFillAndApply`)은 `state.copy()` 를 저장해 원본 dirty 가 남는다. **매도 후에는 `position=false` 라 재시도 경로조차 못 타 영영 남는다** | **fix** — 커밋 성공 후 원본 flag 해제. 1차 수정에서 "복사본이라 대상 아님" 이라 판단했던 것이 틀렸다 — 저장되는 스냅샷에 peak 이 들어가므로 durable 은 최신이다 |

# Deferred

- **durable 쓰기 실패의 backoff·로그 rate-limit**(codex P2-b 잔여): DB 장애가 길어지면 `persistPeak`·`persistPending` 등이 매 tick 재시도하며 warn 을 남긴다. 이번 변경만의 문제가 아니라 durable 쓰기 경로 공통 사안이라 별도로 다룬다.

## pre-push codex review (2026-08-22) — P3 1건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P3(1차) | `persist()` 에만 해제를 넣어 `persistPending`·`persistStateOrThrow` 성공 시 dirty 가 남는다 | **fix** — 개별 호출자마다 넣는 대신 **`upsertState()` 단일 통로**를 만들어 모든 성공 경로가 함께 해소하게 했다 |
| P3(2차) | 체결 커밋(`commitFillAndApply`)은 `state.copy()` 를 저장해 원본 dirty 가 남는다. **매도 후에는 `position=false` 라 재시도 경로조차 못 타 영영 남는다** | **fix** — 커밋 성공 후 원본 flag 해제. 1차 수정에서 "복사본이라 대상 아님" 이라 판단했던 것이 틀렸다 — 저장되는 스냅샷에 peak 이 들어가므로 durable 은 최신이다 |

# Deferred

(없음)
