# Coin Trading Bot

Kotlin/Spring Boot(WebFlux) 기반의 **Upbit 자동매매 플랫폼**. 단일 JVM 앱(`:bot`)이 시세 수집·매매 엔진·REST API·SPA를 모두 담당하고, 공용 도메인/지표/전략은 `:common`에 분리돼 있습니다. 스윙 전략 7개를 지원하며, 사용자별 종목/전략 설정과 백테스트를 제공합니다.

> 이 프로젝트는 경량화(rightsizing)를 거쳐 단일 인스턴스(EC2 t4g.medium)에서 `app + PostgreSQL + Redis`만으로 동작합니다. 구 `:collector`/`:research` 모듈, Kafka 이벤트 백본, ML(Smile), 스캘핑 전략, Claude 분석, Prometheus/Grafana/Loki 모니터링은 제거됐습니다.

## 시스템 아키텍처

```
   ┌──────────┐   push    ┌──────────────────────────────────────────────┐
   │  GitHub   ├──────────►│              EC2 (t4g.medium, arm64)          │
   │  Actions  │ GHCR push │                                              │
   │  (CI)     │           │   ┌──────────────────────────────────────┐   │
   └──────────┘           │   │   docker compose (prod, GHCR pull)    │   │
                           │   │                                        │   │
   ┌──────────┐  WS+REST   │   │   ┌──────────────┐   ┌─────────────┐  │   │
   │  Upbit   │◄───────────┼───┤   │   bot/app    │──►│ PostgreSQL  │  │   │
   └──────────┘            │   │   │   :8080      │   │   :5432     │  │   │
                           │   │   │ (수집+매매   │   └─────────────┘  │   │
   ┌──────────┐   알림     │   │   │  +REST+SPA) │   ┌─────────────┐  │   │
   │ Discord  │◄───────────┼───┤   │             │──►│   Redis     │  │   │
   │ Webhook  │            │   │   └──────┬───────┘   │   :6379     │  │   │
   └──────────┘            │   │          │           └─────────────┘  │   │
   ┌──────────┐            │   │          ▼                            │   │
   │ Browser  │◄───────────┼───┤   REST + SSE (Tide SPA)              │   │
   │  (SPA)   │            │   └──────────────────────────────────────┘   │
   └──────────┘            └──────────────────────────────────────────────┘
```

시세 수집은 `bot` 내부의 `marketdata/` 패키지가 **in-process로** 담당합니다 (ticker는 Upbit WebSocket, candle은 REST 폴링). 단일 JVM이라 별도 메시지 버스(Kafka)가 필요 없습니다.

### 컨테이너 구성 (`deploy/aws/docker-compose.prod.yml`)

| 서비스 | 이미지 | 역할 |
|--------|--------|------|
| `app` | `ghcr.io/yoon627/coin-trading-bot` (GHCR, multi-arch) | Spring Boot 메인 앱 (시세 수집 + 매매 엔진 + REST API + SPA) |
| `postgres` | `postgres:17-alpine` | 메인 데이터베이스 |
| `redis` | `redis:7-alpine` | 가격/지표 캐시, API Rate Limiting |

## 기술 스택

| 레이어 | 기술 | 용도 |
|--------|------|------|
| Language | Kotlin 2.1, JDK 21 | 코루틴 기반 비동기 처리 |
| Build | Gradle (Kotlin DSL), 멀티모듈 (`common`/`bot`) | 모듈 격리. Gradle toolchain이 JDK 21 자동 프로비저닝 |
| Framework | Spring Boot 3.4, **WebFlux** | 리액티브 웹 서버 |
| Frontend | React 18 + Babel-standalone (in-browser JSX), 빌드 단계 없음 | Tide SPA (`bot/src/main/resources/static/tide-app/`) |
| HTTP | Spring WebClient | Upbit API / Discord 논블로킹 호출 |
| 시세 수집 | Upbit WebSocket(ticker) + REST 폴링(candle) | `bot/marketdata/` in-process 수집 |
| Auth | Spring Security + JWT (jjwt, httpOnly+Secure 쿠키) | 유저 인증, API 보호 |
| 암호화 | AES-GCM 256-bit | 사용자별 Upbit API 키 저장 (`SecretsCrypto`) |
| Database | PostgreSQL 17 | 유저, 거래 이력, 봇 상태, 시장 데이터 |
| Cache | Redis 7 (reactive) | 가격/지표 캐시, API Rate Limiting (prod 프로필에서 활성) |
| ORM | Spring Data R2DBC | 비동기 DB 접근 |
| Migration | Flyway | DB 스키마 버전 관리 (V1~V13) |
| Container | Docker + Docker Compose | 서비스 컨테이너화 |
| Deploy | AWS EC2 **t4g.medium** (arm64, 4GB) | 단일 인스턴스 운영 (월 ~5만원 예산) |
| CI/CD | GitHub Actions + GHCR | 테스트 게이트 + multi-arch(amd64/arm64) 이미지 push |

