# Coin Trading Bot

Kotlin/Spring Boot 기반의 멀티 거래소(Upbit/Binance/KIS) 자동매매 플랫폼. 데이터 수집(`:collector`) → Kafka → 매매 봇(`:bot`)으로 분리된 이벤트 기반 아키텍처이며, 스윙 전략 8개 + 스캘핑 전략 2개 + ML(Gradient Boosted Trees)을 지원하고, 별도 `:research` 모듈로 walk-forward 백테스팅도 가능합니다.

## 시스템 아키텍처

```
                           ┌────────────────────────────────────────────────┐
                           │                EC2 (t2.micro)                  │
                           │                                                │
   ┌──────────┐            │  ┌────────────────────────────────────────┐    │
   │  GitHub   │  push     │  │             Docker Compose              │    │
   │  Actions  ├──────────►│  │                                          │    │
   │  CI/CD   │  pull img  │  │  ┌─────────────┐    ┌──────────────┐  │    │
   └──────────┘            │  │  │  collector  │───►│    Kafka     │  │    │
                           │  │  │   :8081     │    │   :9092      │  │    │
   ┌──────────┐            │  │  └─────────────┘    └──────┬───────┘  │    │
   │  Upbit   │◄───────────│──┤                             │           │    │
   │ Binance  │  WS+REST   │  │  ┌─────────────┐            ▼           │    │
   │   KIS    │            │  │  │  bot/app    │◄──── consumes events  │    │
   └──────────┘            │  │  │   :8080     │                        │    │
                           │  │  │ (REST+SPA)  │◄──►┌──────────────┐  │    │
   ┌──────────┐            │  │  └──────┬──────┘    │  PostgreSQL   │  │    │
   │ Discord  │◄───────────│──┤         │           │    :5432      │  │    │
   │ Webhook  │  알림      │  │         │           └──────────────┘  │    │
   └──────────┘            │  │         │           ┌──────────────┐  │    │
                           │  │         └──────────►│    Redis     │  │    │
   ┌──────────┐            │  │                     │    :6379     │  │    │
   │ Browser  │◄───────────│──┤                     └──────────────┘  │    │
   │  (SPA)   │            │  │                                         │    │
   └──────────┘            │  │  [모니터링 profile, optional]            │    │
                           │  │  Prometheus:9090 → Grafana:3000          │    │
                           │  │  Promtail → Loki:3100                    │    │
                           │  └────────────────────────────────────────┘    │
                           └────────────────────────────────────────────────┘
```

### 컨테이너 구성

기본 profile (실거래 운영 최소셋):

| 서비스 | 이미지 | 역할 |
|--------|--------|------|
| `bot` (app) | `ghcr.io/yoon627/coin-trading-bot` | Spring Boot 메인 앱 (REST API + SPA + 매매 엔진) |
| `collector` | 로컬 빌드 (`collector/Dockerfile`) | Upbit/Binance/KIS → Kafka 정규화 수집기 (:8081) |
| `kafka` | `apache/kafka:3.9.0` | 이벤트 백본 (KRaft 모드, ZooKeeper 불필요) |
| `kafka-init` | `apache/kafka:3.9.0` | 시작 시 토픽 생성 후 종료 |
| `postgres` | `postgres:17-alpine` | 메인 데이터베이스 |
| `redis` | `redis:7-alpine` | 가격/지표 캐시, Rate Limiting |

`monitoring` profile (옵션, `--profile monitoring`):

| 서비스 | 이미지 | 역할 |
|--------|--------|------|
| `prometheus` | `prom/prometheus` | 메트릭 수집 (:9090) |
| `loki` | `grafana/loki:2.9.0` | 로그 수집 (:3100) |
| `promtail` | `grafana/promtail:2.9.0` | 컨테이너 로그 전송 |
| `grafana` | `grafana/grafana` | 대시보드 (:3000, admin/admin) |

