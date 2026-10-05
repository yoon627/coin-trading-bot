# Trading Platform - 전체 구조 분석

## 1. 기술 스택

| 항목 | 기술 |
|------|------|
| **언어** | Kotlin 2.1 (JDK 21) |
| **프레임워크** | Spring Boot 3.4 + WebFlux (비동기/리액티브) |
| **빌드** | Gradle (Kotlin DSL), 멀티모듈 (`common`, `bot`) |
| **데이터베이스** | PostgreSQL 17 (R2DBC 비동기 드라이버) |
| **마이그레이션** | Flyway (V1~V28) |
| **인증** | Spring Security + JWT (jjwt, httpOnly+Secure 쿠키) |
| **비동기** | Kotlin Coroutines + Reactor |
| **암호화** | AES-GCM 256-bit (사용자별 Upbit API 키 저장) |
| **컨테이너** | Docker + Docker Compose |
| **TLS** | Caddy 2 + Let's Encrypt (HTTPS 종단, sslip.io 자동 도메인) |
| **배포** | Vultr 서울 vc2-1c-2gb (amd64, 2GB, $10) — 유일한 운영 경로. AWS EC2(2026-07-31 자원 삭제)·OCI(프로비저닝 안 함) 판은 2026-10 에 저장소에서 지웠다 |
| **CI/CD** | GitHub Actions + GHCR (multi-arch 이미지 push) |

> 경량화(rightsizing)로 Kafka, ML(Smile), Claude 분석, Resilience4j, Prometheus/Grafana/Loki, 별도 `:collector`/`:research` 모듈은 제거됐다. KIS(한국투자증권 국내주식) 봇도 2026-09-16 에 통째로 제거됐다(코드·`/api/stock/*`·`/api/kis/*`·UI·V27 스키마 정리). 설계 기록은 git 이력(`git log --all -- bot/src/main/kotlin/com/trading/bot/kis`).

---

## 2. 아키텍처 개요

**단일 JVM(`:bot`)** 이 시세 수집·매매 엔진·REST API·SPA를 모두 담당한다. 시세 수집은 in-process(`bot/marketdata/`)로 처리되므로 별도 메시지 버스(Kafka)나 수집 서비스가 필요 없다.

```
[시세 수집 — bot/marketdata/ (in-process)]
  UpbitMarketFeed
   ├── WebSocket  → ticker (실시간 가격)
   └── REST 폴링  → candle (OHLCV)
        │
        ▼
  MarketDataStore (in-memory) ──→ TradingEngine → 주문 실행
  CandleAggregator (분봉 → 상위 봉) ──→ MarketDataStore   — 시세는 DB 에 저장하지 않는다
```

---

## 3. 프로젝트 구조 (멀티모듈 Gradle)

```
coin-trading-bot/
├── common/                          # 공유 도메인 + 인디케이터 + 스윙 전략
│   └── src/main/kotlin/com/trading/common/
│       ├── domain/                  # NormalizedCandle, NormalizedTicker, Exchange, MarketPair
│       └── strategy/                # Indicators (목표가, RSI, MA) + TradingStrategy 인터페이스 + CombinedStrategy
│                                    #   (@Bean 등록은 :bot/config/StrategyConfig)
│
├── bot/                             # 메인 앱 (시세 수집 + 매매 엔진 + REST + SPA)
│   └── src/main/kotlin/com/trading/bot/
│       ├── api/                     # REST 컨트롤러 4개 + UpbitErrorHandlerAdvice (AuthController 는 auth/)
│       ├── auth/                    # JWT 인증 (AuthController, JwtProvider, SecurityConfig)
│       ├── client/                  # UpbitClient (REST 주문/조회)
│       ├── marketdata/              # in-process 시세 수집 (WS ticker + REST candle, 구 collector 흡수) — 상시 WS 연결 단일화
│       ├── engine/                  # TradingEngine, TradeExecutionService, PositionManager(+UnknownOrderResolver·BalanceInterpretation)
│       ├── stream/                  # CandleAggregator (분봉 → 상위 봉, store 갱신)
│       ├── config/                  # AppConfig, StrategyConfig, RateLimitFilter 등
│       ├── persistence/             # R2DBC Entity/Repository
│       ├── security/                # SecretsCrypto (AES-GCM), UserSecretsService
│       └── notification/            # DiscordNotifier
│
├── docker-compose.yml               # 로컬 인프라 (app, postgres)
└── deploy/vultr/                    # Vultr 서울 배포 스크립트 + docker-compose.prod.yml (2GB)
```

