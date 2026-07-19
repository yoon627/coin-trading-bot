---
title: test-hardening — 실돈 경로의 무·죽은 테스트를 프로덕션 게이트 테스트로 교체
status: in_progress
started: 2026-07-08
updated: 2026-07-19
---

# Goal

실거래 판단·주문 경로의 테스트 공백 4건을 메꾼다: placeOrder 비재시도(중복주문 방지) 불변식이 주석으로만 존재, processTicker 오케스트레이션(H8 게이트 순서) 무테스트, CandleAggregator(M1→D1/W1 집계 → 라이브 신호 유입) 무테스트, TradingEngineTest 의 mock 자기검증·3초 wall-clock 테스트.

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: UpbitClientRetryPolicyTest 가 retryWhen 을 테스트 내 재조립(프로덕션 미경유), TradingEngineTest.kt:159 delay(3000), MockWebServer 패턴은 UpbitClientTest 에 기존재.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — processTicker 상태 주입 seam 결정 필요(states/activeStrategy 는 private), Key Files 라인 인용 스왑 교정(:68-83=placeOrder, :128-138=retryOnRateLimit), retry 소진 테스트 wall-clock(~3s) 대응, CandleAggregator 관찰 지점(mock 캡처) 결정, TradingEngine 3-plan 겹침으로 착수 순서 조정.
- 2026-07-17: order-state-integrity(#39) main 머지 확인 → 최신 main 위로 rebase. **슬라이스 1 완료(UpbitClient retry)**: `UpbitClientImpl` 에 `retryBackoffBase: Duration = 1s` 생성자 파라미터 추가(배선 2곳 무변경, 테스트만 1ms 주입) → 소진 시나리오 wall-clock 제거. 신규 `UpbitClientRetryTest`(MockWebServer 프로덕션 경유): getAccounts 429×2→200 재시도(req 3), 소진→UpbitApiException(429) 전파(req 3), placeOrder 429→req 1(비재시도). 복제본 `UpbitClientRetryPolicyTest` 삭제. **mutation 검증 1회**: placeOrder 에 `.retryOnRateLimit()` 부착 시 `placeOrder does not retry` 가 req 3≠1 로 FAILED(:77, hang 아닌 assertion) — 회귀 게이트 확인 후 revert. 6개 client 테스트 green(~7s). 리뷰 codex+Claude 모두 APPROVE, 전체 test green, §6 docstring 지적 1건 수정.
- 2026-07-18: 슬라이스 1 = **PR #41 로 main 머지**(squash `8761f19`)·원격브랜치·worktree 정리 완료. **슬라이스 2 착수(CandleAggregator)** — 신규 worktree `candle-aggregator-test`(origin/main 기준). `CandleAggregatorTest` 4개 신설: MockK `capture` 로 `store.addCandle` 관찰(visibility 무변경) — 같은날 M1 다수→D1 병합(open 보존·high=max·low=min·close=last·volume=합), D1 UTC자정·W1 ISO월요일 정렬(dedup 불변식), 일 경계 분리(2 D1), cleanupOldPeriods 3주기 축출→재유입 fresh. 4개 green. 리뷰 codex(실질문제없음)+Claude(APPROVE) — Minor 4건 반영: quoteVolume 합 게이트 추가, 정렬 테스트를 7개 interval 전체로 확대(M5/M15/H1/H4/D1/W1/MO1, 01-17 기준 경계 분리), 경계봉 openPrice assert, 주석 톤다운(relaxed 중복은 wontfix). 전체 `./gradlew test` green(44 클래스/404 테스트/0 실패).
- 2026-07-18(2): 슬라이스 2 = **PR #46 로 main 머지**(squash `1ecee2e`)·정리 완료. **슬라이스 3 착수(processTicker + 죽은테스트)** — engine-lifecycle(#43)·order-state(#39) 머지로 Blocker 해소, 신규 worktree `processticker-tests`(origin/main). Explore: `processTicker(ticker)` private(:191, `state=states[ticker]?:return; strategy=activeStrategy?:return`), 상태 접근 private, 이미 internal seam 다수(`resolveExitStrategy(state,..)`·`decideSell`·`evaluateChartExit`)·`setStrategy(name)` 공개. 기존 getRealtimePrice 직접호출 테스트(:465-500) 존재 → 구형 self-stub(:218)은 중복.

# Next

슬라이스 3 구현·리뷰·검증 완료(8 시나리오, 양 리뷰 APPROVE, 전체 green). → 커밋 → push → PR → 머지. **이것이 test-hardening 마지막 착수가능 슬라이스** — 머지되면 plan status: done(processTicker/CandleAggregator/UpbitClient retry 3슬라이스 전부 완료).

## Progress (slice 3 구현)
- 2026-07-19: seam 추출(`processTicker(ticker)` wrapper → `internal suspend processTicker(ticker,state,strategy)` 코어, 동작보존) + TradingEngineTest 7 시나리오 추가 + 죽은 테스트 2개(:218 self-stub, :236 delay(3000)) 삭제. `strategy.shouldBuy` 가 suspend 라 `coEvery` 로 stub. **mutation 검증 완료(Major-1)**: S2 sell return·S3 pendingBuy reconciled return isolated 제거 → 각 해당 테스트만 FAIL(markSold/markBought 충실도 덕에 관측), S4/S5/S6 skip return 3개 결합 제거 → 정확히 그 3개만 FAIL. 복원 후 TradingEngineTest 36개 green.
- 2026-07-19(2): 리뷰 codex(Major 1)+Claude code-reviewer(APPROVE, Minor 1) 모두 **pendingSell reconciled 분기(:225-227) 미커버** 지적(S3 pendingBuy 의 매도판 대칭 누락) → **8번째 시나리오 추가**(pendingSell reconciled→같은 tick buy 미평가, boughtToday=false 전날청산), 해당 return mutation 으로 검증(8번째만 FAIL). S2 boughtToday=false 의도 주석 추가(Claude Nit). 최종 TradingEngineTest 37개 + 전체 `./gradlew test` 46클래스/429테스트/0실패 green. 리뷰: seam 동작보존·MockK(shouldBuy suspend→coEvery)·삭제 커버리지 대체 = 양 리뷰 확인.

## 시나리오 (7, plan-review 반영 — mock 이 TradingState 를 실제처럼 변이 + downstream 트리거 stub)
1. buy(REST 폴백): fresh state, `latestPrice null` 명시 + store miss → getRealtimePrice null → `upbitClient.getTicker` REST 가격 → shouldBuy=true → `buy(currentPrice=REST가격)` 검증 (:236 커버 보존)
2. sell: position=true, `checkStopLoss=true`→decideSell STOP_LOSS, `sell answers { state.markSold(); rec }` → sell 호출 + 이후 buy 미호출(return) 검증
3. pendingBuy reconciled: `reconcilePendingBuy answers { state.markBought(); rec }` + `checkStopLoss=true` → onTrade+return → **같은 tick sell 미호출**(verify exactly 0) 검증
4. pendingBuy 미해소 skip: pendingBuyUuid 유지 + shouldBuy=true → buy 미호출(:215 return) 검증
5. pendingSell 미해소 skip: position=true, pendingSellUuid 유지 + checkStopLoss=true → sell 미호출(:226 return) 검증
6. boughtToday skip: position=false, boughtToday=true + shouldBuy=true → buy 미호출(:242 return) 검증
7. unsynced: unsynced=true → `syncPosition` 호출 검증

## Review Disposition (plan-review)
- [fix] Major-1(Claude, 거짓커버리지): mock `answers` 로 상태 변이 + 핵심 게이트/return 제거 **mutation 수동확인**(slice1 규율) → Progress 기록
- [fix] Major-2(Claude): skip 시나리오 `shouldBuy=true`/`checkStopLoss=true` stub (non-relaxed strategy 예외→거짓통과 방지)
- [fix] Major-3(Claude)+Major-1(codex): pendingSell 게이트 시나리오 추가(#5), unsynced(#7)
- [fix] Major-2(codex)+Minor-1(Claude): buy 시나리오 REST 폴백 경로 + `latestPrice null` 명시
- [ack] Minor-2(Claude): 코어 직접호출은 wrapper/runLoop 미커버 — 기존 start/stop 테스트가 간접 커버(수용)
- [note] seam 추출 동작보존·internal 접근·삭제 커버리지 대체 = 양 리뷰 확인

# Decisions

- **복제본 테스트 → 프로덕션 경유 테스트 대체** (UpbitClientRetryPolicyTest 삭제 — 사유: 프로덕션 코드를 한 줄도 실행하지 않아 회귀 게이트 기능 0, 동일 시나리오를 MockWebServer 테스트가 대체). placeOrder 에 `.retryOnRateLimit()` 를 붙이는 회귀(429 타이밍 중복 매수 체결)가 fail 함을 mutation 수동 확인 1회. **소진 시나리오 wall-clock 대응**: 프로덕션 Retry.backoff(2, 1s) 경유 시 ~3s — backoff Duration 주입(생성자/internal) 또는 시나리오 축소로 suite 시간 상한 유지(plan-review 지적: delay(3000) 제거로 얻는 시간을 되돌려주지 않기).
- **processTicker 테스트 seam**: internal 화만으론 부족 — `states`/`activeStrategy` 가 private 이고 states[ticker]==null 이면 즉시 return(:171-173). **최종 시그니처 = `internal suspend fun processTicker(ticker, state, strategy)`** (2026-07-18 확정): order-state·engine-lifecycle 모두 머지됐고 어느 쪽도 processTicker seam 을 도입하지 않아 자유 선택 — `state`·`activeStrategy` 둘 다 private 이므로 **둘 다 주입**해 setStrategy 의존 없이 완전 격리(plan 초안의 `(ticker,state)` 개선). private 1-arg wrapper 가 `states[ticker]?:return; activeStrategy?:return` 해소 후 위임(동작보존). 시나리오: pending+reconcile 성공 → onTrade 후 같은 tick sell 미평가 / pending 유지 → buy·sell skip / position 시 sell 경로 / boughtToday 시 buy 생략.
- **CandleAggregator 단위 테스트 신설**: (1) 같은 날 M1 다수 → D1 1개 병합(high=max,low=min,close=last,volume=합), (2) alignToPeriodStart 가 seed REST D1 openTime(UTC 자정·ISO 월요일)과 일치 — upsert dedup 불변식(과거 D1 오염 실버그 d6c6857 계열 재발 방지), (3) 기간 경계 분리 + cleanupOldPeriods. **관찰 지점**: alignToPeriodStart/cleanupOldPeriods 는 private — MarketDataStore mock 의 addCandle 캡처(argumentCaptor)로 openTime·병합 결과를 검증(visibility 변경 없이, plan-review 지적 반영).
- **죽은 테스트 정리**: `getRealtimePrice prefers fresh WebSocket price`(stub 자신 assert)는 engine.getRealtimePrice 직접 호출로 교체(:372+ 신형 패턴 통일), `falls back to REST...`(delay(3000)+atLeast(1))는 processTicker 단위 테스트로 대체하고 wall-clock 제거.
- **스코프 경계**: WS 프레임 파싱 테스트는 marketdata-consolidation 소관(파싱 함수가 그쪽에서 이동·internal 화). 이 plan 은 클라이언트 retry·엔진 오케스트레이션·집계기만.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/client/UpbitClientRetryPolicyTest.kt` — 대체 대상(:20-30 복제본)
- `bot/src/main/kotlin/com/trading/bot/client/UpbitClientImpl.kt` — :68-83(placeOrder 비재시도, 주석 :73-74), :128-138(retryOnRateLimit)
- `bot/src/test/kotlin/com/trading/bot/client/UpbitClientTest.kt` — MockWebServer 기존 패턴
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :171-226(processTicker seam)
- `bot/src/main/kotlin/com/trading/bot/stream/CandleAggregator.kt` — :35-92
- `bot/src/test/kotlin/com/trading/bot/engine/TradingEngineTest.kt` — :126-163(죽은/약한 테스트), :372-409(신형 패턴)

# Acceptance

- [x] placeOrder 429 → HTTP 요청 정확히 1회 assert green + retryOnRateLimit 부착 mutation 시 fail 확인(수동 1회, 결과 Progress 기록) — 2026-07-17
- [x] getAccounts 계열 429 재시도·소진 시나리오 green — suite 시간 증가 ≤1s(backoff 주입 또는 축소로) — 2026-07-17, backoff base 1ms 주입, 6 client 테스트 ~7s
- [x] processTicker 시나리오 runTest green (wall-clock 0) — 2026-07-19, **8 시나리오**(buy REST폴백·sell·pendingBuy reconciled·pendingBuy skip·pendingSell reconciled·pendingSell skip·boughtToday·unsynced), 6개 게이트 mutation-verified
- [x] CandleAggregator 병합·정렬 일치·경계 테스트 green (addCandle 캡처 방식) — 2026-07-18, CandleAggregatorTest 4개(MockK capture) green
- [x] TradingEngineTest delay(3000) 제거 — 2026-07-19, 죽은 테스트 2개(:218 self-stub, :236 delay(3000)) 삭제, 대체 커버 확인
- [x] `./gradlew test` 전체 green — 2026-07-19, 46 클래스/429 테스트/0 실패

# Blockers

- ~~TradingEngine.kt 3-plan 겹침~~ **해소(2026-07-18)**: order-state(#39)·engine-lifecycle(#43) 모두 머지 → slice 3 착수. 남은 블로커 없음.