## 기술 스택

| 레이어 | 기술 | 용도 |
|--------|------|------|
| Language | Kotlin 2.1, JDK 21 | 코루틴 기반 비동기 처리 |
| Build | Gradle 8.12 (Kotlin DSL), 멀티모듈 (`bot`/`collector`/`common`/`research`) | 모듈 격리 |
| Framework | Spring Boot 3.4, WebFlux | 리액티브 웹 서버 |
| Frontend | React 18 + Babel-standalone (in-browser JSX), 빌드 단계 없음 | Tide SPA (`bot/src/main/resources/static/tide-app/`) |
| Messaging | Apache Kafka 3.9 (KRaft) | 거래소 이벤트 백본 (`market.ticker`, `market.candle`, `trade.execution`, `notification`) |
| HTTP | Spring WebClient | Upbit API / Discord 논블로킹 호출 |
| WebSocket | Upbit/Binance WebSocket | 실시간 가격 스트리밍 (collector 안) |
| Auth | Spring Security + JWT (jjwt, HS256), httpOnly+Secure 쿠키 | 유저 인증, API 보호 |
| 암호화 | AES-GCM 256-bit | 사용자별 거래소 API 키 저장 (`SecretsCrypto`) |
| Database | PostgreSQL 17 | 유저, 거래 이력, 봇 상태, 시장 데이터 |
| Cache | Redis 7 | 가격/지표 캐시, API Rate Limiting |
| ORM | Spring Data R2DBC | 비동기 DB 접근 |
| Migration | Flyway | DB 스키마 버전 관리 (V1~V13) |
| ML | Smile 3.1 (GBM) | 매수 시그널 예측 모델 |
| AI 분석 | Anthropic Claude API (옵션) | `CoinAnalysisScheduler` 시장 코멘트 — `CLAUDE_ANALYSIS_ENABLED=true` 시 |
| Resilience | Resilience4j | Circuit Breaker (Upbit API) |
| Monitoring | Prometheus + Grafana + Loki | 메트릭, 대시보드, 로그 (옵션 profile) |
| Container | Docker + Docker Compose | 전체 서비스 컨테이너화 |
| Deploy | AWS EC2 t2.micro | 프리티어 배포 |
| CI/CD | GitHub Actions + GHCR | Docker 이미지 빌드/배포 |

## 프로젝트 구조

멀티모듈 Gradle 프로젝트 (`settings.gradle.kts`: `include("common", "collector", "bot", "research")`).

