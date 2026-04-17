# Trading Platform - 전체 구조 분석

## 1. 기술 스택

| 항목 | 기술 |
|------|------|
| **언어** | Kotlin 2.1 (JDK 21) |
| **프레임워크** | Spring Boot 3.4 + WebFlux (비동기/리액티브) |
| **빌드** | Gradle 8.12 (Kotlin DSL), 멀티모듈 |
| **메시지 브로커** | Apache Kafka 3.9 (KRaft, ZooKeeper 불필요) |
| **데이터베이스** | PostgreSQL 17 (R2DBC 비동기 드라이버) |
| **캐시** | Redis 7 (선택사항) |
| **인증** | Spring Security + JWT (HS256) |
| **비동기** | Kotlin Coroutines + Reactor |
| **ML** | Smile 3.1.1 (Gradient Boosted Trees) |
| **AI** | Claude Haiku 4.5 (Anthropic API) |
| **장애 대응** | Resilience4j (Circuit Breaker) |
| **모니터링** | Prometheus + Grafana + Loki |
| **암호화** | AES-GCM 256-bit (API 키 저장용) |
| **컨테이너** | Docker + Docker Compose |

---

## 2. 아키텍처 개요

이벤트 기반 분산 아키텍처로, 데이터 수집/처리/매매가 Kafka를 통해 분리되어 있다.

```
[Collector Layer]
  UpbitCollector    ──→ Kafka (market.ticker, market.candle)
  BinanceCollector  ──→ Kafka (market.ticker, market.candle)
  KisCollector      ──→ Kafka (market.ticker, market.candle)  ← 미국주식

[Processing Layer]
  Kafka ──→ CandleAggregator (1분봉 → 5분/15분/1시간/4시간/일/주/월봉)
  Kafka ──→ IndicatorComputeService (타임프레임별 지표 계산 → Redis)
  Kafka ──→ MarketDataPersistenceConsumer ──→ PostgreSQL (시계열 저장)

[Consumer Layer]
  Kafka ──→ MarketDataStore (in-memory) ──→ TradingEngine → 주문 실행
  Kafka ──→ NotificationConsumer ──→ Discord 알림

[Analysis Layer]
  PostgreSQL ──→ Chart API (종목별 멀티 타임프레임 차트 데이터)
  Redis ──→ Indicator API (실시간 지표 조회)
```

---

## 3. 프로젝트 구조 (멀티모듈 Gradle)

```
coin-trading-bot/
├── common/                          # 공유 도메인, 이벤트, 인디케이터
│   └── src/main/kotlin/com/trading/common/
│       ├── domain/                  # NormalizedCandle, NormalizedTicker, OrderBook 등
│       ├── event/                   # MarketDataEvent (Kafka 이벤트 스키마)
│       └── indicator/               # Indicators (기술적 지표 계산)
│
├── collector/                       # 데이터 수집 서비스 (별도 Spring Boot 앱)
│   └── src/main/kotlin/com/trading/collector/
│       ├── exchange/                # 거래소 추상화
│       │   ├── ExchangeClient.kt   # 인터페이스
│       │   ├── upbit/              # 업비트 수집기
│       │   ├── binance/            # 바이낸스 수집기
│       │   └── kis/                # 한국투자증권 수집기 (미국주식)
│       ├── service/                 # MarketDataCollectorService
│       └── config/                  # Kafka, WebClient, Properties
│
├── bot/                             # 트레이딩 봇 + API 서버
│   └── src/main/kotlin/com/trading/bot/
│       ├── api/                     # REST 컨트롤러 (13개)
│       ├── auth/                    # JWT 인증
│       ├── kafka/                   # Kafka Consumer/Producer
│       ├── stream/                  # 캔들 집계, 데이터 저장
│       ├── engine/                  # 트레이딩 엔진 핵심 로직
│       ├── strategy/                # 스윙 전략 (8개) + 스캘핑 전략 (2개)
│       ├── ml/                      # 머신러닝 (GBM)
│       ├── client/                  # 업비트 API 클라이언트 (주문용)
│       ├── persistence/             # DB Entity/Repository
│       ├── config/                  # 설정
│       ├── security/                # 암호화
│       └── notification/            # Discord 알림
│
├── docker-compose.yml               # 전체 인프라 (Kafka, PostgreSQL, Redis, Collector, Bot)
├── monitoring/                      # Prometheus, Grafana, Loki 설정
└── deploy/                          # AWS 배포 스크립트
```

---

## 4. 지원 거래소 및 자산

