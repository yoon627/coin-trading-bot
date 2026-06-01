# Trading Platform - 전체 구조 분석

## 1. 기술 스택

| 항목 | 기술 |
|------|------|
| **언어** | Kotlin 2.1 (JDK 21) |
| **프레임워크** | Spring Boot 3.4 + WebFlux (비동기/리액티브) |
| **빌드** | Gradle (Kotlin DSL), 멀티모듈 (`common`, `bot`) |
| **데이터베이스** | PostgreSQL 17 (R2DBC 비동기 드라이버) |
| **마이그레이션** | Flyway (V1~V13) |
| **캐시** | Redis 7 (reactive, prod 프로필에서 활성) |
| **인증** | Spring Security + JWT (jjwt, httpOnly+Secure 쿠키) |
| **비동기** | Kotlin Coroutines + Reactor |
| **암호화** | AES-GCM 256-bit (사용자별 Upbit API 키 저장) |
| **컨테이너** | Docker + Docker Compose |
| **TLS** | Caddy 2 + Let's Encrypt (HTTPS 종단, sslip.io 자동 도메인) |
| **배포** | AWS EC2 t4g.medium (arm64, 4GB) |
| **CI/CD** | GitHub Actions + GHCR (multi-arch 이미지 push) |

> 경량화(rightsizing)로 Kafka, ML(Smile), Claude 분석, Resilience4j, Prometheus/Grafana/Loki, 별도 `:collector`/`:research` 모듈은 제거됐다.

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
  MarketDataPersistenceService ──→ PostgreSQL (시계열 저장)

[분석 — REST]
  PostgreSQL ──→ Chart API (멀티 타임프레임 캔들)
  Redis      ──→ 가격/지표 캐시
```

---

## 3. 프로젝트 구조 (멀티모듈 Gradle)

```
coin-trading-bot/
├── common/                          # 공유 도메인 + 인디케이터 + 스윙 전략
│   └── src/main/kotlin/com/trading/common/
│       ├── domain/                  # NormalizedCandle, NormalizedTicker, OrderBook, Exchange, MarketPair
│       ├── indicator/               # Indicators (RSI, MACD, BB, MA, EMA)
│       └── strategy/                # TradingStrategy 인터페이스 + 스윙 전략 7개
│                                    #   (@Bean 등록은 :bot/config/StrategyConfig)
│
├── bot/                             # 메인 앱 (시세 수집 + 매매 엔진 + REST + SPA)
│   └── src/main/kotlin/com/trading/bot/
│       ├── api/                     # REST 컨트롤러 12개 + UpbitErrorHandlerAdvice
│       ├── auth/                    # JWT 인증 (AuthController, JwtProvider, SecurityConfig)
│       ├── client/                  # UpbitClient (REST 주문), UpbitWebSocketClient
│       ├── marketdata/              # in-process 시세 수집 (구 collector 흡수)
│       ├── engine/                  # TradingEngine, TradeExecutionService, PositionManager, BacktestEngine
│       ├── stream/                  # CandleAggregator, MarketDataPersistenceService, DataRetentionService
│       ├── cache/                   # PriceCacheService (Redis)
│       ├── config/                  # AppConfig, StrategyConfig, RedisConfig, RateLimitFilter 등
│       ├── persistence/             # R2DBC Entity/Repository
│       ├── security/                # SecretsCrypto (AES-GCM), UserSecretsService
│       └── notification/            # DiscordNotifier
│
├── docker-compose.yml               # 로컬 인프라 (app, postgres, redis)
├── deploy/aws/                      # AWS 배포 스크립트 + docker-compose.prod.yml
└── perf/                            # k6 부하 테스트
```

> 스윙 전략 7개(`VolatilityBreakout`, `RsiBounce`, `GoldenCross`, `MacdCross`, `BollingerBounce`, `MeanReversion`, `CombinedStrategy`)와 `TradingStrategy` 인터페이스는 `:common`에 거주하며 백테스트에도 그대로 재사용된다.

---

## 4. 지원 거래소

| 거래소 | 자산 유형 | 데이터 소스 | 수집 방식 |
|--------|----------|------------|-----------|
| **업비트 (Upbit)** | 코인 (KRW 마켓) | WebSocket + REST | 실시간 시세(WS), 분봉/일봉(REST 폴링) |

모든 시세는 `NormalizedTicker`, `NormalizedCandle`로 정규화된다. (`Exchange` enum에 BINANCE/KIS 값이 남아있으나 현재 연동 코드는 없음 — Upbit 단독 운영.)

---

## 5. 매매 (SWING 모드)

시간봉/일봉 기준 기술적 지표로 매매. 보유 기간 수시간~수일. 사용자별 종목/전략은 `bot_configs`에 저장.

**스윙 전략 (7개):**
| 전략 | 설명 |
|------|------|
| VolatilityBreakout | 래리 윌리엄스 변동성 돌파 (전일 범위 × K) |
| RsiBounce | RSI(14) 과매도(30) 반등 |
| GoldenCross | MA(5)/MA(20) 골든크로스 + RSI 필터 |
| MacdCross | MACD > Signal + 히스토그램 양수 |
| BollingerBounce | 볼린저 밴드 하단 반등 |
| MeanReversion | 평균 회귀 (MA20 대비 -3% + 낮은 변동성) |
| CombinedStrategy | 변동성 돌파 + 추세 + RSI 복합 |

**리스크 관리:** 손절 -3% / 익절 +5% / 트레일링 스탑 고점 대비 -2% / 최대 보유 7일 / 50일 MA 아래 매수 차단 / 09:00 KST 일일 리셋.

---

## 6. 데이터베이스 스키마

### Flyway 마이그레이션 (V1~V13)

| 버전 | 내용 |
|------|------|
| V1~V9 | trade_records, users, bot_state, public_profile, discord_webhook, price_snapshots, admin_role, indexes |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 데이터 |
| V11 | `trade_executions`, `positions`, `strategy_signals` — 매매 기록 |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | bot_configs에 `trade_mode` 컬럼 |

### 핵심 테이블

```
market_candles
├── exchange, market, interval_minutes
├── open_price, high_price, low_price, close_price, volume, quote_volume
├── open_time, close_time
└── UNIQUE(exchange, market, interval_minutes, open_time)

