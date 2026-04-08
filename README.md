# Coin Trading Bot

Kotlin/Spring Boot 기반 Upbit 암호화폐 자동매매 봇. 규칙 기반 전략 7개 + ML(Gradient Boosted Trees) 전략을 지원하며, 백테스팅으로 전략을 비교할 수 있습니다.

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                        Spring Boot App                          │
│                                                                 │
│  ┌──────────┐  ┌───────────┐  ┌───────────┐  ┌──────────────┐  │
│  │   Auth   │  │  Trading  │  │ Backtest  │  │   ML Model   │  │
│  │  (JWT)   │  │  Engine   │  │  Engine   │  │  (Smile GBM) │  │
│  └────┬─────┘  └─────┬─────┘  └─────┬─────┘  └──────┬───────┘  │
│       │              │              │               │           │
│  ┌────┴──────────────┴──────────────┴───────────────┴────────┐  │
│  │              8 Trading Strategies                          │  │
│  │  Volatility │ RSI │ Golden │ MACD │ Bollinger │ Mean │ ML │  │
│  └──────────────────────┬────────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────┴────────────────────────────────────┐  │
│  │  Upbit Client (WebClient + JWT)  │  Discord Notifier      │  │
│  └──────────────────────┬────────────────────────────────────┘  │
│                         │                                       │
│  ┌──────────────────────┴────────────────────────────────────┐  │
│  │  PostgreSQL (Docker) │ Flyway │ R2DBC │ Spring Security   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## 기술 스택

| 레이어 | 기술 | 용도 |
|--------|------|------|
| Language | Kotlin 2.1, JDK 21 | 코루틴 기반 비동기 처리 |
| Framework | Spring Boot 3.4, WebFlux | 리액티브 웹 서버 |
| HTTP | Spring WebClient | Upbit API / Discord 논블로킹 호출 |
| Auth | Spring Security + JWT (jjwt) | 유저 인증, API 보호 |
| Database | PostgreSQL 17 (Docker) | 유저, 거래 이력, 봇 상태 저장 |
| ORM | Spring Data R2DBC | 비동기 DB 접근 |
| Migration | Flyway | DB 스키마 버전 관리 |
| ML | Smile 3.1 (GBM) | 매수 시그널 예측 모델 |
| Monitoring | Micrometer + Prometheus | 메트릭 수집 |
| Deploy | AWS EC2 t2.micro | 프리티어 배포 |
| CI/CD | GitHub Actions | 자동 테스트 + 배포 |

## 프로젝트 구조

