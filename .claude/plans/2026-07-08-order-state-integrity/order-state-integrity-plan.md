---
title: order-state-integrity — 매도·수동주문 경로의 주문 상태 무결성 (미확정 주문 유실/중복 방지)
status: in_progress
started: 2026-07-08
updated: 2026-07-10
---

# Goal

placeOrder 이후 "상태 불명"이 기록 유실·중복 주문으로 이어지는 3개 구멍을 막는다:
매도 미확정 reconcile 부재(phantom-clear 기록 유실), syncPosition 실패 삼킴(이중 포지션), 수동주문 unknown-state 응답(재시도 유도 중복 주문).

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 발견 3건 기반 plan 작성. 구현 미착수.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — TradingEngine.kt 를 Key Files 로 승격(pendingSell reconcile 훅·sync 재시도 게이트가 들어갈 곳), 매도 partial-fill 분기 추가, 수동 매도 2경로 포함, phantom-clear 복원 순서 명문화, engine-lifecycle 과의 머지 순서 고정.

# Next

TDD 로 착수: PositionManager.sell() 미확정 시나리오 재현 테스트 먼저 (awaitFill 실패 스텁 → 다음 tick 체결 확정 → TradeRecord 생성 기대) → Red 확인 → pendingSellUuid + reconcile 구현.

# Decisions

- **H8 매수 패턴(pendingBuyUuid + reconcilePendingBuy)을 매도에 미러링** — `state.pendingSellUuid`(+사유) 보존, **reconcile 호출 훅은 TradingEngine.processTicker 의 매도 평가 이전**(매수 pending 게이트 :180-190 과 같은 위치·순서 원칙). 확정 시 TradeRecord 생성해 onTrade(기록+Discord 알림) 경로 합류. (이유: 검증된 기존 패턴 재사용 — 단, 훅 없이는 "다음 tick 확정"이 실행될 곳이 없음(plan-review major))
- **매도 partial-fill 분기(매수와 의미가 다름)**: state=cancel 이라도 executedVolume>0 이면 체결분 TradeRecord 기록 + 잔여 수량으로 포지션 갱신 + 잔여분 재매도 대상. 단순 "미체결 → 재매도"는 부분 체결분 기록을 또 유실한다. (plan-review major)
- **phantom-clear(markSold) 경로**: pendingSellUuid 있으면 getOrder 응답(price/volume)과 **markSold 이전의 state.avgBuyPrice** 로 기록 복원 후 마감 — 복원에 필요한 값 확보 순서를 구현에서 보장. (이유: 실제 돈이 나간 청산이 감사 추적에서 사라지는 것 방지 — 현재 PositionManager.kt:200 은 TradeRecord 없이 종료)
- **수동주문 응답 의미 분리 — 3경로 전부**: executeBuy(:50→:59 후조회)·executeSellAll(:94→:103 동일 구조)·executeSellVolume(:136 선조회) 및 ManualTradeController 의 buy(:47-49)/sell(:93-95) 실패→400 응답. placeOrder 성공 이후 단계 실패는 order uuid 포함 success(+`recorded: false` 류 경고 필드)로 응답, 기록 실패는 log.error. executeBuy 의 getTicker 는 선조회로 이동(sellVolume 과 통일). (이유: 접수된 주문에 4xx/5xx → 사용자 재시도 → 이중 접수)
- **syncPosition 실패 시 신규 진입만 차단 — 재시도 메커니즘 신설**: 현재 sync 는 runLoop 시작 시 1회뿐(TradingEngine.kt:125-127)이라 "다음 tick 재시도"는 존재하지 않는 경로다(plan-review major). state 에 unsynced 플래그를 두고 processTicker 초입에서 재시도, 성공 시 해소·실패 시 그 tick 매수 평가 skip. 지속 실패는 log.error 승격. 매도 게이트는 기존 유지. (이유: 재시작 직후 429 빈발 구간의 이중 포지션 차단, 행동 변경 최소화)
- **전제**: log.error → Discord 도달은 `DISCORD_ERROR_ALERT_ENABLED=true` 운영 설정 의존(compose 기본 false — ops-safety-net plan 이 실측 확인 항목으로 커버).
- **스코프 경계**: open #19(reconcile halt 상한)·#20(pendingBuyUuid durable 영속화)은 매수 pending 의 기능 확장으로 별도. pendingSellUuid 의 durable 영속화도 #20 해소 시 같은 방식으로 후속(여기선 메모리 보존까지).

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :171-226 processTicker(pendingSell reconcile 훅 + unsynced 재시도 게이트 삽입 지점), :125-127(1회성 sync 호출부)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — :200(phantom-clear), :216-219(미확정 warn 후 유실), :246-249(예외 삼킴), :80-106(H8 매수 미러 대상 패턴), :42-44(sync 실패 삼킴)
- `bot/src/main/kotlin/com/trading/bot/domain/TradingState.kt` — pendingSellUuid·unsynced 필드 추가 지점
- `bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt` — :50-59(executeBuy), :94-103(executeSellAll), :136-147(executeSellVolume), :221-227(saveAndNotify 실패 반환)
- `bot/src/main/kotlin/com/trading/bot/api/ManualTradeController.kt` — :47-49(buy 400), :93-95(sell 400)
- `bot/src/test/kotlin/com/trading/bot/engine/PositionManagerExtendedTest.kt` — 기존 reconcile 테스트 패턴(:458-527) 재사용

# Acceptance

- [ ] 매도 awaitFill 실패(스텁) → 다음 tick reconcile 로 체결 확정 → TradeRecord 생성 + onTrade 호출 단위 테스트 green
- [ ] 매도 partial-fill: cancel+executedVolume>0 → 체결분 기록 + 잔여 포지션 유지 테스트 green
- [ ] phantom-clear 경로: pendingSellUuid 존재 시 기록 복원 후 markSold 테스트 green
- [ ] 수동 buy/sellAll: placeOrder 성공 + 후처리 실패 시 HTTP 2xx + 응답에 order uuid (WebTestClient — WebFlux 이므로 통합 테스트 필수, memory: WebFlux @ExceptionHandler 규약)
- [ ] syncPosition 실패 tick 매수 미평가 + 다음 tick 재시도·해소 테스트 green
- [ ] `./gradlew test` 전체 green (JDK25 로컬이면 JAVA_HOME=jbr-21 prefix — memory 참조)

# Blockers

- (순서) **이 plan 을 engine-lifecycle 보다 먼저 머지** — TradingEngine.kt·PositionManager.kt 3-plan 겹침(engine-lifecycle·test-hardening)의 기준 브랜치. pendingSell 구조 위에 취소 안전성(NonCancellable/CE-rethrow)을 얹는 방향이 반대보다 충돌이 적다(plan-review 권고). test-hardening 의 processTicker seam 설계와 시그니처 협의 필요.
