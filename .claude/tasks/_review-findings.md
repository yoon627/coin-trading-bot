# Review Findings (confirmed 54) — workflow w89i4yu1g

심각도별: critical 7 / high 9 / medium 20 / low 18

## critical

### [trading-correctness] No double-buy / position guard: bot re-buys every interval while signal holds, draining balance
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:152-165
- **문제**: processTicker only checks state.position for the SELL branch (line 133). The BUY branch runs unconditionally when strategy.shouldBuy is true — it never checks state.position, state.boughtToday, or any in-flight order. Compounding this, TradingState.markBought (bot/.../domain/TradingState.kt:40-55) updates avgBuyPrice/holdVolume but NEVER sets boughtToday=true, so boughtToday is permanently false and DailyResetManager.resetDaily()/shouldSellForDailyReset gating on it is dead. With tradingProperties.intervalSeconds defaulting to 10, any signal that stays true (e.g. VolatilityBreakout: currentPrice > targetPrice) triggers a new buy every ~10 seconds. PositionManager.buy invests minOf(krwBalance, maxInvestAmount) each time, so the bot will fire repeated 100k-KRW market buys (averaging up via markBought) until KRW is exhausted.
- **수정**: Before buying, return early if state.position is true (or implement explicit, bounded averaging-up rules). Set boughtToday=true inside markBought (or in the buy path) and gate buys on !state.boughtToday so DailyResetManager actually enforces one entry per trading day. Add a test asserting a second buy is suppressed while position==true / boughtToday==true.

### [trading-correctness] Fabricated fill quantity ignores fee/slippage; sell submits inflated volume and gets rejected
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt:50-60
- **문제**: buy() places a market order then computes volume = investAmount / currentPrice (line 59) and stores it as holdVolume, completely ignoring the returned Order object (executedVolume / fill price). Upbit charges 0.05% on KRW pairs and the fee is taken out of the coin received, so the actual filled quantity is roughly investAmount*(1-0.0005)/fillPrice — strictly less than what is recorded. sell() (lines 82-89) then submits volume = state.holdVolume.toString() for a market ask. Because the recorded volume exceeds true holdings, Upbit rejects the order (insufficient balance). The catch at line 106 swallows the error and returns null, and state.markSold() is never reached — but on the next loop the engine re-evaluates and may retry indefinitely, OR (worse, via the take-profit/stop-loss path) the position never exits at all.
- **수정**: Read the placeOrder response: poll getOrder(uuid) until state=done and use sum of actual trade fills (executed_volume net of fee) for holdVolume and a volume-weighted fill price for avgBuyPrice. For sells, query the live coin balance from getAccounts() and submit that (as executeSellAll already does at TradeExecutionService.kt:86), or floor to the exchange quantity precision below the true balance. Use BigDecimal throughout.

### [trading-correctness] Orders are fire-and-forget: rejected and partial fills not handled, state mutated as if filled
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt:40-110
- **문제**: Both buy() and sell() call upbitClient.placeOrder and immediately mutate TradingState (markBought / markSold) and emit a TradeRecord, without ever confirming the order reached state='done'. Upbit market orders can be partially filled or rejected (min-order, price-band, balance). On buy, markBought records the full intended size even if only part filled or nothing filled. On sell, markSold() clears the position (avgBuyPrice/holdVolume=0) on line 104 BEFORE confirming the ask actually executed — if the ask is later cancelled/partially filled, the bot believes it is flat while still holding coins, and its risk controls go dark for that residual.
- **수정**: Confirm fills via getOrder(uuid) before mutating state; reconcile holdVolume/avgBuyPrice from actual executed fills. On rejection, leave state unchanged and surface the error (do not return null silently). Only call markSold() after the ask is confirmed fully filled; for partial fills, decrement holdVolume by executed_volume.

### [security-auth] Upbit 키 암호화 키가 JWT 서명 키와 동일 secret에서 파생 (키 용도 분리 실패)
- **위치**: bot/src/main/kotlin/com/trading/bot/security/SecretKeyMaterialProvider.kt:27
- **문제**: ✅확실. encryptionKeyBytes()(SecretKeyMaterialProvider.kt:26-29)는 `appProperties.encryptionSecret.ifBlank { appProperties.jwtSecret }` 로, APP_ENCRYPTION_SECRET 이 비면 JWT_SECRET 으로 폴백한다. 두 키 모두 deriveKey()=SHA-256(secret)(line 44-47)로 동일하게 파생되므로, encryption secret 미설정 시 JWT 서명 키와 사용자 Upbit access/secret 키 암호화 키가 완전히 동일해진다. application.yml:46-48 과 application-prod.yml:24-26 모두 두 값을 별개 env(JWT_SECRET, APP_ENCRYPTION_SECRET)로 받지만 후자 미설정을 막는 검증이 없어 폴백이 조용히 발동한다. 또한 SHA-256(secret)을 그대로 키로 쓰는 것은 KDF(HKDF/PBKDF2)가 아니라 단순 해시라 salt/iteration이 없다.
- **수정**: prod에서 APP_ENCRYPTION_SECRET 을 필수화하고 JWT_SECRET 폴백 제거(resolveSecret처럼 prod면 throw). 두 secret을 물리적으로 분리하고, 암호화 키는 HKDF로 별도 info 라벨('jwt' vs 'upbit-keys')을 줘 파생하거나 KMS/envelope encryption 도입. 최소한 같은 secret이라도 HKDF info 분리로 키를 다르게 만들 것.

### [upbit-integration] retryOnRateLimit re-submits non-idempotent placeOrder on 429 — duplicate market orders
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitClientImpl.kt:81 (and 68-82, 127-137)
- **문제**: placeOrder() wraps the POST /v1/orders call in Mono.defer{...}.retryOnRateLimit(). retryOnRateLimit (line 127) retries up to 2 times whenever the failure is a UpbitApiException with statusCode==429. Because the request is built inside Mono.defer, every retry rebuilds the body AND a brand-new JWT with a fresh nonce (UpbitAuthProvider.createToken uses UUID.randomUUID() per call, line 16). Upbit therefore treats each retry as a distinct new order, not a replay. A 429 returned AFTER Upbit already accepted the order (e.g. response-side throttling, or a 429 on a retried attempt that itself succeeded server-side) causes the same bid/ask to be placed 2-3 times. Callers PositionManager.buy (line 50, ord_type=price market buy) and PositionManager.sell (line 82, ord_type=market sell), plus TradeExecutionService.executeBuy/executeSellAll/executeSellVolume (lines 37/81/128), all go through this path. Order placement is non-idempotent and Upbit supports no client-supplied idempotency key on this code path (the `identifier` field is not used).
- **수정**: Do not retry order creation. Split retryOnRateLimit so only idempotent GETs (accounts/candles/ticker/getOrder) retry on 429; placeOrder must either never retry, or retry only after confirming via getOrder/identifier that no order was created. Add an `identifier` (client order id) to OrderRequest and dedupe server-side. Add a regression test asserting placeOrder issues exactly one POST even when the first response is 429.