```
coin-trading-bot/
├── common/                                # 공유 도메인 + Kafka 이벤트 + 인디케이터
│   └── src/main/kotlin/com/trading/common/
│       ├── domain/                        #   NormalizedCandle, NormalizedTicker, OrderBook, Exchange
│       ├── event/                         #   MarketDataEvent (Kafka 스키마)
│       ├── indicator/                     #   Indicators (RSI, MACD, BB, MA, EMA)
│       └── strategy/                      #   레거시 스윙 전략 7개 (`:research`에서 어댑터로 재사용)
│
├── collector/                             # 데이터 수집 서비스 (별도 Spring Boot 앱, :8081)
│   └── src/main/kotlin/com/trading/collector/
│       ├── exchange/                      #   ExchangeClient 인터페이스
│       │   ├── upbit/                     #     UpbitCollector (WS + REST)
│       │   ├── binance/                   #     BinanceCollector (WS + REST)
│       │   └── kis/                       #     KisCollector (REST 폴링, 미국주식)
│       └── service/                       #   MarketDataCollectorService → Kafka 발행
│
├── bot/                                   # 메인 앱 (REST + 매매 엔진 + SPA, :8080)
│   └── src/main/kotlin/com/trading/bot/
│       ├── CoinTradingBotApplication.kt
│       │
│       ├── api/                           # REST 컨트롤러
│       │   ├── TradingController.kt       #   /api/bot/{start,stop,status,strategy}, /api/account, /api/user/{me,keys}
│       │   ├── BotConfigController.kt     #   /api/bot/{configs, config, config/{id}} (사용자별 종목/전략 설정)
│       │   ├── PortfolioController.kt     #   /api/portfolio (보유 코인 + 수익률)
│       │   ├── ManualTradeController.kt   #   /api/trade/{buy,sell}
│       │   ├── StrategyController.kt      #   /api/strategies/{,performance,backtest}
│       │   ├── ChartController.kt         #   /api/chart/{candles,indicators,tickers,compare}
│       │   ├── TradeHistoryController.kt  #   /api/trades
│       │   ├── LeaderboardController.kt   #   /api/leaderboard, /api/user/{id}/profile, /api/user/settings
│       │   ├── PriceStreamController.kt   #   /api/prices/{stream,latest,status} (SSE)
│       │   ├── WatchlistController.kt     #   /api/watchlist
│       │   ├── MlController.kt            #   /api/ml/{train,tune,predict,status}
│       │   ├── UpbitErrorHandlerAdvice.kt #   Upbit 401/429/5xx → 사용자 친화 4xx 응답
│       │   └── RequestValidators.kt
│       │
│       ├── auth/                          #   AuthController (회원가입/로그인/로그아웃),
│       │                                  #   JwtProvider, JwtAuthFilter, SecurityConfig
│       │
│       ├── config/                        #   AppConfig, MlProperties, WebClientConfig,
│       │                                  #   SchedulerConfig, ResilienceConfig, RateLimitFilter,
│       │                                  #   RedisConfig, SafeErrorAttributes
│       │
│       ├── domain/                        #   Account, Order, Ticker, RealtimePrice, TradeRecord,
│       │                                  #   TradingState (그 외 공유 도메인은 `:common`)
│       │
│       ├── engine/                        # 트레이딩 핵심
│       │   ├── TradingEngine.kt           #   매매 루프 (코루틴)
│       │   ├── TradeExecutionService.kt   #   주문 실행 + Kafka(trade.execution)
│       │   ├── PositionManager.kt         #   포지션 동기화 + 리스크
│       │   ├── DailyResetManager.kt       #   09:00 KST 일일 리셋
│       │   ├── UserTradingManager.kt      #   유저별 엔진 관리 + 상태 영속화
│       │   ├── BacktestEngine.kt          #   과거 데이터 시뮬레이션
│       │   ├── PriceCollector.kt          #   가격 스냅샷 수집
│       │   └── CoinAnalysisScheduler.kt   #   Claude API 시장 코멘트 (옵션)
│       │
│       ├── strategy/                      # 봇 전략 (스캘핑 2개 + ML 1개)
│       │   ├── scalp/                     #   SpreadScalp, MomentumScalp
│       │   └── MlStrategy.kt              #   GBM 예측 + MA50 필터
│       │   # (스윙 전략 7개는 `:common`에서 LegacyStrategyAdapter로 재사용)
│       │
│       ├── ml/                            #   FeatureExtractor, MlModelService, HyperparameterTuner,
│       │                                  #   MlRetrainScheduler
│       │
│       ├── client/                        #   UpbitClient (REST 주문), UpbitAuthProvider,
│       │                                  #   UpbitWebSocketClient (legacy, 신규 수집은 collector)
│       │
│       ├── kafka/                         #   MarketDataConsumer, TradeEventProducer
│       ├── stream/                        #   CandleAggregator, MarketDataPersistenceConsumer,
│       │                                  #   IndicatorComputeService
│       ├── cache/PriceCacheService.kt     #   Redis 가격 캐시
│       ├── persistence/                   #   R2DBC 엔티티 + Repository
│       ├── security/                      #   SecretsCrypto (AES-GCM), UserSecretsService,
│       │                                  #   SecretKeyMaterialProvider
│       └── notification/DiscordNotifier.kt
│
│   └── src/main/resources/
│       ├── application.yml                # 기본 설정 (로컬 dev)
│       ├── application-prod.yml           # prod 오버라이드
│       ├── static/                        # SPA 정적 자산 (Tide React, babel-standalone)
│       │   ├── login.html app.html index.html
│       │   └── tide-app/                  #   api.js, ui.jsx, screens.jsx, tokens.css
│       └── db/migration/                  # Flyway V1~V13
│
├── research/                              # 리서치·백테스트 (:common만 의존, Spring 미사용)
│   └── src/main/kotlin/com/trading/research/
│       ├── engine/                        #   이벤트 드리븐 시뮬레이터
│       ├── strategy/                      #   ResearchStrategy + LegacyStrategyAdapter
│       ├── data/                          #   DataLoader (PG JDBC + HikariCP)
│       ├── execution/                     #   OrderBook, FillSimulator, CostModel
│       ├── portfolio/ risk/ sizing/       #   회계, 킬 스위치, 사이징
│       ├── walkforward/                   #   WalkForwardRunner + ParameterGrid
│       ├── metrics/                       #   Sharpe/Sortino/Calmar/MaxDD/VaR
│       ├── report/                        #   JSON/CSV/Markdown 리포트
│       └── cli/                           #   Clikt 기반 CLI 러너
│
├── monitoring/                            # Prometheus/Loki/Promtail/Grafana 설정
├── perf/                                  # k6 부하 테스트
├── deploy/aws/                            # AWS 배포 스크립트
├── docs/superpowers/specs/                # 설계 스펙 문서 (날짜별)
├── docker-compose.yml                     # 전체 서비스 정의 (default + monitoring profile)
├── Dockerfile                             # bot 이미지 빌드
└── collector/Dockerfile                   # collector 이미지 빌드
```

