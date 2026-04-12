# Coin Trading Bot - 전체 구조 분석

## 1. 기술 스택

| 항목 | 기술 |
|------|------|
| **언어** | Kotlin 2.1 (JDK 21) |
| **프레임워크** | Spring Boot 3.4 + WebFlux (비동기/리액티브) |
| **빌드** | Gradle 8.12 (Kotlin DSL) |
| **데이터베이스** | PostgreSQL 17 (R2DBC 비동기 드라이버) |
| **캐시** | Redis 7 (선택사항, 없으면 자동 비활성화) |
| **인증** | Spring Security + JWT (HS256) |
| **비동기** | Kotlin Coroutines + Reactor |
| **ML** | Smile 3.1.1 (Gradient Boosted Trees) |
| **AI** | Claude Haiku 4.5 (Anthropic API) |
| **장애 대응** | Resilience4j (Circuit Breaker) |
| **모니터링** | Prometheus + Grafana + Loki |
| **암호화** | AES-GCM 256-bit (API 키 저장용) |
| **컨테이너** | Docker + Docker Compose |

---

## 2. 디렉토리 구조

```
coin-trading-bot/
├── src/main/kotlin/com/trading/bot/
│   ├── CoinTradingBotApplication.kt        # Spring Boot 진입점
│   ├── api/                                 # REST 컨트롤러 (11개)
│   ├── auth/                                # JWT 인증 (5개)
│   ├── config/                              # 설정 빈 (7개)
│   ├── domain/                              # 도메인 모델 (6개)
│   ├── engine/                              # 트레이딩 핵심 로직 (8개)
│   ├── strategy/                            # 매매 전략 (10개)
│   ├── ml/                                  # 머신러닝 (4개)
│   ├── client/                              # 외부 API 연동 (3개)
│   ├── cache/                               # Redis 캐싱 (1개)
│   ├── persistence/                         # 데이터 접근 계층 (8개)
│   ├── security/                            # 암호화/시크릿 (3개)
│   └── notification/                        # 디스코드 알림 (1개)
│
├── src/main/resources/
│   ├── application.yml                      # 개발 환경 설정
│   ├── application-prod.yml                 # 운영 환경 설정
│   ├── db/migration/                        # Flyway SQL 마이그레이션 (V1~V9)
│   └── static/                              # 프론트엔드 (HTML/CSS/JS)
│
├── src/test/kotlin/                         # 테스트 (약 3,000 LOC, 20개 클래스)
├── monitoring/                              # Prometheus/Grafana/Loki 설정
├── docker-compose.yml                       # 멀티 컨테이너 구성
├── Dockerfile                               # 멀티 스테이지 빌드
└── deploy/aws/                              # AWS 배포 스크립트
```

---

## 3. 핵심 패키지별 상세 설명

### 3.1 `domain/` - 도메인 모델

프로젝트의 핵심 데이터 구조들이다.

**Candle** - 업비트에서 가져오는 OHLCV 캔들 데이터
```
market, openingPrice, highPrice, lowPrice, tradePrice,
candleAccTradePrice, candleAccTradeVolume, candleDateTimeKst
```

**TradingState** - 메모리에서 관리하는 포지션 상태
```
ticker          - 종목 코드 (예: KRW-BTC)
position        - 포지션 보유 여부
avgBuyPrice     - 평균 매수가
holdVolume      - 보유 수량
peakPrice       - 보유 중 최고가 (트레일링 스탑용)
buyDate         - 매수 일시
boughtToday     - 오늘 매수 여부 (하루 1회 제한)
lastTradeTime   - 마지막 거래 시각
```

**TradeRecord** - 거래 실행 기록
```
ticker, side(BUY/SELL), price, volume, totalAmount,
pnlPercent(손익률), reason(매도 사유), strategy(사용 전략)
```

**SellReason** 열거형:
- `TAKE_PROFIT` - 익절 (+5%)
- `TRAILING_STOP` - 트레일링 스탑 (고점 대비 -2%)
- `STOP_LOSS` - 손절 (-3%)
- `DAILY_RESET` - 일일 리셋 (09:00 KST)
- `MANUAL` - 수동 매도