```
src/main/kotlin/com/trading/bot/
│
├── CoinTradingBotApplication.kt     # 앱 진입점
│
├── api/                              # REST API 컨트롤러
│   ├── TradingController.kt         #   봇 시작/중지/상태, 유저 키 관리
│   ├── PortfolioController.kt       #   보유 코인 + 수익률 조회
│   ├── StrategyController.kt        #   전략 목록, 성과 집계, 백테스트
│   ├── TradeHistoryController.kt    #   거래 이력 조회
│   └── MlController.kt             #   ML 모델 학습/예측
│
├── auth/                             # 인증 레이어
│   ├── AuthController.kt            #   회원가입/로그인 API
│   ├── JwtProvider.kt               #   JWT 토큰 생성/검증
│   ├── JwtAuthFilter.kt             #   요청별 JWT 인증 WebFilter
│   ├── SecurityConfig.kt            #   Spring Security 설정
│   └── UserUtils.kt                 #   현재 유저 ID 추출 유틸
│
├── config/                           # 설정
│   ├── AppConfig.kt                 #   @ConfigurationProperties 3개
│   │                                #   (UpbitProperties, TradingProperties, DiscordProperties)
│   ├── WebClientConfig.kt           #   Upbit/Discord WebClient 빈
│   └── SchedulerConfig.kt           #   스케줄러 스레드풀 설정
│
├── domain/                           # 도메인 모델 (순수 데이터)
│   ├── Candle.kt                    #   OHLCV 캔들 데이터
│   ├── Account.kt                   #   Upbit 계좌 잔고
│   ├── Order.kt                     #   주문 요청/응답, 현재가(Ticker)
│   ├── TradeRecord.kt               #   거래 기록 + TradeSide/SellReason enum
│   └── TradingState.kt              #   봇 포지션 상태 (매수가, 보유량, 고점 등)
│
├── engine/                           # 트레이딩 핵심 엔진
│   ├── TradingEngine.kt             #   메인 트레이딩 루프 (코루틴)
│   ├── PositionManager.kt           #   매수/매도/손절/익절/트레일링스탑
│   ├── DailyResetManager.kt         #   09:00 KST 일일 리셋
│   ├── UserTradingManager.kt        #   유저별 엔진 관리 + 상태 영속화
│   └── BacktestEngine.kt            #   과거 데이터 시뮬레이션
│
├── strategy/                         # 트레이딩 전략
│   ├── TradingStrategy.kt           #   전략 인터페이스
│   ├── Indicators.kt                #   기술적 지표 (RSI, MACD, BB, MA, EMA)
│   ├── VolatilityBreakout.kt        #   변동성 돌파 전략
│   ├── RsiBounce.kt                 #   RSI 과매도 반등
│   ├── GoldenCross.kt               #   5/20일 이평선 골든크로스
│   ├── CombinedStrategy.kt          #   변동성 돌파 + MA 상승 + RSI
│   ├── BollingerBounce.kt           #   볼린저밴드 하단 반등
│   ├── MacdCross.kt                 #   MACD 골든크로스
│   ├── MeanReversion.kt             #   평균 회귀 전략
│   └── MlStrategy.kt               #   ML 모델 기반 전략
│
├── ml/                               # 머신러닝
│   ├── FeatureExtractor.kt          #   캔들 → 20개 피처 벡터 변환
│   └── MlModelService.kt            #   GBM 학습/예측/평가
│
├── client/                           # 외부 API
│   ├── UpbitClient.kt               #   Upbit REST API (코루틴 + 재시도)
│   └── UpbitAuthProvider.kt         #   Upbit JWT 인증 (HS256 + SHA-512)
│
├── persistence/                      # 데이터 접근
│   ├── UserRepository.kt            #   유저 CRUD
│   ├── BotStateRepository.kt        #   봇 실행 상태 영속화
│   ├── TradeRecordRepository.kt     #   거래 이력 CRUD
│   └── entity/
│       ├── UserEntity.kt
│       ├── BotStateEntity.kt
│       └── TradeRecordEntity.kt
│
└── notification/
    └── DiscordNotifier.kt            #   Discord Webhook (Embed 알림)
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

**주요 피처:** ma50_dist (추세), bb_width (변동성), volatility_10d, rsi_14, rsi_7

### 리스크 관리

| 메커니즘 | 설명 |
|----------|------|
| 손절 (Stop Loss) | 매수가 대비 -3% |
| 익절 (Take Profit) | 매수가 대비 +5% |
| 트레일링 스탑 | 고점 대비 -2% 하락 시 수익 보존 매도 |
| 최대 보유일 | 7일 초과 시 강제 매도 |
| 시장 필터 | 50일 MA 아래에서는 매수 차단 |
| 일일 리셋 | 09:00 KST 기준 매수 플래그 초기화 |

## 백테스팅

과거 데이터로 전략을 시뮬레이션하고 비교합니다.

```
POST /api/strategies/backtest
{
    "ticker": "KRW-BTC",
    "days": 200,
    "take_profit_pct": 5.0,
    "max_loss_pct": 3.0,
    "trailing_stop_pct": 2.0,
    "max_hold_days": 7,
    "use_market_filter": true
}
```

**성과 지표:** 총 수익률, 승률, 평균 수익률, MDD(최대 낙폭), Sharpe Ratio, Profit Factor, Buy & Hold 대비 수익률

## API 엔드포인트

### 인증
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 회원가입 (Upbit API 키 포함 가능) |
| POST | `/api/auth/login` | 로그인 → JWT 발급 |

### 봇 제어
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/bot/start` | 봇 시작 (전략/티커 지정 가능) |
| POST | `/api/bot/stop` | 봇 중지 |
| GET | `/api/bot/status` | 봇 상태 (실행 여부, 전략, 포지션) |
| POST | `/api/bot/strategy` | 전략 변경 |