## 프로젝트 구조

멀티모듈 Gradle 프로젝트 (`settings.gradle.kts`: `include("common", "bot")`).

```
coin-trading-bot/
├── common/                                # 공유 도메인 + 인디케이터 + 스윙 전략
│   └── src/main/kotlin/com/trading/common/
│       ├── domain/                        #   NormalizedCandle, NormalizedTicker, OrderBook, Exchange, MarketPair
│       ├── indicator/                     #   Indicators (RSI, MACD, BB, MA, EMA)
│       └── strategy/                      #   TradingStrategy 인터페이스 + 스윙 전략 7개
│
├── bot/                                   # 메인 앱 (시세 수집 + 매매 엔진 + REST + SPA, :8080)
│   └── src/main/kotlin/com/trading/bot/
│       ├── CoinTradingBotApplication.kt
│       ├── api/                           #   REST 컨트롤러 12개 + UpbitErrorHandlerAdvice, RequestValidators
│       ├── auth/                          #   AuthController, JwtProvider, JwtAuthFilter, SecurityConfig
│       ├── client/                        #   UpbitClient(REST 주문), UpbitAuthProvider, UpbitWebSocketClient
│       ├── marketdata/                    #   MarketDataIngestionService, UpbitMarketFeed, MarketDataStore (구 collector 흡수)
│       ├── engine/                        #   TradingEngine, TradeExecutionService, PositionManager,
│       │                                  #   DailyResetManager, UserTradingManager, BacktestEngine, PriceCollector
│       ├── stream/                        #   CandleAggregator, MarketDataPersistenceService, DataRetentionService
│       ├── cache/                         #   PriceCacheService (Redis)
│       ├── config/                        #   AppConfig, WebClientConfig, RedisConfig, RateLimitFilter,
│       │                                  #   SchedulerConfig, StrategyConfig, PersistenceConfig, SafeErrorAttributes
│       ├── domain/                        #   Account, Order, RealtimePrice, TradeRecord, TradingState
│       ├── persistence/                   #   R2DBC 엔티티 + Repository
│       ├── security/                      #   SecretsCrypto (AES-GCM), UserSecretsService, SecretKeyMaterialProvider
│       └── notification/                  #   DiscordNotifier
│
│   └── src/main/resources/
│       ├── application.yml                # 기본 설정 (로컬 dev, Redis 비활성)
│       ├── application-prod.yml           # prod 오버라이드 (Redis 활성, R2DBC pool)
│       ├── static/                        # SPA 정적 자산 (Tide React, babel-standalone)
│       │   ├── login.html app.html
│       │   └── tide-app/                  #   api.js, ui.jsx, screens.jsx, tokens.css
│       └── db/migration/                  # Flyway V1~V13
│
├── perf/                                  # k6 부하 테스트
├── deploy/aws/                            # AWS 배포 스크립트 + docker-compose.prod.yml
└── docs/superpowers/specs/                # 설계 스펙 문서 (날짜별)
```

## 프론트엔드 (Tide SPA)

`bot/src/main/resources/static/` 의 정적 자산. **별도 빌드 단계 없음** — `app.html`이 babel-standalone을 통해 브라우저에서 JSX를 직접 트랜스파일.

- 진입: `/login.html` → 로그인/회원가입 → `/app.html`
- 라우팅: 해시 기반 (`#dashboard`, `#bot`, `#trade`, `#orders`, `#backtest`, `#wallet`, `#settings`)
- 통신: `api.js`가 httpOnly JWT 쿠키로 모든 `/api/*` 호출. 401 → 자동 `/login.html` redirect (단 `/api/auth/*` 응답은 예외 — invalid credential 메시지 노출).
- 응답 형식: 백엔드는 Jackson `SnakeCaseStrategy` — 프론트에서 보내는 JSON도 snake_case (`access_key`, `upbit_access_key` 등).

## 트레이딩 전략

봇은 시간/일봉 + 기술적 지표 기반의 **스윙 전략 7개**를 지원합니다. 사용자별 종목/전략 설정은 `bot_configs` 테이블에 저장되며, `/api/bot/config*` 로 관리합니다. 전략은 `:common`에 거주하므로 백테스트에도 그대로 사용됩니다.