---

### 3.2 `engine/` - 트레이딩 엔진 (핵심)

이 패키지가 봇의 심장이다. 자동 매매의 모든 로직이 여기에 있다.

#### TradingEngine - 메인 이벤트 루프

각 사용자별로 하나의 TradingEngine 인스턴스가 코루틴 스코프에서 실행된다. 10초 간격으로 다음 루프를 반복한다:

```
while (running) {
    1. 일일 리셋 확인 (09:00 KST) → boughtToday 플래그 초기화
    
    2. 각 티커(종목)에 대해:
       a. 현재가 조회 (WebSocket 우선, 30초 이상 지연시 REST 폴백)
       
       b. 포지션 보유 중이면:
          - 최고가(peakPrice) 갱신
          - 손절 확인: 현재가 < 매수가 * 0.97 (-3%) → SELL
          - 트레일링 스탑: 현재가 < 최고가 * 0.98 (-2%) → SELL
          - 익절 확인: 현재가 > 매수가 * 1.05 (+5%) → SELL
          - 최대 보유 기간: 7일 초과 → SELL
       
       c. 포지션 미보유시:
          - 30일 캔들 데이터 조회
          - 활성 전략의 shouldBuy() 평가
          - BUY 시그널 발생 → 매수 실행
    
    3. 10초 대기
}
```

#### PositionManager - 리스크 관리 및 주문 실행

실제 주문 실행과 포지션 동기화를 담당한다:
- `syncPosition()` - 메모리 상태를 업비트 실제 계좌와 동기화
- `buy()` - 매수 주문 실행 후 TradingState 갱신
- `sell()` - 매도 주문 실행, 손익 계산 후 기록
- `checkStopLoss()` - -3% 도달시 자동 매도
- `checkTakeProfit()` - +5% 도달시 자동 매도
- `checkTrailingStop()` - 고점 대비 -2% 하락시 자동 매도

#### TradeExecutionService - 거래 기록 및 알림

- 매수/매도 주문을 실행하고 DB에 TradeRecord 저장
- 디스코드 웹훅으로 거래 알림 전송
- 손익률 계산 (수수료 포함)

#### UserTradingManager - 사용자별 엔진 관리

멀티유저를 지원하기 위한 팩토리/매니저:
- `startBot()` - 사용자별 TradingEngine 생성 및 시작
- `stopBot()` - 엔진 중지 후 상태를 DB에 저장
- `restoreOnStartup()` - 앱 재시작시 DB에서 실행 중이던 엔진 복원
- `createUpbitClient()` - 사용자의 암호화된 API 키로 UpbitClient 생성

#### BacktestEngine - 백테스트 엔진

과거 데이터로 전략을 시뮬레이션:
- 50개 이상의 캔들 데이터 필요
- 계산 지표: 승률, 샤프 비율, 최대 낙폭, 수익 팩터
- 모든 전략을 동시에 비교 가능

#### PriceCollector - 가격 수집기

`@Scheduled`로 5분 간격 실행. 업비트에서 현재가를 조회하여 `price_snapshots` 테이블에 저장한다. Claude AI 분석의 입력 데이터로 사용된다.

#### CoinAnalysisScheduler - Claude AI 분석 스케줄러

매시간 실행 (23:00~07:00 KST에만 동작):
1. 최근 6시간 가격 스냅샷 조회
2. 현재 보유 종목 정보 취합
3. Claude Haiku에게 시장 데이터 전달하여 분석 요청
4. 응답에서 ACTION:BUY/SELL/HOLD 파싱
5. BUY면 잔고의 최대 20% 매수, SELL이면 전량 매도
6. 분석 결과를 디스코드로 알림

#### DailyResetManager - 일일 리셋

매일 09:00 KST에 모든 종목의 `boughtToday` 플래그를 초기화한다. 하루에 같은 종목을 여러 번 매수하는 것을 방지하기 위한 장치다.

---

### 3.3 `strategy/` - 매매 전략 (8가지)

모든 전략은 `TradingStrategy` 인터페이스를 구현한다:

```kotlin
interface TradingStrategy {
    val name: String
    fun shouldBuy(candles: List<Candle>, currentPrice: Double): Boolean
}
```

#### (1) VolatilityBreakout - 변동성 돌파 전략
래리 윌리엄스의 변동성 돌파 전략. 전일 고가-저가 범위의 K배(기본 0.5)를 당일 시가에 더한 값을 돌파하면 매수한다.
```
매수 조건: 현재가 > 당일시가 + (전일고가 - 전일저가) * K
```

#### (2) RsiBounce - RSI 반등 전략
RSI(14)가 과매도 구간(30 이하)에서 반등할 때 매수한다.
```
매수 조건: RSI가 30 아래에서 위로 교차
```

#### (3) GoldenCross - 골든크로스 전략
단기 이동평균(5일)이 장기 이동평균(20일)을 상향 돌파하고, RSI가 과열(70) 이하일 때 매수한다.
```
매수 조건: MA5 > MA20 교차 발생 + RSI < 70
```

#### (4) CombinedStrategy - 복합 전략
변동성 돌파 + 상승 추세 + RSI 필터를 결합한 보수적 전략.
```
매수 조건: 변동성 돌파 충족 + MA5 > MA20 (상승 추세) + 30 < RSI < 70
```

#### (5) BollingerBounce - 볼린저 밴드 반등 전략
가격이 볼린저 밴드 하단에 닿고 RSI가 과매도 근처일 때 평균 회귀를 노린다.
```
매수 조건: 가격이 하단밴드 터치 + 25 < RSI < 45
```

#### (6) MacdCross - MACD 교차 전략
MACD가 시그널선을 상향 돌파하고 히스토그램이 양수일 때 매수한다.
```
매수 조건: MACD > Signal + histogram > 0
```

#### (7) MeanReversion - 평균 회귀 전략
가격이 20일 이동평균에서 3% 이상 하락하고, 변동성이 낮으며, RSI가 회복 중일 때 매수한다.
```
매수 조건: 가격 < MA20 * 0.97 + 낮은 변동성 + RSI 회복세
```

#### (8) MlStrategy - 머신러닝 전략
학습된 GBM 모델이 BUY를 예측하고 확률이 60% 이상이며, 가격이 50일 이동평균 위일 때 매수한다.
```
매수 조건: 모델 예측 = BUY + 신뢰도 > 0.6 + 현재가 > MA50
```

#### Indicators - 기술적 지표 계산 유틸리티

모든 전략이 공유하는 지표 계산 함수들:
- `sma(period)` - 단순 이동평균
- `ema(period)` - 지수 이동평균
- `rsi(period)` - 상대강도지수
- `macd()` - MACD (12, 26, 9)
- `bollingerBands()` - 볼린저 밴드 (20, 2)

---

### 3.4 `ml/` - 머신러닝

#### MlModelService - GBM 모델 학습/추론

**모델 사양:**
- 알고리즘: Gradient Boosted Trees (100 trees, depth 4, shrinkage 0.1)
- 입력: 20개 특성 벡터
- 출력: 이진 분류 (BUY=1 / HOLD=0)
- 저장: `./ml-models/{ticker}.model` 파일

**파이프라인:**
1. 200일 캔들 데이터 조회
2. 시계열 윈도우로 특성 추출 + 라벨링
3. 라벨: N일 내 X% 상승하면 1, 아니면 0
4. 80/20 시계열 분할 (미래 데이터 유출 방지)
5. 학습 후 정확도/정밀도/재현율 평가
6. 모델 파일로 저장

#### FeatureExtractor - 20개 특성 추출

캔들 데이터에서 추출하는 특성들:
1. RSI (14일, 7일)
2. MACD 히스토그램 및 시그널 거리
3. 볼린저 밴드 위치 및 밴드폭
4. 이동평균 거리 (5일, 20일, 50일) - 정규화
5. 이동평균 기울기 (3일 변화율)
6. 거래량 비율 (5일, 20일 대비)
7. 가격 변화율 (1일, 3일, 7일)
8. 캔들 패턴 (레인지, 몸통 비율)
9. 10일 변동성 (수익률 표준편차)
10. 로그 수익률