| 거래소 | 자산 유형 | 데이터 소스 | 수집 방식 |
|--------|----------|------------|-----------|
| **업비트 (Upbit)** | 코인 (KRW 마켓) | WebSocket + REST | 실시간 시세, 분봉/일봉 |
| **바이낸스 (Binance)** | 코인 (USDT 마켓) | WebSocket + REST | 실시간 24hrTicker, klines |
| **한국투자증권 (KIS)** | 미국주식 | REST 폴링 | 현재가 10초 간격, 일봉 |

**정규화 포맷:** 모든 거래소 데이터는 `NormalizedTicker`, `NormalizedCandle`로 통일되어 Kafka에 발행.
- 업비트: `KRW-BTC` → `BTC/KRW`
- 바이낸스: `BTCUSDT` → `BTC/USDT`
- KIS: `AAPL` → `AAPL/USD`

---

## 5. 매매 모드

### 5.1 SWING 모드 (차트 기반 스윙 트레이딩)

시간봉/일봉 기준으로 기술적 지표를 분석하여 매매. 보유 기간 수시간~수일.

**스윙 전략 (8개):**
| 전략 | 설명 |
|------|------|
| VolatilityBreakout | 래리 윌리엄스 변동성 돌파 (전일 범위 × K) |
| RsiBounce | RSI(14) 과매도(30) 반등 |
| GoldenCross | MA(5)/MA(20) 골든크로스 + RSI 필터 |
| MacdCross | MACD > Signal + 히스토그램 양수 |
| BollingerBounce | 볼린저 밴드 하단 반등 |
| MeanReversion | 평균 회귀 (MA20 대비 -3% + 낮은 변동성) |
| CombinedStrategy | 변동성 돌파 + 추세 + RSI 복합 |
| MlStrategy | GBM 머신러닝 예측 (60%+ 확률 + MA50 필터) |

**리스크 관리:**
- 손절: 매수가 대비 -3%
- 익절: 매수가 대비 +5%
- 트레일링 스탑: 고점 대비 -2%
- 일일 매수 제한: 종목당 1회/일

### 5.2 SCALP 모드 (호가창 기반 단타 트레이딩)

호가창(OrderBook) + 틱 데이터 기반으로 초단기 매매. 보유 기간 수초~수분.

**스캘핑 전략 (2개):**
| 전략 | 설명 |
|------|------|
| SpreadScalp | 호가 스프레드가 넓고 매수벽이 두꺼울 때 진입, 스프레드 차익 |
| MomentumScalp | 단기 가격 급등 + 거래량 폭증 감지 시 추세 편승 |

**스캘핑 데이터:**
- `NormalizedOrderBook`: 호가창 깊이, 스프레드, 매수/매도 불균형 비율
- `tickerHistory`: 최근 100개 틱 히스토리 (가격 추세 판단)

---

## 6. Kafka 토픽 설계

| 토픽 | 키 | 파티션 | 보존 | 용도 |
|------|-----|--------|------|------|
| `market.ticker` | `{exchange}:{market}` | 6 | 24h | 실시간 시세 |
| `market.candle` | `{exchange}:{market}:{interval}` | 6 | 7d | OHLCV 캔들 |
| `trade.execution` | `{userId}:{exchange}:{market}` | 3 | 30d | 매매 실행 기록 |
| `notification` | `{userId}` | 1 | 3d | Discord 알림 이벤트 |

**직렬화:** JSON (Jackson). Kafka에서 `kafka-console-consumer`로 바로 확인 가능.

---

## 7. 데이터베이스 스키마

### Flyway 마이그레이션 (V1~V13)

| 버전 | 내용 |
|------|------|
| V1~V9 | (레거시) trade_records, users, bot_state, price_snapshots |
| V10 | `market_tickers`, `market_candles` — 시계열 시세 데이터 |
| V11 | `trade_executions`, `positions`, `strategy_signals` — 매매 기록 |
| V12 | `user_exchange_keys`, `bot_configs` — 사용자별 설정 |
| V13 | bot_configs에 `trade_mode` 컬럼 (SWING/SCALP) |

### 핵심 테이블

```
market_candles
├── exchange, market, interval_minutes
├── open_price, high_price, low_price, close_price
├── volume, quote_volume
├── open_time, close_time
└── UNIQUE(exchange, market, interval_minutes, open_time)

trade_executions
├── user_id (FK → users)
├── exchange, market, side, order_type
├── price, volume, total_amount, fee
├── pnl_percent, pnl_amount
├── reason, strategy, status
└── executed_at

bot_configs
├── user_id (FK → users)
├── exchange, market, strategy, trade_mode
├── parameters (JSONB)
└── UNIQUE(user_id, exchange, market)
```

---

## 8. 멀티 타임프레임 분석

Collector가 수집한 1분봉을 `CandleAggregator`가 상위 타임프레임으로 자동 집계:

```
1분봉 (Collector 수집)
  → 5분봉, 15분봉, 1시간봉, 4시간봉, 일봉, 주봉, 월봉 (CandleAggregator)
```

**Chart API** (`/api/chart/`)를 통해 조회:
- `GET /api/chart/candles?exchange=UPBIT&market=BTC/KRW&interval=1h&count=100`
- `GET /api/chart/indicators?exchange=UPBIT&market=BTC/KRW&interval=1d&indicators=rsi,macd,bb`
- `GET /api/chart/compare?markets=UPBIT:BTC/KRW,BINANCE:BTC/USDT` (김프 비교)

---

## 9. 전체 데이터 흐름

### 이벤트 기반 매매 흐름

```
[거래소 API]
    │
    ▼
[Collector] ──→ Kafka (market.ticker, market.candle)
                    │
    ┌───────────────┼──────────────────┐
    ▼               ▼                  ▼
[MarketDataStore] [CandleAggregator] [DB 저장]
 (in-memory)       (멀티 타임프레임)   (PostgreSQL)
    │
    ▼
[TradingEngine] ── 10초 루프
    ├── SWING 모드: 일봉/시간봉 + 전략 시그널 → 매수/매도
    └── SCALP 모드: 호가창 + 틱 히스토리 → 초단기 매매
         │
         ▼
    [UpbitClient.placeOrder()] → 거래소 주문 실행
         │
         ▼
    [TradeExecutionService]
    ├── trade_executions 테이블 저장
    ├── Kafka(trade.execution) 발행
    └── Discord 알림
```

---

## 10. REST API 요약

### 인증
| POST `/api/auth/register` | 회원가입 |
| POST `/api/auth/login` | 로그인 (JWT 발급) |

### 봇 제어
| POST `/api/bot/start` | 봇 시작 |
| POST `/api/bot/stop` | 봇 중지 |
| GET `/api/bot/status` | 봇 상태 |
| POST `/api/bot/strategy` | 전략 변경 |

### 봇 설정 (신규)
| GET `/api/bot/configs` | 종목/전략 설정 목록 |
| POST `/api/bot/config` | 종목별 전략 설정 생성 (exchange, market, strategy, trade_mode) |
| DELETE `/api/bot/config/{id}` | 설정 삭제 |

### 차트 분석 (신규)
| GET `/api/chart/candles` | 캔들 조회 (거래소, 종목, 타임프레임) |
| GET `/api/chart/indicators` | 지표 조회 (RSI, MACD, BB, MA, EMA) |
| GET `/api/chart/tickers` | 실시간 시세 전체 조회 |
| GET `/api/chart/compare` | 거래소 간 비교 (김치 프리미엄) |

### 수동 거래 / 포트폴리오 / 전략 / ML
기존 API 유지 (TradingController, PortfolioController, StrategyController, MlController 등)

---

## 11. Docker Compose 인프라

```
┌─────────────────────────────────────────────────────────────────┐
│                     Docker Compose                               │
├──────────┬───────────┬──────────┬──────────┬────────────────────┤
│ app:8080 │ collector │ kafka    │ postgres │ [모니터링 옵션]     │
│ (Bot +   │ :8081     │ :9092    │ :5432    │ prometheus:9090    │
│  API)    │ (수집기)   │ (KRaft)  │ (PG 17)  │ grafana:3000      │
│          │           │          │          │ loki:3100          │
├──────────┤           │          ├──────────┤                    │
│ redis    │           │          │ kafka-   │                    │
│ :6379    │           │          │ init     │                    │
└──────────┴───────────┴──────────┴──────────┴────────────────────┘
```

---

## 12. 핵심 아키텍처 패턴

1. **이벤트 기반 아키텍처** — Kafka로 데이터 수집/소비 분리, 느슨한 결합
2. **멀티모듈 Gradle** — common(공유), collector(수집), bot(매매) 관심사 분리
3. **거래소 추상화** — ExchangeClient 인터페이스로 업비트/바이낸스/KIS 통일
4. **전략 패턴** — TradingStrategy(스윙) + ScalpStrategy(단타) 인터페이스
5. **리액티브 아키텍처** — WebFlux + Coroutines + R2DBC 논블로킹 I/O
6. **서킷 브레이커** — Resilience4j로 외부 API 장애 격리
7. **멀티 타임프레임** — 1분봉을 자동 집계하여 모든 타임프레임 지원
8. **저장 시 암호화** — AES-GCM으로 DB 내 API 키 보호
9. **멀티유저** — 사용자별 독립 엔진 + API 키 + 종목/전략 설정
10. **확장 가능** — Exchange enum에 추가하면 새 거래소/자산 유형 지원