## 프론트엔드 (Tide SPA)

`bot/src/main/resources/static/` 의 정적 자산. **별도 빌드 단계 없음** — `app.html`이 babel-standalone을 통해 브라우저에서 JSX를 직접 트랜스파일.

- 진입: `/login.html` → 로그인/회원가입 → `/app.html`
- 라우팅: 해시 기반 (`#dashboard`, `#bot`, `#trade`, `#orders`, `#backtest`, `#wallet`, `#settings`)
- 통신: `api.js` (`TideAPI._fetch`)이 httpOnly JWT 쿠키 + CSRF 면제 fetch로 모든 `/api/*` 호출. 401 → 자동 `/login.html` redirect (단 `/api/auth/*` 응답은 예외 — invalid credential 메시지 노출).
- 응답 형식: 백엔드는 Jackson `SnakeCaseStrategy` — 프론트에서 보내는 JSON도 snake_case (`access_key`, `upbit_access_key` 등).

## 트레이딩 전략

봇은 **SWING 모드** (시간/일봉 + 기술적 지표)와 **SCALP 모드** (호가창 + 틱) 두 가지를 지원합니다. 사용자별 종목/전략 설정은 `bot_configs` 테이블에 저장되며, `/api/bot/config*` 로 관리합니다.

### SWING 전략 (스윙 7개 + ML 1개)

| 전략 | 매수 조건 | 특징 |
|------|----------|------|
| `volatility_breakout` | 현재가 > 전일 레인지 * K + 당일 시가 | 래리 윌리엄스 변동성 돌파 |
| `rsi_bounce` | RSI(14)가 30 아래에서 위로 교차 | 과매도 반등 포착 |
| `golden_cross` | 5일 MA > 20일 MA 교차 + RSI < 70 | 추세 전환 |
| `combined` | 변동성 돌파 + MA 상승 추세 + RSI 30~70 | 복합 필터 |
| `bollinger_bounce` | 가격이 볼린저 하단밴드 반등 + RSI 25~45 | 밴드 활용 |
| `macd_cross` | MACD > Signal 교차 + 히스토그램 양수 | 모멘텀 전환 |
| `mean_reversion` | MA20 대비 -3% 이탈 + 낮은 변동성 + RSI 회복 | 평균 회귀 |
| `ml_model` | GBM (100 trees, depth 4) 매수 확률 ≥ 0.6 + MA50 필터 | 20-피처 머신러닝 |

