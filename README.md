# Coin Trading Bot

Kotlin/Spring Boot 기반 Upbit 암호화폐 자동매매 봇. 규칙 기반 전략 7개 + ML(Gradient Boosted Trees) 전략을 지원하며, 백테스팅으로 전략을 비교할 수 있습니다.

## 시스템 아키텍처

```
                           ┌─────────────────────────────────────────┐
                           │              EC2 (t2.micro)             │
                           │                                         │
   ┌──────────┐            │  ┌──────────────────────────────────┐   │
   │  GitHub   │  push     │  │          Docker Compose           │   │
   │  Actions  ├──────────►│  │                                    │   │
   │  CI/CD   │  pull img  │  │  ┌─────────┐    ┌────────────┐   │   │
   └──────────┘            │  │  │   App    │◄──►│ PostgreSQL │   │   │
                           │  │  │ :8080    │    │   :5432    │   │   │
   ┌──────────┐            │  │  └────┬─────┘    └────────────┘   │   │
   │  Upbit   │◄───────────│──│───────┤                            │   │
   │  API     │  WebSocket │  │       │          ┌────────────┐   │   │
   └──────────┘  + REST    │  │       ├─────────►│   Redis    │   │   │
                           │  │       │          │   :6379    │   │   │
   ┌──────────┐            │  │       │          └────────────┘   │   │
   │ Discord  │◄───────────│──│───────┤                            │   │
   │ Webhook  │  알림      │  │       │          ┌────────────┐   │   │
   └──────────┘            │  │       └─────────►│ Prometheus │   │   │
                           │  │                  │   :9090    │   │   │
   ┌──────────┐            │  │  ┌─────────┐    └──────┬─────┘   │   │
   │ Browser  │◄───────────│──│──│ Grafana  │◄──────────┘         │   │
   │          │  :3000     │  │  │  :3000   │◄──┐                 │   │
   └──────────┘            │  │  └─────────┘   │  ┌──────────┐   │   │
                           │  │                 └──│   Loki   │   │   │
                           │  │  ┌──────────┐     │  :3100   │   │   │
                           │  │  │ Promtail │────►└──────────┘   │   │
                           │  │  │ (log)    │                     │   │
                           │  │  └──────────┘                     │   │
                           │  └──────────────────────────────────┘   │
                           └─────────────────────────────────────────┘
```

### 컨테이너 구성

| 서비스 | 이미지 | 역할 |
|--------|--------|------|
| `app` | `ghcr.io/yoon627/coin-trading-bot` | Spring Boot 앱 |
| `postgres` | `postgres:17-alpine` | 메인 데이터베이스 |
| `redis` | `redis:7-alpine` | 가격 캐시, Rate Limiting |
| `prometheus` | `prom/prometheus` | 메트릭 수집 |
| `loki` | `grafana/loki:2.9.0` | 로그 수집 |
| `promtail` | `grafana/promtail:2.9.0` | 컨테이너 로그 전송 |
| `grafana` | `grafana/grafana` | 대시보드 (메트릭 + 로그) |

## 기술 스택

| 레이어 | 기술 | 용도 |
|--------|------|------|
| Language | Kotlin 2.1, JDK 21 | 코루틴 기반 비동기 처리 |
| Framework | Spring Boot 3.4, WebFlux | 리액티브 웹 서버 |
| HTTP | Spring WebClient | Upbit API / Discord 논블로킹 호출 |
| WebSocket | Upbit WebSocket | 실시간 가격 스트리밍 |
| Auth | Spring Security + JWT (jjwt) | 유저 인증, API 보호 |
| Database | PostgreSQL 17 | 유저, 거래 이력, 봇 상태 저장 |
| Cache | Redis 7 | 가격 캐시, API Rate Limiting |
| ORM | Spring Data R2DBC | 비동기 DB 접근 |
| Migration | Flyway | DB 스키마 버전 관리 (9 migrations) |
| ML | Smile 3.1 (GBM) | 매수 시그널 예측 모델 |
| Resilience | Resilience4j | Circuit Breaker (Upbit API) |
| Monitoring | Prometheus + Grafana + Loki | 메트릭, 대시보드, 로그 |
| Container | Docker + Docker Compose | 전체 서비스 컨테이너화 |
| Deploy | AWS EC2 t2.micro | 프리티어 배포 |
| CI/CD | GitHub Actions + GHCR | Docker 이미지 빌드/배포 |