trade_executions
├── user_id (FK → users)
├── exchange, market, side, order_type
├── price, volume, total_amount, fee, pnl_percent, pnl_amount
├── reason, strategy, status, executed_at

bot_configs
├── user_id (FK → users)
├── exchange, market, strategy, trade_mode, parameters (JSONB)
└── UNIQUE(user_id, exchange, market)
```

---

## 7. 멀티 타임프레임 분석

`marketdata`가 수집한 캔들을 `CandleAggregator`가 상위 타임프레임으로 집계:

```
캔들 → 5분/15분/1시간/4시간/일/주/월봉 (CandleAggregator)
```

**Chart API** (`/api/chart/`):
- `GET /api/chart/candles?exchange=UPBIT&market=BTC/KRW&interval=1h&count=100`
- `GET /api/chart/indicators?...&indicators=rsi,macd,bb`

---

## 8. 전체 데이터 흐름

```
[Upbit API] ── WS(ticker) + REST(candle)
    │
    ▼
[marketdata/UpbitMarketFeed]
    ├──→ MarketDataStore (in-memory)
    └──→ MarketDataPersistenceService → PostgreSQL
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
| 봇 제어 | TradingController | `/api/bot/{start,stop,status,strategy}` |
| 봇 설정 | BotConfigController | `/api/bot/{configs,config,config/{id}}` |
| 사용자 | TradingController/LeaderboardController | `/api/user/{me,keys,settings}` |
| 트레이딩 | Portfolio/ManualTrade/TradeHistory | `/api/{portfolio,account,trade/buy,trade/sell,trades}` |
| 차트 | ChartController | `/api/chart/{candles,indicators,tickers,compare}` |
| 전략 | StrategyController | `/api/strategies/{,performance,backtest}` |
| 가격(SSE) | PriceStreamController | `/api/prices/{stream,latest,status}` |
| 리더보드 | LeaderboardController | `/api/leaderboard`, `/api/user/{id}/profile` |
| 관심종목 | WatchlistController | `/api/watchlist` |

### 에러 응답 정책
- `SafeErrorAttributes`가 `ResponseStatusException.reason`만 노출 (FQCN/스택 leak 차단).
- `UpbitErrorHandlerAdvice`가 `UpbitApiException` → 사용자 친화적 4xx 변환. raw 401은 노출하지 않음 (FE 401 자동 logout 회피).

---

## 10. Docker Compose 인프라

```
   Browser ──HTTPS:443──► caddy(TLS 종단) ──reverse_proxy──► app:8080

┌──────────────┬──────────────┬────────────────┬───────────────┐
│  caddy :443  │   app :8080  │ postgres :5432 │  redis :6379  │
│  (TLS 종단)  │  (수집+매매  │   (PG 17)      │   (캐시)      │
│  (LE 자동)   │   +REST+SPA) │                │               │
└──────────────┴──────────────┴────────────────┴───────────────┘
```

- 외부 진입점은 Caddy(:80/:443). `app`은 호스트에 노출되지 않고(`expose` 만) Caddy 가 `app:8080` 으로 리버스 프록시한다(Let's Encrypt 자동 발급).
- 배포(`deploy/aws/docker-compose.prod.yml`)는 caddy(TLS 종단) + GHCR `app` 이미지 pull, 로컬(`docker-compose.yml`)은 caddy 없이 `build: .` 로컬 빌드(`app:8080` 직접).

---

## 11. 핵심 아키텍처 패턴

1. **단일 JVM in-process** — 시세 수집·매매·API를 한 앱에서 처리, 메시지 버스 불필요
2. **멀티모듈 Gradle** — common(공유 도메인/전략) / bot(앱) 관심사 분리
3. **전략 패턴** — `TradingStrategy` 인터페이스 + 스윙 전략 7개
4. **리액티브 아키텍처** — WebFlux + Coroutines + R2DBC 논블로킹 I/O
5. **멀티 타임프레임** — 캔들을 자동 집계하여 모든 타임프레임 지원
6. **저장 시 암호화** — AES-GCM으로 DB 내 Upbit API 키 보호
7. **멀티유저** — 사용자별 독립 엔진 + API 키 + 종목/전략 설정
8. **리서치-라이브 공유** — 스윙 전략 7개를 `:common`에 두어 라이브/백테스트 양쪽에서 재사용
