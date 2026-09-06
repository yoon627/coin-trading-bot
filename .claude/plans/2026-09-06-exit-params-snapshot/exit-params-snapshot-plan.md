---
title: exit-params-snapshot — 청산 게이트가 진입 시점 스냅샷을 따르게 한다 (#177)
status: in_progress
started: 2026-09-06
updated: 2026-09-06
---

# Goal

`ExitParamsSnapshot` 은 진입 시점에 기록되고 재시작에도 복원되는데 **청산이 그 값을 읽지 않았다**.
그래서 보유 중 전역 설정을 바꾸면 이미 열린 포지션의 청산 기준까지 즉시 바뀌었다 —
2026-09-06 트레일링 승격에서 실제로 발생했고, 그 거래들은 *진입은 옛 규칙, 청산은 새 규칙*이라 성과 귀속이 깨진다.

# Progress

- 2026-09-06 — worktree 생성(base `main@e26d71f`). 구조 확인: 스냅샷 저장(`markBought` 뒤 `exitParams ?: snapshot`)·
  durable(`exit_params_json`)·복원(`TradingStateService.decodeExitParams`)은 이미 있고 **소비만 없었다**.
  소비 대상 4곳 확정 — `checkTakeProfit`·`checkStopLoss`·`checkTrailingStop`(`PositionManager`),
  `shouldSellForDailyReset`(`DailyResetManager`).
- 2026-09-06 — **TDD**: 실패 테스트 6건 선작성 → Red 확인(스냅샷 소비 4건 실패, 폴백 2건은 이미 통과) → 구현 → Green 6/6.
- 2026-09-06 — `./gradlew build` 실행 990 / skip 19 / 실패 0(회귀 0). wiki `exit-gates` 의
  "아직 소비되지 않는다" 절을 현행으로 교체하고, **옛 동작을 기대하던 smoke 검사 문구도 함께 이전**(변이로 CAUGHT 확인).

# Next

PR·머지·배포 후 운영에서 회귀 없음 확인.

# Decisions

## 1) 스냅샷이 없으면 전역으로 폴백한다

이 변경 이전에 열린 포지션·복원 실패분에는 스냅샷이 없다. 그때 청산을 막거나 임의값을 쓰면 사고다 —
**기존 동작 보존**이 폴백의 유일한 목적이다. 테스트 2건이 이 경로를 가둔다.

## 2) `chartExitEnabled` 는 스냅샷에 넣지 않는다

임계가 아니라 **모드 스위치**다. 진입 시점에 off 였다고 해서 그 포지션만 차트청산을 영영 못 쓰게 하는 것은
운영자가 기대하는 동작이 아니다. 전역이 소유한다.

## 3) KIS 주식 경로는 범위 밖

`StockPositionManager` 도 전역을 읽지만 별도 상태(`StockPositionState`)에 **스냅샷 필드 자체가 없다**.
스키마 추가가 동반되므로 별도 작업으로 남긴다.

## 4) 배포 시 한 번은 기준이 되돌아간다

2026-09-06 승격 **이전**에 열린 포지션은 스냅샷에 옛 값(트레일 2.0/arm 3)을 들고 있다. 이 변경이 배포되면
그 포지션들은 다시 옛 기준으로 판정된다 — **의도한 동작**(진입 시점 규칙)이며, `maxHoldDays=1` 이라
대부분 이미 청산돼 영향은 작다.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — `exitParamsOf()` + 게이트 3종
- `bot/src/main/kotlin/com/trading/bot/engine/DailyResetManager.kt` — 보유상한
- `bot/src/test/kotlin/com/trading/bot/engine/ExitParamsSnapshotConsumptionTest.kt` — 게이트 4종 + 폴백 2종
- `wiki/pages/concept/exit-gates.md` · `wiki/smoke.sh` — 답이 뒤집힌 문서·검사

# Blockers

없음.

# Acceptance

1. ✅ 손절·익절·트레일링·보유상한이 진입 시점 스냅샷을 따른다(테스트 4건).
2. ✅ 스냅샷이 없으면 전역으로 폴백해 기존 동작이 보존된다(테스트 2건).
3. ✅ Red → Green 순서를 지켰다(선작성 6건 중 4건 Red 확인).
4. ✅ 회귀 0 — `./gradlew build` 실행 990 / skip 19 / 실패 0.
5. ✅ wiki `exit-gates` 갱신 + smoke 기대 문구 이전, **변이로 검사 작동 확인**.
6. ⏳ 배포 후 운영 로그에 회귀 없음 확인.

# Deferred

- **KIS 경로(`StockPositionManager`)** — 별도 상태에 스냅샷 필드가 없어 스키마 추가 동반. (중간)
- **성과 귀속 질의** — 2026-09-06 승격~이 배포 사이에 열린 거래는 진입·청산 규칙이 다르다.
  집계에서 그 구간을 어떻게 셀지는 정의되지 않았다. (낮음)