## 프로젝트 구조

```
coin-trading-bot/
├── src/main/kotlin/com/trading/bot/
│   ├── CoinTradingBotApplication.kt      # 앱 진입점
│   │
│   ├── api/                               # REST API 컨트롤러
│   │   ├── TradingController.kt          #   봇 시작/중지/상태, 유저 키 관리
│   │   ├── PortfolioController.kt        #   보유 코인 + 수익률 조회
│   │   ├── ManualTradeController.kt      #   수동 매수/매도
│   │   ├── StrategyController.kt         #   전략 목록, 성과 집계, 백테스트
│   │   ├── TradeHistoryController.kt     #   거래 이력 조회
│   │   ├── LeaderboardController.kt      #   리더보드, 유저 프로필
│   │   ├── PriceStreamController.kt      #   실시간 가격 SSE 스트림
│   │   ├── WatchlistController.kt        #   관심 코인 목록
│   │   ├── MlController.kt              #   ML 모델 학습/예측/튜닝
│   │   └── RequestValidators.kt         #   입력값 검증
│   │
│   ├── auth/                              # 인증 레이어
│   │   ├── AuthController.kt             #   회원가입/로그인/로그아웃 API
│   │   ├── JwtProvider.kt                #   JWT 토큰 생성/검증
│   │   ├── JwtAuthFilter.kt              #   요청별 JWT 인증 WebFilter
│   │   ├── SecurityConfig.kt             #   Spring Security + CORS 설정
│   │   └── UserUtils.kt                  #   현재 유저 ID 추출 유틸
│   │
│   ├── config/                            # 설정
│   │   ├── AppConfig.kt                  #   @ConfigurationProperties
│   │   ├── MlProperties.kt              #   ML 모델 설정 (auto-retrain 등)
│   │   ├── WebClientConfig.kt            #   Upbit/Discord WebClient 빈
│   │   ├── SchedulerConfig.kt            #   스케줄러 스레드풀 설정
│   │   ├── ResilienceConfig.kt           #   Resilience4j Circuit Breaker
│   │   ├── RateLimitFilter.kt            #   Redis 기반 Rate Limiting
│   │   └── RedisConfig.kt               #   Redis 조건부 활성화
│   │
│   ├── domain/                            # 도메인 모델 (순수 데이터)
│   │   ├── Candle.kt                     #   OHLCV 캔들 데이터
│   │   ├── Account.kt                    #   Upbit 계좌 잔고
│   │   ├── Order.kt                      #   주문 요청/응답, 현재가(Ticker)
│   │   ├── RealtimePrice.kt             #   WebSocket 실시간 가격
│   │   ├── TradeRecord.kt                #   거래 기록 + TradeSide/SellReason enum
│   │   └── TradingState.kt               #   봇 포지션 상태
│   │
│   ├── engine/                            # 트레이딩 핵심 엔진
│   │   ├── TradingEngine.kt              #   메인 트레이딩 루프 (코루틴)
│   │   ├── TradeExecutionService.kt      #   매수/매도 주문 실행
│   │   ├── PositionManager.kt            #   포지션 동기화 + 리스크 관리
│   │   ├── DailyResetManager.kt          #   09:00 KST 일일 리셋
│   │   ├── UserTradingManager.kt         #   유저별 엔진 관리 + 상태 영속화
│   │   ├── BacktestEngine.kt             #   과거 데이터 시뮬레이션
│   │   ├── PriceCollector.kt             #   가격 스냅샷 수집
│   │   └── CoinAnalysisScheduler.kt      #   Claude AI 분석 스케줄러
│   │
│   ├── strategy/                          # 트레이딩 전략 (8개)
│   │   ├── TradingStrategy.kt            #   전략 인터페이스
│   │   ├── Indicators.kt                 #   기술적 지표 (RSI, MACD, BB, MA, EMA)
│   │   ├── VolatilityBreakout.kt         #   변동성 돌파
│   │   ├── RsiBounce.kt                  #   RSI 과매도 반등
│   │   ├── GoldenCross.kt                #   골든크로스
│   │   ├── CombinedStrategy.kt           #   복합 전략
│   │   ├── BollingerBounce.kt            #   볼린저밴드 반등
│   │   ├── MacdCross.kt                  #   MACD 크로스
│   │   ├── MeanReversion.kt              #   평균 회귀
│   │   └── MlStrategy.kt                #   ML 모델 기반
│   │
│   ├── ml/                                # 머신러닝
│   │   ├── FeatureExtractor.kt           #   캔들 → 20개 피처 벡터
│   │   ├── MlModelService.kt             #   GBM 학습/예측/평가/영속화
│   │   ├── HyperparameterTuner.kt        #   하이퍼파라미터 자동 튜닝
│   │   └── MlRetrainScheduler.kt         #   자동 재학습 스케줄러
│   │
│   ├── client/                            # 외부 API 클라이언트
│   │   ├── UpbitClient.kt                #   Upbit REST API (코루틴 + 재시도)
│   │   ├── UpbitAuthProvider.kt          #   Upbit JWT 인증 (HS256 + SHA-512)
│   │   └── UpbitWebSocketClient.kt       #   Upbit WebSocket 실시간 가격
│   │
│   ├── cache/
│   │   └── PriceCacheService.kt          #   Redis 가격 캐시 (5초 TTL)
│   │
│   ├── persistence/                       # 데이터 접근
│   │   ├── UserRepository.kt
│   │   ├── BotStateRepository.kt
│   │   ├── TradeRecordRepository.kt
│   │   ├── PriceSnapshotRepository.kt
│   │   └── entity/                        #   R2DBC 엔티티
│   │
│   ├── security/                          # API 키 암호화
│   │   ├── SecretsCrypto.kt              #   AES-GCM 암호화
│   │   ├── UserSecretsService.kt
│   │   └── SecretKeyMaterialProvider.kt
│   │
│   └── notification/
│       └── DiscordNotifier.kt             #   Discord Webhook (Embed 알림)
│
├── src/main/resources/
│   ├── application.yml                    # 기본 설정 (로컬 개발)
│   ├── application-prod.yml               # 프로덕션 오버라이드
│   ├── static/                            # 프론트엔드 (HTML/CSS/JS)
│   └── db/migration/                      # Flyway 마이그레이션 (V1~V9)
│
├── monitoring/                            # 모니터링 설정
│   ├── prometheus.yml                     # Prometheus 스크래핑 설정
│   ├── loki.yml                           # Loki 로그 수집 설정
│   ├── promtail.yml                       # Promtail 컨테이너 로그 수집
│   └── grafana/provisioning/              # Grafana 자동 프로비저닝
│       ├── datasources/                   #   Prometheus + Loki 데이터소스
│       └── dashboards/json/               #   사전 구성 대시보드
│
├── perf/                                  # 성능 테스트 (k6)
│   └── load-test.js                       # 부하 테스트 시나리오
│
├── deploy/aws/                            # AWS 배포 스크립트
│   └── deploy.sh                          # setup/deploy/ssh/status/logs/destroy
│
├── research/                              # 리서치·백테스트·리포트 전용 모듈 (:common만 의존)
│   └── src/main/kotlin/com/trading/research/
│       ├── engine/                        #   이벤트 드리븐 시뮬레이터
│       ├── strategy/                      #   ResearchStrategy + LegacyStrategyAdapter
│       ├── data/                          #   DataLoader (PG JDBC + HikariCP)
│       ├── execution/                     #   OrderBook, FillSimulator, CostModel
│       ├── portfolio/                     #   Position, Portfolio 회계
│       ├── risk/                          #   RiskManager + KillSwitch
│       ├── sizing/                        #   SizingCalculator
│       ├── walkforward/                   #   WalkForwardRunner + ParameterGrid
│       ├── metrics/                       #   Sharpe/Sortino/Calmar/MaxDD/VaR
│       ├── report/                        #   JSON/CSV/Markdown 리포트
│       └── cli/                           #   Clikt 기반 CLI 러너
│
├── docker-compose.yml                     # 전체 서비스 정의
├── Dockerfile                             # 멀티스테이지 빌드
└── .github/workflows/deploy.yml           # CI/CD 파이프라인
```

