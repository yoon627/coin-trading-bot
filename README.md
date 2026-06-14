# Coin Trading Bot

Kotlin과 Spring Boot WebFlux로 만든 **Upbit 자동매매 애플리케이션**입니다. 하나의 `bot` 애플리케이션이 시세 수집, 자동·수동 매매, 백테스트, REST API와 웹 UI를 제공하고, `common` 모듈이 공용 도메인 모델과 7개 스윙 전략을 담당합니다.

> [!WARNING]
> 이 프로젝트는 투자 수익을 보장하지 않습니다. 실거래 전 API 키 권한, 주문 금액, 손절 조건을 확인하고 충분히 테스트하세요. Upbit API 키에는 출금 권한을 부여하지 않는 것을 권장합니다.

## 주요 기능

- Upbit WebSocket ticker와 REST candle을 이용한 in-process 시세 수집
- 사용자별 Upbit API 키 암호화 저장과 종목·전략 설정
- 7개 스윙 전략 기반 자동매매 및 수동 주문
- 손절, 익절, 트레일링 스탑, 최대 보유 기간 등 리스크 관리
- 실시간 가격 SSE, 포트폴리오, 거래 이력, 차트와 기술 지표
- 같은 전략 구현을 재사용하는 백테스트
- JWT httpOnly 쿠키 인증과 IP 기반 API rate limiting
- Discord 거래 알림 및 선택적 서버 오류 알림
- React 18 기반 SPA(별도 프런트엔드 빌드 단계 없음)

## 빠른 시작

### 요구 사항

- Docker 및 Docker Compose
- JDK 21 권장. Gradle toolchain이 JDK 21을 자동으로 준비할 수 있으므로 `JAVA_HOME` 설정은 필수가 아닙니다.

### 1. PostgreSQL 실행

기본 개발 프로필은 PostgreSQL을 사용하며 Redis는 비활성화되어 있습니다. 루트 Compose가 PostgreSQL을 호스트의 `5432` 포트에 공개하므로 다음과 같이 실행합니다.

```bash
docker compose up -d postgres
```

### 2. 애플리케이션 실행

`application.yml`의 개발 기본 포트는 `5433`이므로, 위 Compose와 함께 사용할 때는 `DB_PORT=5432`를 지정합니다.

macOS/Linux:

```bash
DB_PORT=5432 ./gradlew :bot:bootRun
```

Windows PowerShell:

```powershell
$env:DB_PORT = "5432"
.\gradlew.bat :bot:bootRun
```

브라우저에서 <http://localhost:8080>에 접속해 회원가입한 뒤, 설정 화면에서 사용자별 Upbit API 키를 등록할 수 있습니다. API 키 없이도 UI와 공개 시세 기능, 백테스트 등 주문이 필요하지 않은 기능을 살펴볼 수 있습니다.

> 개발 환경에서 `JWT_SECRET`과 `APP_ENCRYPTION_SECRET`이 비어 있으면 실행 시 임시 키가 생성됩니다. 재시작 후 세션과 저장된 Upbit 키를 유지하려면 두 값을 고정하세요. `prod` 프로필에서는 두 값이 필수입니다.

### 빌드와 테스트

```bash
./gradlew build
./gradlew test
./gradlew :bot:compileKotlin
./gradlew :bot:test --tests "com.trading.bot.engine.TradingEngineTest"
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용합니다.

## 아키텍처

```text
Browser ──HTTPS──> Caddy ──HTTP──> bot :8080 ──R2DBC──> PostgreSQL
                                      │
                                      ├──reactive──> Redis (prod cache/rate limit)
                                      ├──WS/REST───> Upbit
                                      └──webhook───> Discord
