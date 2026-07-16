---
title: order-state-integrity — 매도·수동주문 경로의 주문 상태 무결성 (미확정 주문 유실/중복 방지)
status: in_progress
started: 2026-07-08
updated: 2026-07-17
---

# Goal

placeOrder 이후 "상태 불명"이 기록 유실·중복 주문으로 이어지는 3개 구멍을 막는다:
매도 미확정 reconcile 부재(phantom-clear 기록 유실), syncPosition 실패 삼킴(이중 포지션), 수동주문 unknown-state 응답(재시도 유도 중복 주문).

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 발견 3건 기반 plan 작성. 구현 미착수.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — TradingEngine.kt 를 Key Files 로 승격(pendingSell reconcile 훅·sync 재시도 게이트가 들어갈 곳), 매도 partial-fill 분기 추가, 수동 매도 2경로 포함, phantom-clear 복원 순서 명문화, engine-lifecycle 과의 머지 순서 고정.
- 2026-07-16: 구현 착수(dlc). Explore 로 Key Files 실코드·기존 테스트 패턴(PositionManagerExtendedTest H8 reconcile, TradeExecutionServiceTest) 대조 완료 — plan 라인 참조 유효(±1~2줄). TDD 로 구멍1(매도 reconcile)부터.
- 2026-07-16: 3구멍 TDD 구현 완료(각 Red→Green). 구멍1: pendingSellUuid/Reason + reconcilePendingSell/applySellFillOutcome/recoverSellFromBalance/completeSellRecord/buildSellRecord + processTicker 훅. 구멍2: unsynced 플래그 + buy() 가드 + processTicker 재시도. 구멍3: TradeExecutionResult.recorded + recordOrder 헬퍼 + Controller 응답 필드. 테스트 PositionManagerExtendedTest +11, TradeExecutionServiceTest +6. `./gradlew test` 전체 green(JDK21).
- 2026-07-17: 리뷰·fix loop 2회 완료. code-reviewer subagent 는 session limit 중단 → codex(gpt-5.5,xhigh) + 메인 직접 대체. fix1(codex P2×2): applySellFillOutcome wait 우선(부분진행 조기청산 방지)·recoverSellFromBalance totalBalance, executeSellVolume avgBuyPrice 사전확보. fix2(메인 직접, codex 미포착): placeOrder try-catch 제거→예외 전파(UpbitErrorHandlerAdvice 우회+rawBody 노출 회귀 수정). 회귀 테스트 +3. simplify: substantive 없음. 최종 `./gradlew build`(typecheck+test+assemble) BUILD SUCCESSFUL.

# Next

구현·리뷰·검증 완료. 남은 것: 작업 브랜치 커밋 → push → PR. **Blocker(순서)**: engine-lifecycle 보다 먼저 머지(아래 Blockers). acceptance 48(수동주문 WebTestClient 통합 테스트)은 인프라 부재로 단위+advice 분석 대체 — 통합 테스트 추가 필요 시 별도 작업.

# Decisions

- **H8 매수 패턴(pendingBuyUuid + reconcilePendingBuy)을 매도에 미러링** — `state.pendingSellUuid`(+사유) 보존, **reconcile 호출 훅은 TradingEngine.processTicker 의 매도 평가 이전**(매수 pending 게이트 :180-190 과 같은 위치·순서 원칙). 확정 시 TradeRecord 생성해 onTrade(기록+Discord 알림) 경로 합류. (이유: 검증된 기존 패턴 재사용 — 단, 훅 없이는 "다음 tick 확정"이 실행될 곳이 없음(plan-review major))
- **매도 partial-fill 분기(매수와 의미가 다름)**: state=cancel 이라도 executedVolume>0 이면 체결분 TradeRecord 기록 + 잔여 수량으로 포지션 갱신 + 잔여분 재매도 대상. 단순 "미체결 → 재매도"는 부분 체결분 기록을 또 유실한다. (plan-review major)
- **phantom-clear(markSold) 경로**: pendingSellUuid 있으면 getOrder 응답(price/volume)과 **markSold 이전의 state.avgBuyPrice** 로 기록 복원 후 마감 — 복원에 필요한 값 확보 순서를 구현에서 보장. (이유: 실제 돈이 나간 청산이 감사 추적에서 사라지는 것 방지 — 현재 PositionManager.kt:200 은 TradeRecord 없이 종료)
- **수동주문 응답 의미 분리 — 3경로 전부**: executeBuy(:50→:59 후조회)·executeSellAll(:94→:103 동일 구조)·executeSellVolume(:136 선조회) 및 ManualTradeController 의 buy(:47-49)/sell(:93-95) 실패→400 응답. placeOrder 성공 이후 단계 실패는 order uuid 포함 success(+`recorded: false` 류 경고 필드)로 응답, 기록 실패는 log.error. (이유: 접수된 주문에 4xx/5xx → 사용자 재시도 → 이중 접수) — **2026-07-16 구현 순서 변경**: 원안 "getTicker 선조회 통일" 대신 **placeOrder 를 분기점으로 통일**(placeOrder 를 try 로 감싸 실패만 failure, placeOrder 성공 후 부가조회 getTicker/getAccounts(pnl)+기록 save/notify 는 한 try 로 감싸 실패 시 success+uuid+recorded=false). 이유: 선조회는 가격/평단 조회 실패가 실거래(특히 청산 매도)를 막는다 — 부가정보보다 주문 실행 우선. acceptance(2xx+uuid) 불변, 순서만 조정.
- **syncPosition 실패 시 신규 진입만 차단 — 재시도 메커니즘 신설**: 현재 sync 는 runLoop 시작 시 1회뿐(TradingEngine.kt:125-127)이라 "다음 tick 재시도"는 존재하지 않는 경로다(plan-review major). state 에 unsynced 플래그를 두고 processTicker 초입에서 재시도, 성공 시 해소. **매수 차단은 buy() 초입 unsynced 가드**(단위 테스트 가능, 실질 이중포지션 방어), processTicker 재시도가 해소 메커니즘. 매도 게이트는 기존 유지. (이유: 재시작 직후 429 빈발 구간의 이중 포지션 차단, 행동 변경 최소화) — **2026-07-16 조정**: 원안 "지속 실패 log.error 승격" 제거 → syncPosition 은 warn 유지. 이유: processTicker 가 매 tick 재시도하므로 error 면 장애 지속 시 매 tick Discord 알림 스팸. warn 은 ERROR appender 미대상이라 안전하고, 이중포지션은 buy() 가드로 이미 차단됨.
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

