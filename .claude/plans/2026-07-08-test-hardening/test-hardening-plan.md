---
title: test-hardening — 실돈 경로의 무·죽은 테스트를 프로덕션 게이트 테스트로 교체
status: in_progress
started: 2026-07-08
updated: 2026-07-17
---

# Goal

실거래 판단·주문 경로의 테스트 공백 4건을 메꾼다: placeOrder 비재시도(중복주문 방지) 불변식이 주석으로만 존재, processTicker 오케스트레이션(H8 게이트 순서) 무테스트, CandleAggregator(M1→D1/W1 집계 → 라이브 신호 유입) 무테스트, TradingEngineTest 의 mock 자기검증·3초 wall-clock 테스트.

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: UpbitClientRetryPolicyTest 가 retryWhen 을 테스트 내 재조립(프로덕션 미경유), TradingEngineTest.kt:159 delay(3000), MockWebServer 패턴은 UpbitClientTest 에 기존재.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — processTicker 상태 주입 seam 결정 필요(states/activeStrategy 는 private), Key Files 라인 인용 스왑 교정(:68-83=placeOrder, :128-138=retryOnRateLimit), retry 소진 테스트 wall-clock(~3s) 대응, CandleAggregator 관찰 지점(mock 캡처) 결정, TradingEngine 3-plan 겹침으로 착수 순서 조정.
- 2026-07-17: order-state-integrity(#39) main 머지 확인 → 최신 main 위로 rebase. **슬라이스 1 완료(UpbitClient retry)**: `UpbitClientImpl` 에 `retryBackoffBase: Duration = 1s` 생성자 파라미터 추가(배선 2곳 무변경, 테스트만 1ms 주입) → 소진 시나리오 wall-clock 제거. 신규 `UpbitClientRetryTest`(MockWebServer 프로덕션 경유): getAccounts 429×2→200 재시도(req 3), 소진→UpbitApiException(429) 전파(req 3), placeOrder 429→req 1(비재시도). 복제본 `UpbitClientRetryPolicyTest` 삭제. **mutation 검증 1회**: placeOrder 에 `.retryOnRateLimit()` 부착 시 `placeOrder does not retry` 가 req 3≠1 로 FAILED(:77, hang 아닌 assertion) — 회귀 게이트 확인 후 revert. 6개 client 테스트 green(~7s).

# Next

슬라이스 1(UpbitClient retry) 완료 — 코드리뷰·simplify·전체검증 후 별도 커밋. 다음 겹침 없는 슬라이스: **CandleAggregator 단위 테스트**(M1→D1 병합·alignToPeriodStart 정렬 일치·경계+cleanup, addCandle 캡처 방식). processTicker seam 및 죽은 테스트 정리(TradingEngineTest)는 **engine-lifecycle 미머지**라 여전히 대기(Blockers) — order-state 는 머지됨.

# Decisions

- **복제본 테스트 → 프로덕션 경유 테스트 대체** (UpbitClientRetryPolicyTest 삭제 — 사유: 프로덕션 코드를 한 줄도 실행하지 않아 회귀 게이트 기능 0, 동일 시나리오를 MockWebServer 테스트가 대체). placeOrder 에 `.retryOnRateLimit()` 를 붙이는 회귀(429 타이밍 중복 매수 체결)가 fail 함을 mutation 수동 확인 1회. **소진 시나리오 wall-clock 대응**: 프로덕션 Retry.backoff(2, 1s) 경유 시 ~3s — backoff Duration 주입(생성자/internal) 또는 시나리오 축소로 suite 시간 상한 유지(plan-review 지적: delay(3000) 제거로 얻는 시간을 되돌려주지 않기).
- **processTicker 테스트 seam**: internal 화만으론 부족 — `states`/`activeStrategy` 가 private 이고 states[ticker]==null 이면 즉시 return(:171-173). **`processTicker(ticker, state)` 파라미터화를 1안**으로 결정(상태 주입이 명시적, order-state 의 pendingSell 훅·unsynced 게이트 테스트에도 같은 seam 재사용). 시그니처는 order-state-integrity plan 과 협의 — 그쪽이 먼저 머지되면 그 형태를 따른다. 시나리오: pending+reconcile 성공 → onTrade 후 같은 tick sell 미평가 / pending 유지 → buy·sell skip / position 시 sell 경로 / boughtToday 시 buy 생략.
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
- [ ] processTicker 4개 시나리오 runTest green (wall-clock sleep 0)
- [ ] CandleAggregator 병합·정렬 일치·경계 테스트 green (addCandle 캡처 방식)
- [ ] TradingEngineTest delay(3000) 제거 — 전체 테스트 시간 단축 확인
- [ ] `./gradlew test` 전체 green (테스트 삭제는 대체 사유 명시 커밋)

# Blockers

- **TradingEngine.kt 3-plan 겹침**: order-state-integrity(pendingSell 훅·unsynced 게이트)·engine-lifecycle(stop/runLoop/CE)이 같은 파일의 같은 구간을 변경 — processTicker seam 부분은 **두 plan 머지 후 rebase 착수**. UpbitClient retry·CandleAggregator 부분은 겹침 없어 선착수 가능(부분 분리 커밋).