#### HyperparameterTuner - 하이퍼파라미터 튜닝

그리드 서치로 최적 파라미터 탐색:
- ntrees, depth, shrinkage, subsample 조합
- 최적 파라미터 + 메트릭 반환
- API로 수동 실행: `POST /api/ml/tune`

#### MlRetrainScheduler - 자동 재학습

Cron 기반 자동 재학습 (기본 비활성화, `ML_AUTO_RETRAIN=true`로 활성화).

---

### 3.5 `client/` - 업비트 API 연동

#### UpbitClient - REST API 클라이언트

Spring WebClient 기반의 비동기 HTTP 클라이언트:

```kotlin
suspend fun getAccounts(): List<Account>           // 전체 계좌 조회
suspend fun getDayCandles(market, count): List<Candle>  // 일봉 조회
suspend fun getMinuteCandles(market, unit, count): List<Candle>  // 분봉 조회
suspend fun getTicker(markets): List<Ticker>       // 현재가 조회
suspend fun placeOrder(request): Order             // 주문 실행
suspend fun getOrder(uuid): Order                  // 주문 조회
suspend fun cancelOrder(uuid): Order               // 주문 취소
```

**에러 처리:**
- 429 (Rate Limit): 지수 백오프로 최대 2회 재시도
- 모든 API 에러를 응답 코드 + 본문과 함께 로깅
- Resilience4j Circuit Breaker로 장애 전파 차단

#### UpbitWebSocketClient - 실시간 가격 스트리밍

`wss://api.upbit.com/websocket/v1`에 연결하여 실시간 체결가를 수신:
- 구독 메시지: `[{ticket}, {type: "ticker", codes: ["KRW-BTC", ...]}]`
- `ConcurrentHashMap`에 종목별 최신 가격 저장
- Reactor Sink로 구독자에게 가격 이벤트 발행
- 연결 끊김시 지수 백오프(최대 60초)로 자동 재연결
- `priceFlow(): Flux<RealtimePrice>` - SSE 스트리밍용

#### UpbitAuthProvider - API 인증

업비트 API 호출에 필요한 JWT 토큰 생성:
```
Claims: access_key, nonce(UUID), query_hash(SHA-512), query_hash_alg
서명: HS256 + secret_key
헤더: "Bearer {token}"
```

---

### 3.6 `api/` - REST API 컨트롤러

#### 인증 (Public)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 회원가입 |
| POST | `/api/auth/login` | 로그인 (JWT 발급) |
| POST | `/api/auth/logout` | 로그아웃 |

#### 봇 제어 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/bot/start` | 봇 시작 (티커, 전략 지정 가능) |
| POST | `/api/bot/stop` | 봇 중지 |
| GET | `/api/bot/status` | 봇 상태 조회 (실행 여부, 전략, 포지션) |
| POST | `/api/bot/strategy` | 활성 전략 변경 |

#### 수동 거래 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/trade/buy` | 수동 매수 |
| POST | `/api/trade/sell` | 수동 매도 |

#### 포트폴리오 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/portfolio` | 보유 종목 + 종목별 손익 |
| GET | `/api/account` | 업비트 계좌 상세 |

#### 전략 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/strategies` | 전체 전략 목록 (8개) |
| GET | `/api/strategies/performance` | 전략별 성과 지표 |
| POST | `/api/strategies/backtest` | 과거 데이터로 전략 백테스트 |

#### 머신러닝 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/ml/train` | GBM 모델 학습 |
| GET | `/api/ml/predict` | 매수 시그널 예측 (확률%) |
| GET | `/api/ml/status` | 모델 메트릭 (정확도, 정밀도, 재현율) |
| POST | `/api/ml/tune` | 하이퍼파라미터 자동 튜닝 |

#### 사용자 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/user/me` | 내 프로필 |
| POST | `/api/user/keys` | 업비트 API 키 등록/수정 (암호화 저장) |

#### 리더보드 (Public)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/leaderboard` | 수익률 상위 유저 |
| GET | `/api/user/{id}/profile` | 공개 프로필 |