> 스윙 전략 7개는 `:common`에 거주하며 `:research` 모듈에서 `LegacyStrategyAdapter`로 백테스트에도 그대로 사용됩니다.

### SCALP 전략 (2개)

| 전략 | 진입 조건 | 특징 |
|------|----------|------|
| `spread_scalp` | 호가 스프레드가 넓고 매수벽이 두꺼울 때 | 스프레드 차익 |
| `momentum_scalp` | 단기 가격 급등 + 거래량 폭증 | 추세 편승 |

호가창(`NormalizedOrderBook`) + 최근 100개 틱 히스토리 기반.

**ML 파이프라인:**
```
캔들 200일 → 피처 추출 (20개) → 라벨링 (N일 내 X% 상승?) → GBM 학습 → 매수 확률 예측
```

### 리스크 관리

| 메커니즘 | 설명 |
|----------|------|
| 손절 (Stop Loss) | 매수가 대비 -3% |
| 익절 (Take Profit) | 매수가 대비 +5% |
| 트레일링 스탑 | 고점 대비 -2% 하락 시 수익 보존 매도 |
| 최대 보유일 | 7일 초과 시 강제 매도 |
| 시장 필터 | 50일 MA 아래에서는 매수 차단 |
| 일일 리셋 | 09:00 KST 기준 매수 플래그 초기화 |

## 리서치 / 백테스트 (`:research` 모듈)

실거래 투입 전 "이 전략이 진짜 돈을 버는가"를 검증하기 위한 **이벤트 드리븐 백테스트 프레임워크**. 라이브 엔진(`:bot`)과 완전히 분리되어 있으며, `:common`에만 의존합니다 (Spring Boot 없음).

### 핵심 특징

| 항목 | 내용 |
|------|------|
| Lookahead bias 방지 | BarStream은 close 이후에만 시그널 생성, 체결은 다음 바 시가 기준 |
| 정확한 Sharpe | per-period 수익률 기반 × √252 (일봉) 연율화 |
| 포지션 사이징 | `FixedFraction`, `VolTarget`(변동성 타깃), `Notional`(고정 금액) 지원 |
| 리스크 관리 | Stop loss / Trailing stop / Take profit / Time exit + 일/누적 DD 킬 스위치 |
| Walk-forward | In-sample 파라미터 그리드 서치 → Out-of-sample 검증 자동화 |
| 결정론 | 동일 입력 → 동일 바이트 수준 결과 (난수 시드 고정, 이터레이션 순서 고정) |

### 사용법

```bash
# CLI (v1 주의: dry-run 외에는 v1.1에서 strategy factory 와이어링 예정)
./gradlew :research:run --args='\
  --strategy RsiBounce \
  --assets UPBIT:BTC/KRW,KIS:AAPL \
  --from 2022-01-01 --to 2024-12-31 \
  --initial-cash 10000000 \
  --sizing fixed-fraction:0.1'
```

현 v1에서는 **프로그래매틱 `Engine.run()`** 사용을 권장합니다. 기존 스윙 전략 7개는 `LegacyStrategyAdapter`로 `:research`에서 바로 검증 가능합니다.

### 리포트 출력

`research-reports/{strategy}/{YYYYMMDD-HHMMSS}/` 아래에 4개 파일 생성:

| 파일 | 내용 |
|------|------|
| `result.json` | 메트릭 전체 (Sharpe, Sortino, Calmar, MaxDD, VaR 등) + 설정 스냅샷 |
| `report.md` | 사람이 읽는 요약 (전략/기간/성과/리스크) |
| `trades.csv` | 체결 이력 (entry/exit, PnL, holding period) |
| `equity-curve.csv` | 일별 자본 곡선 |