| 전략 | 매수 조건 | 특징 |
|------|----------|------|
| `volatility_breakout` | 현재가 > 전일 레인지 × K + 당일 시가 | 래리 윌리엄스 변동성 돌파 |
| `rsi_bounce` | RSI(14)가 30 아래에서 위로 교차 | 과매도 반등 포착 |
| `golden_cross` | 5일 MA > 20일 MA 교차 + RSI < 70 | 추세 전환 |
| `combined` | 변동성 돌파 + MA 상승 추세 + RSI 30~70 | 복합 필터 |
| `bollinger_bounce` | 가격이 볼린저 하단밴드 반등 + RSI 25~45 | 밴드 활용 |
| `macd_cross` | MACD > Signal 교차 + 히스토그램 양수 | 모멘텀 전환 |
| `mean_reversion` | MA20 대비 -3% 이탈 + 낮은 변동성 + RSI 회복 | 평균 회귀 |

### 리스크 관리

| 메커니즘 | 설명 |
|----------|------|
| 손절 (Stop Loss) | 매수가 대비 -3% |
| 익절 (Take Profit) | 매수가 대비 +5% |
| 트레일링 스탑 | 고점 대비 -2% 하락 시 수익 보존 매도 |
| 최대 보유일 | 7일 초과 시 강제 매도 |
| 시장 필터 | 50일 MA 아래에서는 매수 차단 |
| 일일 리셋 | 09:00 KST 기준 매수 플래그 초기화 |

## API 엔드포인트

요청/응답은 모두 JSON snake_case (Jackson `SnakeCaseStrategy`). 인증된 엔드포인트는 httpOnly JWT 쿠키로 호출. Upbit 호출 실패는 `UpbitErrorHandlerAdvice`가 잡아 사용자 친화적 4xx로 변환 (raw 401은 노출하지 않음 — FE 자동 logout 회피).

### 인증 (Public)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 회원가입 (가입 시 Upbit 키 동시 등록 가능, prod면 Secure 쿠키 강제) |
| POST | `/api/auth/login` | 로그인 (JWT 쿠키 발급) |
| POST | `/api/auth/logout` | 로그아웃 (쿠키 클리어) |

### 봇 제어 / 설정 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/bot/start` · `/api/bot/stop` | 봇 시작/중지 |
| GET | `/api/bot/status` | 봇 상태 (running, strategy, tickers, positions) |
| POST | `/api/bot/strategy` | 전략 변경 |
| GET | `/api/bot/configs` | 사용자의 모든 봇 설정 |
| POST | `/api/bot/config` | 종목별 설정 생성 (exchange, market, strategy) |
| DELETE | `/api/bot/config/{id}` | 설정 삭제 |

### 사용자 / 트레이딩 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/user/me` | 현재 유저 (id, username, has_upbit_keys, public_profile 등) |
| POST | `/api/user/keys` | Upbit API 키 등록/갱신 (AES-GCM 암호화 저장) |
| POST | `/api/user/settings` | 프로필/디스코드 웹훅 부분 갱신 |
| GET | `/api/portfolio` | 보유 코인 + 평가금액 + 수익률 |
| GET | `/api/account` | Upbit 계좌 원본 데이터 |
| POST | `/api/trade/buy` · `/api/trade/sell` | 수동 매수/매도 |
| GET | `/api/trades` | 거래 이력 (`{total, limit, offset, records}`) |

### 차트 / 전략 / 기타
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/chart/candles?exchange=&market=&interval=&count=` | 멀티 타임프레임 캔들 (1m~1M) |
| GET | `/api/chart/indicators` | 종목별 지표 (RSI, MACD, BB, MA, EMA) |
| GET | `/api/chart/tickers` | 실시간 시세 전체 |
| GET | `/api/strategies` · `/performance` | 전략 목록 / 실거래 성과 |
| POST | `/api/strategies/backtest` | 백테스트 실행 (전체 전략 비교) |
| GET | `/api/watchlist` | 관심 코인 목록 |
| GET | `/api/prices/stream` (SSE) · `/latest` · `/status` | 실시간 가격 스트림/스냅샷/연결 상태 (Public) |
| GET | `/api/leaderboard` · `/api/user/{userId}/profile` | 수익률 랭킹 / 공개 프로필 (Public) |

## 로컬 개발

### 사전 요구사항

- **JDK 21** — Gradle toolchain + Foojay resolver가 설정돼 있어, 로컬에 JDK 21이 없으면 첫 빌드 시 자동 다운로드됩니다 (`JAVA_HOME` 수동 설정 불필요).
- Docker & Docker Compose

### 실행 (인프라는 컨테이너, bot은 로컬 Gradle)

```bash
# 인프라(postgres, redis)만 띄우기 — 시세 수집은 bot 내부에서 처리되므로 별도 서비스 불필요
docker compose up -d postgres redis

# bot 로컬 실행 (dev 프로필)
./gradlew :bot:bootRun

