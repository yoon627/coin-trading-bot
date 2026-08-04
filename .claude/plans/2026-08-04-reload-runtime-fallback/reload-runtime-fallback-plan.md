---
title: reload-runtime-fallback — reloadUserRuntime 폴백이 거짓 성공을 반환하는 문제 (#51)
status: in_progress
started: 2026-08-04
updated: 2026-08-04
---

# Goal

`reloadUserRuntime` 이 durable 상태 로드 실패로 **옛 자격증명 엔진을 되살렸을 때 호출자가 그 사실을 알 수 있게** 한다. 지금은 조용히 `return` 해서 `/api/user/keys` 가 `200 {"status":"saved"}` 를 반환하고, 사용자는 키가 교체됐다고 믿지만 봇은 이전 계정으로 계속 주문한다(webhook 교체 시 옛 webhook 으로 거래내역 유출).

**폴백 동작 자체는 보존한다** — PR #50 이 "stop 된 엔진만 남아 손절이 무기한 중단"을 막으려고 넣은 것이라, 옛 엔진 재기동을 없애면 더 나쁜 결함으로 되돌아간다. 바꾸는 것은 **호출자 계약**뿐이다.

# Progress

- 2026-08-04: Explore 완료. `UserTradingManager.kt:297-309` 의 catch 가 `existing.start(...)` 후 `return@withLock` — 호출자는 성공으로 본다. 호출부는 `TradingController.kt:96`(`/api/user/keys`), `LeaderboardController.kt:119`(`/api/user/settings`) 2곳.
- 2026-08-04: KIS 쪽 `StockUserTradingManager.reloadUserRuntime`(:149)에는 durable 상태 로드가 없어 **같은 결함이 없다** — 범위 밖.
- 2026-08-04: 프론트 `tide-app/api.js:_fetch` 는 `!res.ok` 일 때 body 의 `message`/`error` 를 그대로 throw 해 UI 에 노출한다 → **5xx 를 쓰면 프론트 수정 없이 사용자에게 전달된다**.
- 2026-08-04: 사용자 결정 — ① 실패 시 **503 + 상황 설명 메시지** ② 범위는 **loadStates 폴백만**(다른 조기 return 경로는 유지).
- 2026-08-04: TDD Red(예외 타입 미존재로 컴파일 실패) → 구현 → Green. `RuntimeReloadFailedException` + 컨트롤러 2곳 503 매핑.
- 2026-08-04: WebFlux 통합 테스트에서 body `message` 가 비어 실패 → 원인은 `bindToController` 하네스가 `SafeErrorAttributes` 를 안 쓰기 때문. 상태코드 검증과 문구 계약 검증을 분리해 해결(문구 노출은 기존 `SafeErrorAttributesTest` 가 보장).
- 2026-08-04: codex code-review(high) P0 0 / P1 2 / P2 3 → 전량 처분. **P1-b 가 실질 결함**이었다 — 되살리기 자체가 실패하면 500 이 나가고 봇이 정지된 채 남는데, 그 상황에 "이전 설정으로 거래 중" 문구를 쓰면 정반대 안내가 된다. 589 tests green.

# Next

**PR 생성·머지 대기.** 구현·리뷰·검증 완료(589 tests green, codex P0 0).

# Decisions

## 폴백은 유지, 계약만 바꾼다

옛 엔진 재기동(`existing.start(...)`)은 **그대로 수행한 뒤** 전용 예외를 던진다. 순서가 중요하다 — 먼저 던지면 stop 된 엔진만 남아 PR #50 이 막으려던 결함이 되살아난다.

## 전용 예외 + 컨트롤러 매핑

- `reloadUserRuntime` 이 폴백 시 전용 예외를 throw. 반환값(sealed result)이 아니라 예외를 쓰는 이유: 호출자 2곳이 모두 "실패면 즉시 응답 중단" 이라 반환값을 검사하지 않고 흘리는 실수가 가능한 형태를 피한다.
- 컨트롤러 2곳에서 catch → `ResponseStatusException(SERVICE_UNAVAILABLE, <메시지>)`.
- **메시지가 이 작업의 핵심 산출물**이다. "저장 실패"로 오해하면 사용자가 키를 다시 넣는 헛수고를 한다. 담아야 할 것: ① 키/설정 **저장은 성공** ② 봇 반영 실패 ③ **봇이 이전 자격증명으로 계속 거래 중** ④ 재시도하면 반영된다.
- ⚠️ 전역 `@RestControllerAdvice` 로 매핑하지 않는다 — 이 repo 는 WebFlux 라 advice 에서 throw 하면 500 이 된다(memory `webflux-exception-handler-pattern`). 호출자가 2곳뿐이라 직접 catch 가 단순하고 안전하다.

## 범위

- `UserTradingManager.reloadUserRuntime` 의 `loadStates` catch 경로만.
- 다른 조기 return 3개(`shuttingDown` / `engines[userId]==null` / `user==null`)는 유지 — 앞의 둘은 정상 경로이고 셋째는 극히 드물다(사용자 결정).
- KIS `StockUserTradingManager` 는 해당 결함 없음.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt:286-317` — `reloadUserRuntime`, 폴백은 297-309
- `bot/src/main/kotlin/com/trading/bot/api/TradingController.kt:85-98` — `POST /api/user/keys`
- `bot/src/main/kotlin/com/trading/bot/api/LeaderboardController.kt:105-124` — `POST /api/user/settings`
- `bot/src/test/kotlin/com/trading/bot/engine/UserTradingManagerTest.kt:105-122` — 현재 폴백 동작을 고정한 테스트, 갱신 대상
- `bot/src/main/resources/static/tide-app/api.js:7-30` — `_fetch` 가 `!res.ok` 에서 message 를 throw(프론트 수정 불필요의 근거)

# Blockers

(없음)

# Acceptance

- [x] **폴백 동작 보존**: `loadStates` 실패 시에도 옛 엔진이 `start` 로 재기동되고 `engines[userId]` 가 교체되지 않는다 — 기존 테스트 단언 유지
- [x] **계약 변경**: 같은 상황에서 `reloadUserRuntime` 이 전용 예외를 던진다 — 테스트로 확인
- [x] **순서**: 예외를 던지기 전에 재기동이 이미 끝나 있다 — `coVerify` 로 확인
- [x] **컨트롤러 응답**: `/api/user/keys`·`/api/user/settings` 가 503 + 위 4요소를 담은 메시지를 반환 — WebTestClient 통합 테스트로 확인(WebFlux 는 단위 테스트로 상태코드를 보증하지 못한다, memory `webflux-exception-handler-pattern`)
- [x] **정상 경로 회귀**: 로드가 성공하면 종전대로 200 + 엔진 교체
- [x] **빌드·테스트**: JDK 21 `./gradlew build` — **589 tests, 0 failures**
- [x] **복구 실패 구분**(codex P1-b): `existing.start` 실패 시 `engineRestored=false` + 정지 안내 문구, 원래 원인은 `addSuppressed` 로 보존
- [x] **취소 보존**(codex P2-a): `CancellationException` 은 복구를 시도하지 않고 재전파

# Review Disposition

codex code-review (2026-08-04, effort=high) — P0 0 / P1 2 / P2 3, 미해결 0.

| # | finding | 처분 |
|---|---|---|
| P1-a | `/api/user/kis-keys` 는 `KisClientFactory` 캐시만 무효화 — 실행 중 `KisStockTradingEngine` 은 옛 client 를 계속 보유하고 200 반환. `StockUserTradingManager.reloadUserRuntime` 은 **호출자가 없다** | **defer** — #51 은 Upbit 경로 대상이고, KIS 는 엔진 교체 시 포지션 복원(`restorePositionState`)까지 함께 설계해야 해 범위가 다르다. 아래 `## pre-push codex review (2026-08-04, high) — P1 1건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | 취소 재전파가 폴백 복구를 건너뛴다 — `existing.stop()` 이후 `loadStates` 에서 취소가 나면 정지된 엔진이 남고, durable 상태는 running 이라 손절이 무기한 멈춘다. 이후 reload 도 `wasRunning=false` 로 보아 되살리지 않는다 | **fix** — 복구를 `withContext(NonCancellable)` 로 수행한 뒤 취소를 재전파. **P2-a 를 고치다 내가 만든 회귀**였다(취소를 존중하려다 PR #50 이 막으려던 결함을 되살림). 테스트를 "복구 안 함" → "복구하되 취소는 전파" 로 정정 |

| P1(2차) | 복구 실패 시 정지 엔진이 `engines` 에 남아, 안내대로 누른 `/api/bot/start` 가 `computeIfAbsent` 로 그것을 재사용 → **옛 자격증명·webhook 으로 거래 재개** | **fix** — 복구 실패 시 `engines.remove(userId, existing)`. 안내 문구가 유도하는 행동이 #51 이 고치려던 상황을 만들던 자기모순이었다 |

# Deferred` + 후속 이슈 제안 |
| P1-b | 되살리기(`existing.start`) 자체가 실패하면 원래 예외가 그대로 전파돼 컨트롤러 catch 를 비껴가고(500), 엔진은 **정지된 채** 남는다 | **fix** — `start` 를 try 로 감싸 `engineRestored=false` 로 구분해 던진다. 원인 유실 방지로 `addSuppressed`. 이 상황은 "이전 설정으로 거래 중" 과 정반대라 **별도 문구**(`RELOAD_FAILED_ENGINE_STOPPED_MESSAGE`)를 쓴다 — 사용자가 할 조치가 다르다 |
| P2-a | `catch (e: Exception)` 이 `CancellationException` 을 삼켜, 취소된 요청이 복구 작업을 수행하고 일반 실패로 보고된다 | **fix** — `CancellationException` 을 먼저 잡아 재전파. ⚠️ 첫 수정은 복구까지 건너뛰어 **새 결함을 만들었다**(아래 pre-push P1) |
| P2-b | WebFlux 테스트가 문구를 실제 응답에서 검증하지 않아, 컨트롤러가 다른 reason 을 써도 통과 | **fix(부분)** — `reloadFailureMessage()` 를 컨트롤러가 쓰는 유일한 경로로 만들고 그 분기를 테스트로 고정. body 의 `message` 노출은 `SafeErrorAttributesTest`(`ResponseStatusException reason is exposed as message`)가 이미 보장하므로 중복 검증하지 않는다 |
| P2-c | 폴백 테스트가 성공 경로·재호출·취소를 검증하지 않음 | **fix(부분)** — 취소 경로와 복구 실패 경로를 추가. 성공 경로는 `런타임 교체가 성공하면 종전대로 200` 이 커버. 재호출은 mutex 동작이라 이번 변경과 무관 |

## pre-push codex review (2026-08-04, high) — P1 1건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | 취소 재전파가 폴백 복구를 건너뛴다 — `existing.stop()` 이후 `loadStates` 에서 취소가 나면 정지된 엔진이 남고, durable 상태는 running 이라 손절이 무기한 멈춘다. 이후 reload 도 `wasRunning=false` 로 보아 되살리지 않는다 | **fix** — 복구를 `withContext(NonCancellable)` 로 수행한 뒤 취소를 재전파. **P2-a 를 고치다 내가 만든 회귀**였다(취소를 존중하려다 PR #50 이 막으려던 결함을 되살림). 테스트를 "복구 안 함" → "복구하되 취소는 전파" 로 정정 |

| P1(2차) | 복구 실패 시 정지 엔진이 `engines` 에 남아, 안내대로 누른 `/api/bot/start` 가 `computeIfAbsent` 로 그것을 재사용 → **옛 자격증명·webhook 으로 거래 재개** | **fix** — 복구 실패 시 `engines.remove(userId, existing)`. 안내 문구가 유도하는 행동이 #51 이 고치려던 상황을 만들던 자기모순이었다 |

# Deferred

- **KIS 키 변경이 실행 중 엔진에 반영되지 않는다**(codex P1-a, 범위 밖): `/api/user/kis-keys`(`TradingController.kt:106-128`)가 `kisClientFactory.invalidate(userId)` 만 호출한다. 이미 생성된 `KisStockTradingEngine` 은 자체 `client` 를 들고 있어 새 키가 반영되지 않은 채 200 이 나간다. `StockUserTradingManager.reloadUserRuntime`(:149)이 존재하지만 **호출자가 없고**, 그 구현은 `restorePositionState` 없이 엔진을 교체해 포지션 복원이 빠진다. → 엔진 교체 + 포지션 복원 + 실패 시 503 계약을 함께 설계하는 별도 작업으로 이슈 제안.
