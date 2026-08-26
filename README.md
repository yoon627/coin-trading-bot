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

DB 매핑·제약을 실제 PostgreSQL로 검증하는 통합테스트는 접속 정보가 있을 때만 실행되고, 없으면 건너뜁니다. 임시 컨테이너를 띄워 함께 돌리려면:

```bash
./scripts/run-db-tests.sh          # DB 통합테스트만
./scripts/run-db-tests.sh --all    # 전체 테스트
```

CI는 `services: postgres`로 DB를 제공하고 `DB_TESTS_REQUIRED=true`를 켜 두므로, 이 테스트가 조용히 건너뛰어지면 빌드가 실패합니다.

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
│           ├── db/migration/     # Flyway V1~V20
│           └── static/           # login.html, app.html, tide-app/
├── deploy/aws/                   # AWS 생성·배포 스크립트와 prod Compose
├── deploy/oci/                   # Oracle Cloud(Always Free) 생성·배포 스크립트와 prod Compose
├── deploy/vultr/                 # Vultr 서울 생성·배포 스크립트와 prod Compose (2GB 예산)
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
| `knee_reversal` | 40봉 고점 대비 15% 이상 하락 후, 20봉 저점 대비 3~12% 반등 구간에서 진입 |
| `knee_pullback` | MA20 > MA40 상승 추세에서 MA20 부근까지 눌린 뒤 반등 양봉 |

> `knee_*` 전략은 청산도 함께 정의한다(과열 RSI 꺾임 또는 볼린저 상단 복귀 — "어깨"). 다만 차트 청산은
> `TRADING_CHART_EXIT_ENABLED` 가 켜져 있을 때만 평가되고, `TRADING_MAX_HOLD_DAYS` 가 1이면 다음 거래일
> 09:00에 강제 청산되므로 스윙 보유를 의도한다면 두 값을 함께 조정해야 한다.

### 기본 리스크 관리

| 항목 | 기본값 | 환경변수 |
|---|---:|---|
| 익절 | +5% | `TRADING_TAKE_PROFIT_PCT` |
| 손절 | -5% | `TRADING_MAX_LOSS_PCT` |
| 트레일링 폭 | 고점 대비 -2% | `TRADING_TRAILING_STOP_PCT` |
| 트레일링 활성 수익률 | +3%(고점이 이 수익률에 닿은 뒤 평가) | `TRADING_TRAILING_ARM_PCT` |
| 최대 보유 기간 | 1 거래일(KST 09:00 경계) | `TRADING_MAX_HOLD_DAYS` |
| 차트 기반 청산 | 비활성 | `TRADING_CHART_EXIT_ENABLED` |
| 기록용 왕복 수수료율 | 0.001 | `TRADING_ROUND_TRIP_FEE_RATE` |

