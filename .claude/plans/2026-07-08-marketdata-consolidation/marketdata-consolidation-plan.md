---
title: marketdata-consolidation — Upbit WS 수집 단일화 + half-open 고착 워치독 + 파싱 테스트 (1·2단계 done, 3단계 풀통합 진행)
status: in_progress
started: 2026-07-08
updated: 2026-07-19
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
- 2026-07-19(BLOCKER 해소·1·2단계 완결 확인): **PR-b 는 실제로 PR #44 로 origin/main 머지됨**(`5338572`). blocked 는 stale — codex hang 은 PR #45(pre-push codex 직렬화+timeout)로 해소된 것으로 보임. 검증: dangling `783b61e` 의 소스 5파일(UpbitWebSocketClient·UpbitMarketFeed·양 테스트)이 origin/main 과 **byte-identical**, TradingEngine `unsubscribe(activeTickers)` 도 origin/main:112 존재. **1단계(#36)·2단계 PR-a(#37)·PR-b(#44) 전부 머지 = 2단계 완결.** 남은 건 3단계뿐. 신규 worktree `marketdata-full-consolidation`(origin/main #46 기준)에서 3단계 착수. main 12커밋 전진(특히 #43 graceful shutdown·TradingEngine 수명주기) 반영해 Explore 재확인.
- 2026-07-19(3단계 Explore 완료): 현 구조 확정 — **상시 WS 연결 2개**: ① UpbitMarketFeed(→MarketDataIngestionService→MarketDataStore, NormalizedTicker) ② UpbitWebSocketClient(→latestPrices/sink, RealtimePrice). 둘 다 watchlist 구독 중복. UpbitWebSocketClient 소비자 3곳: PriceStreamController(SSE: priceFlow·allLatestPrices·isConnected·subscribe), TradingEngine(getRealtimePrice tier-2 폴백 + start/stop subscribe/unsubscribe), UserTradingManager(DI 주입만). TradingEngine 는 **이미 MarketDataStore 우선**(tier-1), WS tier-2, REST tier-3. SSE 는 `allowedTickers=watchlist` 로만 필터. 핵심 발견: `startBot(tickers)`(UserTradingManager:191-207)이 **임의 티커 허용**(watchlist 검증 없음) → engine 티커가 watchlist 밖일 수 있음 = 동작 동등성 유일 갭.

# Next