#### 실시간 가격 (인증 필요)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/prices/stream` | SSE 실시간 가격 스트림 |

---

### 3.7 `auth/` - 인증/보안

- **JwtProvider** - JWT 토큰 생성/검증 (HS256, 만료시간 설정)
- **JwtAuthFilter** - WebFilter로 요청마다 JWT 검증
- **SecurityConfig** - Spring Security 설정 (공개/보호 경로 구분)
- **UserUtils** - SecurityContext에서 현재 사용자 추출

---

### 3.8 `config/` - 설정

- **AppConfig** - `@ConfigurationProperties`로 application.yml 바인딩
- **UpbitProperties** - 업비트 API URL, 키
- **TradingProperties** - 매매 파라미터 (티커 목록, K값, 손절/익절 비율)
- **ClaudeProperties** - Claude API 설정 (키, 모델, 활성화 여부, 슬립 시간)
- **WebClientConfig** - WebClient 빈 설정 (타임아웃, 커넥션 풀)
- **SchedulerConfig** - 스케줄러 스레드풀 설정
- **ResilienceConfig** - Resilience4j 서킷 브레이커 설정
- **RateLimitFilter** - API 요청 속도 제한 (토큰 버킷)
- **RedisConfig** - Redis 연결 설정 (조건부 활성화)

---

### 3.9 `persistence/` - 데이터 접근 계층

**엔티티 (R2DBC):**
- `UserEntity` - users 테이블 (암호화된 API 키 포함)
- `TradeRecordEntity` - trade_records 테이블
- `BotStateEntity` - bot_state 테이블
- `PriceSnapshotEntity` - price_snapshots 테이블

**리포지토리:**
- `UserRepository` - ID/username 조회
- `TradeRecordRepository` - userId별 조회, 전략별 집계
- `BotStateRepository` - userId별 조회, 실행 상태 필터
- `PriceSnapshotRepository` - ticker + 날짜 범위 조회

---

### 3.10 `security/` - 암호화

- **SecretsCrypto** - AES-GCM 256-bit 암복호화 (업비트 API 키 저장용)
- **UserSecretsService** - 사용자의 API 키를 암호화하여 DB에 저장/조회
- **SecretKeyMaterialProvider** - 암호화 마스터 키 제공 (환경변수)

---

### 3.11 `notification/` - 알림

- **DiscordNotifier** - Discord Webhook을 통해 Embed 형식으로 알림 전송
  - 매수/매도 거래 알림
  - Claude AI 분석 결과 알림
  - 에러 알림

---

### 3.12 `cache/` - 캐싱

- **PriceCacheService** - Redis에 가격 데이터 캐싱 (5초 TTL)
  - 키 형식: `price:KRW-BTC`
  - 업비트 API 호출 횟수 절감

---

## 4. 데이터베이스 스키마

### Flyway 마이그레이션 (V1~V9)

| 버전 | 내용 |
|------|------|
| V1 | `trade_records` 테이블 생성 |
| V2 | `users` 테이블 생성 (username UNIQUE, password, API 키) |
| V3 | `bot_state` 테이블 생성 (userId, running, strategy, tickers) |
| V4 | users에 `public_profile`, `public_strategy` 컬럼 추가 |
| V5 | users에 `discord_webhook_url` 컬럼 추가 |
| V6 | 암호화된 키 컬럼 크기 확장 (AES-GCM + Base64) |
| V7 | `price_snapshots` 테이블 생성 |
| V8 | users에 `admin` 플래그 추가 |
| V9 | 성능 인덱스 생성 (ticker, userId, createdAt, capturedAt) |

### ERD 개요

```
users
├── id (PK)
├── username (UNIQUE)
├── password (BCrypt)
├── upbit_access_key (AES-GCM 암호화)
├── upbit_secret_key (AES-GCM 암호화)
├── public_profile, public_strategy
├── discord_webhook_url
├── admin
└── created_at

trade_records
├── id (PK)
├── ticker
├── side (BUY/SELL)
├── price, volume, total_amount
├── pnl_percent
├── reason (TAKE_PROFIT, STOP_LOSS 등)
├── strategy
├── user_id (FK → users)
└── created_at

bot_state
├── id (PK)
├── user_id (FK → users)
├── running
├── strategy
├── tickers (JSON)
└── last_updated

price_snapshots
├── id (PK)
├── ticker
├── price, high_price, low_price
├── volume, signed_change_rate
└── captured_at
```