```

`bot`의 `marketdata` 패키지가 ticker와 candle을 직접 수집합니다. 현재 런타임에는 별도 collector, Kafka, research/ML 서비스가 없습니다. `monitoring/`에 남아 있는 설정도 현재 production Compose 스택에는 포함되지 않습니다.

운영 환경에서는 단일 EC2 인스턴스의 Docker Compose가 `caddy + app + postgres + redis`를 실행합니다. Caddy만 80/443 포트를 공개하고 `app:8080`, PostgreSQL, Redis는 Compose 내부 네트워크에서만 접근할 수 있습니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| Language | Kotlin 2.1, JDK 21 |
| Framework | Spring Boot 3.4, WebFlux, Kotlin Coroutines |
| Persistence | Spring Data R2DBC, PostgreSQL 17, Flyway |
| Cache | Reactive Redis 7(prod 프로필에서 활성) |
| Auth/Security | Spring Security, JWT, BCrypt, AES-GCM |
| Frontend | React 18, Babel Standalone, 정적 SPA |
| Build/Deploy | Gradle Kotlin DSL, Docker Compose, GitHub Actions, GHCR |
| TLS | Caddy 2, Let's Encrypt |

## 프로젝트 구조

```text
coin-trading-bot/
├── common/                       # 공용 모델, 설정, 기술 지표와 7개 전략
│   └── src/main/kotlin/com/trading/common/
│       ├── config/               # TradingProperties
│       ├── domain/               # candle, ticker, order book, market 모델
│       └── strategy/             # 전략, Indicators, ExitGates
├── bot/                          # Spring Boot 애플리케이션
│   └── src/main/
│       ├── kotlin/com/trading/bot/
│       │   ├── api/              # REST/SSE 컨트롤러와 요청 검증
│       │   ├── auth/             # JWT 인증과 Security 설정
│       │   ├── cache/            # Redis 가격 캐시
│       │   ├── client/           # Upbit REST 클라이언트
│       │   ├── engine/           # 매매, 포지션, 백테스트 엔진
│       │   ├── kis/              # KIS(한국투자) 주식 봇: client(token/주문/조회),
│       │   │                     #   order(StockOrderService WAL + StockOrderReconciler), domain, config
│       │   ├── marketdata/       # 시세 수집(WS ticker + REST candle)과 인메모리 저장소·스트림
│       │   ├── notification/     # Discord 거래·오류 알림
│       │   ├── persistence/      # R2DBC 엔티티와 repository
│       │   ├── security/         # 사용자 API 키 암호화
│       │   └── stream/           # candle 집계, 영속화, 보존 정책
│       └── resources/
│           ├── db/migration/     # Flyway V1~V15
│           └── static/           # login.html, app.html, tide-app/
├── deploy/aws/                   # AWS 생성·배포 스크립트와 prod Compose
├── perf/                         # k6 시나리오(현재 API와 동기화 여부 확인 필요)
└── docker-compose.yml            # 로컬/단일 호스트용 app, postgres, redis
```

> **KIS 주식 봇 (Phase 1 — 기반)**: 한국투자증권 OpenAPI 연동의 기반(브로커 클라이언트 + 주문유실 방지 WAL/reconcile)이 `bot/kis/` 에 추가됐다. 전략 루프·시세수집·UI 는 아직 미배선(후속). 안전상 기본 **dry-run**(`KIS_LIVE_ENABLED=false`) — 실주문은 `KIS_LIVE_ENABLED=true` + 사용자 계좌 `kis_paper=false` 둘 다 명시해야 송신된다. 설계 기록: `.claude/plans/2026-06-14-stock-bot-kis/`.

## 트레이딩 전략

전략은 `common/src/main/kotlin/com/trading/common/strategy/`에 있으며 라이브 매매와 백테스트가 같은 구현을 사용합니다.

| 이름 | 진입 조건 요약 |
|---|---|
| `volatility_breakout` | 현재가가 전일 범위와 당일 시가로 계산한 돌파가를 상향 돌파 |
| `rsi_bounce` | RSI(14)가 과매도 구간에서 반등 |
| `golden_cross` | 단기 이동평균이 장기 이동평균을 상향 돌파하고 RSI 과열 제외 |
| `combined` | 변동성 돌파, 상승 추세와 RSI 필터 결합 |
| `bollinger_bounce` | 볼린저 하단 밴드 부근 반등과 RSI 조건 결합 |
| `macd_cross` | MACD가 signal을 상향 돌파하고 histogram이 양수 |
| `mean_reversion` | MA20 대비 하락, 변동성 및 RSI 회복 조건 결합 |

### 기본 리스크 관리

| 항목 | 기본값 | 환경변수 |
|---|---:|---|
| 익절 | +2% | `TRADING_TAKE_PROFIT_PCT` |
| 손절 | -5% | `TRADING_MAX_LOSS_PCT` |
| 트레일링 폭 | 고점 대비 -2% | `TRADING_TRAILING_STOP_PCT` |
| 트레일링 활성 수익률 | 0%(수익 구간에서 즉시) | `TRADING_TRAILING_ARM_PCT` |
| 최대 보유 기간 | 1 거래일(KST 09:00 경계) | `TRADING_MAX_HOLD_DAYS` |
| 차트 기반 청산 | 비활성 | `TRADING_CHART_EXIT_ENABLED` |
| 기록용 왕복 수수료율 | 0.001 | `TRADING_ROUND_TRIP_FEE_RATE` |

매도 기록의 `pnl_percent`는 왕복 수수료율을 차감한 순수익률이며, 청산 조건 판정은 수수료 차감 전 수익률을 사용합니다. 50일 이동평균 시장 필터는 백테스트 전용입니다.

## 웹 UI

정적 자산은 `bot/src/main/resources/static/`에 있습니다. `app.html`이 Babel Standalone으로 JSX를 브라우저에서 변환하므로 Node.js 기반 빌드 단계가 없습니다.

- `/login.html`: 회원가입과 로그인
- `/app.html`: dashboard, bot, trade, orders, backtest, wallet, settings 화면
- `/api/*`: httpOnly JWT 쿠키를 사용하는 same-origin API
- JSON 필드명: 요청과 응답 모두 `snake_case`

## API 개요

인증이 필요한 API는 JWT 쿠키 또는 bearer token을 사용합니다. 아래는 현재 컨트롤러 기준의 주요 엔드포인트입니다.

| 영역 | Method | Path | 인증 |
|---|---|---|---|
| 인증 | POST | `/api/auth/register`, `/login`, `/logout` | Public |
| 사용자 | GET/POST | `/api/user/me`, `/api/user/keys`, `/api/user/settings` | 필요 |
| 봇 | GET/POST | `/api/bot/status`, `/start`, `/stop`, `/strategy`, `/halt/clear` | 필요 |
| 봇 설정 | GET/POST/DELETE | `/api/bot/configs`, `/config`, `/config/{id}` | 필요 |
| 주문 | POST | `/api/trade/buy`, `/api/trade/sell` | 필요 |
| 자산/이력 | GET | `/api/account`, `/api/portfolio`, `/api/trades` | 필요 |
| 차트 | GET | `/api/chart/candles`, `/indicators`, `/tickers`, `/compare` | 필요 |
| 전략 | GET/POST | `/api/strategies`, `/performance`, `/backtest` | 필요 |
| 관심 목록 | GET | `/api/watchlist` | 필요 |
| 실시간 가격 | GET | `/api/prices/stream`, `/latest`, `/status` | Public |
| 커뮤니티 | GET | `/api/leaderboard`, `/api/user/{userId}/profile` | Public |
| 상태 확인 | GET | `/actuator/health`, `/actuator/info` | Public |

## 환경변수

### 운영 필수값

| 변수 | 설명 |
|---|---|
| `DB_PASSWORD` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | JWT 서명 키. 변경하면 기존 세션이 무효화됩니다. |
| `APP_ENCRYPTION_SECRET` | 사용자 Upbit 키 암호화 마스터 키. 변경하면 기존 암호문을 복호화할 수 없습니다. |

`deploy/aws/deploy.sh setup`은 비어 있는 필수 시크릿을 생성해 `deploy/aws/.env`에 저장합니다. 이 파일과 특히 `APP_ENCRYPTION_SECRET`을 안전하게 백업하세요.

### 주요 선택값

| 변수 | 기본값 | 설명 |
|---|---|---|
| `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY` | 없음 | 선택적 전역 fallback 키. 일반적으로 UI에서 사용자별 키 등록 |
| `TRADING_TICKERS` | `KRW-BTC` | 쉼표로 구분한 기본 거래 종목 |
| `TRADING_STRATEGY` | `combined` | 기본 전략 |
| `TRADING_INVEST_RATIO` | `0.1` | 주문 시 투자 비율 |
| `TRADING_MAX_INVEST_AMOUNT` | `100000` | 최대 투자 금액(KRW) |
| `TRADING_AUTO_START` | `false` | 애플리케이션 시작 시 봇 자동 시작 |
| `WATCHLIST_TICKERS` | 주요 KRW 종목 | 관심 목록 종목 |
| `DISCORD_WEBHOOK_URL` | 없음 | 거래 알림 웹훅 |
| `DISCORD_ERROR_ALERT_ENABLED` | `false` | 서버 ERROR 로그 알림 활성화 |
| `DISCORD_ERROR_WEBHOOK_URL` | 없음 | 오류 알림 전용 웹훅 |
| `REDIS_ENABLED` | dev `false`, prod Compose `true` | Redis 캐시 활성화 |
| `APP_DOMAIN` | 없음 | 운영 CORS 및 Caddy TLS 도메인 |

리스크 관련 변수는 [기본 리스크 관리](#기본-리스크-관리)를 참고하세요. 전체 배포 예시는 [`deploy/aws/.env.example`](deploy/aws/.env.example), 애플리케이션 기본값은 [`application.yml`](bot/src/main/resources/application.yml)에 있습니다.

## AWS 배포

현재 운영 구성은 EC2 `t4g.medium`(arm64, 4GB) 한 대에서 Caddy, app, PostgreSQL, Redis를 실행합니다. GitHub Actions는 `main` push와 수동 실행 시 GHCR에 `amd64`/`arm64` 이미지를 push하지만 EC2 배포 자체는 자동으로 수행하지 않습니다.

```bash
cp deploy/aws/.env.example deploy/aws/.env
# deploy/aws/.env에서 AWS, 접근 범위, 이미지 설정 확인

./deploy/aws/deploy.sh setup
./deploy/aws/deploy.sh deploy

./deploy/aws/deploy.sh status
./deploy/aws/deploy.sh logs
./deploy/aws/deploy.sh ssh
```

중지·재시작은 `stop`/`start`, AWS 리소스 전체 삭제는 `destroy` 명령을 사용합니다. `destroy`는 과금 중단을 위한 파괴적 작업이므로 대상 리소스를 반드시 확인하세요.

`APP_DOMAIN`이 비어 있으면 배포 스크립트가 EC2 공인 IP 기반 `sslip.io` 도메인을 만들고 Caddy가 Let's Encrypt 인증서를 발급합니다. 자세한 설정과 문제 해결은 [`deploy/aws/README.md`](deploy/aws/README.md)를 참고하세요.

## CI/CD

`.github/workflows/deploy.yml`의 현재 흐름은 다음과 같습니다.

```text
Pull request ──> ./gradlew test --parallel
main push    ──> test ──> multi-arch Docker image ──> GHCR
manual deploy ─> deploy/aws/deploy.sh deploy ──> EC2가 대상 SHA 이미지 pull(헬스 실패 시 자동 롤백)
```

## 참고 사항

- 현재 지원 거래소는 Upbit뿐입니다.
- `collector`, `research`, Kafka, ML/스캘핑/Claude 분석은 현재 애플리케이션에 포함되지 않습니다.
- `perf/load-test.js`에는 제거된 Prometheus/ML 엔드포인트 검사가 남아 있으므로, 성능 테스트 전에 현재 API에 맞게 시나리오를 갱신해야 합니다.
- 설계 배경과 경량화 이력은 [`PROJECT_ANALYSIS.md`](PROJECT_ANALYSIS.md)를 참고하세요.