**3단계 풀통합** (worktree `marketdata-full-consolidation`, base origin/main #46). 1·2단계는 머지 완료 → 이 단계가 남은 전부.
- ✅ 설계 분기 확정: **b1**(UpbitWebSocketClient 완전 제거) 사용자 승인.
- ✅ 리뷰: 격리 plan-reviewer 2회 한도사망 → 메인 자체리뷰 완료(# Review Disposition). 설계 blocker 없음.

**구현 순서(TDD, 각 단계 후 `:bot:compileKotlin`; 가능하면 단계별 커밋)**:
1. **MarketDataStore reactive sink**(순수 추가·무파괴): `Sinks.many().multicast().onBackpressureBuffer<NormalizedTicker>(256)` + `fun tickerStream(): Flux<NormalizedTicker>`, `updateTicker` 끝에서 `tryEmitNext`(FAIL_ZERO_SUBSCRIBER 무시). **MarketDataStoreTest 에 emit/zero-subscriber Red→Green**. writer 전수 grep(ingestTicker 단일 확인).
2. **PriceStreamController → store**: 의존 `UpbitWebSocketClient`→`MarketDataStore`. priceFlow=`tickerStream()` UPBIT 필터+`toRealtimePrice()` 변환, sampling 유지. allLatestPrices=store 스냅샷 변환. subscribe 호출 제거. connected=store freshness. **PriceStreamControllerTest 갱신**.
3. **TradingEngine**: 생성자 `webSocketClient` 파라미터 제거, start/stop subscribe/unsubscribe 제거, getRealtimePrice tier-2 제거(store→REST 2단). **생성부 전수 grep 후 동시 갱신**. TradingEngineTest 갱신.
4. **UserTradingManager**: `upbitWebSocketClient` DI·createEngine 인자 제거. UserTradingManagerTest 갱신.
5. **제거**: UpbitWebSocketClient.kt + UpbitWebSocketClientTest.kt 삭제. RealtimePrice 는 domain 유지.
6. **검증**: `./gradlew :bot:test` green + 로컬 실행 관찰(부팅 로그 WS 연결 1개·UpbitWebSocketClient 로그 부재, SSE 스트림 수신).
- ⚠️ 주간 한도(2026-07-20 08pm 리셋) 압박 — 단계별 커밋으로 중단 대비. 중단 시 이 Next 순서로 /c 재개.

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
- **2026-07-19 3단계 확정 설계 (b1, 사용자 승인)**: UpbitWebSocketClient 를 **완전 제거**하고 상시 WS 연결을 UpbitMarketFeed 1개로 통합.
  - **MarketDataStore reactive stream**: store 에 `Sinks.many().multicast().onBackpressureBuffer<NormalizedTicker>(256)` 추가, `updateTicker` 에서 `tryEmitNext`(FAIL_ZERO_SUBSCRIBER 는 정상 drop — 현 UpbitWebSocketClient sink 규약 이식). `fun tickerStream(): Flux<NormalizedTicker>` 노출. 단일 writer = MarketDataIngestionService.ingestTicker → store.updateTicker.
  - **PriceStreamController → store 기반**: 의존 `UpbitWebSocketClient` → `MarketDataStore`. `priceFlow` = `store.tickerStream()` 를 UPBIT 필터 + `NormalizedTicker→RealtimePrice` 변환(market=`MarketPair.toUpbitFormat`, tradePrice=price, signedChangeRate=changeRate24h, accTradePrice24h=quoteVolume24h, high/low=highPrice24h/lowPrice24h, timestamp=toEpochMilli). `allLatestPrices` = store 스냅샷 변환. **subscribe 호출 제거**(요청 티커 ⊆ allowedTickers ⊆ watchlist = 이미 상시 수집). `connected` = store freshness(watchlist 티커 중 staleMs 내 수신 존재) 파생.
  - **TradingEngine**: `getRealtimePrice` tier-2(WS) 제거 → tier-1 store + tier-3 REST 만. `start`/`stop` 의 `webSocketClient?.subscribe/unsubscribe` 제거. 생성자 `webSocketClient` 파라미터 제거. watchlist 밖 티커는 REST 폴백(b1 승인).
  - **UserTradingManager**: `upbitWebSocketClient` DI·`createEngine` 의 `webSocketClient=` 인자 제거.
  - **제거**: `UpbitWebSocketClient.kt` + `UpbitWebSocketClientTest.kt` + `RealtimePrice` 는 SSE 응답 DTO 로 유지(domain 잔존).
  - **동등성 caveat**: watchlist 티커·SSE 는 완전 동등. watchlist 밖 engine 티커만 실시간성 WS→REST 강등(staleness 게이팅이 매매 정확성 보호 — 얼어붙은 가격 매매 없음).

# Key Files

3단계(b1) 대상 (base origin/main #46):
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataStore.kt` — reactive sink 추가·`tickerStream()` 노출·`updateTicker` emit (현 순수 store, 29-38 updateTicker)
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` — 단일 writer 확인(ingestTicker:160-172), 변경 없음(store.updateTicker 가 emit 트리거)
- `bot/src/main/kotlin/com/trading/bot/api/PriceStreamController.kt` — WS→store 의존 교체, DTO 변환, subscribe 제거, connected 파생
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt` — :42(webSocketClient 파라미터 제거), :80·112(subscribe/unsubscribe 제거), :166-189(getRealtimePrice tier-2 제거)
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt` — :53·299(upbitWebSocketClient DI·인자 제거)
- `bot/src/main/kotlin/com/trading/bot/domain/RealtimePrice.kt` — SSE 응답 DTO 로 유지(변환 소스 = NormalizedTicker)
- **제거**: `bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt` + `bot/src/test/kotlin/com/trading/bot/client/UpbitWebSocketClientTest.kt`
- 갱신 테스트: `PriceStreamControllerTest.kt`, `TradingEngineTest.kt`, `UserTradingManagerTest.kt`, `MarketDataStoreTest.kt`(sink 신규)

# Acceptance

- [x] 폴백 구독: 거래 티커(watchlist 밖 포함) 전부 WS 폴백 구독 커버 — TradingEngineTest(start→subscribe KRW-DOGE/ADA)·UpbitWebSocketClientTest(watchlist 주입·추가구독) green
- [x] (fix) 공개 API 노출 regression 차단: 미인증 /api/prices/** 가 watchlist 밖 티커 미노출 — PriceStreamControllerTest 회귀 green
- [x] (fix) watchlist 입력 정규화(uppercase·distinct) — WatchlistPropertiesTest green
- [x] 주 경로 워치독+재시작: runTickerCollection 재구독 루프(CancellationException rethrow) + checkTickerHealth→restartTickerCollection(cancelAndJoin) 구현, MarketDataWatchdogProperties.isStale green. 실제 재기동은 통합 성격(수동 관찰)
- [x] 폴백 워치독: checkConnectionHealth→dispose(→doFinally 세대가드 재연결) 구현, isStale green. dispose→재연결은 통합 성격
- [x] 세대 가드: shouldActForGeneration predicate 단위테스트 green(myGen==generation·shuttingDown 케이스). 실제 스레드 race 는 transport seam 없이 결정적 불가 → 수동/통합 관찰(정직 조정)
- [x] 파싱 fixture 테스트 5종 green — UpbitMarketFeedParsingTest(정상/optional누락/비-ticker/깨진JSON/timestamp), parseTickerMessage internal 분리+warn(null 반환 검증, 로그는 코드리뷰)
- [x] (PR-b) unsubscribe ref-count — WSClient 11 green(baseline∪refCounts, TradingEngine.stop→unsubscribe). subscribedTickers 단조증가(1단계 defer) 해소
- [ ] (3단계) MarketDataStore reactive stream: `updateTicker`→`tickerStream()` emit 관찰, zero-subscriber 시 정상 drop(FAIL_ZERO_SUBSCRIBER 로그無) — MarketDataStoreTest 신규 green
- [ ] (3단계) SSE store 기반 전환: `/api/prices/stream`·`/latest`·`/status` 가 store 데이터로 응답, allowedTickers(watchlist) 필터·상한 유지, NormalizedTicker→RealtimePrice 변환 정확(market=KRW-BTC 포맷) — PriceStreamControllerTest 갱신 green
- [ ] (3단계) TradingEngine 폴백 store+REST 2단: `getRealtimePrice` store 신선 시 store, stale/miss 시 REST — TradingEngineTest 갱신 green(WS 주입 제거 후 컴파일·동작)
- [ ] (3단계) UpbitWebSocketClient·Test 제거 후 컴파일·DI 정상(UserTradingManager createEngine WS 인자 없음) — `./gradlew :bot:compileKotlin` green
- [ ] (3단계) 상시 WS 연결 **1개** — 로컬 실행·관찰(부팅 로그에 UpbitMarketFeed WS 연결 1건, UpbitWebSocketClient 연결 로그 부재), SSE 스트림 수신·엔진 매매 동등성
- [ ] `./gradlew :bot:test` 전체 green

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
- **(3단계 리뷰 방식, 2026-07-20)**: 격리 plan-reviewer subagent 2회 연속 **API 한도(세션→주간)로 강제종료**·codex 미가용 → §9 "미가용 시 생략 사유 기록 후 메인이 같은 관점 직접 점검"에 따라 **메인 자체 비판 리뷰**로 대체. 아래 finding 은 그 결과.
- (3단계 self-review) TradingEngine 생성부 누락 위험(Major): **fix** — `webSocketClient` 파라미터 제거 시 프로덕션(`UserTradingManager.createEngine`) + **모든 테스트 생성부** 동시 갱신. 구현 첫 단계에서 생성부 전수 grep.
- (3단계 self-review) store sink 단일 writer 보장(Major): **fix** — ticker 쓰기가 `MarketDataIngestionService.ingestTicker`→`store.updateTicker` 단일 경로인지, `CandleAggregator`/기타 소비자가 sink 도입에 안 깨지는지 구현 시 확인(writer grep).
- (3단계 self-review) SSE `connected` 의미 이동(Minor): **fix(수용)** — 소켓 open→store freshness(watchlist 티커 staleMs 내 수신). 더 정확한 헬스 신호지만 부팅 warmup 중 false 가능 → 문서 1줄. 컨트롤러 sampling 유지 + `tryEmitNext`(비블로킹)로 ingestion 안 막음.
- (3단계 self-review) watchlist 밖 티커 REST 부하 증가(Minor): **risk-accept** — b1 승인. staleness 게이팅이 매매 정확성 보호(얼어붙은 가격 매매 없음). 대량 out-of-watchlist 운용 시에만 유의.
- (3단계 self-review) sink 위치(arch): **결정** — MarketDataStore 에 배치(단일 진실원=스냅샷+스트림 한 의존; ingestion 배치는 API→collection 역의존 유발). reactor 는 이미 전역 의존이라 신규 의존 없음.
- (3단계 self-review) 포맷/필드 매핑: **검증완료(no-op)** — `signed_change_rate`·`acc_trade_price_24h`·`high/low_price` 를 두 파서가 동일 원본에서 파생 → RealtimePrice 변환 무손실, `toUpbitFormat("BTC/KRW")="KRW-BTC"` allowedTickers 매칭 정확.

# Deferred

- unsubscribe 경로 부재 → subscribedTickers 단조 증가 (Major, UpbitWebSocketClient): 2단계 registry.
- subscribe→reconnect 재연결 race 노출 증가 (Major, UpbitWebSocketClient): 2단계 세대 가드.

# Workflow Findings

- **codex pre-push 무한 hang** · 재발조건: 다중 worktree 세션이 동시에 `git push` → codex 병렬 실행 경합 → `codex exec review`(high-reasoning) 40분+ 무한 대기(background 무관, `running codex...` 후 `OK:`/`BLOCK:` 안 나옴) · 근본: `.git/hooks/pre-push` line 168-173 codex 호출에 `timeout` wrapper 부재 → hang 시 hook·push 무한 · 수정 후보: hook 에 `timeout <N>` + hang fail 처리, codex 동시 실행 flock 직렬화(운영 자산 — 승인 후 wt→dlc) · 발생: 이 세션 다수(P2·P3 fix 후 재push 마다).
- **자기개선 사각**(왜 자동 축적 안 됐나): ① dlc-signal telemetry(`~/.claude/scripts/dlc-signal.js`)는 dlc 파이프라인 **내부** 단계만 계측 — push/pre-push hook 은 dlc Report **이후**라 사각(dlc-signals.jsonl codex 신호 0건) ② wiki `workflow-failures.md`(및 wiki/ 전체) 미구축이라 dlc "workflow 실패 wiki 기록" 규약이 무효 · 개선 후보: hook 이 codex timeout/실패를 telemetry 로 로깅→`/improve` 집계, 또는 wiki 구축. 현재는 memory [[project_prepush_codex_slow]] 수동 기록으로만 대체(hang 모드 추가함).

# Blockers

- ~~PR-b push 차단(codex hang)~~ **해소(2026-07-19)**: PR-b 는 PR #44 로 머지됨(codex hang 은 #45 로 fix). 1·2단계 완결.
- (없음) 3단계 blocker 없음 — 설계 분기(b1/b2) 사용자 확정 대기 중.