### [api-validation] 공개 SSE 엔드포인트가 전역 WebSocket 구독을 무제한·무검증으로 변경 (인증 없는 자원 고갈)
- **위치**: bot/src/main/kotlin/com/trading/bot/api/PriceStreamController.kt:22-29
- **문제**: ✅확실. SecurityConfig.kt:36에서 /api/prices/** 가 permitAll이라 인증 없이 호출 가능하다. streamPrices()는 요청 tickers를 it.uppercase()만 거쳐 RequestValidators.normalizeMarkets(1~20개 제한·포맷 검증)를 우회하고 webSocketClient.subscribe(tickerSet.toList())로 그대로 넘긴다. UpbitWebSocketClient.kt:52-57의 subscribe()는 새 ticker를 전역 ConcurrentHashMap.newKeySet(subscribedTickers, 상한·eviction 없음, line 29)에 누적하고, 새 ticker가 하나라도 있으면 reconnect()(line 56→68-71)를 호출해 공유 upstream WS 연결을 dispose 후 재연결한다. unsubscribe 경로도 없다. 따라서 인증 없는 외부 요청이 매번 다른 ticker 문자열로 호출하면 (a) subscribedTickers 무한 증가 → buildSubscriptionMessage(line 110-118)의 codes 페이로드 무한 증가, (b) 호출마다 upstream Upbit WS reconnect 폭주를 일으킬 수 있다.
- **수정**: /api/prices/stream을 authenticated()로 옮기거나, 최소한 streamPrices/getLatestPrices의 tickers를 RequestValidators.normalizeMarkets로 검증(개수 상한 적용)한다. subscribe는 전역 구독 변경 대신 '이미 구독된 ticker만 필터'하도록 분리하거나, 동적 구독에 전역 ticker 총량 캡과 디바운스(매 호출 reconnect 금지)를 둔다.

### [build-test-quality] Dockerfile 이 존재하지 않는 collector/research 모듈 파일을 COPY → 이미지 빌드 실패
- **위치**: Dockerfile:7-8
- **문제**: Dockerfile 이 `COPY collector/build.gradle.kts ./collector/` (line 7) 와 `COPY research/build.gradle.kts ./research/` (line 8) 를 수행하지만 collector/ 와 research/ 디렉토리는 리포지토리에 존재하지 않는다(git ls-files 에 0 건, ls 결과 No such file or directory). settings.gradle.kts 에도 `include("common", "bot")` 만 등록돼 있다. Docker COPY 는 소스 미존재 시 즉시 실패하므로 `docker build .` 가 build 스테이지 초반에서 중단된다.
- **수정**: collector/research 모듈을 실제로 추가하거나, Dockerfile line 7-8 의 두 COPY 라인을 삭제한다. 모듈 구성(settings.gradle.kts)·Dockerfile·docker-compose·CI 를 한 소스로 일치시킨다.

## high

### [trading-correctness] Backtest look-ahead: entry executes at the same candle's close used to generate the signal
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt:80-136
- **문제**: In simulateTrades, currentPrice = chronological[i].tradePrice (candle i's CLOSE) and window = subList(..., i+1) includes candle i itself. processEntry then enters at state.buyPrice = currentPrice (= the same close). For VolatilityBreakout, shouldBuy returns currentPrice > targetPrice where targetPrice depends on today's open + yesterday's range — but the simulation only learns the close after the full day completes, then 'buys' at that close. Real trading would have to enter intraday at the breakout level, not the settled close. RSI/MACD/Bollinger entries likewise condition on the just-closed bar and fill at its close. This biases backtest returns upward versus achievable fills.
- **수정**: Generate the signal from candles up to and including i-1 (the last fully closed bar) and execute the fill at candle i's open (or the breakout/target price for breakout strategies). Never use candle i's close both to decide and to fill.

### [trading-correctness] investRatio is silently ignored; position sizing uses full balance up to maxInvestAmount
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt:139-141
- **문제**: calculateInvestAmount returns minOf(krwBalance, tradingProperties.maxInvestAmount). TradingProperties.investRatio (default 0.1, common/.../config/TradingProperties.kt:9) is never referenced anywhere in the live engine (rg confirms only BacktestEngine has its own investRatio). An operator who configures investRatio=0.1 expecting 10% sizing will instead get full-balance-up-to-100k per order. Combined with the missing double-buy guard, this is what makes the over-allocation in finding #1 so severe.
- **수정**: Apply investRatio: investAmount = minOf(krwBalance * investRatio, maxInvestAmount), and clamp to >= MIN_ORDER_AMOUNT_KRW. Add a unit test pinning the sizing formula.

### [concurrency-reactive] WebSocket reconnect가 disposable을 단일 추적해 연결 누수, @PreDestroy 후에도 재연결
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt:46-50,68-71,103-107,142-163
- **문제**: reconnect()는 disposable?.dispose() 직후 connect()로 즉시 새 연결을 만들고(68-71), 동시에 기존 연결의 doFinally(103-105)가 별도 daemon 스레드로 scheduleReconnect를 또 예약한다(142-163). subscribe()(52-57)는 mutex 없이 호출되므로 동시 호출 또는 dispose/doFinally 경합 시 여러 connect가 중첩되어 마지막 disposable만 추적되고 이전 WebSocket은 살아남아 누수된다(중복 ticker 스트림·중복 sink emit). 또한 @PreDestroy destroy()(46-50)는 connected=false와 dispose만 하고 subscribedTickers를 비우거나 running 플래그를 닫지 않는다. dispose가 유발한 doFinally가 scheduleReconnect 조건(connected=false && subscribedTickers.isNotEmpty)을 만족시키면 종료 도중 새 연결을 띄운다.
- **수정**: subscribe/reconnect/connect/scheduleReconnect를 단일 lock으로 직렬화하고, UpbitMarketFeed처럼 running:AtomicBoolean 도입해 destroy()에서 running=false로 설정 후 dispose. doFinally의 재연결은 running.get()일 때만 수행.

### [security-auth] Rate limit이 Redis 부재 시 완전 fail-open — 로그인 브루트포스/주문 폭주 무방비
- **위치**: bot/src/main/kotlin/com/trading/bot/config/RateLimitFilter.kt:29
- **문제**: ✅확실. RateLimitFilter.kt:29 `if (redisTemplate == null) return chain.filter(exchange)` — Redis 미구성 시 전 경로 rate limit이 무력화된다. RedisConfig.kt:13 은 `@ConditionalOnProperty(redis.enabled, havingValue=true, matchIfMissing=false)` 라 redis.enabled 미설정/false면 ReactiveRedisTemplate 빈이 없어 생성자 주입값이 null이 된다. base application.yml 에는 redis.enabled 키 자체가 없고 RedisAutoConfiguration 을 exclude(application.yml:12-15), application-prod.yml:21-22 의 기본값도 `redis.enabled: false`(REDIS_ENABLED:false)다. docker-compose.yml:18 만 REDIS_ENABLED=true 로 켜므로, 그 compose를 안 쓰는 모든 배포/로컬/테스트에서 /api/auth/login 브루트포스, /api/trade/* 주문 폭주가 무제한 허용된다. 또한 켜져 있어도 비-auth 경로 키가 클라이언트 제공 헤더 X-User-Id(line 45-46)라서 헤더만 회전하면 limit 우회 가능하다.
- **수정**: prod에서 Redis 부재 시 기동 실패(fail-closed) 또는 in-memory 폴백 리미터 적용. rate-limit 키를 클라이언트 헤더가 아니라 인증된 principal(currentUserId)로 결정(헤더 X-User-Id 사용 제거). 로그인은 IP+username 조합 + 점증 backoff/lockout 추가.

### [persistence-data] 거래 기록 2-테이블 쓰기에 트랜잭션 없음 — audit 영구 불일치
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/TradeExecutionService.kt:162-186
- **문제**: ✅확실. saveAndNotify 는 tradeRecordRepository.save(record) (trade_records) 와 tradeExecutionRepository.save(executionEntity) (trade_executions) 를 두 개의 독립적인 R2DBC 호출로 순차 실행한다. 트랜잭션 경계가 없다 (코드베이스 전체에 @Transactional / TransactionalOperator 가 0건; rg 로 확인). 주석(176-177줄)도 '별도 트랜잭션이라 record 는 남는다'고 인정한다. 첫 save 성공 후 둘째 save 실패 시 trade_records 에는 행이 있지만 trade_executions 에는 없는 상태가 영구히 남는다. R2DBC 에서 suspend fn 에 @Transactional 을 붙여도 동작하지 않는 함정이 있어, TransactionalOperator 를 명시적으로 써야 한다.
- **수정**: 두 save 를 하나의 트랜잭션으로 묶는다. R2DBC suspend 환경에서는 TransactionalOperator.transactional(mono { ... }) 또는 코루틴용 transactionalOperator.executeAndAwait { ... } 를 사용한다. 단순 @Transactional on suspend fn 은 R2DBC 에서 트랜잭션이 전파되지 않으므로 금지.

### [persistence-data] 1분봉 영속화 멱등성 없음 — UNIQUE 위반 조용히 삼킴
- **위치**: bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt:47-62
- **문제**: ✅확실. market_candles 는 UNIQUE(exchange, market, interval_minutes, open_time) (V10:32) 을 가지나, persistCandle 은 단순 INSERT(save)만 수행한다. 동일 분봉이 재수신되면(웹소켓 재연결·중복 push) duplicate key 예외가 나고, .subscribe({}, { log.warn })(62줄)로 조용히 warn 처리만 된다. ON CONFLICT 처리가 없어 update-on-conflict 가 불가능하고, 후행 데이터(확정된 종가/거래량)가 반영되지 않는다.
- **수정**: INSERT ... ON CONFLICT (exchange, market, interval_minutes, open_time) DO UPDATE SET high=GREATEST(...), low=LEAST(...), close=EXCLUDED.close, volume=EXCLUDED.volume ... 형태의 명시적 upsert @Query 를 추가. 단순 save() 대신 사용.

### [persistence-data] 리더보드: 전 유저 SELL 레코드를 메모리 로드 후 클라이언트 측 LIMIT
- **위치**: bot/src/main/kotlin/com/trading/bot/persistence/TradeRecordRepository.kt:51-56
- **문제**: ✅확실. findAllSells 는 findBySideAndPnlPercentIsNotNull("SELL") (derived query, SQL 에 LIMIT 없음) 로 모든 유저의 모든 SELL 행을 Flux 로 스트리밍한 뒤 .take(1000) 을 클라이언트 측에서 적용한다. LeaderboardController.kt:31-32 는 이를 받아 groupBy { it.userId } 로 메모리에서 집계한다. ORDER BY 도 없어 어떤 1000건이 잘릴지 비결정적이며, 공개 유저 수와 무관하게 전체 SELL 을 읽는다.
- **수정**: DB 측 집계 쿼리로 전환: SELECT user_id, COUNT(*), SUM(CASE WHEN pnl_percent>0 THEN 1 ELSE 0 END), SUM(pnl_percent) FROM trade_records WHERE side='SELL' AND pnl_percent IS NOT NULL AND user_id IN (:publicUserIds) GROUP BY user_id. idx_trade_records_side_pnl(V9) 가 이를 지원한다.

### [api-validation] ChartController count가 음수/거대값 검증 없이 take()와 DB LIMIT으로 전달 (500 및 부하)
- **위치**: bot/src/main/kotlin/com/trading/bot/api/ChartController.kt:32-48
- **문제**: ✅확실. getCandles/getIndicators의 count(@RequestParam, 기본 100/50)에 상·하한 검증이 없다. count가 음수면 marketDataStore.getCandles(...)가 buffer.take(count)(MarketDataStore.kt:69)를 호출하는데, Kotlin stdlib의 Iterable.take(n)은 n<0일 때 IllegalArgumentException('Requested element count -N is less than zero')을 던진다 → ResponseStatusException이 아니므로 SafeErrorAttributes를 거쳐 500으로 떨어진다(ChartControllerTest의 '500 누출 방지' 의도와 모순). count가 거대값이면 findRecent(..., :limit)(MarketDataRepository.kt:25, LIMIT :limit)으로 무제한 DB 조회 + collectList()로 전체 메모리 적재가 발생한다. 추가로 market 파라미터는 normalizeMarket 검증 없이 store/DB lookup과 (Strategy 외) 응답에 그대로 쓰인다.
- **수정**: count를 coerceIn(1, MAX)로 클램프(예: TradeHistoryController.sanitizeTradeLimit 패턴 재사용)하고, market은 requestValidators.normalizeMarket로 검증한다. compare의 markets도 split 후 개수 상한을 둔다.

### [build-test-quality] CI 와 docker-compose 가 존재하지 않는 collector 모듈/Dockerfile 참조
- **위치**: .github/workflows/deploy.yml:50-58
- **문제**: deploy.yml:50-58 의 'Build and push collector image' 스텝이 `file: collector/Dockerfile` 을 사용하지만 collector/Dockerfile 은 존재하지 않는다(ls: No such file or directory). docker-compose.yml:124-128 의 collector 서비스도 `dockerfile: collector/Dockerfile` 을 가리키고, deploy.yml:83 의 `docker compose pull app collector` 와 line 86 `up -d` 도 collector 이미지를 기대한다. settings.gradle.kts:6 에는 collector 가 없다.
- **수정**: collector 모듈을 추가하거나, deploy.yml 의 collector 빌드/배포 스텝과 docker-compose.yml 의 collector 서비스를 제거한다.

## medium

### [trading-correctness] Money math on Double + raw Double.toString for order volume
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt:55,87
- **문제**: Order amounts and quantities flow through Double the whole way. The buy price uses floor(investAmount).toLong() (OK-ish for KRW integer amounts), but sell volume is state.holdVolume.toString() (line 87), which can emit scientific notation (e.g. '1.0E-4') or excessive precision that the Upbit API rejects or truncates, and there is no rounding to Upbit's per-market quantity precision. avgBuyPrice averaging in TradingState.markBought (lines 43-45) accumulates floating-point error over repeated buys.
- **수정**: Use BigDecimal for amounts/quantities and format volume with a fixed plain-string (BigDecimal.toPlainString) rounded down to the market's quantity tick size. Validate against min-order-amount after rounding.

### [trading-correctness] RSI never applies Wilder smoothing; effectively a 14-period SMA on only the last 15 candles
- **위치**: common/src/main/kotlin/com/trading/common/strategy/Indicators.kt:17-42
- **문제**: calculateRsi takes only period+1 closes (take(period+1)), producing exactly `period` gains/losses. avgGain/avgLoss are seeded as the simple average of all `period` values, and the Wilder smoothing loop `for (i in period until gains.size)` never executes because gains.size == period. So regardless of how many candles the caller passes (RsiBounce passes >=16, others pass ~30), RSI is always a plain SMA over the most recent 14 deltas, not the standard Wilder RSI. The identical bug exists in common/.../indicator/Indicators.kt:17-41. RsiBounce/GoldenCross/MacdCross thresholds (e.g. <70, crossing 30) were presumably calibrated against true RSI.
- **수정**: Either feed more than period+1 candles and let Wilder smoothing run over the extra history, or document and rename it as SMA-RSI. Pin behavior with a test against a known RSI reference series. Consolidate the two duplicate Indicators implementations.

### [trading-correctness] Two divergent Indicators implementations risk one-sided fixes
- **위치**: common/src/main/kotlin/com/trading/common/strategy/Indicators.kt:7
- **문제**: There are two parallel objects named Indicators: common/.../strategy/Indicators.kt (operates on Candle, used by all live strategies) and common/.../indicator/Indicators.kt (operates on NormalizedCandle). They duplicate calculateRsi/Macd/Bollinger/Ma/Ema with the same Wilder-smoothing bug and the same newest-first ordering assumptions. The strategy path converts NormalizedCandle->Candle via toLegacyCandle and uses the strategy/ one, so the indicator/ copy may be partially dead, but any future fix to one will silently miss the other.
- **수정**: Collapse to a single indicator implementation parameterized by a close-price extractor, delete the duplicate, and route all callers through it.

### [trading-correctness] Backtest fee model omits slippage, min-order, partial fills, and uses flat round-trip fee
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/BacktestEngine.kt:109,142
- **문제**: Exit/close apply netPnl = pnl - (feeRate*2*100), i.e. a flat 2x fee on the percentage return. This ignores slippage on market fills, the 5000 KRW minimum order, partial fills, and the fact that fees apply to notional (not symmetric on a % basis when compounding state.balance). The market filter (line 127-128) and entry also never enforce min-order viability. Together with the look-ahead in #5, backtest output is optimistic.
- **수정**: Model fee on notional at both legs, add a slippage assumption for market orders, and skip trades below min-order size. Reconcile the fee/sizing model with the live PositionManager so backtest and live agree.

### [trading-correctness] buy/sell exceptions swallowed, hiding failed real-money orders
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PositionManager.kt:71-74,106-109
- **문제**: Both buy() and sell() wrap the entire order in a broad try/catch (Exception) that only log.error(...message) (no stack trace, no error type) and return null. A rejected order, a network timeout that actually placed the order, or an auth failure all look identical to the engine and to operators. There is no alerting, no retry-with-idempotency, and on sell failure the engine simply continues without exiting the position.
- **수정**: Narrow the catch, log the exception object (stack trace) and Upbit error code, send an alert (Discord) on order failure, and distinguish 'order placed but unconfirmed' from 'order not placed'. Never clear position state on an unconfirmed sell.

### [trading-correctness] syncPosition runs once at startup; manual/external trades and avgBuyPrice drift untracked
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:89-91
- **문제**: runLoop calls positionManager.syncPosition once per ticker before the loop. After that, TradingState is the sole source of truth, mutated only by the engine's own buy/sell. Manual sells via TradeExecutionService, external transfers, or partial fills are never reconciled back into TradingState, so avgBuyPrice/holdVolume/position can drift from the real Upbit balance for the lifetime of the engine.
- **수정**: Periodically re-sync TradingState from getAccounts() (e.g. each loop or every N loops), and reconcile after every fill confirmation.

### [concurrency-reactive] activeStrategy가 동기화 없는 공유 가변 상태 (가시성 미보장)
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:47,77-82,126
- **문제**: activeStrategy는 일반 var(47)로, WebFlux 요청 경로의 setStrategy(77-82)에서 쓰이고 엔진 루프(Dispatchers.Default)의 processTicker(126)에서 읽힌다. @Volatile/atomic/confinement 없이 서로 다른 스레드가 읽고 쓰므로 전략 변경의 가시성과 적용 시점이 정의되지 않는다. activeTickers(48) var도 start()에서 쓰고 runLoop/getActiveTickers에서 읽히는 동일 문제가 있다.
- **수정**: activeStrategy/activeTickers를 @Volatile var 또는 AtomicReference로 변경. 더 견고하게는 strategy 변경을 엔진 루프와 동일 스레드/액터로 전달.

### [security-auth] 약한 비밀번호 정책 — 길이 8자만 검사, 복잡도/유출목록/길이 상한 없음
- **위치**: bot/src/main/kotlin/com/trading/bot/api/RequestValidators.kt:22
- **문제**: ✅확실. validatePassword(RequestValidators.kt:22-26)는 `password.length < 8` 만 검사한다. 복잡도·흔한 비밀번호 차단·상한 없음. bcrypt(SecurityConfig.kt:45) 자체는 적절하나 bcrypt는 72바이트 초과를 잘라내므로 상한 미설정 시 사용자 혼동/DoS(매우 긴 입력 해싱 비용) 여지가 있다. rate limit이 fail-open(위 항목)인 환경과 결합되면 '8자 단순 비밀번호 + 무제한 시도'로 브루트포스가 현실적 위협이 된다.
- **수정**: 최소 길이 상향(12자 권장) + 흔한 비밀번호 차단(예: 상위 목록 검사) 또는 zxcvbn류 강도 검사. 상한(예: 64~72자) 설정. 로그인 실패 backoff/lockout 병행.

### [upbit-integration] query_hash computed over URL-encoded string; spec requires non-URL-encoded query string, and body is JSON not the hashed string
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitClientImpl.kt:70-72
- **문제**: placeOrder builds queryString by URLEncoder.encode()-ing each key and value, then passes it to authProvider.authorizationHeader(queryString), which SHA-512 hashes that exact (URL-encoded) string in UpbitAuthProvider.createToken (line 21). Upbit's official auth reference computes query_hash as sha512(unquote(urlencode(params))) — i.e. the NON-URL-encoded query string (verified at https://global-docs.upbit.com/reference/auth.md). For current values (market=KRW-BTC, side=bid, numeric prices) URLEncoder leaves them unchanged so signing happens to pass, but any value requiring percent-encoding (or a future param) yields a hash over `a%2Bb` while Upbit hashes `a+b`, causing a 401 invalid query_hash. Compounding this, the request is sent via .bodyValue(params) as a JSON map (line 77) while the hash is derived from a query string; the codebase already has the correct non-encoded OrderRequest.toQueryString() (Order.kt:30) but does not use it.
- **수정**: Hash the non-URL-encoded query string: use request.toQueryString() (Order.kt:30) as the query_hash input so body params and hash input come from one source, and remove the URLEncoder.encode call from the hash input. Add a unit test that includes a value requiring encoding and asserts query_hash equals sha512 of the raw `k=v&k=v` string.

### [upbit-integration] processMessage swallows all exceptions silently — websocket parse/schema errors invisible
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt:137-139
- **문제**: processMessage wraps the whole parse-and-emit block in try/catch with an empty body (comment 'Ignore parse errors for non-ticker messages'). This also silently swallows: Upbit error/status frames, a future schema change that removes/renames `code`/`trade_price`, and the result of sink.tryEmitNext (line 136) which can fail/drop on backpressure with the 256-buffer multicast sink. node["code"].asText() (line 126) on a missing field returns "" rather than throwing, so a malformed ticker can also be stored under an empty-string market key without any log.
- **수정**: Catch narrowly, log at warn/debug with a rate-limited counter, branch explicitly on `type` and on Upbit `error`/`status` frames, validate that `code`/`trade_price` are present before storing, and check the EmitResult of tryEmitNext to surface drops.

### [upbit-integration] destroy()/shutdown can spawn a reconnect thread; raw-Thread reconnect has no cancellation generation
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitWebSocketClient.kt:46-50
- **문제**: @PreDestroy destroy() sets connected=false and disposes the subscription but does not set a shutdown flag nor clear subscribedTickers. disposing triggers doFinally (line 103) which calls scheduleReconnect(); that method only checks `!connected.get() && subscribedTickers.isNotEmpty()` (line 143) — both still true after destroy() — so it starts a daemon Thread that sleeps then calls connect() during/after shutdown. Separately, subscribe() -> reconnect() (lines 52-71) and scheduleReconnect() both call connect() with no generation token, so rapid subscribe churn or overlapping reconnect timers can open multiple concurrent websocket connections.
- **수정**: Add an explicit `@Volatile shuttingDown` flag checked in scheduleReconnect()/connect(); have destroy() set it and (optionally) clear subscribedTickers. Replace the raw Thread reconnect with a single scheduled executor and a generation counter so stale reconnect callbacks are dropped.

### [persistence-data] market_candles / trade_executions / strategy_signals retention 없음 — 무한 증가
- **위치**: bot/src/main/kotlin/com/trading/bot/stream/DataRetentionService.kt:20-26
- **문제**: ✅확실. retention 스케줄러는 market_tickers(7일, DataRetentionService) 와 price_snapshots(7일, PriceCollector.cleanupOldSnapshots) 두 테이블만 정리한다. market_candles 는 다중 timeframe(1m,5m,...,1M) × 다수 마켓으로 매분 INSERT 되지만 삭제 경로가 전혀 없다(rg 확인). strategy_signals, trade_executions 도 무한 증가. 특히 market_candles 는 인덱스 idx_market_candles_lookup 도 함께 비대해진다.
- **수정**: market_candles 에 interval 별 차등 retention(예: 1m 은 N일, 상위 timeframe 은 장기 보존) 삭제 잡 추가. strategy_signals 도 retention 추가. 장기적으로 시계열 테이블은 파티셔닝(월별) 검토.

### [persistence-data] PriceCollector 다건 INSERT 를 fire-and-forget subscribe() — 실패 무시 + 비원자
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PriceCollector.kt:56-68
- **문제**: ✅확실. collectPrices 는 루프 안에서 priceSnapshotRepository.save(entity).subscribe() 를 각 ticker 마다 호출한다. (1) onError 핸들러 없는 subscribe() 라 저장 실패가 완전히 삼켜진다(로그조차 없음). (2) 트랜잭션이 없어 일부 ticker 만 저장되고 일부는 실패하는 부분 저장이 가능하다. (3) fire-and-forget 이라 scope.launch 의 try/catch(70줄)는 INSERT 실패를 잡지 못한다(이미 분리된 비동기 스트림).
- **수정**: saveAll(entities) 로 모아 하나의 reactive 체인으로 만들고 awaitSingleOrNull/collectList 로 await 하며 onError 로깅. 한 배치를 트랜잭션으로 묶어 all-or-nothing 보장.

### [api-validation] BotConfigController createConfig 입력 무검증 — 잘못된 JSON이 JSONB 컬럼에서 500, 개수/길이 제한 없음
- **위치**: bot/src/main/kotlin/com/trading/bot/api/BotConfigController.kt:36-53
- **문제**: ✅확실. createConfig는 request.exchange/market/strategy/parameters를 어떤 검증도 없이 BotConfigEntity에 그대로 저장한다. parameters 컬럼은 V12__create_config_tables.sql:21에서 JSONB DEFAULT '{}' 이므로, request.parameters에 비-JSON 문자열을 보내면 R2DBC insert 시 Postgres JSONB 캐스트 에러가 나고 ResponseStatusException이 아니라 일반 예외 → 500. market/strategy는 RequestValidators.kt:39-60의 정규화/포맷 검증(다른 컨트롤러는 적용)을 우회한다. 또한 per-user config 개수 상한이 없어 무제한 생성이 가능하다.
- **수정**: parameters를 ObjectMapper.readTree로 JSON 유효성 검사 후 정규화하고 최대 길이를 제한한다. market/strategy는 requestValidators.normalizeMarket/normalizeStrategy로 검증, exchange는 enum 파싱으로 검증한다. per-user config 개수 상한을 추가한다.

### [api-validation] BotConfig 목록 조회에 pagination/limit 부재 — 전체 collectList
- **위치**: bot/src/main/kotlin/com/trading/bot/api/BotConfigController.kt:27-33
- **문제**: ✅확실. getConfigs는 botConfigRepository.findByUserId(userId)(TradingRepository.kt:37-38: SELECT * FROM bot_configs WHERE user_id = :userId, LIMIT 없음) 결과를 collectList().awaitSingle()로 전부 메모리에 적재한다. 같은 컨트롤러의 createConfig가 개수 제한을 두지 않으므로 누적 시 응답 크기/메모리가 선형 증가. TradeHistoryController가 limit/offset을 적용하는 것과 대비된다.
- **수정**: limit/offset(또는 cursor) pagination을 추가하고 쿼리에 LIMIT/OFFSET을 둔다. createConfig에 per-user 상한을 함께 둔다.

### [deploy-infra-cost] docker-compose 의 collector 서비스가 없는 collector/Dockerfile 을 build → compose up/pull 실패
- **위치**: docker-compose.yml:124-145
- **문제**: docker-compose.yml:124-128 의 `collector` 서비스가 `build: { context: ., dockerfile: collector/Dockerfile }` 를 지정하지만 collector/Dockerfile 은 존재하지 않는다. 또한 deploy.sh:165 와 .github/workflows/deploy.yml:83 의 `docker compose pull app collector` 가 GHCR 에 없는 coin-trading-bot-collector 이미지를 pull 하려다 실패한다. collector 는 bot 의 in-process MarketDataIngestionService(`구 collector 모듈을 흡수한 in-process 시세 수집기`)로 이미 대체되었다.
- **수정**: docker-compose.yml 에서 `collector` 서비스(124-145) 전체 삭제. deploy.sh:149-151(collector build/push), deploy.sh:165/180 의 `collector` 인자, .github/workflows/deploy.yml:50-58(collector image build) 과 line 83/102 의 `collector` 인자도 함께 제거.

### [build-test-quality] JaCoCo 커버리지 게이트 부재 + CI 가 JaCoCo 를 전혀 실행하지 않음
- **위치**: bot/build.gradle.kts:50-56
- **문제**: jacocoTestReport 만 설정돼 있고(html/xml 리포트) jacocoTestCoverageVerification(violationRules/minimum) 가 없어 커버리지 임계치 미달이 빌드를 실패시키지 못한다. 또한 test 가 jacocoTestReport 를 finalizedBy 로 트리거하지 않고, CI(deploy.yml:30)는 `./gradlew test --parallel` 만 실행 → jacocoTestReport 자체가 CI 에서 한 번도 실행되지 않는다. JaCoCo 설정이 사실상 죽은 코드.
- **수정**: jacocoTestCoverageVerification 에 engine/execution 패키지 대상 최소 임계치(violationRules)를 두고 `tasks.check { dependsOn(jacocoTestCoverageVerification) }`, `tasks.test { finalizedBy(jacocoTestReport) }` 로 연결한 뒤 CI 를 `./gradlew check`(또는 jacocoTestCoverageVerification 포함)로 변경한다.

### [build-test-quality] CI 가 수동 트리거(workflow_dispatch)뿐이라 push 시 테스트가 자동 게이트되지 않음
- **위치**: .github/workflows/deploy.yml:3-7
- **문제**: on: 에 push/pull_request 트리거가 없고 workflow_dispatch 만 있다(주석상 EC2 OOM 으로 push trigger 보류). 따라서 평소 커밋/머지 시 `./gradlew test`(line 30)가 자동 실행되지 않는다. Dockerfile:12 의 `bootJar ... -x test` 는 이미지 빌드 시 테스트를 건너뛰므로, 유일한 테스트 게이트인 CI 가 수동 실행에만 의존한다.
- **수정**: 테스트 전용 경량 워크플로(pull_request + push)를 추가해 `./gradlew check` 를 게이트로 돌린다. 무거운 빌드/배포(EC2)는 workflow_dispatch 로 유지하되 테스트 게이트는 분리한다.

### [build-test-quality] money-critical saveAndNotify 부분 실패(execution row 누락 후 throw) 경로 미테스트
- **위치**: bot/src/test/kotlin/com/trading/bot/engine/TradeExecutionServiceTest.kt:39
- **문제**: TradeExecutionService.saveAndNotify(TradeExecutionService.kt:178-186)는 trade_executions 저장 실패 시 'record 는 남고 execution row 는 누락'되는 부분 실패를 로깅 후 throw 하는 명시적 audit 경로다. 그러나 테스트는 setup 에서 `every { tradeExecutionRepository.save(any()) } returns Mono.just(...)` (line 39)로 성공만 stub 하고, Mono.error 로 실패를 주입해 throw/failure 전파를 검증하는 테스트가 없다. private saveAndNotify(TradeExecutionService.kt:206-213)의 catch→failure 반환 경로도 미커버.
- **수정**: tradeExecutionRepository.save 가 Mono.error 를 반환하는 케이스를 추가해 (1) public saveAndNotify 가 예외를 throw 하고 discordNotifier 가 호출되지 않는지, (2) executeBuy/SellAll 경로에서 TradeExecutionResult.failure 가 반환되는지 검증한다.

### [build-test-quality] r2dbc-h2/h2 의존성이 고아 상태 + Postgres 전용 마이그레이션으로 dialect drift 검증 불가
- **위치**: bot/build.gradle.kts:30-31
- **문제**: runtimeOnly r2dbc-h2(line 30)·h2(line 31) 가 선언돼 있으나, 테스트에 @SpringBootTest/@DataR2dbcTest 가 0건이고 test 용 application.yml/H2 프로파일도 없다(bot/src/test/resources 부재). 모든 테스트는 mockk 단위 테스트라 H2 컨텍스트를 띄우지 않는다. 한편 application.yml:8-21 과 application-prod.yml:8-19 는 prod·default 모두 r2dbc:postgresql + Flyway postgresql 을 사용하고, 마이그레이션은 BIGINT GENERATED ALWAYS AS IDENTITY(V1·V2·V10·V11 등), TIMESTAMPTZ·JSONB·NOW()(V11·V12)로 전부 Postgres 전용이다. 즉 H2 의존성은 어디서도 쓰이지 않는 dead dependency 이며, H2 로 마이그레이션/쿼리를 돌리는 통합 테스트도 없어 PG-only 문법의 dialect drift 를 잡을 수단이 없다.
- **수정**: 둘 중 하나로 정리: (a) H2 를 쓰지 않을 거면 runtimeOnly r2dbc-h2·h2 제거, (b) 실제 검증을 원하면 Testcontainers(postgres)로 @DataR2dbcTest + Flyway 마이그레이션 통합 테스트를 추가해 PG 문법/스키마를 실DB에서 게이트한다. H2 를 테스트 DB 로 쓰는 선택은 PG 전용 문법 때문에 부적합.

## low

### [concurrency-reactive] DB persist가 reactive backpressure를 끊고 무제한 fire-and-forget 누적
- **위치**: bot/src/main/kotlin/com/trading/bot/stream/MarketDataPersistenceService.kt:43-44,61-62
- **문제**: persistTicker/persistCandle은 repository.save(...).subscribe(...)로 독립 구독을 띄운다. ingestion Flow(MarketDataIngestionService.collectTickers)는 이 저장 완료를 기다리지 않으므로 DB가 느려지면 in-flight R2DBC write가 상한 없이 누적된다. 호출자 코루틴은 backpressure 신호를 받지 못한다. PriceCollector.collectPrices(67), DataRetentionService(24-25)도 동일하게 untracked subscription이다.
- **수정**: ingestion을 suspend 경로로 묶어 awaitSingle/await 또는 flatMap(concurrency 제한)로 backpressure를 전파. 최소한 bounded 큐 또는 onBackpressureDrop로 in-flight 상한 설정.

### [concurrency-reactive] TradingEngine scope가 stop/제거 시 cancel되지 않아 코루틴 누수
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:44,55-67
- **문제**: scope = CoroutineScope(Dispatchers.Default + SupervisorJob())(44)는 어디서도 cancel되지 않는다. stop()은 running만 false로 바꾸므로 runLoop는 진행 중인 delay(intervalSeconds*1000) 또는 suspend I/O가 자연 복귀할 때까지 살아 있다. stopBot이 engines.remove로 참조를 버려도 GC 대상이 아니며(활성 코루틴이 scope를 참조), reloadUserRuntime로 교체된 구 엔진도 동일하게 잔존한다. 누적되면 좀비 코루틴/엔진 인스턴스가 쌓인다.
- **수정**: stop()에서 scope.cancel() 호출(또는 runLoop Job을 보관해 cancel). 단, 진행 중 주문의 취소 안전성을 함께 고려(critical 1번 참조).

### [security-auth] admin 권한 경계 미구현 — admin 컬럼/조회만 있고 enforce하는 인가 로직 부재
- **위치**: bot/src/main/kotlin/com/trading/bot/auth/JwtAuthFilter.kt:20
- **문제**: ✅확실. V8__add_admin_role.sql 이 users.admin 컬럼을 추가하고 특정 사용자를 admin=TRUE 로 설정, UserEntity.kt:18 과 UserRepository.kt:11 findByAdminTrue() 까지 존재하나, JwtAuthFilter.kt:20 은 `UsernamePasswordAuthenticationToken(userId, null, emptyList())` 로 authorities를 항상 비워둔다. JWT에도 role/admin claim이 없고(JwtProvider.kt:16-25), SecurityConfig.kt:31-39 의 authorizeExchange 에 hasRole/hasAuthority 가 전혀 없어 admin 전용 경로 개념 자체가 없다. 즉 admin 역할이 설계됐지만 인가에 전혀 반영되지 않는다 — 현재 admin 전용 기능이 노출돼 있다면 모든 로그인 사용자가 접근 가능하고, 향후 추가 시 사고 위험.
- **수정**: admin 전용 기능이 있다면 JWT에 role claim을 싣고 JwtAuthFilter에서 user.admin 조회 후 ROLE_ADMIN authority 부여, SecurityConfig에서 해당 경로에 hasRole('ADMIN') 적용. admin 기능이 아직 없다면 미사용 admin 컬럼/리포지토리는 죽은 코드로 제거하거나 사용 시점까지 보류 명시.

### [security-auth] JWT를 httpOnly 쿠키와 응답 body에 동시 반환 — httpOnly 보호 무력화
- **위치**: bot/src/main/kotlin/com/trading/bot/auth/AuthController.kt:61
- **문제**: ✅확실. register(AuthController.kt:59-61), login(line 72-74)이 토큰을 httpOnly 쿠키로 심으면서 동시에 AuthResponse.token(line 124)으로 응답 body에도 평문 반환한다. httpOnly 쿠키의 목적은 JS가 토큰에 접근하지 못하게 하는 것인데, body로도 주면 SPA/JS·브라우저 확장·프록시·로그가 토큰을 다룰 수 있어 XSS 토큰 탈취 표면이 다시 열린다.
- **수정**: 쿠키 인증을 채택했다면 AuthResponse에서 token 필드 제거(username 등만 반환). 헤더 Bearer 방식만 쓰는 클라이언트가 있다면 그쪽은 쿠키를 심지 말고 body 토큰만 주는 식으로 경로를 분리.

### [security-auth] Upbit secret 길이 검증과 HMAC-SHA256 키 요구사항 불일치로 토큰 생성 예외 가능
- **위치**: bot/src/main/kotlin/com/trading/bot/client/UpbitAuthProvider.kt:27
- **문제**: ✅확실. normalizeApiKey(RequestValidators.kt:33)는 16~128자를 허용하나, UpbitAuthProvider.kt:27 `Keys.hmacShaKeyFor(upbitProperties.secretKey.toByteArray())` 는 jjwt가 HS256에 최소 256비트(32바이트) 키를 요구하므로 16~31바이트 secret은 저장은 되지만 실제 거래 호출 시 WeakKeyException 으로 실패한다. 보안 취약점이라기보다 검증-사용 불일치로 인한 런타임 실패/가용성 이슈.
- **수정**: Upbit secret key 실제 포맷(통상 충분히 긺)에 맞춰 normalizeApiKey 하한을 32바이트 이상으로 조정하거나, UpbitAuthProvider에서 키 길이 부족 시 명확한 사용자 메시지로 매핑. 실제 Upbit 키 스펙 확인 후 검증 기준 일원화.

### [upbit-integration] Error mapping collapses distinct Upbit failures into a single 400
- **위치**: bot/src/main/kotlin/com/trading/bot/api/UpbitErrorHandlerAdvice.kt:36-49
- **문제**: mapException folds nearly all 4xx (403 out_of_scope, order rejections like insufficient_funds / under_min_total_market, and any 418 IP block which falls into the 400..499 branch) into a generic HttpStatus.BAD_REQUEST with a vague 'Upbit가 요청을 거부했습니다' message. Only 401 and 429 get specific handling. The errorName is interpolated but operators/users cannot distinguish permission, balance, min-order, or IP-block failures from the HTTP status.
- **수정**: Map known Upbit error names (insufficient_funds, under_min_total_*, out_of_scope, 418) to distinct status/messages, and surface the raw errorName in the response so the SPA and logs can differentiate. Keep 418 visibly distinct from generic 400.

### [persistence-data] 모든 금액/가격 컬럼이 DOUBLE PRECISION (float) — 정밀도 손실
- **위치**: bot/src/main/resources/db/migration/V11__create_trading_tables.sql:9-13
- **문제**: ✅확실. price/volume/total_amount/fee/pnl_amount/avg_buy_price/peak_price 등 모든 화폐·수량 컬럼이 DOUBLE PRECISION 으로 선언됨. 동일 패턴이 V1(trade_records: price/volume/total_amount/pnl_percent), V7(price_snapshots), V10(market_tickers/market_candles 의 모든 가격·거래량)에도 반복된다. 엔티티(TradeExecutionEntity, PositionEntity 등)도 전부 Kotlin Double 이다. KRW 호가는 정수지만 코인 수량(volume)·평단가·누적금액은 작은 잔차가 누적되며, total_amount = currentPrice * vol (TradeExecutionService.kt:100,143) 같은 곱셈에서 오차가 증폭된다.
- **수정**: money/price/volume 컬럼을 NUMERIC(예: 가격 NUMERIC(20,8), 금액 NUMERIC(24,8)) 으로 마이그레이션하고, 엔티티 타입을 java.math.BigDecimal 로 변경. 신규 테이블은 처음부터 NUMERIC 으로. 도메인 계산도 BigDecimal 로 통일.

### [persistence-data] retention cutoff 를 KST 로 계산하나 컬럼은 naive TIMESTAMP
- **위치**: bot/src/main/kotlin/com/trading/bot/engine/PriceCollector.kt:33-35
- **문제**: ⚠️추정(서버 TZ 의존). price_snapshots.captured_at 은 TIMESTAMP (timezone 없음, V7:10) 이고 PriceCollector 는 capturedAt=LocalDateTime.now(kst)(KST) 로 저장한다(64줄). cleanupOldSnapshots 도 cutoff=LocalDateTime.now(kst).minusDays(7) 로 KST 기준 비교하므로 쓰기/삭제는 KST 로 일관된다. 그러나 동일 cron "0 0 3 * * *" 가 DataRetentionService.cleanupOldTickers 와 PriceCollector.cleanupOldSnapshots 양쪽에 동시 걸려 있고, market_tickers 는 TIMESTAMPTZ + Instant 기반이라 두 테이블의 시간 기준계가 혼재한다. 또 @Scheduled cron 의 기준 TZ 는 JVM 기본 TZ 라 KST 가 아니면 새벽 3시 KST 에 안 돈다.
- **수정**: @Scheduled(cron=..., zone="Asia/Seoul") 로 TZ 명시. price_snapshots.captured_at 도 TIMESTAMPTZ + Instant 로 통일해 market_tickers 와 시간 기준계를 맞춘다.

### [persistence-data] market_tickers 대량 DELETE 가 단일 문장 — 락/팽창 위험
- **위치**: bot/src/main/kotlin/com/trading/bot/persistence/MarketDataRepository.kt:16-17
- **문제**: ✅확실. deleteOlderThan 은 DELETE FROM market_tickers WHERE recorded_at < :before 단일 문장이다. 10초마다 1/10 샘플링(MarketDataPersistenceService.kt:28)으로 다수 마켓 × 거래소가 쌓이면 7일치 삭제가 수십만~수백만 행이 될 수 있고, 단일 DELETE 는 큰 트랜잭션·테이블 팽창(bloat)·VACUUM 부담을 유발한다. idx_market_tickers_lookup 은 (exchange, market, recorded_at DESC) 라 recorded_at 단독 범위 삭제에 최적은 아니다(인덱스 선두컬럼 불일치).
- **수정**: 배치 삭제(LIMIT 단위 반복) 또는 월별 파티셔닝 후 파티션 drop. recorded_at 단독 인덱스 추가 검토.

### [api-validation] 수동 거래 실패 응답에 downstream 원문 오류 메시지 노출 가능
- **위치**: bot/src/main/kotlin/com/trading/bot/api/ManualTradeController.kt:47-49,89-90
- **문제**: ⚠️추정. manualBuy/manualSell이 result.error를 그대로 ResponseStatusException(HttpStatus.BAD_REQUEST, result.error)의 reason으로 넣는다. SafeErrorAttributes.kt:34-37은 ResponseStatusException.reason을 그대로 message로 클라이언트에 노출한다(이는 의도된 설계). 따라서 TradeExecutionService가 채우는 result.error에 Upbit raw 응답·내부 예외 메시지·스택 단편이 들어가면 그대로 응답에 나간다. result.error의 출처 코드를 확인하지 못해 추정으로 분류.
- **수정**: result.error를 사용자용 안전 메시지로 매핑(UpbitErrorHandlerAdvice.mapException 패턴 재사용)하거나 화이트리스트 메시지만 reason에 넣고, 원문은 서버 로그로만 남긴다.

### [api-validation] ManualSell에서 sellAll=true 시 함께 보낸 volume이 조용히 무시됨
- **위치**: bot/src/main/kotlin/com/trading/bot/api/ManualTradeController.kt:65-87
- **문제**: ✅확실. req.sellAll == true이면 volume 값과 무관하게 executeSellAll(전량 매도)을 실행한다. 클라이언트가 부분 매도 의도로 volume을 채우면서 sellAll 플래그를 실수로 true로 보내면, 검증/경고 없이 전량 매도된다. 실거래 자금 이동 엔드포인트인데 모순된 입력을 거부하지 않는다.
- **수정**: sellAll == true && volume != null 인 모순 입력을 400으로 거부하거나, 둘 중 하나만 허용하는 명시적 검증을 추가한다.

### [api-validation] Backtest 파라미터 finite/range 검증 없이 엔진 전달 + ticker 미검증
- **위치**: bot/src/main/kotlin/com/trading/bot/api/StrategyController.kt:76-87
- **문제**: ✅확실. days만 coerceIn(30,200)으로 클램프되고, takeProfitPct/maxLossPct/kValue/trailingStopPct/maxHoldDays는 finite·범위 검증 없이 BacktestConfig로 들어간다. NaN/Infinity/음수/거대값이 전달되면 비정상 시뮬레이션 결과나 엔진 내부 산술 예외(→500)를 유발할 수 있다. req.ticker 또한 normalizeMarket 없이 client.getDayCandles(ticker, days)로 전달된다(URI 템플릿이라 injection은 아니나 형식 오류는 Upbit 4xx로 떨어짐).
- **수정**: 각 Double 파라미터에 isFinite + 합리적 범위 검증을 추가하고, ticker는 requestValidators.normalizeMarket로 검증한다.

### [api-validation] 실거래/봇 시작 엔드포인트에 확인·idempotency 부재
- **위치**: bot/src/main/kotlin/com/trading/bot/api/ManualTradeController.kt:25-51
- **문제**: ⚠️추정(설계 트레이드오프). manualBuy/manualSell과 TradingController.startBot(TradingController.kt:26-41, body 없이 기본값으로 시작 가능)은 인증만 통과하면 즉시 실거래/자동매매를 시작한다. confirmation token이나 idempotency key가 없어, 동일 요청 중복 전송(네트워크 재시도 등) 시 중복 주문이 발생할 수 있다. CSRF는 SecurityConfig.kt:21에서 disable되어 있으나 JWT가 httpOnly 쿠키로도 전달되므로(JwtAuthFilter.kt:34) 쿠키 기반 CSRF 표면이 존재한다.
- **수정**: 자금 이동 엔드포인트에 idempotency key 또는 클라이언트 nonce를 도입한다. 쿠키 인증을 허용하는 상태에서 CSRF disable이면 상태 변경 POST에 대해 커스텀 헤더(X-Requested-With) 요구 등 보강을 검토한다.

### [api-validation] normalizeSellVolume이 거대 scientific notation 통과 + 원문 문자열 그대로 전달
- **위치**: bot/src/main/kotlin/com/trading/bot/api/RequestValidators.kt:72-83
- **문제**: ✅확실. toDoubleOrNull로 파싱 후 isFinite && >0 && >=1e-6만 검사하고 원문 문자열(normalized)을 그대로 반환해 매도 주문 volume으로 사용한다(ManualTradeController.kt:75-82). '1e100' 같은 과대값도 finite이므로 통과하고, 상한·소수 자릿수(scale) 검증이 없다. 실제 보유 수량 초과는 downstream(Upbit)에서 거부되겠지만 컨트롤러 계층 방어는 비어 있다.
- **수정**: volume에 상한과 최대 소수 자릿수 검증을 추가하고, 파싱된 BigDecimal의 canonical 표현을 downstream에 전달하는 것을 검토한다.

### [api-validation] ChartController indicators의 알 수 없는 값 무시 (조용한 부분 결과)
- **위치**: bot/src/main/kotlin/com/trading/bot/api/ChartController.kt:63-83
- **문제**: ✅확실. getIndicators는 요청된 indicators 중 rsi/macd/bb/ma/ema만 매칭하고 그 외(오타 포함)는 조용히 무시해 빈/부분 결과를 200으로 반환한다. 클라이언트 입력 오류가 표면화되지 않는다.
- **수정**: 허용 목록 외 값이 포함되면 400을 반환하거나, 응답에 무시된 indicator를 명시한다.

### [deploy-infra-cost] prod DB 연결 sslMode=disable (R2DBC + Flyway 모두 평문)
- **위치**: bot/src/main/resources/application-prod.yml:9,17
- **문제**: application-prod.yml:9 `r2dbc:postgresql://...?sslMode=disable`, line 17 `jdbc:postgresql://...?sslmode=disable`. 현재 compose 의 동일 호스트 컨테이너 간 통신이면 위험이 제한적이나, RDS 나 별도 DB 로 이동하면 DB 자격증명·거래 데이터가 평문 전송된다. 또한 sslMode 가 코드에 하드코딩되어 환경별 전환이 불가.
- **수정**: `?sslMode=${DB_SSL_MODE:require}` 로 파라미터화하고 로컬 compose 에서만 disable 주입. 원격 DB 사용 시 require/verify-full 강제.

### [build-test-quality] TradingEngineTest 가 runBlocking + delay(3000) 실시간 sleep 으로 flaky/느림
- **위치**: bot/src/test/kotlin/com/trading/bot/engine/TradingEngineTest.kt:127-146
- **문제**: `falls back to REST when WebSocket price is stale` 테스트가 runBlocking(line 127) 안에서 engine.start() 후 `delay(3000)`(line 142) 실제 3초 wall-clock sleep 으로 백그라운드 scope.launch(runLoop, TradingEngine.kt:59,102 delay(intervalSeconds*1000))가 최소 1회 돌기를 기다린다. 가상 시계(runTest/TestDispatcher)가 아니라 실시간 지연이라 CI 부하/스케줄링에 따라 coVerify(atLeast=1) 가 간헐 실패할 수 있고, 매 실행 3초를 소비한다.
- **수정**: TradingEngine 루프에 주입 가능한 CoroutineScope/Dispatcher 를 두고 runTest + StandardTestDispatcher 로 가상 시간(advanceTimeBy) 사용, 또는 awaitility 류로 조건 폴링(최대 대기+짧은 간격)으로 교체해 고정 sleep 을 제거한다.

### [build-test-quality] common 모듈은 dependencyManagement BOM 만 import 하고 spring-boot-dependencies 버전을 하드코딩 — 루트 버전과 이중 관리
- **위치**: common/build.gradle.kts:8
- **문제**: common/build.gradle.kts:8 가 `mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")` 로 버전을 직접 박고, bot/build.gradle.kts:1-3 는 org.springframework.boot 플러그인을 적용해 BOM 을 자동 주입한다(루트 build.gradle.kts:2 에서 3.4.1 선언). 두 곳에 3.4.1 이 별도로 박혀 있어 업그레이드 시 한쪽만 바뀌면 모듈 간 Spring 버전이 어긋날 수 있다.
- **수정**: buildSrc 의 version catalog(gradle/libs.versions.toml) 또는 루트 ext 로 Spring Boot 버전을 단일 소스화하고 common 의 BOM import 도 그 변수를 참조하게 한다.