## 트레이딩 전략

### 규칙 기반 (7개)

| 전략 | 매수 조건 | 특징 |
|------|----------|------|
| `volatility_breakout` | 현재가 > 전일 레인지 * K + 당일 시가 | 래리 윌리엄스 변동성 돌파 |
| `rsi_bounce` | RSI(14)가 30 아래에서 위로 교차 | 과매도 반등 포착 |
| `golden_cross` | 5일 MA > 20일 MA 교차 + RSI < 70 | 추세 전환 |
| `combined` | 변동성 돌파 + MA 상승 추세 + RSI 30~70 | 복합 필터 |
| `bollinger_bounce` | 가격이 볼린저 하단밴드 반등 + RSI 25~45 | 밴드 활용 |
| `macd_cross` | MACD > Signal 교차 + 히스토그램 양수 | 모멘텀 전환 |
| `mean_reversion` | MA20 대비 -3% 이탈 + 낮은 변동성 + RSI 회복 | 평균 회귀 |

### ML 기반 (1개)

| 전략 | 모델 | 피처 |
|------|------|------|
| `ml_model` | Gradient Boosted Trees (100 trees, depth 4) | RSI, MACD, BB, MA, 거래량, 변동성 등 20개 |

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
# 55 tests (Testcontainers DB 테스트는 기본 skip)
JAVA_HOME=... ./gradlew :research:test