### 테스트

```bash
# 14 unit tests + 1 opt-in Testcontainers 통합 테스트
JAVA_HOME=... ./gradlew :research:test

# DB 통합 테스트 opt-in
JAVA_HOME=... ./gradlew :research:test -Dtest.docker=true
```

## API 엔드포인트

요청/응답은 모두 JSON snake_case (Jackson `SnakeCaseStrategy`). 인증된 엔드포인트는 httpOnly JWT 쿠키로 호출. Upbit 호출 실패는 `UpbitErrorHandlerAdvice`가 잡아 사용자 친화적 4xx로 변환 (raw 401은 절대 노출 X — FE 자동 logout 회피).

### 인증 (Public)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 회원가입 (가입 시 Upbit 키 동시 등록 가능, prod 프로필이면 Secure 쿠키 강제) |
| POST | `/api/auth/login` | 로그인 (JWT 쿠키 발급) |
| POST | `/api/auth/logout` | 로그아웃 (쿠키 클리어) |

### 봇 제어 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/bot/start` | 봇 시작 (전략/티커 지정 가능) |
| POST | `/api/bot/stop` | 봇 중지 |
| GET | `/api/bot/status` | 봇 상태 (running, strategy, tickers, positions) |
| POST | `/api/bot/strategy` | 전략 변경 |

### 봇 설정 (Authenticated, 사용자별 종목/전략 매핑)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/bot/configs` | 사용자의 모든 봇 설정 |
| POST | `/api/bot/config` | 종목별 설정 생성 (exchange, market, strategy, trade_mode=SWING\|SCALP) |
| DELETE | `/api/bot/config/{id}` | 설정 삭제 |

### 사용자 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/user/me` | 현재 유저 (id, username, has_upbit_keys, public_profile, has_discord_webhook) |
| POST | `/api/user/keys` | Upbit API 키 등록/갱신 (AES-GCM 암호화 저장) |
| POST | `/api/user/settings` | 프로필/디스코드 웹훅 등 부분 갱신 (보낸 필드만 반영) |

### 트레이딩 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/portfolio` | 보유 코인 + 평가금액 + 수익률 (Upbit `/v1/accounts` + `/v1/ticker` 결합) |
| GET | `/api/account` | Upbit 계좌 원본 데이터 |
| POST | `/api/trade/buy` | 수동 매수 |
| POST | `/api/trade/sell` | 수동 매도 |
| GET | `/api/trades` | 거래 이력 (envelope: `{total, limit, offset, records}`) |

### 차트/지표 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/chart/candles?exchange=&market=&interval=&count=` | 멀티 타임프레임 캔들 (1m/5m/15m/1h/4h/1d/1w/1M) |
| GET | `/api/chart/indicators` | 종목별 지표 (RSI, MACD, BB, MA, EMA) |
| GET | `/api/chart/tickers` | 실시간 시세 전체 |
| GET | `/api/chart/compare?markets=UPBIT:BTC/KRW,BINANCE:BTC/USDT` | 거래소 간 비교 (김치 프리미엄) |

### 전략/백테스트 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/strategies` | 전략 목록 |
| GET | `/api/strategies/performance` | 실거래 전략별 성과 |
| POST | `/api/strategies/backtest` | 백테스트 실행 (전체 전략 비교) |

### 관심종목 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/watchlist` | 사용자 관심 코인 목록 |

### ML (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/ml/train` | ML 모델 학습 |
| POST | `/api/ml/tune` | 하이퍼파라미터 튜닝 |
| GET | `/api/ml/predict?ticker=KRW-BTC` | 매수 시그널 예측 |
| GET | `/api/ml/status?ticker=KRW-BTC` | 모델 상태/성능 지표 |