---

## 5. 전체 데이터 흐름

### 자동 매매 흐름

```
[사용자] → POST /api/bot/start
    │
    ▼
[UserTradingManager]
    ├── DB에서 사용자 API 키 복호화
    ├── UpbitClient 생성
    └── TradingEngine 시작 (코루틴)
         │
         ▼ (10초 루프)
    [TradingEngine]
    ├── UpbitWebSocketClient → 실시간 가격
    ├── UpbitClient.getDayCandles() → 30일 캔들
    ├── Strategy.shouldBuy() → 매수 시그널 평가
    │   ├── Indicators.rsi(), macd(), bollingerBands()
    │   └── MlModelService.predict() (ML 전략시)
    │
    ├── [매수 시그널 발생]
    │   └── PositionManager.buy()
    │       └── TradeExecutionService.executeBuy()
    │           ├── UpbitClient.placeOrder() → 업비트 매수 주문
    │           ├── TradeRecordRepository.save() → DB 기록
    │           └── DiscordNotifier.send() → 디스코드 알림
    │
    └── [매도 조건 충족]
        └── PositionManager.sell()
            └── TradeExecutionService.executeSellAll()
                ├── UpbitClient.placeOrder() → 업비트 매도 주문
                ├── 손익 계산
                ├── TradeRecordRepository.save() → DB 기록
                └── DiscordNotifier.send() → 디스코드 알림
```

### Claude AI 분석 흐름

```
[PriceCollector] ──(5분 간격)──→ price_snapshots 테이블
                                      │
[CoinAnalysisScheduler] ──(매시간)──→ │
    ├── 최근 6시간 스냅샷 조회        ◄─┘
    ├── 현재 보유 포지션 취합
    ├── Claude Haiku API 호출
    │   └── 프롬프트: 시장 데이터 + 보유 현황 → 매매 판단 요청
    ├── 응답 파싱: ACTION:BUY/SELL/HOLD
    ├── 해당 액션 자동 실행
    └── 디스코드로 분석 결과 알림
```

### 프론트엔드 실시간 가격 흐름

```
[브라우저] ← SSE ← [PriceStreamController]
                        │
                        ▼
              [UpbitWebSocketClient]
                        │
                        ▼ (WebSocket)
                   [업비트 서버]
```

---

## 6. 리스크 관리 체계

| 장치 | 조건 | 행동 |
|------|------|------|
| **손절 (Stop Loss)** | 현재가 < 매수가 * 0.97 | 즉시 전량 매도 |
| **익절 (Take Profit)** | 현재가 > 매수가 * 1.05 | 즉시 전량 매도 |
| **트레일링 스탑** | 현재가 < 고점 * 0.98 | 즉시 전량 매도 (수익 보존) |
| **최대 보유 기간** | 매수 후 7일 경과 | 강제 매도 |
| **일일 매수 제한** | 같은 종목 하루 1회 | 추가 매수 차단 |
| **시장 필터** | 현재가 < MA50 | 매수 시그널 무시 (하락 추세) |
| **Claude AI 매수 한도** | 잔고의 최대 20% | 과도한 포지션 방지 |
| **서킷 브레이커** | API 장애 감지 | 주문 중단, 폴백 동작 |
| **API 속도 제한** | 토큰 버킷 방식 | 과도한 요청 차단 |

---

## 7. 배포 및 모니터링

### Docker 멀티 스테이지 빌드

```dockerfile
# 1단계: 빌드
FROM gradle:8.12-jdk21 → JAR 생성

# 2단계: 실행
FROM eclipse-temurin:21-jre-alpine → JAR 복사, 포트 8080 노출
Health Check: /actuator/health (30초 간격)
JVM 옵션: -XX:MaxRAMPercentage=75.0
```

### Docker Compose 서비스