# 접속
open http://localhost:8080         # SPA → /login.html
```

### 빌드 / 테스트 / 타입 체크

```bash
./gradlew build                    # 전 모듈 빌드
./gradlew test                     # 단위 테스트
./gradlew :bot:compileKotlin       # 타입 체크 (빠른 피드백)
./gradlew :bot:test --tests "com.trading.bot.engine.TradingEngineTest"
```

### 성능 테스트

```bash
brew install k6
k6 run perf/load-test.js                                   # 로컬 서버 대상
k6 run -e BASE_URL=http://<server-ip>:8080 perf/load-test.js  # 특정 서버 대상
```

## AWS 배포

단일 EC2 인스턴스에 Docker Compose로 `app + postgres + redis`를 띄웁니다. CI가 GHCR에 multi-arch 이미지를 push하고, 인스턴스는 그 이미지를 pull 합니다.

```
EC2 t4g.medium (arm64, 4GB, 20GB gp3 EBS)
└── docker compose (deploy/aws/docker-compose.prod.yml)
    ├── app (GHCR 이미지 pull)
    ├── PostgreSQL 17
    └── Redis 7
```

### 배포 절차

```bash
# 1. 설정 (최초 1회)
cp deploy/aws/.env.example deploy/aws/.env
vi deploy/aws/.env   # Upbit 키, SSH_ALLOW_CIDR 등. 시크릿(JWT/암호화/DB)은 setup이 자동 생성

# 2. 인프라 생성 (키페어 + VPC + SG + EC2)
./deploy/aws/deploy.sh setup

# 3. 앱 배포 (GHCR pull + compose up)
./deploy/aws/deploy.sh deploy

# 운영 명령
./deploy/aws/deploy.sh status    # 컨테이너 상태
./deploy/aws/deploy.sh logs      # 앱 로그
./deploy/aws/deploy.sh ssh       # EC2 접속
./deploy/aws/deploy.sh stop      # 전체 중지
./deploy/aws/deploy.sh start     # 전체 시작
./deploy/aws/deploy.sh destroy   # AWS 리소스 전체 삭제 (과금 중단)
```

> `INSTANCE_TYPE`, `EBS_SIZE_GB`, `SSH_ALLOW_CIDR`, `APP_ALLOW_CIDR` 는 `.env`로 조정합니다. `APP_ENCRYPTION_SECRET`은 사용자별 Upbit 키를 복호화하는 AES 키이므로 **한 번 생성된 뒤 변경하면 안 됩니다**.

### CI/CD 파이프라인 (`.github/workflows/deploy.yml`)

```
main push / PR → GitHub Actions
  ├── test  : ./gradlew test
  └── build-and-push (main push·dispatch에서만)
       └── docker buildx → GHCR multi-arch(amd64+arm64) push (:latest, :sha)
```

배포는 CI가 자동으로 하지 않습니다 — 이미지 push 후 `deploy/aws/deploy.sh deploy`로 인스턴스가 최신 이미지를 pull 합니다.

## Discord 알림

매수/매도 시 Embed 형태로 알림: 티커·가격·금액·전략, 매도 시 수익률·사유(TAKE_PROFIT / TRAILING_STOP / STOP_LOSS), 현재 KRW 잔고.

## 환경변수

### 🔴 Prod에서 반드시 설정 (미설정 시 기동 실패 또는 보안 위반)

| 변수 | 설명 |
|------|------|
| `JWT_SECRET` | JWT 서명 키. 미설정 시 prod 프로필 기동 실패. 재시작 시 키가 바뀌면 모든 세션 무효화. |
| `APP_ENCRYPTION_SECRET` | 사용자별 Upbit API 키를 AES-GCM 256으로 암호화하는 마스터 키. **변경하면 저장된 모든 사용자 키가 복호화 불가**. |

> `deploy.sh setup`이 위 두 값과 `DB_PASSWORD`를 자동 생성해 `deploy/aws/.env`에 저장합니다. **반드시 백업**하세요.

### 운영 / 거래

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_PASSWORD` | `trading` | PostgreSQL 비밀번호 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | container 기본 | DB 접속 정보 (compose 자동 주입) |
| `REDIS_HOST` / `REDIS_ENABLED` | `redis` / prod에서 활성 | Redis 캐시 |
| `UPBIT_ACCESS_KEY` / `UPBIT_SECRET_KEY` | - | (옵션) 글로벌 fallback Upbit 키. 보통은 사용자별 키를 `/api/user/keys`로 등록 |
| `TRADING_TICKERS` | `KRW-BTC` | 거래 대상 (쉼표 구분) |
| `TRADING_STRATEGY` | `volatility_breakout` | 기본 전략 |
| `TRADING_AUTO_START` | `false` | 서버 시작 시 자동 매매 시작 |
| `DISCORD_WEBHOOK_URL` | - | Discord 알림 웹훅 (사용자별 등록도 가능) |