- [x] 매도 awaitFill 실패 → 다음 tick reconcile 로 체결 확정 → TradeRecord green (`reconcilePendingSell records trade and clears position when done` 등; onTrade 는 processTicker 훅=매수 pending 미러 관례)
- [x] 매도 partial-fill: cancel+executedVolume>0 → 체결분 기록 + 잔여 포지션 유지 green (`records executed and keeps remaining on partial fill`) + wait+executed>0 조기청산 방지(`keeps pending when wait with partial executed`)
- [x] phantom-clear 경로: pendingSellUuid 존재 시 기록 복원 후 markSold green (`recovers from zero balance when getOrder fails`; locked 잔량은 미체결 유지 `keeps pending when getOrder fails and balance is locked`)
- [~] 수동 buy/sellAll: placeOrder 성공 + 후처리 실패 → 2xx + order uuid — **단위 테스트 + advice 코드 분석으로 검증(대체)**. `returns success recorded false when persistence fails`(성공+recorded=false→Controller 200), `propagates placeOrder exception to advice`(placeOrder 실패→UpbitApiException 전파→UpbitErrorHandlerAdvice 매핑). WebTestClient 통합 테스트는 이 repo 인프라 부재(reactive security/다의존)로 미작성 — 그 목적(예외→응답 경로 정확성)은 대체 검증으로 달성했고, 통합 테스트가 잡았어야 할 실제 회귀(advice 우회)를 fix-loop 2 에서 발견·수정.
- [x] syncPosition 실패 tick 매수 미평가 + 다음 tick 재시도·해소 green (`buy is blocked while position unsynced`, `syncPosition marks/clears unsynced`)
- [x] `./gradlew test` 전체 green + `build` BUILD SUCCESSFUL (JDK21 prefix)

# Review Disposition

- 2026-07-16 codex-review(gpt-5.5, xhigh) P2 2건 — 둘 다 **fix**(메인 직접 리뷰가 놓침):
  - [P2] partial wait 조기청산 (PositionManager applySellFillOutcome/recoverSellFromBalance): `executed>0` 이 `wait` 가드보다 먼저라 wait+executed>0(부분 진행중, 잔량 locked)에서 free=0 이면 markSold 오판 → fix: wait 를 최우선 분기로, recover 는 `totalBalance()`(free+locked) 로 판정.
  - [P2] executeSellVolume cost basis 유실: avgBuyPrice 를 placeOrder 후 조회해 전량매도 시 pnl null → fix: avgBuyPrice 를 placeOrder 전에 확보.
- P0/P1 없음(codex). code-reviewer subagent 는 session limit 로 중단 → codex + 메인 직접으로 대체.
- 2026-07-16 fix-loop 2회차 (메인 직접 리뷰 — codex 미포착, acceptance 48번 검증 지점) **fix**:
  - [P1급] placeOrder try-catch 가 UpbitErrorHandlerAdvice 우회: executeBuy/SellAll/SellVolume 이 placeOrder 의 UpbitApiException 을 잡아 failure(e.message)로 획일화 → advice 의 429/418/insufficient_funds/error_name 매핑 우회 + rawBody(e.message) 노출 회귀 → fix: placeOrder 실패는 **전파**(advice 처리), 성공 후 실패만 recordOrder 가 recorded=false 흡수. 테스트 `returns failure when placeOrder fails` → `propagates ... exception`(assertThrows).

# Blockers

- (순서) **이 plan 을 engine-lifecycle 보다 먼저 머지** — TradingEngine.kt·PositionManager.kt 3-plan 겹침(engine-lifecycle·test-hardening)의 기준 브랜치. pendingSell 구조 위에 취소 안전성(NonCancellable/CE-rethrow)을 얹는 방향이 반대보다 충돌이 적다(plan-review 권고). test-hardening 의 processTicker seam 설계와 시그니처 협의 필요.