### 실시간 가격 (Public)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/prices/stream` | SSE 실시간 가격 스트림 |
| GET | `/api/prices/latest` | 최신 가격 스냅샷 (`Map<market, RealtimePrice>`) |
| GET | `/api/prices/status` | WebSocket 연결 상태 |

### 리더보드 (Public)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/leaderboard` | 수익률 랭킹 |
| GET | `/api/user/{userId}/profile` | 유저 공개 프로필 |

## 로컬 개발

### 사전 요구사항

- JDK 21 — 이 프로젝트는 `JAVA_HOME`이 21을 가리켜야 빌드됩니다. 예시:
  ```bash
  export JAVA_HOME=/Users/jongyoonlee/Library/Java/JavaVirtualMachines/jbr-21.0.9/Contents/Home
  ```
  (이하 모든 `./gradlew` 명령은 21이 잡힌 JAVA_HOME 전제)
- Docker & Docker Compose

### 빠른 dev 실행 (인프라만 컨테이너, bot은 로컬 Gradle)

```bash
# 인프라 띄우기 (kafka + collector 포함). bot 매매 흐름은 collector→kafka→bot이라 둘 다 필수.
docker compose up -d postgres redis kafka kafka-init collector

# bot 로컬 실행 (dev profile)
./gradlew :bot:bootRun

# 접속
open http://localhost:8080         # SPA → /app.html (해시 라우팅)
```

### 전체 스택 실행 (운영과 동일)

```bash
# bot 이미지 빌드 + 핵심 서비스 (postgres, redis, kafka, collector, bot)
docker compose up -d --build

# 모니터링 추가 (prometheus, loki, promtail, grafana)
docker compose --profile monitoring up -d --build

# 접속
# bot/app:    http://localhost:8080
# collector:  http://localhost:8081  (수집 상태/health)
# kafka:      localhost:19092         (외부 클라이언트용)
# Grafana:    http://localhost:3000   (admin / $GRAFANA_PASSWORD, 기본 admin)
# Prometheus: http://localhost:9090
```

### 빌드 / 테스트 / 타입 체크

```bash
# 빌드 (전 모듈)
./gradlew build

# 단위 테스트
./gradlew test

# 모듈별 테스트
./gradlew :bot:test
./gradlew :research:test
./gradlew :collector:test

# 타입 체크만 (빠른 피드백)
./gradlew :bot:compileKotlin

# 특정 테스트
./gradlew :bot:test --tests "com.trading.bot.engine.TradingEngineTest"

# research 통합 테스트 (Testcontainers, opt-in)
./gradlew :research:test -Dtest.docker=true
```

### 성능 테스트

```bash
# k6 설치
brew install k6

# 로컬 서버 대상
k6 run perf/load-test.js

# 특정 서버 대상
k6 run -e BASE_URL=http://<server-ip>:8080 perf/load-test.js
```

## AWS 배포 (프리티어)

```
EC2 t2.micro (20GB EBS)
└── Docker Compose
    ├── App (GHCR 이미지)
    ├── PostgreSQL 17
    ├── Redis 7
    ├── Prometheus
    ├── Loki + Promtail
    └── Grafana
```

### CI/CD 파이프라인

```
main push → GitHub Actions
  ├── ./gradlew test (217 tests)
  ├── Docker build → GHCR push
  └── SSH to EC2
       ├── docker compose pull
       ├── docker compose up -d
       └── Health check (30s timeout)
```

**GitHub Secrets 필요:** `EC2_HOST`, `EC2_SSH_KEY`

### 수동 배포

