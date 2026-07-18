---
title: marketdata-consolidation — Upbit WS 수집 단일화 + half-open 고착 워치독 + 파싱 테스트
status: blocked
started: 2026-07-08
updated: 2026-07-18
---

# Goal

단계별 목표(plan-review 반영으로 명확화):
- 1단계: 실거래 폴백 체인 실효성 회복 — WS 폴백이 거래 티커를 실제로 커버.
- 2단계: **양쪽 수집 경로(UpbitMarketFeed=주 경로, UpbitWebSocketClient=폴백)** 의 half-open 무수신 고착을 워치독으로 복구 + 재연결 race 제거 + 파싱 테스트.
- 3단계(별도 PR 가능): 상시 WS 연결 1개로 통합(UpbitMarketFeed 단일 수집, RealtimePrice 는 응답 DTO 변환만).

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: WS_URL 동일 상수 2벌, init 하드코딩 4종목, ping/keepalive 0건.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — **critical 교정**: 워치독이 폴백 클라이언트만 덮으면 Goal(영구 REST 폴백 고착 해소)이 성립하지 않음. 주 경로 UpbitMarketFeed 는 callbackFlow 지역 상태(:49-53)라 외부 워치독 접근 불가 + MarketDataIngestionService 는 flow 종료 시 재시작 없이 죽음(:65-71) → 2단계 스코프 확장. 또한 봇 시작 시 WS 구독 자체가 없어 watchlist 교체만으론 불충분 → engine.start 시 subscribe 추가.
- 2026-07-16: 1단계 착수 — Explore 완료(UpbitWebSocketClient·TradingEngine·UserTradingManager·UpbitMarketFeed·WatchlistProperties 확인). ①=WS폴백클라이언트 watchlist 주입, ②=engine.start→subscribe. TDD 진행.
- 2026-07-17: ①② 구현·Green(UpbitWebSocketClientTest 7·TradingEngineTest 31 pass). code-review(Claude+Codex): **공개 API 노출 regression 발견** — 변경②가 미인증 `/api/prices/**`(SecurityConfig:40 permitAll)에 사용자 거래 티커(watchlist 밖) 유입. 사용자 승인 → 이 PR에서 fix(공개 엔드포인트 watchlist 제한 + watchlist 정규화 + subscribedMarkets internal + 회귀 테스트).
- 2026-07-17(cont): fix 완료·전체 `:bot:test` green(PriceStreamControllerTest 10·WatchlistPropertiesTest 4·UpbitWebSocketClientTest 7·TradingEngineTest 31, 실패 0). 1단계 구현 완료(미커밋). simplify: allowedTickers `.map{uppercase}` redundant(tickerList 정규화) — 이중 방어로 유지(제안만).
- 2026-07-17(2단계 착수): PR#36(1단계) squash 머지(origin/main a44782a). 사용자 선택 **'신뢰성 먼저 2 PR 분할'** — **PR-a**=주경로 재시작+워치독·폴백 워치독·세대가드(half-open 고착 해소 핵심), **PR-b**=unsubscribe ref-count·파싱 테스트. base=origin/main, 브랜치 `marketdata-consolidation-2`. Explore 완료(collectTickers 재시작 없음·양 WS 세대가드 없음·폴백 lastMessageAt 없음).
- 2026-07-17(2a plan-review): Claude+Codex 수렴 — 원 설계 **NO-GO**(세대 증가시점 race·connected clobber·워치독 20s 폭주·cancel 이중연결·CancellationException 삼킴). 사용자 '전체 재설계 제대로' 선택 → 아래 재설계 반영.
- 2026-07-17(2a 구현): UpbitWebSocketClient 세대가드(dispose前 generation++·doFinally/doOnError/scheduleReconnect 세대 게이팅·sleep後 재확인)+폴백 워치독, MarketDataIngestionService 재시작루프(CancellationException rethrow)+워치독(cancelAndJoin·restartMutex·shutdown), MarketDataWatchdogProperties(kill-switch)+application.yml. 전체 `:bot:test` green(Watchdog4·WSClient8·Ingestion6). code-review: subagent 세션한도 중단→메인 직접(결함 없음, plan-review 지적 반영 확인), codex 는 push 게이트로 병행. 원본 로직 diff 보존 확인.
- 2026-07-17(2a codex 게이트 fix): pre-push codex 2건 fix — P3(워치독 restart TOCTOU: mutex 안 isStale 재확인), P2(UpbitMarketFeed 지연 재연결 좀비 이중 WS: awaitClose interrupt + subscribe後 running 재확인). :bot:test green 유지. push 재시도(codex high-reasoning 리뷰 ~10분).
- 2026-07-17(PR-a 머지): **PR #37 squash 머지**(origin/main 78ad3fc). PR-a(세대가드·양경로 워치독·UpbitMarketFeed close-safe·kill-switch property) 완료. 2단계 절반 done. 세션 컨텍스트 방대 → 사용자 선택 '새 세션에서 PR-b 이어가기'.
- 2026-07-17(PR-b, 이 세션 계속): 사용자 '니가해' → PR-b 이 세션 진행. base 정렬(브랜치 marketdata-consolidation-3, origin/main 기준 + plan cherry-pick). 파싱 fixture 5종(UpbitMarketFeedParsingTest)+parseTickerMessage internal·warn, unsubscribe ref-count(baseline watchlist ∪ refCounts engine, TradingEngine.stop→unsubscribe) — subscribedTickers 단조증가(1단계 defer) 해소. 전체 :bot:test green(파싱5·WSClient11).
- 2026-07-18(PR-b push BLOCKED): PR-b 커밋(`783b61e`, 코드+테스트+plan) 검증 완료. pre-push codex 3회 P2·P3 fix 반영(소켓 dispose 순서·connected 정리·UpbitMarketFeed close-safe). 그러나 **codex pre-push 무한 hang**(다중 세션 경합 + hook timeout 부재)으로 push 차단 → `status: blocked`. 근본원인·자기개선 사각 분석(# Workflow Findings), memory [[project_prepush_codex_slow]] 에 hang 모드 추가.

# Next

**PR-b 는 구현·검증 완료, push 만 codex hang 으로 차단(# Blockers).** 재개 절차 (브랜치 `marketdata-consolidation-3`, HEAD `783b61e`):
1. **push**: codex hang 해소(다른 세션 push 종료 대기) 후 `git push -u origin marketdata-consolidation-3`(run_in_background — [[project_prepush_codex_slow]]). 재발 시 `CODEX_SKIP=1 git push`(hook 공식 bypass). ⚠️ 이 plan 갱신이 marketdata-consolidation-3 에 커밋되니, 다른 worktree 에서 이어받으면 이 브랜치 기준.
2. **PR·머지**: `gh pr create --base main --head marketdata-consolidation-3`(body: `scratchpad/prb_pr_body.md` 참고, 없으면 재작성) → squash 머지 → **2단계 완결**.
3. **3단계**(별도): 상시 WS 연결 1개로 풀 통합 — UpbitMarketFeed 단일 수집, SSE(PriceStreamController)·엔진 폴백을 MarketDataStore 기반 전환(동작 동등성 확인 필수).
4. **개선(승인 시, 별도 wt→dlc)**: pre-push hook 에 codex `timeout` + hang fail 처리, codex 동시 실행 flock 직렬화(# Workflow Findings).

# Decisions

- 2026-07-17(2a 설계 상세):
  - **세대가드**: 양 WS(UpbitWebSocketClient·UpbitMarketFeed.tickerFlow)에 generation AtomicInteger. connect() 진입 시 `myGen=generation.incrementAndGet()`, doFinally/scheduleReconnect 는 `myGen==generation.get() && !shuttingDown` 일 때만 재연결. reconnect/dispose 로 무효화된 이전 연결의 doFinally 가 중복 connect·disposable 덮어쓰기 leak·이중 수신을 못 내게.
  - **폴백 워치독**: UpbitWebSocketClient `@Volatile lastMessageAt`(processMessage 성공 시 갱신) + `@Scheduled(fixedRate=WATCHDOG_INTERVAL_MS)` → `connected && subscribedTickers 비지않음 && now-lastMessageAt>STALE_MS` 면 warn+dispose()(기존 doFinally→scheduleReconnect 재사용). 판정은 `internal fun isStale(...)` 순수함수로 분리해 단위테스트, dispose 트리거는 통합 성격.
  - **주경로 재시작**: MarketDataIngestionService — collectTickers→`runTickerCollection`(while isActive: try collect / catch(비-Cancellation) log / delay backoff 재구독) + `@Volatile lastTickerAt`(ingestTicker 시 갱신) + `@Scheduled` 워치독(now-lastTickerAt>STALE_MS 면 tickerJob.cancel→새 launch — half-open 은 flow 재구독=새 연결로만 해소). markets 필드화(@Scheduled 접근).
  - **임계값(상수, 활발 watchlist 기준)**: STALE_MS=60_000, WATCHDOG_INTERVAL_MS=20_000, RESTART_BACKOFF_MS=1_000. 설정화 보류(과한 옵션 지양, 필요 시 후속). → **plan-review 로 폐기, 아래 재설계에서 property 화**.
- 2026-07-17(2a **재설계**, plan-review 반영):
  - **세대가드**: `startConnection()` 단일 진입 — `myGen=generation.incrementAndGet()` **후** 구 `disposable.dispose()`. 구 연결 doFinally 는 myGen<현재라 상태변경·재연결 no-op. `connected.set(false)`·scheduleReconnect 전부 `myGen==generation.get()` 게이팅. `scheduleReconnect(deadGen)` 는 sleep 후 connectionLock 안에서 `deadGen==generation` 재확인. (증가시점을 dispose 이전으로 — connect-진입 증가의 race 제거)
  - **reset-on-connect grace**: startConnection·restart 시 lastMessageAt/lastTickerAt=now 리셋(워치독 간격<임계 폭주 방지) + @Scheduled initialDelay.
  - **주경로**: generation 제거(callbackFlow 지역 `running` 으로 충분 — 인스턴스 generation harmful). runTickerCollection `catch(CancellationException){throw e}` 우선. restartTickerCollection = restartMutex + tickerJob.cancelAndJoin(이중 WS 창 제거), @Scheduled 는 scope.launch offload(poolSize=2 블로킹 회피). shutdown 플래그.
  - **kill-switch+임계값 property**: `MarketDataWatchdogProperties`(enabled/staleMs/intervalMs/initialDelayMs/restartBackoffMs) — 상수 하드코딩 폐기(오판 시 재배포만이 롤백인 리스크). @Scheduled fixedDelayString/initialDelayString.
  - **관점**: 매매 정확성은 TradingEngine PRICE_STALE_THRESHOLD_MS=30_000 게이팅+REST 폴백이 이미 보호 — 이 워치독은 피드 자동복구용이라 kill-switch 로 보수적.
- 2026-07-16(1단계 seam): UpbitWebSocketClient 에 `autoConnect`(기본 true) 생성자 seam 추가 — 테스트에서 false 로 실 WS 연결 억제(§7 네트워크 회피), 운영 기본값·동작 불변. 관찰용 `subscribedMarkets()` 노출. 연결 비활성 '구조 교체' 자체는 2단계 재연결 race 리팩토링과 함께.

- **단계 분할 유지하되 2단계 스코프 확장**: 1단계 = 폴백 구독 실효화(S), 2단계 = 무수신 워치독(양 경로)+세대 가드+파싱 테스트(M), 3단계 = 풀 통합(M~L, 별도 PR 가능). "1·2단계만으로 실운영 위험 해소" 주장은 **2단계가 주 경로를 덮을 때만 성립**(plan-review critical 교정).
- **주 경로(UpbitMarketFeed) 워치독**: MarketDataIngestionService 레벨에서 **store 최신 수신 timestamp 기반 감시** — N초 무수신 시 수집 flow 재기동(현재 collect 종료 시 재시작 없는 :65-71 도 함께 해소: 재시작 루프 + backoff). callbackFlow 내부 상태를 밖으로 여는 것보다 파급이 작다. (대안이던 'feed 상태 component 승격'은 3단계 통합 때 함께)
- **폴백(UpbitWebSocketClient) 워치독**: 마지막 수신 timestamp 기반 @Scheduled — 임계 초과 시 disposable.dispose() 로 기존 doFinally→scheduleReconnect 경로 재사용.
- **재연결 race**: 연결 세대(generation) 부여, doFinally 는 자기 세대가 현재일 때만 scheduleReconnect. (이유: 핸드셰이크 >1s 장애 상황에서 중복 connect → disposable 덮어쓰기 leak·이중 수신 — UpbitWebSocketClient.kt:75-118,162-186)
- **파싱 테스트는 이 plan 스코프**: 파싱 함수 internal 분리 + 실제 Upbit ticker 프레임 fixture(정상/필드누락/비-ticker/깨진 JSON/timestamp 단위). UpbitMarketFeed.parseTickerMessage 의 `catch { null }` 무로그(:187-189)에 warn 추가. 기존 UpbitWebSocketClientTest 의 실네트워크 유발 테스트(subscribe)는 연결 비활성 구조로 교체.
- 3단계 진행 시 SSE(PriceStreamController)·엔진 폴백을 MarketDataStore 기반으로 전환 — 동작 동등성 확인 필수.

# Key Files

- `bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt` — :39-48(init 하드코딩·무의존 @Component), :75-118(connect/reconnect), :130-160(파싱), :162-186(scheduleReconnect)
- `bot/src/main/kotlin/com/trading/bot/marketdata/UpbitMarketFeed.kt` — :44-101(callbackFlow 지역 상태·재연결), :168-190(parseTickerMessage)
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` — :65-71(collect 종료 시 미재시작 — 주 경로 워치독 삽입 지점)
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :146-169(폴백 소비자), start(구독 추가 지점)
- `bot/src/main/kotlin/com/trading/bot/api/PriceStreamController.kt` — :45(SSE 구독 경로)
- `bot/src/main/kotlin/com/trading/bot/config/WatchlistProperties` — 주입 대상
- `bot/src/test/kotlin/com/trading/bot/client/UpbitWebSocketClientTest.kt` — 교체 대상

# Acceptance

- [x] 폴백 구독: 거래 티커(watchlist 밖 포함) 전부 WS 폴백 구독 커버 — TradingEngineTest(start→subscribe KRW-DOGE/ADA)·UpbitWebSocketClientTest(watchlist 주입·추가구독) green
- [x] (fix) 공개 API 노출 regression 차단: 미인증 /api/prices/** 가 watchlist 밖 티커 미노출 — PriceStreamControllerTest 회귀 green
- [x] (fix) watchlist 입력 정규화(uppercase·distinct) — WatchlistPropertiesTest green
- [x] 주 경로 워치독+재시작: runTickerCollection 재구독 루프(CancellationException rethrow) + checkTickerHealth→restartTickerCollection(cancelAndJoin) 구현, MarketDataWatchdogProperties.isStale green. 실제 재기동은 통합 성격(수동 관찰)
- [x] 폴백 워치독: checkConnectionHealth→dispose(→doFinally 세대가드 재연결) 구현, isStale green. dispose→재연결은 통합 성격
- [x] 세대 가드: shouldActForGeneration predicate 단위테스트 green(myGen==generation·shuttingDown 케이스). 실제 스레드 race 는 transport seam 없이 결정적 불가 → 수동/통합 관찰(정직 조정)
- [x] 파싱 fixture 테스트 5종 green — UpbitMarketFeedParsingTest(정상/optional누락/비-ticker/깨진JSON/timestamp), parseTickerMessage internal 분리+warn(null 반환 검증, 로그는 코드리뷰)
- [x] (PR-b) unsubscribe ref-count — WSClient 11 green(baseline∪refCounts, TradingEngine.stop→unsubscribe). subscribedTickers 단조증가(1단계 defer) 해소
- [ ] (3단계 진행 시) 상시 WS 연결 1개 — 로컬 실행·관찰(연결 로그/netstat), SSE·엔진 폴백 동등성 확인
- [ ] `./gradlew test` 전체 green

# Review Disposition

- Major 공개 API 노출(regression, PriceStreamController): **fix** — /latest·/status·/stream(미지정)을 allowedTickers(watchlist)로 제한. 원래 명시 통제 복원.
- Major watchlist 입력 정규화 부재(AppConfig): **fix** — tickerList() uppercase+distinct.
- Minor subscribedMarkets() public: **fix** — internal 강등.
- Minor 테스트 커버리지: **fix** — WatchlistProperties 정규화 + PriceStreamController 공개노출 회귀.
- Major unsubscribe 부재(전역 싱글턴 subscribedTickers 단조증가): **defer**→2단계(ref-count/엔진 union registry). 공개노출 fix로 노출 위험 제거, 잔여는 메모리/WS 부하.
- Major 재연결 race 노출 빈도 증가(subscribe→reconnect): **defer**→2단계(세대 가드 근본해결).
- Minor autoConnect test seam: **risk-accept** — JVM(jar) 부팅 안전 확인(kotlin-reflect·Spring default-param skip). AOT/native 전환 시 재검토(spring-framework#29820).
- (2a plan-review, 전 finding **fix** by 재설계): 세대 증가시점·connected clobber·scheduleReconnect 세대재확인·워치독 timestamp reset/grace/initialDelay·주경로 CancellationException/cancelAndJoin/restartMutex/shutdown·kill-switch property.
- (2a) UpbitMarketFeed 인스턴스 generation: **wontfix**(harmful) — tickerFlow 지역 running 가드로 충분.
- (2a) 세대가드 결정적 race 테스트용 transport seam: **defer**(acceptance 하향) — predicate 단위테스트 + 수동관찰.
- (2a) STALE 저유동 false-positive: **fix**(property 조정 가능) + 문서화.
- (2a codex pre-push P3) 워치독 restart TOCTOU(mutex 대기 중 late-tick): **fix** — restartMutex 안에서 isStale 재확인 후에만 cancelAndJoin.
- (2a codex pre-push P2) UpbitMarketFeed 지연 재연결 좀비 이중 WS: **fix** — awaitClose 가 재연결 스레드 interrupt + connect 가 subscribe 직후 running 재확인 dispose(close-safe). plan-review 의 'running 지역가드 충분' 판단을 codex 가 교정.

# Deferred

- unsubscribe 경로 부재 → subscribedTickers 단조 증가 (Major, UpbitWebSocketClient): 2단계 registry.
- subscribe→reconnect 재연결 race 노출 증가 (Major, UpbitWebSocketClient): 2단계 세대 가드.

# Workflow Findings

- **codex pre-push 무한 hang** · 재발조건: 다중 worktree 세션이 동시에 `git push` → codex 병렬 실행 경합 → `codex exec review`(high-reasoning) 40분+ 무한 대기(background 무관, `running codex...` 후 `OK:`/`BLOCK:` 안 나옴) · 근본: `.git/hooks/pre-push` line 168-173 codex 호출에 `timeout` wrapper 부재 → hang 시 hook·push 무한 · 수정 후보: hook 에 `timeout <N>` + hang fail 처리, codex 동시 실행 flock 직렬화(운영 자산 — 승인 후 wt→dlc) · 발생: 이 세션 다수(P2·P3 fix 후 재push 마다).
- **자기개선 사각**(왜 자동 축적 안 됐나): ① dlc-signal telemetry(`~/.claude/scripts/dlc-signal.js`)는 dlc 파이프라인 **내부** 단계만 계측 — push/pre-push hook 은 dlc Report **이후**라 사각(dlc-signals.jsonl codex 신호 0건) ② wiki `workflow-failures.md`(및 wiki/ 전체) 미구축이라 dlc "workflow 실패 wiki 기록" 규약이 무효 · 개선 후보: hook 이 codex timeout/실패를 telemetry 로 로깅→`/improve` 집계, 또는 wiki 구축. 현재는 memory [[project_prepush_codex_slow]] 수동 기록으로만 대체(hang 모드 추가함).

# Blockers

- **PR-b push 차단**: pre-push codex 무한 hang(위 Workflow Findings) → PR-b(커밋 `783b61e`, 코드 검증 완료·`:bot:test` green)가 원격 미반영. 풀려면: codex 회복 후 push(background) 또는 `CODEX_SKIP=1 git push`(hook 공식 bypass, `--no-verify` 아님).
- (기존) dead-path-cleanup 이 이 plan 의 워치독(수집 신뢰성)에 **기능 의존**: 이 plan 머지 후 착수.