```
┌─────────────────────────────────────────────────┐
│                Docker Compose                     │
├──────────┬──────────┬──────────┬─────────────────┤
│ app:8080 │ postgres │ redis    │ [모니터링 옵션]  │
│ (Spring  │ :5432    │ :6379    │ prometheus:9090  │
│  Boot)   │ (PG 17)  │ (Redis7) │ grafana:3000     │
│          │          │          │ loki:3100         │
│          │          │          │ promtail          │
└──────────┴──────────┴──────────┴─────────────────┘
```

### 모니터링 스택

- **Prometheus** - `/actuator/prometheus`에서 메트릭 수집 (Micrometer)
- **Grafana** - 대시보드 시각화 (사전 프로비저닝)
- **Loki + Promtail** - 컨테이너 로그 수집 및 검색

### AWS 배포

- EC2 t2.micro (프리 티어)
- `deploy/aws/deploy.sh` 스크립트 (setup, deploy, ssh, status, logs, destroy)
- GitHub Actions CI/CD → GHCR 이미지 → EC2 배포

---

## 8. 테스트 구조

20개 테스트 클래스, 약 3,000 LOC:

| 영역 | 테스트 클래스 | 검증 내용 |
|------|-------------|-----------|
| **엔진** | TradingEngineTest | 메인 루프, 포지션 갱신, 전략 평가 |
| **포지션** | PositionManagerTest, ExtendedTest | 매수/매도, 손절/익절/트레일링 스탑 |
| **백테스트** | BacktestEngineTest | 시뮬레이션, 샤프비율, 낙폭 |
| **전략** | Strategy별 Test | 각 전략의 시그널 로직 |
| **지표** | IndicatorsTest | RSI, MACD, 볼린저밴드, MA 계산 |
| **ML** | MlModelServiceTest | GBM 학습, 평가, 직렬화 |
| **특성** | FeatureExtractorTest | 20개 특성 추출, 데이터셋 생성 |
| **튜닝** | HyperparameterTunerTest | 그리드 서치, 파라미터 최적화 |
| **클라이언트** | UpbitClientTest | REST 목킹, 재시도, 에러 처리 |
| **WebSocket** | UpbitWebSocketClientTest | 연결, 메시지 파싱 |
| **인증** | JwtProviderTest | JWT 생성, 검증, 클레임 |
| **암호화** | SecretsCryptoTest | AES-GCM 암복호화 |
| **캐시** | PriceCacheServiceTest | Redis 캐시 연산 |
| **속도제한** | RateLimitFilterTest | 토큰 버킷 구현 |
| **알림** | DiscordNotifierTest | Embed 페이로드 포맷 |
| **리셋** | DailyResetManagerTest | KST 타임존, 리셋 로직 |
| **실행** | TradeExecutionServiceTest | 기록 저장, 알림 |
| **검증** | RequestValidatorsTest | 입력값 검증 (마켓, 전략 등) |
| **스트림** | PriceStreamControllerTest | SSE 스트리밍 |

**테스트 도구:** JUnit 5, Mockk, SpringMockk, MockWebServer, Reactor Test, Coroutines Test

---

## 9. 핵심 아키텍처 패턴 요약

1. **리액티브 아키텍처** - WebFlux + Coroutines로 논블로킹 I/O
2. **전략 패턴** - TradingStrategy 인터페이스로 8개 전략 교체 가능
3. **팩토리 패턴** - UserTradingManager가 사용자별 엔진 + 클라이언트 생성
4. **리포지토리 패턴** - R2DBC 비동기 데이터 접근
5. **서킷 브레이커** - Resilience4j로 외부 API 장애 격리
6. **이벤트 기반** - Reactor Sink로 실시간 가격 SSE 스트리밍
7. **스케줄링** - @Scheduled로 가격 수집, Claude 분석, ML 재학습
8. **저장 시 암호화** - AES-GCM으로 DB 내 API 키 보호
9. **멀티유저** - 사용자별 독립 엔진 + API 키 + 포지션 관리
10. **관심사 분리** - Controller → Service → Repository → R2DBC 계층 구조