기본값의 정의처는 `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` 하나입니다(#75). `application.yml`·`docker-compose*.yml`·`deploy/*/deploy.sh` 는 기본값을 갖지 않으며, 환경변수를 설정하지 않으면 위 값이 그대로 적용됩니다.

매도 기록의 `pnl_percent`는 왕복 수수료율을 차감한 순수익률이며, 청산 조건 판정은 수수료 차감 전 수익률을 사용합니다. 50일 이동평균 시장 필터는 백테스트 전용입니다.

> **익절·트레일링 값의 관계**: 익절(`TAKE_PROFIT`)이 트레일링 폭·활성 수익률보다 **커야** 트레일링이 실효합니다.
> 예전 기본값은 익절 2% = 트레일링 폭 2%여서 익절이 항상 먼저 걸려 **트레일링이 사실상 동작하지 않았습니다**
> (앱이 부팅 시 경고를 남깁니다). 지금은 익절 5% / 트레일링 폭 2% / 활성 3%로, 고점이 +3%를 넘긴 뒤
> 고점 대비 2% 밀리면 트레일링이 청산합니다. 값을 바꿀 때 이 대소 관계를 깨지 않도록 주의하세요.

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
| 자산/이력 | GET | `/api/account`, `/api/portfolio`, `/api/trades`, `/api/trades/roundtrips` | 필요 |
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

리스크 관련 변수는 [기본 리스크 관리](#기본-리스크-관리)를 참고하세요. 현재 운영 배포 예시는 [`deploy/vultr/.env.example`](deploy/vultr/.env.example), 애플리케이션 기본값은 [`TradingProperties.kt`](common/src/main/kotlin/com/trading/common/config/TradingProperties.kt)에 있습니다.

> **배포 시 주의** — 배포 계층(`deploy/*/deploy.sh`, `docker-compose*.yml`)은 `TRADING_*` 기본값을 갖지 않습니다. `.env` 에 설정한 키만 컨테이너로 전달되고, 나머지는 앱 기본값이 적용됩니다. GitHub Actions 자동 배포는 `VULTR_DEPLOY_ENV` secret 을 그대로 `.env` 로 쓰므로, **앱 기본값에 위임하려는 키는 그 secret 에서도 지워야 합니다**(운영 고유값인 `TRADING_TICKERS`·`TRADING_STRATEGY`·`TRADING_INVEST_RATIO`·`TRADING_AUTO_START` 는 유지).

## AWS 배포 (historical)

AWS EC2 `t4g.medium`은 2026-07-31 Vultr cutover 후 인스턴스·EBS·EIP까지 삭제됐다. `deploy/aws/`는
구성·복구 설계의 historical reference로 보존하지만 현재 운영 경로가 아니며, AWS 롤백 명령을 실행할
대상도 없다. 현재 운영은 아래 Vultr 섹션을 따른다.

자세한 과거 설정은 [`deploy/aws/README.md`](deploy/aws/README.md)를 참고하되 현재 계정 자산에
대해 `setup`/`start`/`destroy`를 실행하지 마세요.

## Vultr 배포 (비용 절감 — 월 $10)

AWS 실측 $39.29/월 대비 **-75%**. Vultr 서울(`icn`) `vc2-1c-2gb`(1 vCPU x86_64 / 2GB / 55GB SSD /
2TB 대역폭)에 같은 스택을 올린다. 공인 IP·디스크·대역폭이 요금에 포함이라 별도 과금이 없다.

현재 `do-anything.cloud`에서 8종목을 거래하고 `TRADING_INVEST_RATIO=0.15`로 운영한다. 기존
인스턴스 상태는 [Vultr 상태 페이지](https://status.vultr.com/)의 전역 장애·maintenance를 확인한
뒤 변경한다. 2026-08-01 현재 `ALRT-F83KAW9` 신규 배포 간헐 실패 경보와 2026-08-04 00:00~01:00
KST 전역 DB cutover가 공지돼 있어, 해당 시간대에는 새 인스턴스 생성·삭제·리사이즈를 하지 않는다.

2GB로 낮춘 근거는 **운영 59일차 EC2 실측**이다 — app 420MiB / postgres 380MiB / redis 3.4MiB /
caddy 14MiB = 합계 818MiB, load average 0.00. 컨테이너 제한도 이에 맞춰 조정했다(합계 1472m).

```bash
install -m 600 deploy/vultr/.env.example deploy/vultr/.env
# VULTR_API_KEY + APP_ENCRYPTION_SECRET(AWS 값 복사) 입력

./deploy/vultr/deploy.sh setup    # SSH 키 + 방화벽 + 인스턴스
./deploy/vultr/deploy.sh deploy
./deploy/vultr/deploy.sh mem      # 2GB 여유 확인
```

운영 명령과 배포 동작(대상 SHA 고정, 헬스체크 실패 시 자동 롤백)은 historical AWS 판과 동일하고,
메모리 실사용을 보는 `mem` 명령이 추가돼 있다. 계정 준비(⚠️ **API Access Control에 공인 IP 등록 필수**),
완료된 AWS→Vultr **cutover 절차**, AWS 삭제 전용 historical rollback, S3 호환 백업 설정은
[`deploy/vultr/README.md`](deploy/vultr/README.md)에 있다.

## Oracle Cloud 배포 (비용 $0 대안)

같은 스택을 OCI 서울 리전의 Always Free 인스턴스(`VM.Standard.A1.Flex`, 2 OCPU ARM / 12GB)에
올리는 경로입니다. AWS 구성은 실측 **$39.29/월**(2026-06)인 반면 이쪽은 무료 한도 내에서 **$0**이고
메모리는 4GB → 12GB로 늘어납니다. 컨테이너 메모리 제한은 AWS 판과 동일하게 유지합니다.

```bash
cp deploy/oci/.env.example deploy/oci/.env
# ⚠️ APP_ENCRYPTION_SECRET은 자동 생성되지 않습니다 — 이전 시 AWS 값을 그대로 복사하세요.

./deploy/oci/deploy.sh setup    # VCN/NSG + 버킷/IAM + A1.Flex 인스턴스 (capacity 재시도 포함)
./deploy/oci/deploy.sh deploy
```

운영 명령(`status`/`logs`/`ssh`/`stop`/`start`/`destroy`)과 배포 동작(대상 SHA 고정, 헬스체크 실패 시
자동 롤백)은 AWS 판과 동일합니다. 계정 준비 체크리스트(**홈 리전을 반드시 서울로** — 사후 변경 불가),
AWS→OCI **cutover 절차**(같은 Upbit 계정에 두 봇이 붙지 않도록 하는 단일 실행 보장), 거래 활성화
전/후로 나뉘는 **롤백 절차**, 무료 티어 리스크는 [`deploy/oci/README.md`](deploy/oci/README.md)에 있습니다.

## CI/CD

`.github/workflows/deploy.yml`의 현재 흐름은 다음과 같습니다.

```text
Pull request ──> ./gradlew test --parallel
main push    ──> test ──> multi-arch Docker image ──> GHCR ──> SSH ──> Vultr deploy ──> health/rollback
main manual  ──> test ──> multi-arch Docker image ──> GHCR ──> SSH ──> Vultr deploy ──> health/rollback
```

**문서 전용 push 는 배포하지 않는다.** `on.push.paths-ignore` 가 `**.md`·`.claude/**`·`wiki/**`·`docs/**`
만 바뀐 push 에서 워크플로를 건너뛴다 — main push 가 곧 컨테이너 재생성(=트레이딩 엔진 재시작)이라
문서 커밋으로 봇을 재시작시키지 않기 위해서다. 코드와 문서가 섞인 push 는 정상 배포된다.
문서만 바꾼 뒤 그래도 배포해야 하면 Actions 에서 `workflow_dispatch` 로 수동 실행한다.

Vultr 자동 배포는 `test`와 GHCR push가 모두 성공한 뒤에만 실행된다. Actions repository secrets에
`VULTR_DEPLOY_ENV`(운영 배포용 `.env`), `VULTR_PUBLIC_IP`, `VULTR_SSH_PRIVATE_KEY`,
`VULTR_SSH_USER`를 등록해야 한다. `VULTR_API_KEY`는 기존 인스턴스에 SSH로 배포하는 경로에서는
사용하지 않는다. job은 성공 확인 SHA를 원격 `/opt/app/.last-good-sha`에 저장해 rollback 기준으로
사용한다. 최초 실행 때만 현재 app
컨테이너가 healthy인 경우에 한해 bootstrap한다. 상태 파일이 없고 컨테이너도 healthy가 아니면 배포를
거부한다. `deploy.sh`의 migration gate와 180초 health check를 그대로 적용한다. PR에서는 배포하지
않으며, 동시 배포는 하나만 허용한다. queued 실행이 오래된 SHA이면 `origin/main`과 일치하지 않아
배포하지 않는다.

GitHub-hosted runner 접근을 위해 Vultr cloud firewall에 `ctb-ssh-github-actions` 22/tcp
`0.0.0.0/0` 규칙이 필요하며, 운영 SSH는 key-only로 hardening되어 있어야 한다. 상세 절차와
재실행 시 보존·검증 규칙은 [`deploy/vultr/README.md`](deploy/vultr/README.md)를 따른다. 수동
SSH는 `SSH_ALLOW_CIDR`로 제한하고 Actions 전용 규칙만 동적 runner IP를 위해 공개한다.

자동화가 멈추면 Actions 로그의 실패 단계와 Vultr에서 `./deploy/vultr/deploy.sh status` 결과를 먼저
확인한다. 수동 복구가 필요하면 운영용 `.env`와 SSH key를 로컬에 준비한 뒤 기존 명령을 직접 실행한다.

## 참고 사항

- 현재 지원 거래소는 Upbit뿐입니다.
- `collector`, `research`, Kafka, ML/스캘핑/Claude 분석은 현재 애플리케이션에 포함되지 않습니다.
- `perf/load-test.js`에는 제거된 Prometheus/ML 엔드포인트 검사가 남아 있으므로, 성능 테스트 전에 현재 API에 맞게 시나리오를 갱신해야 합니다.
- 설계 배경과 경량화 이력은 [`PROJECT_ANALYSIS.md`](PROJECT_ANALYSIS.md)를 참고하세요.
- 누적 지식베이스(아키텍처 개념·결정 배경·겪은 함정)는 [`wiki/index.md`](wiki/index.md)에 있습니다. 운영 규약은 [`wiki/WIKI.md`](wiki/WIKI.md). 페이지를 고쳤으면 **세 가지를 모두** 돌립니다 — 하나만 돌리면 나머지 위반을 놓칩니다:
  ```bash
  uv run --no-project python "$HOME/.claude/skills/wiki/check_links.py" wiki  # 링크·index 동기화
  bash wiki/verify.sh   # stem·frontmatter·페이지 수
  bash wiki/smoke.sh    # 대표 질문 응답성 + 진행중 작업 상태 침범
  ```