```bash
# 1. 설정 (최초 1회)
cp deploy/aws/.env.example deploy/aws/.env
vi deploy/aws/.env  # API 키, DB 비밀번호 입력

# 2. 인프라 생성
./deploy/aws/deploy.sh setup

# 3. 앱 배포
./deploy/aws/deploy.sh deploy

# 운영 명령
./deploy/aws/deploy.sh status    # 컨테이너 상태 확인
./deploy/aws/deploy.sh logs      # 앱 로그 확인
./deploy/aws/deploy.sh ssh       # EC2 접속
./deploy/aws/deploy.sh stop      # 전체 중지
./deploy/aws/deploy.sh start     # 전체 시작
./deploy/aws/deploy.sh destroy   # AWS 리소스 전체 삭제
```

## 모니터링

### Grafana 대시보드 (`http://<server>:3000`)

사전 구성된 패널:
- Application Up / JVM Memory / CPU Usage
- HTTP Request Rate / Error Rate (4xx+5xx)
- HTTP Response Time (P99)
- Database Connection Pool (acquired/idle/pending)

### 로그 (Loki)

Grafana > Explore > Loki 데이터소스에서 컨테이너 로그 검색:
```
{service="app"} |= "ERROR"
{service="postgres"} |= "slow"
```

## Discord 알림

매수/매도 시 Embed 형태로 알림:
- 티커, 가격, 금액, 전략
- 매도 시: 수익률, 사유 (TAKE_PROFIT / TRAILING_STOP / STOP_LOSS)
- 현재 KRW 잔고

## 환경변수

### 🔴 Prod에서 반드시 설정 (미설정 시 기동 실패 또는 보안 위반)

| 변수 | 설명 |
|------|------|
| `JWT_SECRET` | JWT 서명 키. 설정 안 하면 prod 프로필에서 기동 실패. 재시작 시 키가 바뀌면 모든 세션이 무효화됨. |
| `APP_ENCRYPTION_SECRET` | 사용자별 거래소 API 키를 AES-GCM 256으로 암호화할 때 쓰는 마스터 키. **재시작 시 다른 값으로 바꾸면 기존에 저장된 모든 사용자 키가 복호화 불가**. |

### 운영 환경

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_PASSWORD` | `trading` | PostgreSQL 비밀번호 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | container 기본 | DB 접속 정보 (docker-compose 자동 주입) |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka 부트스트랩 |
| `REDIS_HOST` / `REDIS_ENABLED` | `redis` / `true` (compose) | Redis 활성화 |
| `GRAFANA_PASSWORD` | `admin` | Grafana 관리자 비밀번호 |

### 거래/분석

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `UPBIT_ACCESS_KEY` / `UPBIT_SECRET_KEY` | - | (옵션) 글로벌 fallback Upbit 키. 보통은 사용자별 키를 `/api/user/keys` 로 등록. |
| `TRADING_TICKERS` | `KRW-BTC` | 거래 대상 (쉼표 구분) |
| `TRADING_STRATEGY` | `volatility_breakout` | 기본 전략 |
| `TRADING_AUTO_START` | `false` | 서버 시작 시 자동 매매 시작 |
| `ML_AUTO_RETRAIN` | `true` | ML 모델 자동 재학습 |
| `ML_MODEL_DIR` | `/app/ml-models` | ML 모델 영속화 경로 |
| `DISCORD_WEBHOOK_URL` | - | Discord 알림 웹훅 (사용자별 등록도 가능) |
| `CLAUDE_API_KEY` | - | Claude API 키 (시장 코멘트용) |
| `CLAUDE_ANALYSIS_ENABLED` | `false` | `CoinAnalysisScheduler` 활성화 여부 |

### Collector

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `COLLECTOR_UPBIT_ENABLED` | `true` | Upbit 수집 on/off |
| `COLLECTOR_UPBIT_MARKETS` | `KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL` | 수집 대상 |
| `COLLECTOR_BINANCE_ENABLED` | `true` | Binance 수집 on/off |
| `COLLECTOR_BINANCE_MARKETS` | `BTCUSDT,ETHUSDT,XRPUSDT,SOLUSDT` | 수집 대상 |