# DB 통합 테스트 opt-in
JAVA_HOME=... ./gradlew :research:test -Dtest.docker=true
```

## API 엔드포인트

### 인증 (Public)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 회원가입 |
| POST | `/api/auth/login` | 로그인 (JWT 발급) |
| POST | `/api/auth/logout` | 로그아웃 |

### 봇 제어 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/bot/start` | 봇 시작 (전략/티커 지정 가능) |
| POST | `/api/bot/stop` | 봇 중지 |
| GET | `/api/bot/status` | 봇 상태 (실행 여부, 전략, 포지션) |
| POST | `/api/bot/strategy` | 전략 변경 |

### 트레이딩 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/portfolio` | 보유 코인, 평가금액, 수익률 |
| GET | `/api/account` | Upbit 계좌 원본 데이터 |
| POST | `/api/trade/buy` | 수동 매수 |
| POST | `/api/trade/sell` | 수동 매도 |
| GET | `/api/trades` | 거래 이력 |

### 전략/백테스트 (Authenticated)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/strategies` | 전략 목록 |
| GET | `/api/strategies/performance` | 실거래 전략별 성과 |
| POST | `/api/strategies/backtest` | 백테스트 실행 (전체 전략 비교) |

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
| GET | `/api/prices/latest` | 최신 가격 스냅샷 |
| GET | `/api/prices/status` | WebSocket 연결 상태 |

### 리더보드 (Public)
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/leaderboard` | 수익률 랭킹 |
| GET | `/api/user/{userId}/profile` | 유저 공개 프로필 |

## 로컬 개발

### 사전 요구사항

- JDK 21
- Docker & Docker Compose

### 실행

```bash
# 1. PostgreSQL + Redis 컨테이너 실행
docker compose up -d postgres redis

# 2. 앱 실행 (Gradle)
./gradlew bootRun

# 3. http://localhost:8080 접속
```

### 전체 스택 실행 (모니터링 포함)

```bash
# 앱 이미지 빌드 + 코어 서비스 실행
docker compose up -d --build

# 모니터링 포함 실행
docker compose --profile monitoring up -d --build

# 접속
# App:       http://localhost:8080
# Grafana:   http://localhost:3000 (admin/admin)
# Prometheus: http://localhost:9090
```

### 테스트

```bash
# 전체 테스트 (217 test cases)
./gradlew test

# 특정 테스트
./gradlew test --tests "com.trading.bot.engine.TradingEngineTest"
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

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `UPBIT_ACCESS_KEY` | - | Upbit API Access Key |
| `UPBIT_SECRET_KEY` | - | Upbit API Secret Key |
| `TRADING_TICKERS` | `KRW-BTC` | 거래 대상 (쉼표 구분) |
| `TRADING_STRATEGY` | `volatility_breakout` | 기본 전략 |
| `TRADING_AUTO_START` | `false` | 서버 시작 시 자동 매매 시작 |
| `DB_PASSWORD` | `trading` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | - | JWT 서명 키 |
| `APP_ENCRYPTION_SECRET` | - | API 키 암호화 키 |
| `DISCORD_WEBHOOK_URL` | - | Discord 알림 웹훅 |
| `CLAUDE_API_KEY` | - | Claude AI 분석 (선택) |
| `REDIS_ENABLED` | `false` | Redis 활성화 |
| `GRAFANA_PASSWORD` | `admin` | Grafana 관리자 비밀번호 |