### 포트폴리오
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/portfolio` | 보유 코인, 평가금액, 수익률 |
| GET | `/api/account` | Upbit 계좌 원본 데이터 |

### 전략/백테스트
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/strategies` | 전략 목록 |
| GET | `/api/strategies/performance` | 실거래 전략별 성과 |
| POST | `/api/strategies/backtest` | 백테스트 실행 (전체 전략 비교) |

### ML
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/ml/train` | ML 모델 학습 |
| GET | `/api/ml/predict?ticker=KRW-BTC` | 현재 시점 매수 시그널 예측 |
| GET | `/api/ml/status?ticker=KRW-BTC` | 모델 상태/성능 지표 |

### 유저
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/user/me` | 내 정보 |
| POST | `/api/user/keys` | Upbit API 키 등록/변경 |
| GET | `/api/trades` | 거래 이력 |

## 데이터베이스 스키마

```sql
-- V1: 거래 이력
trade_records (id, ticker, side, price, volume, total_amount,
              pnl_percent, reason, strategy, user_id, created_at)

-- V2: 유저
users (id, username, password, upbit_access_key, upbit_secret_key, created_at)

-- V3: 봇 상태 영속화 (서버 재시작 시 자동 복구)
bot_state (id, user_id, running, strategy, tickers, updated_at)
```

## 로컬 개발

```bash
# 빌드 + 테스트
./gradlew build

# 로컬 실행 (H2 인메모리 DB)
./gradlew bootRun

# http://localhost:8080 접속
```

## AWS 배포 (프리티어)

```
EC2 t2.micro
├── Java 21 (app.jar)
└── Docker
    └── PostgreSQL 17 (pgdata → EBS 영구 저장)
```

### 배포 명령

```bash
# 1. 설정
cp deploy/aws/.env.example deploy/aws/.env
vi deploy/aws/.env  # API 키, DB 비밀번호 입력

# 2. 인프라 생성 (최초 1회)
./deploy/aws/deploy.sh setup

# 3. 앱 배포
./deploy/aws/deploy.sh deploy

# 운영
./deploy/aws/deploy.sh status   # 상태 확인
./deploy/aws/deploy.sh logs     # 로그
./deploy/aws/deploy.sh ssh      # EC2 접속
./deploy/aws/deploy.sh destroy  # 전체 삭제
```

### CI/CD

`main` 브랜치 push 시 GitHub Actions가 자동으로:
1. `./gradlew test` 실행
2. `bootJar` 빌드
3. SCP로 EC2 전송 + 앱 재시작

**GitHub Secrets 필요:** `EC2_HOST`, `EC2_SSH_KEY`

## Discord 알림

매수/매도 시 Embed 형태로 알림:
- 티커, 가격, 금액, 전략
- 매도 시: 수익률, 사유 (TAKE_PROFIT / TRAILING_STOP / STOP_LOSS)
- 현재 KRW 잔고

## 프론트엔드

`http://<서버IP>:8080` 에서 접속

| 섹션 | 기능 |
|------|------|
| Control | 봇 시작/중지, 전략 선택 |
| Portfolio | KRW 잔고, 총 평가금액 |
| My Holdings | 보유 코인별 수익률 |
| ML Model Training | 모델 학습, 예측 |
| Strategy Comparison | 백테스트 전략 비교 (파라미터 조절) |
| Trade History | 거래 이력 |