> 운영 전략 `CombinedStrategy` 와 `TradingStrategy` 인터페이스는 `:common`에 거주한다. 나머지 전략·연구 코드는 2026-10-01 MVP 정리 1단계에서 지웠다.

---

## 4. 지원 거래소

| 거래소 | 자산 유형 | 데이터 소스 | 수집 방식 |
|--------|----------|------------|-----------|
| **업비트 (Upbit)** | 코인 (KRW 마켓) | WebSocket + REST | 실시간 시세(WS), 분봉/일봉(REST 폴링) |

모든 시세는 `NormalizedTicker`, `NormalizedCandle`로 정규화된다. `Exchange` enum 은 `UPBIT` 하나다(Binance·Alpaca 잔재와 `AssetType` 은 2026-10-01 지웠다).

---

## 5. 매매 (SWING 모드)

시간봉/일봉 기준 기술적 지표로 매매. 보유 기간 수시간~수일. 사용자의 봇 실행 상태(실행 여부·티커 목록)는 `bot_state` 에 저장한다(전략 칸은 `combined` 를 쓰기만 한다 — 롤백 호환).

**스윙 전략:** 운영 전략은 `CombinedStrategy`(`combined`) 하나다. 일봉 21개 이상에서 변동성 돌파(당일 시가 + 전일 레인지 × K) ·
MA5 > MA20 · RSI(14) 30~70 을 모두 만족하면 매수한다. 청산은 전략과 무관하게 손익% 안전망과 보유상한이 맡는다.
엔진과 상태 API 는 첫 등록 전략을 기본으로 쓴다(`StrategyConfig`, `StrategyConfigTest` 가 목록을 고정).

**리스크 관리:** 익절 +5% / 손절 -5% / 트레일링 스탑 고점 대비 -2%(고점 +3% 도달 후) / 최대 보유 1거래일(09:00 KST 경계) / 09:00 KST 일일 리셋.

