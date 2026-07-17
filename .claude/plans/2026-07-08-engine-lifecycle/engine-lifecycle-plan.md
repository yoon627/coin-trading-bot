---
title: engine-lifecycle — 엔진 수명주기 안전화 (cancel/reload/부팅복원/graceful shutdown)
status: in_progress
started: 2026-07-08
updated: 2026-07-17
---

# Goal

엔진 수명주기 전이(stop·reload·부팅 복원·프로세스 종료)에서 in-flight 주문 유실, 중복 매매, 관측 사각을 제거한다. 감사 발견 4건: stop() join 부재(+CancellationException 삼킴), restoreOnStartup Mutex 우회(유령 엔진), restore 단발성+Discord attach 이전 실행, graceful shutdown 전무.

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 발견 4건 기반 plan 작성. spot-check: TradingEngine.kt:100 `loopJob?.cancel()`(join 없음), restoreOnStartup 만 lockFor 미사용, server.shutdown 미설정, stop_grace_period 없음.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — CE rethrow 범위에 runLoop·processTicker 추가, @Order 는 appender 쪽에도 명시(+실질 보장은 backoff 재시도라는 인과 정정), @PreDestroy 상호 순서 확인 항목, NonCancellable 합산 시간 검토, order-state-integrity 선행 머지로 순서 고정.
- 2026-07-17: order-state-integrity(#39 c61b8ce) main 머지 확인 → engine-lifecycle 을 main 에 rebase(코드 충돌 0 — plan 커밋만 재배치). **Blocker 해소.** sync 재진단: #39 가 TradingEngine.kt(pendingSell reconcile +17줄)·PositionManager.kt(pending 구조 +217줄)를 바꿔 Key Files 라인 shift → 아래 갱신. pendingBuy/Sell reconcile 안전망이 이미 존재하므로 취소 안전성은 그 위에 얹는다(plan-review 순서). reload 위험 서술 정밀화: pending 은 state 에 저장되나 reloadUserRuntime(:170-173)이 구 states map 을 통째 교체 → cancel-only stop 이면 구 루프 awaitFill 진행분이 유실 → cancelAndJoin 필요는 유효.
- 2026-07-17(구현 1/3 — 취소 안전성, task 1·2 done): stop() suspend+cancelAndJoin, PositionManager buy/sell 후처리 `withContext(NonCancellable)`+CE rethrow, TradingEngine runLoop/processTicker CE rethrow. TDD: PositionManagerExtendedTest 2건(NonCancellable 완주 Red→Green 확인), TradingEngineTest 1건(stop join + 오탐 ERROR 없음). 기존 stop() 호출 @Test 4건 runBlocking 래핑. 관련 스위트 green. **가상시간 대신 실측**: TradingEngine 자체 scope(Dispatchers.Default)라 runTest 가상시간 미적용 → PositionManager(순수 suspend)는 runTest, engine 통합은 runBlocking+실측(결과 동일). NonCancellable 이 pending reconcile 안전망과 협력해 유실 0.

# Next

restore 재작성(task 3·4): restoreOnStartup 을 @PostConstruct → ApplicationReadyEvent 로 이동, per-user 블록 lockFor.withLock + lock 후 engines 재확인 skip(유령 엔진 방지), 유한 backoff 재시도(첫 실패가 appender attach 이후로 밀림) + 최종 실패 '봇 미복원' ERROR alert. 이후 graceful shutdown 3종(task 5).

# Decisions

- **stop() suspend 화 + cancelAndJoin**: 호출부(stopBot/reloadUserRuntime)는 이미 suspend 라 전파 비용 낮음. TradingController 경유 테스트의 mock 재작성 영향 포함. (이유: reload 가 cancel 만 하고 새 엔진을 즉시 기동하면, 구 루프가 placeOrder 직후 awaitFill 중일 때 pendingBuyUuid 가 폐기될 states 에만 남아 H8 방어망 무력화 + 새 엔진 중복 매수 — TradingEngine.kt:96-103, UserTradingManager.kt:163-178)
- **CancellationException rethrow 는 4곳 전부**: PositionManager buy/sell 후처리(:84-90, :246-249) + **TradingEngine.runLoop(:139-142)·processTicker(:223-225)** — 후자 2곳을 빼면 cancel 이 "Trading loop error" ERROR(→Discord 스팸)로 둔갑하고 delay 재진입 후에야 종료돼 join 이 지연된다(plan-review major). placeOrder 성공 이후 후처리(awaitFill~applyFillOutcome)는 `withContext(NonCancellable)` 로 원자화. **NonCancellable 합산 시간 검토**: awaitFill 최대 10회×300ms+API 왕복 — 다중 유저·티커 동시 후처리가 shutdown 30s 예산 안인지 확인, 초과 위험 시 타임아웃 축소.
- **restoreOnStartup 의 per-user 블록을 lockFor(userId).withLock 으로** + lock 획득 후 engines/전략 재확인해 사용자가 이미 개입했으면 skip. (이유: UserTradingManager.kt:47-50 주석이 명시한 race 를 restore 경로만 방치 — stopBot 교차 시 map 에 없는 유령 엔진)
- **restore 이동 + 알림 보장의 정확한 인과**: @PostConstruct → ApplicationReadyEvent 리스너로 이동하되, **DiscordErrorLogAppender 는 @Order 미지정(기본 LOWEST_PRECEDENCE)이라 appender 쪽에 높은 우선순위 @Order 를 명시**하고 restore 리스너를 그 뒤로. 단 restore 는 비동기 launch 라 리스너 순서만으로 log.error 시점을 보장하지 못한다 — **실질 보장은 유한 backoff 재시도**(첫 실패 재시도가 attach 이후로 밀림) + 최종 실패 시 log.error '봇 미복원' alert. (plan-review 정정 반영)
- **graceful shutdown 3종 세트**: application.yml `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`, UserTradingManager @PreDestroy 전체 engine stop(suspend 브리지), compose `stop_grace_period: 40s`. **@PreDestroy 상호 순서 확인**: DiscordErrorLogAppender.detach(:50-56)가 엔진 stop 보다 먼저 실행되면 종료 중 에러가 Discord 미도달 — 빈 소멸 순서를 확인해 필요 시 의존성/@DependsOn 으로 고정. (이유: 매 배포가 '실행 중 tick 강제 킬' — ops 발견이지만 suspend stop() 과 같은 코드 접점)
- **전제**: alert 류 log.error 의 Discord 도달은 `DISCORD_ERROR_ALERT_ENABLED=true` 의존(compose 기본 false — ops-safety-net 이 실측 확인).
- **스코프 경계**: pendingBuyUuid durable 영속화(#20)는 별도 — 프로세스 생존 중 전이의 무결성만.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :98-105(stop, cancel-only), :141-144(runLoop catch → CE rethrow), :242-244(processTicker catch → CE rethrow), :53(scope)
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — :55-85(restore, lockFor 미사용), :163-178(reload — :170 existing.stop()·:173 map 교체), :47-53(lockFor 규약 주석)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — :93-99(buy 후처리, catch :96 → CE rethrow+NonCancellable), :258-271(sell 후처리, catch :268 → CE rethrow+NonCancellable), :25-26(awaitFill 상수 10×300ms)
- `bot/src/main/kotlin/com/trading/bot/notification/DiscordErrorLogAppender.kt` — :29-56(attach/detach, @Order 부여 지점)
- `bot/src/main/resources/application.yml`, `deploy/aws/docker-compose.prod.yml` — graceful 설정 지점
- `bot/src/test/kotlin/com/trading/bot/api/TradingControllerTest*` — stop suspend 화 영향 확인

# Acceptance

- [ ] reload/stop 시 awaitFill 진행 중이면 join 으로 tick 완료 대기 (runTest 가상시간, pending 유실 없음 assert)
- [ ] buy/sell·runLoop·processTicker 4곳 모두 cancel → CE rethrow + NonCancellable 구간 완주 테스트
- [ ] restore ↔ stopBot 경합 테스트: 유령 엔진 미발생(engines map 과 실행 루프 일치)
- [ ] restore 실패 → backoff 재시도 → 최종 실패 시 ERROR 로그 테스트
- [ ] graceful: 로컬 실행·관찰 — SIGTERM 시 "graceful shutdown" 로그 + 진행 중 tick 완료 후 종료 확인(수동 검증 절차), @PreDestroy 순서(appender detach 가 엔진 stop 이후) 확인
- [ ] `./gradlew test` 전체 green

# Blockers

- ~~order-state-integrity 머지 선행~~ — **해소** (2026-07-17: #39 c61b8ce main 머지 → rebase 완료). pendingBuy/Sell reconcile 구조 위에 취소 안전성을 얹는다(plan-review 권고 순서). test-hardening 과 TradingEngine 겹침은 유효 — 착수 순서로 회피(engine-lifecycle 먼저).
