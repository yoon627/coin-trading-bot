---
title: stuck-sell-alert — 막힌 매도 알림을 재시작에 무관하게 (#55)
status: in_progress
started: 2026-08-22
updated: 2026-08-22
---

# Goal

막힌 pending sell 알림이 **재시작 횟수와 무관하게** 발화하게 한다. 지금은 연속 실패 횟수를 메모리에만 세는데 pending 은 durable 이라 수명이 어긋난다 — 배포·크래시가 반복되면 매번 0부터 세어 임계에 못 닿고, 그 사이 `processTicker` 는 매도·매수 평가를 통째로 막아 보유 포지션이 손절 없이 방치된다.

# Progress

- 2026-08-22: 이슈가 제시한 두 안 중 **경과시간 판정**을 택했다(재시작 무관). V20 으로 `pending_sell_since`·`pending_sell_alerted` 를 durable 화하고 카운터를 제거. 실제 Postgres 16 에 V1~V20 적용해 컬럼 타입·제약 확인. 664 tests green.

# Next

codex 리뷰 반영 → PR.

# Decisions

## 카운터가 아니라 시각을 durable 로

이슈가 제시한 두 안 중 후자. 카운터를 영속화해도 되지만, "몇 번 실패했나"는 tick 주기·재시작에 따라 흔들리는 반면 "언제부터 막혀 있나"는 그렇지 않다. `pending_sell_since` 하나로 판정이 결정된다.

## 임계는 기존 의미를 시간으로 환산

`reconcileHaltThreshold`(기본 20) × `intervalSeconds`(기본 10) = 200초. 새 설정을 만들지 않고 기존 값의 의미(N tick)를 그대로 옮겼다 — 운영자가 이미 아는 축을 유지한다.

## 알림은 pending 하나당 1회, 플래그도 durable

`pending_sell_alerted` 가 비영속이면 재시작마다 같은 pending 을 다시 알린다. durable 로 두면 한 번만 뜬다. `clearPendingSell()` 에서 시각·플래그를 함께 초기화해 다음 pending 이 깨끗한 상태로 시작한다.

## 마이그레이션 이전 pending 의 처리

V20 이전에 시작된 pending 은 `pending_sell_since` 가 NULL 이다. 그 경우 **지금부터 센다**(현재 시각을 기록). 임의로 과거 시각을 넣어 즉시 알리면 배포 직후 스팸이 되고, 무시하면 영영 안 알린다.

# Key Files

- `bot/src/main/resources/db/migration/V20__pending_sell_stuck_alert.sql` — 신규 컬럼 2개
- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt` — `pendingSellSince`·`pendingSellAlerted`, `clearPendingSell()` 초기화
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `warnIfSellStuckTooLong()`, 주문 접수 시 시각 기록
- `bot/src/main/kotlin/com/trading/bot/persistence/{TradingStateService,entity/TradingStateEntity}.kt` — 양방향 매핑

# Blockers

(없음)

# Acceptance

- [x] **마이그레이션 실적용**: Postgres 16 에 V1~V20 순차 적용 성공, 컬럼 타입·NOT NULL·default 확인
- [x] **임계 도달 시 알림**: `pendingSellSince` 가 200초 이전이면 ERROR 1회 — 테스트
- [x] **재시작 무관**: 카운터 없이 durable 시각만으로 판정 — 위 테스트가 카운터를 쓰지 않는다
- [x] **중복 발화 없음**: `pendingSellAlerted=true` 면 재발화 안 함 — 로그 캡처로 확인
- [x] **해소 시 초기화**: `clearPendingSell()` 이 시각·플래그를 함께 비움 — 테스트
- [x] **임계 전 무발화**: 5회 reconcile 로는 알리지 않음 — 기존 테스트 갱신
- [x] **빌드·테스트**: JDK 21 `./gradlew build` — 664 tests, 0 failures
- [x] **리뷰**: codex code-review(high) P0 0 · P1 1 · P2 4 · P3 1 전량 처분
- [x] **임계 경계**: fake clock 으로 199초(무발화)·200초(발화 1회) 검증
- [x] **매핑 왕복**: `upsert` 캡처 + `loadStates` 로 두 방향 고정(`volume_24h` 사고 재발 방지)
- [x] **설정 방어**: `intervalSeconds >= 1` 검증 추가 — 0 이면 임계가 0초가 되어 즉시 발화

# Review Disposition

codex code-review (2026-08-22, effort=high) — **P0 0** / P1 1 / P2 4 / P3 1, 미해결 0.

| # | finding | 처분 |
|---|---|---|
| P1 | `pendingSellAlerted = true` 를 먼저 저장한 뒤 로그를 남겨, 그 사이 크래시·전송 실패 시 재알림이 막혀 알림이 영구 유실된다 | **fix(순서 반전)** — 알린 **뒤에** 표시한다. 중복 알림이 유실보다 낫다. 전송 성공까지 보장하려면 outbox 가 필요해 그건 Deferred |
| P2-a | 임계 테스트가 즉시 5회 호출 후 `false` 만 확인해, 알림 로직을 통째로 지워도 통과 | **fix** — `Clock` 을 주입하고 199초/200초 경계를 검증 |
| P2-b | `Instant.now()` + 300초 여유라 시간 단위 오류·경계·clock 역행을 못 잡음 | **fix** — fixed clock 으로 전환, ERROR 로그 **개수**까지 단언 |
| P2-c | 새 필드의 저장·복원 round-trip 테스트가 없어 컬럼명을 틀려도 통과 | **fix** — `upsert` 캡처(저장) + `loadStates`(복원) 양방향 고정. 이 repo 의 `volume_24h` 매핑 사고 선례를 주석에 남김 |
| P2-d | `intervalSeconds <= 0` 미검증 — 0 이면 임계가 0초라 첫 reconcile 에서 즉시 발화 | **fix** — `TradingProperties.init` 에 `>= 1` 검증(기존 `reconcileHaltThreshold` 선례와 동형) |
| P3 | 문서가 최신 마이그레이션을 V19 로 기록 | **fix** — `README`·`PROJECT_ANALYSIS`·wiki 를 V20 과 두 컬럼까지 갱신(리뷰 전에 이미 처리) |

codex 가 함께 확인해 준 것: `sellReconcileFailureCount` 잔여 참조 없음 ✅ / 정상체결·부분체결·취소·잔고복구·`markSold` 가 `clearPendingSell()` 로 시각·플래그를 초기화 ✅ / `NOT NULL DEFAULT FALSE` 는 PostgreSQL 17 에서 기존 행에 안전 ✅.

# Deferred

- **알림 전송 보장(outbox)**(codex P1 잔여): 지금은 "알린 뒤 표시"라 크래시 시 재알림되지만, Discord 전송 자체가 실패하면(best-effort) 로그만 남고 사람에게 안 닿을 수 있다. delivery 상태를 durable 로 관리하는 outbox 는 이 이슈보다 범위가 크고 `DiscordNotifier` 전체에 걸리는 사안이라 별도로 다룬다.
- **설정 변경 시 기준 이동**(codex P2-d 잔여): 재시작 후 `intervalSeconds` 를 바꾸면 기존 `pendingSellSince` 에 새 임계가 적용된다. pending 생성 시점의 임계를 durable 로 박을지는 열어 둔다 — 현재는 "지금 설정 기준"이 오히려 직관적이다.
