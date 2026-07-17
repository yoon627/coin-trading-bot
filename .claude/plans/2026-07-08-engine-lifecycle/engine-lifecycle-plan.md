---
title: engine-lifecycle — 엔진 수명주기 안전화 (cancel/reload/부팅복원/graceful shutdown)
status: in_progress
started: 2026-07-08
updated: 2026-07-18
---

# Goal

엔진 수명주기 전이(stop·reload·부팅 복원·프로세스 종료)에서 in-flight 주문 유실, 중복 매매, 관측 사각을 제거한다. 감사 발견 4건: stop() join 부재(+CancellationException 삼킴), restoreOnStartup Mutex 우회(유령 엔진), restore 단발성+Discord attach 이전 실행, graceful shutdown 전무.

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 발견 4건 기반 plan 작성. spot-check: TradingEngine.kt:100 `loopJob?.cancel()`(join 없음), restoreOnStartup 만 lockFor 미사용, server.shutdown 미설정, stop_grace_period 없음.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — CE rethrow 범위에 runLoop·processTicker 추가, @Order 는 appender 쪽에도 명시(+실질 보장은 backoff 재시도라는 인과 정정), @PreDestroy 상호 순서 확인 항목, NonCancellable 합산 시간 검토, order-state-integrity 선행 머지로 순서 고정.
- 2026-07-17: order-state-integrity(#39 c61b8ce) main 머지 확인 → engine-lifecycle 을 main 에 rebase(코드 충돌 0 — plan 커밋만 재배치). **Blocker 해소.** sync 재진단: #39 가 TradingEngine.kt(pendingSell reconcile +17줄)·PositionManager.kt(pending 구조 +217줄)를 바꿔 Key Files 라인 shift → 아래 갱신. pendingBuy/Sell reconcile 안전망이 이미 존재하므로 취소 안전성은 그 위에 얹는다(plan-review 순서). reload 위험 서술 정밀화: pending 은 state 에 저장되나 reloadUserRuntime(:170-173)이 구 states map 을 통째 교체 → cancel-only stop 이면 구 루프 awaitFill 진행분이 유실 → cancelAndJoin 필요는 유효.
- 2026-07-17(구현 1/3 — 취소 안전성, task 1·2 done): stop() suspend+cancelAndJoin, PositionManager buy/sell 후처리 `withContext(NonCancellable)`+CE rethrow, TradingEngine runLoop/processTicker CE rethrow. TDD: PositionManagerExtendedTest 2건(NonCancellable 완주 Red→Green 확인), TradingEngineTest 1건(stop join + 오탐 ERROR 없음). 기존 stop() 호출 @Test 4건 runBlocking 래핑. 관련 스위트 green. **가상시간 대신 실측**: TradingEngine 자체 scope(Dispatchers.Default)라 runTest 가상시간 미적용 → PositionManager(순수 suspend)는 runTest, engine 통합은 runBlocking+실측(결과 동일). NonCancellable 이 pending reconcile 안전망과 협력해 유실 0.
- 2026-07-17(구현 2/3 — restore 재작성, task 3·4 done): restoreOnStartup 을 @PostConstruct → @EventListener(ApplicationReadyEvent)+@Order(LOWEST) 로 이동, DiscordErrorLogAppender 에 @Order(HIGHEST) 부여(attach 선행). per-user restoreOne = lockFor.withLock + lock 후 engines 재확인 skip(유령 엔진 차단 — 구 computeIfAbsent 는 실행 중 엔진에 start 재호출). restoreAllRunningBots 유한 backoff(1·2·4·8·16s, ≤5회) + 최종 실패 '봇 미복원' ERROR alert. createEngine internal seam. 신규 UserTradingManagerTest 3건(skip·재시도·최종alert, 가상시간) green.
- 2026-07-17(구현 3/3 — graceful shutdown, task 5 done): application.yml server.shutdown:graceful + spring.lifecycle.timeout-per-shutdown-phase:30s, docker-compose.prod.yml app.stop_grace_period:40s, UserTradingManager @PreDestroy shutdownAll(전 engine 병렬 stop, runBlocking 브리지) + @DependsOn("discordErrorLogAppender")로 소멸 순서(engine stop → appender detach) 고정. UserTradingManagerTest +1(shutdown 시 전 engine stop). engine 스위트 green.
- 2026-07-17(리뷰 — dlc 11): code-reviewer REQUEST CHANGES(codex gpt-5.5 high 병행, 대부분 합의) + architecture-reviewer NEEDS DISCUSSION. 근본 구멍 8건 — C1 placeOrder 취소창 broad catch→pending 미설정→주문유실·이중포지션 / C2 NonCancellable 무한→shutdown SIGKILL / M1 onTrade DB기록 NonCancellable 밖→감사유실 / M2 restore DB 전실패 alert 누락 / M3 chartExit runCatching CE삼킴 / M4 stop 동시호출 no-join / M5 restore 코루틴 lifecycle 미결속→shutdown 후 엔진기동 / M6 suspend broad catch 다수 CE삼킴→pre-order 취소 ERROR로깅→오탐 alert. **arch Major: timeout-per-shutdown-phase(30s)는 @PreDestroy 엔 미적용(SmartLifecycle 전용) → @PreDestroy runBlocking 무한 hang.**
- 2026-07-18(스코프 결정 — 사용자): durable 근본(C1 주문유실·M1 재시작복구)은 **trading-state-durability(#19+#20, engine-lifecycle 다음 순서로 이미 설계·codex 검토 완료)로 defer**. 이 PR = **SmartLifecycle 전환**(C2) + 국소 fix(M2·M3·M4·M5·M6) + onTrade NonCancellable(M1 프로세스생존중 부분).

# Next

fix loop(dlc 12) — 순서: ① M6/M3 CE rethrow 를 suspend broad catch 전반으로 확대(오탐 alert 제거) → ② M2 restore 전실패 alert + 마지막 backoff 제거 → ③ M4 stop 동시 join 직렬화 → ④ M5 restore lifecycle 결속(restoreJob+shutting-down) → ⑤ C2 SmartLifecycle 전환(@DependsOn·@PreDestroy 대체) + M1 onTrade NonCancellable → ⑥ 테스트 보강(DB 전실패·shutdown 심화). 각 TDD. 이후 code-reviewer 재검토 → simplify → 전체 검증.

# Decisions

- **stop() suspend 화 + cancelAndJoin**: 호출부(stopBot/reloadUserRuntime)는 이미 suspend 라 전파 비용 낮음. TradingController 경유 테스트의 mock 재작성 영향 포함. (이유: reload 가 cancel 만 하고 새 엔진을 즉시 기동하면, 구 루프가 placeOrder 직후 awaitFill 중일 때 pendingBuyUuid 가 폐기될 states 에만 남아 H8 방어망 무력화 + 새 엔진 중복 매수 — TradingEngine.kt:96-103, UserTradingManager.kt:163-178)
- **CancellationException rethrow 는 4곳 전부**: PositionManager buy/sell 후처리(:84-90, :246-249) + **TradingEngine.runLoop(:139-142)·processTicker(:223-225)** — 후자 2곳을 빼면 cancel 이 "Trading loop error" ERROR(→Discord 스팸)로 둔갑하고 delay 재진입 후에야 종료돼 join 이 지연된다(plan-review major). placeOrder 성공 이후 후처리(awaitFill~applyFillOutcome)는 `withContext(NonCancellable)` 로 원자화. **NonCancellable 합산 시간 검토**: awaitFill 최대 10회×300ms+API 왕복 — 다중 유저·티커 동시 후처리가 shutdown 30s 예산 안인지 확인, 초과 위험 시 타임아웃 축소.
- **restoreOnStartup 의 per-user 블록을 lockFor(userId).withLock 으로** + lock 획득 후 engines/전략 재확인해 사용자가 이미 개입했으면 skip. (이유: UserTradingManager.kt:47-50 주석이 명시한 race 를 restore 경로만 방치 — stopBot 교차 시 map 에 없는 유령 엔진)
- **restore 이동 + 알림 보장의 정확한 인과**: @PostConstruct → ApplicationReadyEvent 리스너로 이동하되, **DiscordErrorLogAppender 는 @Order 미지정(기본 LOWEST_PRECEDENCE)이라 appender 쪽에 높은 우선순위 @Order 를 명시**하고 restore 리스너를 그 뒤로. 단 restore 는 비동기 launch 라 리스너 순서만으로 log.error 시점을 보장하지 못한다 — **실질 보장은 유한 backoff 재시도**(첫 실패 재시도가 attach 이후로 밀림) + 최종 실패 시 log.error '봇 미복원' alert. (plan-review 정정 반영)
- **graceful shutdown 3종 세트**: application.yml `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`, UserTradingManager @PreDestroy 전체 engine stop(suspend 브리지), compose `stop_grace_period: 40s`. **@PreDestroy 상호 순서 확인**: DiscordErrorLogAppender.detach(:50-56)가 엔진 stop 보다 먼저 실행되면 종료 중 에러가 Discord 미도달 — 빈 소멸 순서를 확인해 필요 시 의존성/@DependsOn 으로 고정. (이유: 매 배포가 '실행 중 tick 강제 킬' — ops 발견이지만 suspend stop() 과 같은 코드 접점)
- **전제**: alert 류 log.error 의 Discord 도달은 `DISCORD_ERROR_ALERT_ENABLED=true` 의존(compose 기본 false — ops-safety-net 이 실측 확인).
- **스코프 경계**: pendingBuyUuid durable 영속화(#20)는 별도 — 프로세스 생존 중 전이의 무결성만.

## 리뷰 반영 (dlc 11 — 2026-07-18)
- **[C2 → SmartLifecycle 전환]** @PreDestroy shutdownAll 대체: `timeout-per-shutdown-phase(30s)`는 SmartLifecycle/web graceful 에만 적용되고 @PreDestroy 소멸 콜백엔 미적용(arch 웹검증) → runBlocking 상한 없어 awaitFill hang 시 SIGKILL 까지 블록. engine stop 을 SmartLifecycle(web graceful phase 뒤 phase)로 모델링 → 예산 실제 적용 + 모든 SmartLifecycle.stop 이 @PreDestroy 앞이라 appender detach 순서 자동정렬(**@DependsOn magic-string 제거**). stop 은 bounded.
- **[M6/C1-로깅 → CE rethrow 확대]** suspend 를 감싼 모든 broad catch 에 `catch(CancellationException){throw e}`: placeOrder pre-order catch(현재 취소를 ERROR 로깅→오탐 Discord alert, 이 PR 목표와 정면충돌), syncPosition/reconcilePendingBuy/recoverFromBalance/reconcilePendingSell/sell findAccount/recoverSellFromBalance, chartExitTriggered runCatching(M3). 취소는 전파.
- **[M2 restore alert]** DB 조회 전실패 시 '봇 미복원' ERROR 보장(lastQueryFailed 추적) + 마지막 attempt 불필요 backoff 제거.
- **[M4 stop 직렬화]** stop() 동시호출(shutdownAll ↔ reload/stopBot 경합) 시 CAS 실패자도 공통 loopJob 을 join(Mutex 직렬화, 첫 호출만 cancel).
- **[M5 restore lifecycle 결속]** restore 코루틴을 restoreJob 보관 + shutting-down 플래그로 lifecycle 에 묶어, shutdown 시 restore 먼저 취소·join + 신규 엔진 start 차단(shutdown 후 엔진 기동 방지).
- **[M1 부분 fix]** onTrade(record 영속화)를 NonCancellable 경계 안으로 — 취소 시 체결·상태는 반영됐는데 record 유실 방지(프로세스 생존 중). **재시작 후 복구는 durable(trading-state-durability) 소관**.
- **[defer]** C1 주문유실 근본(placeOrder 취소로 uuid 불명 → open-order 복구)·M1 재시작복구(record durable) → trading-state-durability(#20). 이 PR 은 CE rethrow·onTrade NonCancellable 까지만.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :98-105(stop, cancel-only), :141-144(runLoop catch → CE rethrow), :242-244(processTicker catch → CE rethrow), :53(scope)
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — :55-85(restore, lockFor 미사용), :163-178(reload — :170 existing.stop()·:173 map 교체), :47-53(lockFor 규약 주석)
- `bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt` — :93-99(buy 후처리, catch :96 → CE rethrow+NonCancellable), :258-271(sell 후처리, catch :268 → CE rethrow+NonCancellable), :25-26(awaitFill 상수 10×300ms)
- `bot/src/main/kotlin/com/trading/bot/notification/DiscordErrorLogAppender.kt` — :29-56(attach/detach, @Order 부여 지점)
- `bot/src/main/resources/application.yml`, `deploy/aws/docker-compose.prod.yml` — graceful 설정 지점
- `bot/src/test/kotlin/com/trading/bot/api/TradingControllerTest*` — stop suspend 화 영향 확인

# Acceptance

- [x] reload/stop 시 awaitFill 진행 중이면 join 으로 tick 완료 대기 (TradingEngineTest — stop join + 오탐 ERROR 없음)
- [x] buy/sell 후처리 cancel → NonCancellable 완주 (PositionManagerExtendedTest 2건) + runLoop/processTicker CE rethrow
- [x] restore ↔ 개입 경합: 유령 엔진 미발생 (UserTradingManagerTest — skip 시 createEngine·start·setStrategy 0회)
- [x] restore 실패 → backoff 재시도 → 복원 (UserTradingManagerTest)
- [ ] **[리뷰]** 취소 시 오탐 ERROR 없음: placeOrder/reconcile/chartExit 취소가 ERROR 로그 미발생(로그 캡처)
- [ ] **[리뷰]** restore DB 전실패 → '봇 미복원' ERROR + 마지막 backoff 미실행 테스트
- [ ] **[리뷰]** stop() 동시호출 시 양쪽 loop 완료까지 join 테스트
- [ ] **[리뷰]** restore 진행 중 shutdown → restore 취소·신규 엔진 미기동 테스트
- [ ] **[리뷰]** SmartLifecycle: shutdown 이 30s 예산 내 bounded 종료(hang 없음), stop 이 appender detach 앞(로컬 SIGTERM 관찰)
- [ ] **[리뷰]** onTrade 취소 시 record 유실 없음(NonCancellable) 테스트
- [ ] `./gradlew test` 전체 green

# Blockers

- ~~order-state-integrity 머지 선행~~ — **해소** (2026-07-17: #39 c61b8ce main 머지 → rebase 완료). pendingBuy/Sell reconcile 구조 위에 취소 안전성을 얹는다(plan-review 권고 순서). test-hardening 과 TradingEngine 겹침은 유효 — 착수 순서로 회피(engine-lifecycle 먼저).

# Review Disposition (dlc 11 리뷰 처분)

- C1 (placeOrder 취소 주문유실): **부분 fix**(CE rethrow → 오탐 제거·취소 전파) + **defer**(durable uuid 복구 → trading-state-durability)
- C2 (NonCancellable 무한 hang): **fix** — SmartLifecycle 전환 + bounded stop
- M1 (onTrade 감사유실): **부분 fix**(onTrade NonCancellable, 프로세스생존중) + **defer**(record durable → trading-state-durability)
- M2 (restore 전실패 alert 누락): **fix**
- M3 (chartExit runCatching CE): **fix**
- M4 (stop 동시 no-join): **fix**
- M5 (restore lifecycle 미결속): **fix**
- M6 (broad catch CE 삼킴 family): **fix**
- Minor(테스트 공백·문구): **fix** — DB 전실패·shutdown 심화 테스트, 주석 '30s예산/병렬/리스너순서' 정정(simplify)

# Deferred (범위 밖 — trading-state-durability #20 로 이관)

- **C1 주문유실 근본 / M1 재시작 복구** (Critical/Major): placeOrder 취소로 uuid 불명 시 open-order 조회 복구 + pendingBuyUuid/pendingSellUuid·TradeRecord durable 영속화. 이 PR 은 CE rethrow(오탐 제거)·onTrade NonCancellable(프로세스생존중)까지. 완전 해결은 trading-state-durability(그 plan 이 이미 담당 — Closes #20). 파일: PositionManager.kt buy/sell, TradeExecutionService.saveAndNotify.