**사용자 목록과 활성 집합 (#226):** 엔진은 사용자 목록(`getUserTickers()`)과 활성 집합(사용자 목록 ∪ 잔류)을 따로 든다. 사용자 목록 `bot_state.tickers` 에는 파생값을 되쓰지 않는다. 목록 밖이어도 엔진이 산 포지션·미해소 주문은 청산될 때까지 잔류한다(신규 진입 없음). 재기동(reload)과 실행 중 `/api/bot/start` 비교는 사용자 목록을 쓴다 — 실행 중 다른 목록은 409.

---

## 6. 데이터베이스 스키마

### Flyway 마이그레이션 (V1~V28)

| 버전 | 내용 |
|------|------|
| V1~V9 | trade_records, users, bot_state, public_profile, discord_webhook, price_snapshots(V19 에서 제거), admin_role, indexes |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 데이터 |
| V11 | `trade_executions`, `positions`(V14 에서 제거), `strategy_signals` — 매매 기록 |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | bot_configs에 `trade_mode` 컬럼 |
| V14 | `trading_states` — per-(user, ticker) 거래 상태 durable 영속(미해소 주문 uuid·halt·진입 메타). `trade_executions.exchange_order_id` + 부분 unique(재시작 reconcile 멱등). 미사용 `positions` 제거 |
| V15 | `stock_order_intent` — KIS 주식 주문 WAL(V27 에서 제거) |
| V16 | `users` 에 KIS 자격증명 컬럼(V27 에서 제거) |
| V17 | `bot_state` 에 `exchange` 컬럼((user_id, exchange) 별 1행 — 유지, 현재 값은 UPBIT 뿐). KIS WAL 인덱스 변경(V27 에서 테이블째 제거) |
| V18 | `stock_position_state` — KIS 포지션 durable 스냅샷(V27 에서 제거) |
| V19 | 미사용 `price_snapshots` 제거 — watchlist 가 `market_tickers`/`market_candles` 로 옮겨가 소비자가 없어졌다 |
| V20 | `trading_states` 에 `pending_sell_since`·`pending_sell_alerted` — 막힌 매도 알림을 재시작 횟수와 무관한 경과시간으로 판정 |
| V21 | `trade_records.pnl_amount` 추가 + 매도 기록의 전략 귀속 소급 복구. `buildSellRecord` 가 `strategy` 를 안 넘겨 그때까지의 매도가 전부 `strategy=NULL` 이었다(전략별 손익이 통째로 `unknown` 으로 집계). 귀속은 포지션 구간 내 첫 번째 non-manual BUY 기준 — 수동 매수는 `TradingState` 를 세우지 않아 런타임 `entryStrategy` 후보가 아니다 |
| V22 | `stock_order_intent.strategy`·`reason`(V27 에서 테이블째 제거) |
| V23 | `trading_states` 에 적립 사다리 장부 — `rungs_filled`·`last_action_price`·`flat_peak`·`pending_buy_trigger_price`·`pending_buy_prior_volume`·`pending_sell_trigger_price`·`pending_sell_prior_volume`. 적립 프로파일은 2026-10-01 에 지웠고 앞 네 컬럼은 더 매핑하지 않는다(DROP 은 MVP 3단계). 뒤 세 컬럼은 스윙이 쓴다 — 주문 전 보유량(잔고 복원·응답 못 받은 주문 판정, #227)과 매도 판단가(매도 기록가, #235) |
| V24·V25 | `shadow_exit_observation` 신설 + `live_exit_vwap` — 후보 청산 파라미터 그림자 관측·실행 슬리피지 |
| V26 | `trade_records.order_amount` — 이 주문의 실체결 대금(Σ`trades[].funds`, 수수료 미포함). 엔진 BUY 의 `total_amount` 는 포지션 원가 스냅샷이라 집계·SPA·Discord 가 부풀려 읽던 문제(#146). nullable·백필 없음·롤백 시 DROP 금지 |
| V27 | KIS 경로 제거 — 원자료를 `kis_archive_*` 4테이블(stock_order_intent·stock_position_state·users_keys·trade_executions)에 복사한 뒤 `stock_order_intent`·`stock_position_state` DROP, `users.kis_*` 5컬럼 DROP, `bot_state`·`bot_configs`·`trade_executions` 의 `exchange='KIS'` 행 삭제. PR revert 는 복구가 아니다(Flyway validate 실패) — 복구는 아카이브에서 새 마이그레이션으로(V28 은 아래 identifier). 아카이브 DROP 은 #203 |
| V28 | `trading_states` 에 `pending_buy_identifier`·`pending_sell_identifier` — 주문 전에 남기는 Upbit 클라이언트 identifier. 응답(uuid)을 못 받은 주문을 identifier 조회로 확정해 이중 주문·기록 유실을 막는다(#227). 롤백은 새 이미지 정지 → 두 컬럼이 채워진 행 0 확인(남은 주문은 uuid 로 옮기거나 비움) → 옛 이미지 기동 순서 |

### 핵심 테이블

```
trade_executions
├── user_id (FK → users)
├── exchange, market, side, order_type
├── price, volume, total_amount, fee, pnl_percent, pnl_amount
├── reason, strategy, status, executed_at
```

---

## 7. 멀티 타임프레임 분석

`marketdata`가 수집한 캔들을 `CandleAggregator`가 상위 타임프레임으로 집계해 `MarketDataStore` 에 올린다:

```
캔들 → 5분/15분/1시간/4시간/일/주/월봉 (CandleAggregator)
```

엔진이 읽는 것은 일봉(D1)뿐이다. 나머지 타임프레임을 읽던 차트 API 는 2026-10-01 지웠다.

---

## 8. 전체 데이터 흐름

```
[Upbit API] ── WS(ticker) + REST(candle)
    │
    ▼
[marketdata/UpbitMarketFeed]
    ├──→ MarketDataStore (in-memory)
    └──→ CandleAggregator → MarketDataStore (상위 봉)
            │
            ▼
[TradingEngine] ── 매매 루프 (코루틴)
    └── SWING: 일봉/시간봉 + 전략 시그널 → 매수/매도
         │
         ▼
    [UpbitClient.placeOrder()] → 거래소 주문
         │
         ▼
    [TradeExecutionService] → trade_executions 저장 + Discord 알림
```

---

## 9. REST API 요약

| 그룹 | 컨트롤러 | 대표 경로 |
|------|----------|-----------|
| 인증 | AuthController | `/api/auth/{register,login,logout}` |
| 봇 제어 | TradingController | `/api/bot/{start,stop,status,halt/clear}` |
| 사용자 | TradingController | `/api/user/{me,keys,settings}` |
| 트레이딩 | Portfolio/TradeHistory | `/api/{portfolio,account,trades}` (수동 매수 2026-09-16·수동 매도 2026-09-28 제거) |
| 전략 성과 | StrategyController | `/api/strategies/performance` |

### 에러 응답 정책
- `SafeErrorAttributes`가 `ResponseStatusException.reason`만 노출 (FQCN/스택 leak 차단).
- `UpbitErrorHandlerAdvice`가 `UpbitApiException` → 사용자 친화적 4xx 변환. raw 401은 노출하지 않음 (FE 401 자동 logout 회피).

### Rate limit
- `RateLimitFilter`가 클라이언트 IP(Caddy 가 덮어쓴 `X-Forwarded-For` 첫 값, 없으면 접속 주소)별 1분 고정 창을 앱 메모리에서 센다. 인증 경로(`/api/auth/**`) 30/분, 일반 API 60/분, 넘으면 429 + `Retry-After: 60`. `/actuator`·정적 자원·SPA 진입 경로는 세지 않는다.
- Spring Security(order -100)가 이 필터보다 먼저 돌아 비인증 일반 API 요청은 401 로 끝난다 — 일반 API 한도는 로그인한 요청에 걸린다.
- 카운터는 인스턴스마다 따로이고 재시작하면 0 이 된다(단일 인스턴스 전제, Redis 는 2026-10 에 뺐다).

---

## 10. Docker Compose 인프라

```
   Browser ──HTTPS:443──► caddy(TLS 종단) ──reverse_proxy──► app:8080

┌──────────────┬──────────────┬────────────────┐
│  caddy :443  │   app :8080  │ postgres :5432 │
│  (TLS 종단)  │  (수집+매매  │   (PG 17)      │
│  (LE 자동)   │   +REST+SPA) │                │
└──────────────┴──────────────┴────────────────┘
```

- 외부 진입점은 Caddy(:80/:443). `app`은 호스트에 노출되지 않고(`expose` 만) Caddy 가 `app:8080` 으로 리버스 프록시한다(Let's Encrypt 자동 발급).
- 운영 배포(`deploy/vultr/docker-compose.prod.yml`)는 caddy(TLS 종단) + GHCR `app` 이미지 pull 이다.
  로컬(`docker-compose.yml`)은 caddy 없이 `build: .`
  로컬 빌드(`app:8080` 직접)다.

---

## 11. 핵심 아키텍처 패턴

1. **단일 JVM in-process** — 시세 수집·매매·API를 한 앱에서 처리, 메시지 버스 불필요
2. **멀티모듈 Gradle** — common(공유 도메인/전략) / bot(앱) 관심사 분리
3. **전략 패턴** — `TradingStrategy` 인터페이스 + 운영 전략 `combined`
4. **리액티브 아키텍처** — WebFlux + Coroutines + R2DBC 논블로킹 I/O
5. **멀티 타임프레임** — 캔들을 자동 집계하여 모든 타임프레임 지원
6. **저장 시 암호화** — AES-GCM으로 DB 내 Upbit API 키 보호
7. **사용자별 엔진** — 사용자별 독립 엔진 + API 키 + 종목/전략 설정 (가입은 계정이 없을 때 첫 계정만 받아 실제 사용자는 한 명)
