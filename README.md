# Coin Trading Bot

Upbit 현물 자동매매 봇. Kotlin 2.1, Spring Boot 3.4 WebFlux, R2DBC, PostgreSQL 17.

투자 수익을 보장하지 않는다. 실거래 전에 주문 금액과 손절 조건을 확인하고, Upbit API 키에는 출금 권한을 주지 않는다.

## 주요 기능

- Upbit WebSocket ticker와 REST 캔들로 시세 수집
- 일봉 기반 `combined` 전략으로 자동 매수, 손절·익절·트레일링 스탑·보유 기간 상한으로 청산
- 사용자별 Upbit API 키 암호화 저장(AES-GCM)
- 포트폴리오, 거래 이력, 라운드트립 조회
- JWT httpOnly 쿠키 인증, IP 기준 rate limit(인증 경로 분당 30회, 그 외 API 분당 60회)
- Discord 거래 알림과 서버 오류 알림
- 빌드 단계 없는 React 18 SPA(`bot/src/main/resources/static`)

## 구조

```text
common/   공용 설정(TradingProperties, ExitParamRanges), 도메인 모델, 전략과 지표
bot/      Spring Boot 애플리케이션
  api/          REST 컨트롤러
  auth/         JWT 인증, Security 설정
  client/       Upbit REST 클라이언트
  engine/       매매 엔진, 포지션 관리, 주문 실행
  marketdata/   시세 수집과 인메모리 저장소
  stream/       분봉을 상위 봉으로 집계
  persistence/  R2DBC 엔티티와 repository
  security/     API 키 암호화
  notification/ Discord 알림
  resources/db/migration/  Flyway 마이그레이션
deploy/vultr/  운영 배포 스크립트와 prod Compose
```

## 매수 조건

일봉 21개 이상에서 세 조건을 모두 만족하면 산다.

- 현재가 > 당일 시가 + 전일 레인지 × `TRADING_K_VALUE`
- MA5 > MA20
- RSI(14)가 30~70

주문 금액이 손절가까지 내려갔을 때 Upbit 최소 주문(5,000원)보다 작아지면 사지 않는다. 기본 손절 5%에서는 약 5,264원 미만이다.

## 로컬 실행

JDK 21, Docker, Docker Compose 2.24 이상이 필요하다. 루트 `docker-compose.yml`은 로컬 개발용이고 PostgreSQL을 `127.0.0.1:5432`에 연다.

```bash
docker compose up -d postgres
DB_PORT=5432 ./gradlew :bot:bootRun
```

`application.yml`의 기본 DB 포트가 5433이라 `DB_PORT=5432`를 준다. <http://localhost:8080>에서 첫 계정을 만든다. 회원가입은 계정이 하나도 없을 때만 열린다.

`JWT_SECRET`과 `APP_ENCRYPTION_SECRET`이 비어 있으면 실행할 때마다 임시 키를 만든다. 재시작 후에도 세션과 저장한 API 키를 쓰려면 환경변수로 고정한다. `bootRun`은 `.env`를 읽지 않고, 루트 compose의 app만 `.env`를 읽는다. `prod` 프로필에서는 둘 다 필수다.

### 테스트

```bash
./gradlew test
./scripts/run-db-tests.sh          # 임시 Postgres 컨테이너로 DB 통합 테스트만
./scripts/run-db-tests.sh --all    # 같은 컨테이너로 전체 테스트
```

DB 통합 테스트는 `TEST_DB_*` 접속 정보가 없으면 건너뛴다. CI는 `DB_TESTS_REQUIRED=true`로 돌리므로 건너뛰면 실패한다.

## 설정

기본값은 `TradingProperties.kt`, `KisTradingProperties.kt`, `AppConfig.kt`, `application.yml`에 있다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5433` / `trading` / `trading` / `trading` | prod 프로필의 기본 포트는 5432 |
| `JWT_SECRET` | 없음 | 바꾸면 기존 세션이 끊긴다 |
| `APP_ENCRYPTION_SECRET` | 없음 | 바꾸면 저장된 API 키를 복호화할 수 없다 |
| `TRADING_TICKERS` | `KRW-BTC` | 거래 종목, 쉼표 구분 |
| `TRADING_AUTO_START` | `false` | 기동 시 봇 자동 시작 |
| `TRADING_INVEST_RATIO` | `0.1` | 주문당 투자 비율 |
| `TRADING_MAX_INVEST_AMOUNT` | `100000` | 주문당 최대 금액(KRW) |
| `TRADING_K_VALUE` | `0.5` | 변동성 돌파 계수 |
| `TRADING_TAKE_PROFIT_PCT` | `5.0` | 익절(%) |
| `TRADING_MAX_LOSS_PCT` | `5.0` | 손절(%) |
| `TRADING_TRAILING_STOP_PCT` | `2.0` | 고점 대비 하락 폭(%) |
| `TRADING_TRAILING_ARM_PCT` | `3.0` | 트레일링을 켜는 고점 수익률(%) |
| `TRADING_MAX_HOLD_DAYS` | `1` | 보유 상한(거래일, KST 09:00 경계) |
| `TRADING_ROUND_TRIP_FEE_RATE` | `0.001` | 기록용 수익률에서 빼는 왕복 수수료율 |
| `TRADING_INTERVAL_SECONDS` | `10` | 매매 루프 주기 |
| `TRADING_RECONCILE_HALT_THRESHOLD` | `20` | 미확인 매수 조회가 이 횟수만큼 연속 실패하면 그 종목의 신규 매수를 멈춘다(매도는 계속) |
| `WATCHLIST_TICKERS` | 주요 KRW 13종목 | WebSocket 시세 수집 대상 |
| `MARKETDATA_WATCHDOG_ENABLED` | `true` | WebSocket 무수신 감지·재연결 |
| `KIS_TRADING_ENABLED` / `KIS_TRADING_LIVE_ENABLED` | `false` / `false` | KIS 주식 매매, 실전 계좌 사용. 엔진에는 아직 연결되지 않았다. 켰을 때 KIS 설정 오류는 앱 기동을 막지 않고 ERROR 로그로 알린 뒤 KIS 클라이언트만 만들지 않는다 |
| `KIS_TRADING_INVEST_RATIO`, `KIS_TRADING_INTERVAL_SECONDS`, 청산 키 5개(`KIS_TRADING_TAKE_PROFIT_PCT` 등) | 같은 이름의 `TRADING_*` 기본값 | KIS 프로파일. 허용 구간도 `TRADING_*`와 같다 |
| `KIS_TRADING_MAX_INVEST_AMOUNT` / `KIS_TRADING_MAX_ORDER_AMOUNT` | `1000000` / `2000000` | KIS 1회 투자·주문 금액 상한(KRW) |
| `KIS_TRADING_PAPER_MIN_REQUEST_INTERVAL_MS` / `KIS_TRADING_LIVE_MIN_REQUEST_INTERVAL_MS` | `667` / `60` | 모의·실전 KIS 요청 사이 최소 간격(ms) |
| `KIS_TRADING_COMMISSION_RATE` / `KIS_TRADING_SELL_TAX_RATE` | `0.0002` / `0.002` | KIS 편도 수수료율(매수·매도 양쪽), 매도 증권거래세율. 퍼센트가 아니라 비율이고 허용 구간은 둘 다 `[0, 0.01)`. ETF 거래세 면제는 반영하지 않는다 |
| `DISCORD_WEBHOOK_URL` | 없음 | 거래 알림 |
| `DISCORD_ERROR_ALERT_ENABLED` / `DISCORD_ERROR_WEBHOOK_URL` | `false` / 없음 | 서버 ERROR 로그 알림. 스위치는 배포 preflight 가 소문자 `true`/`false` 만 받는다(오기면 새 컨테이너와 롤백이 함께 기동에 실패한다) |
| `APP_DOMAIN` | 없음 | 운영 CORS origin과 Caddy TLS 도메인 |

## API

요청·응답 JSON 필드는 `snake_case`다. `/api/auth/*`와 `/actuator/health`, `/actuator/info` 외에는 인증이 필요하다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | 첫 계정 생성, 로그인, 로그아웃 |
| GET | `/api/user/me` | 내 정보 |
| POST | `/api/user/keys`, `/api/user/settings` | Upbit 키, 사용자 설정 저장(저장 뒤 등록된 모든 거래소 엔진이 다시 읽는다) |
| POST | `/api/bot/start`, `/api/bot/stop` | 봇 시작·정지 (`exchange`) |
| GET | `/api/bot/status` | 봇 상태, 포지션, 미해소 주문 (`exchange`) |
| POST | `/api/bot/halt/clear` | 종목 halt 해제 (`exchange`) |
| POST | `/api/bot/pending/clear` | 미해소 주문 기록 삭제(봇 정지 상태에서만) (`exchange`) |
| GET | `/api/account`, `/api/portfolio` | 잔고, 포트폴리오 (`exchange`, `UPBIT` 만) |
| GET | `/api/trades`, `/api/trades/roundtrips` | 한 거래소의 거래 이력, 라운드트립(`total` 도 그 거래소 기준) (`exchange`) |
| GET | `/api/kis/keys` | KIS 모의(`KIS_PAPER`)·실전(`KIS`) 키 저장 여부, 마스킹한 계좌(`****5678-01`), 갱신 시각(`updated_at`, UTC ISO-8601) |
| PUT, DELETE | `/api/kis/keys/{KIS_PAPER\|KIS}` | KIS 키 저장(`app_key`, `app_secret`, `account_number`)·삭제 |

표에 `exchange` 가 붙은 API 는 쿼리 파라미터 `exchange`(`UPBIT`·`KIS_PAPER`·`KIS`, 철자 그대로)로 거래소를 고른다. 없거나 빈 값이면 `UPBIT` 다. 모르는 값은 400 이다. 제어 API(`/api/bot/*`)는 그 거래소의 매니저가 등록돼 있어야 하고 아니면 400 이다 — 지금은 `UPBIT` 만 등록돼 있다. KIS 티커는 `KRX-` 와 종목코드 6~7자(영문 대문자·숫자)다. 앞뒤 공백은 빼고 소문자는 대문자로 바꿔 받는다(`krx-005930` → `KRX-005930`). `/api/account`·`/api/portfolio` 는 `UPBIT` 가 아니면 400 이다. 거래 이력은 등록과 상관없이 그 거래소의 기록을 돌려준다.

`/api/kis/keys`는 `KIS_TRADING_ENABLED=true`일 때만 있다(꺼져 있으면 404). 세 값은 `APP_ENCRYPTION_SECRET`으로 각각 암호화해 `user_broker_keys`에 두고, 응답에는 앱키·앱시크릿을 어떤 형태로도 내지 않는다. 앱키는 128자, 앱시크릿은 512자 이하의 공백 없는 ASCII, 계좌번호는 `12345678-01` 또는 `1234567801`이다. 형식이 틀리거나 경로가 `KIS_PAPER`·`KIS`가 아니면 400, `KIS_TRADING_LIVE_ENABLED=false`에서 실전(`KIS`) 키를 저장하면 409, DB 가 10초 안에 답하지 않거나 DB 오류면 503 이다(저장 요청이었다면 반영됐는지 상태 조회로 확인한다). 삭제는 실전 키도 받는다. 저장·삭제 뒤 그 환경의 매니저가 등록돼 있으면 도는 엔진이 키를 다시 읽는다. 지금은 KIS 매니저가 없어 저장·삭제만 한다. 다시 읽기에 실패하면 503 이고 저장·삭제는 이미 반영된 상태다. `APP_ENCRYPTION_SECRET`을 바꾸면 저장된 KIS 키를 복호화하지 못하므로 키를 다시 저장한다. 그때 상태 조회는 `configured: true`에 `account: null`로 보인다.

실행 중인 봇에 다른 종목 목록으로 `/api/bot/start`를 보내면 409다. 목록을 바꾸려면 정지 후 다시 시작한다. 목록에서 뺀 종목도 이미 보유한 포지션은 청산될 때까지 관리한다.

### 미해소 주문 정리

응답을 못 받았는데 잔고가 달라진 주문이나 오래 막힌 매도는 자동으로 풀리지 않고, 그 종목의 매매를 멈춘다. `GET /api/bot/status`의 `positions[].pending_buy`·`pending_sell`로 보인다.

1. Upbit 주문 내역을 확인하고, 미체결이면 Upbit에서 취소한다.
2. 봇을 정지한 뒤 `POST /api/bot/pending/clear {"ticker": "KRW-BTC", "side": "buy"|"sell"}`로 기록을 지운다. 봇이 돌거나 복원 중이면 409, 메모리에만 남은 매도 기록이 있으면 503이니 잠시 후 다시 시도한다.
3. 체결된 매도를 지우면 SELL 기록이 남지 않는다. 응답의 `cleared`(지운 필드 전부, 키는 `trading_states` 컬럼명)를 보고 거래 기록을 맞춘다.
4. 다시 시작하면 기동 시 잔고 동기화가 매수로 들어온 코인을 보유로 편입한다. 보유일은 해제일부터, 청산 파라미터는 현재 설정, 트레일링 고점은 시작 뒤 첫 가격부터 다시 잡는다.
5. 그 종목이 halt 상태였다면 시작한 뒤 `POST /api/bot/halt/clear`로 푼다. 주문 확인이 필요해 멈춘 종목(review)은 그 종목의 매수·매도 pending 이 모두 비기 전까지 halt/clear 가 409 다. pending 은 자동 확정되거나 2번의 pending/clear 로 비운다.

## 리스크 관리

- 익절은 트레일링 폭과 활성 수익률보다 커야 한다. 작으면 익절이 항상 먼저 걸려 트레일링이 동작하지 않는다.
- 매도 기록의 `pnl_percent`는 수수료를 뺀 값이고, 청산 판정은 수수료 전 수익률로 한다.
- 청산 파라미터의 허용 구간은 `ExitParamRanges.kt`에 있다. 손절을 `-5`처럼 부호를 틀려 적는 실수를 거르기 위한 것이다.
- `deploy/vultr/preflight_exit_params.sh`가 배포 전에 형식과 구간을 검사한다. `TRADING_AUTO_START`가 켜져 있으면 청산 키 5개(`TAKE_PROFIT_PCT`, `MAX_LOSS_PCT`, `TRAILING_STOP_PCT`, `TRAILING_ARM_PCT`, `MAX_HOLD_DAYS`)를 `.env`에 명시하지 않은 배포도 막는다. `KIS_TRADING_*` 청산 키는 같은 구간으로, 비용 요율 키 2개는 `[0, 0.01)`로 검사하지만 선언은 요구하지 않는다. 앱은 KIS 값을 기동 로그에 남기지 않고, `KIS_TRADING_ENABLED=true` 일 때만 구간 위반을 ERROR 로 알린다.
- 앱은 기동 시 실효값을 로그로 남기고, 미선언이거나 구간 밖이면 ERROR로 알린다. 보유 포지션의 손절이 멈추지 않도록 거래는 막지 않는다.

## 배포

`.github/workflows/deploy.yml`

- PR: 테스트만 실행
- main push: 테스트 → multi-arch 이미지 빌드 → GHCR push. 배포하지 않는다. 배포가 곧 엔진 재시작이라 머지할 때마다 재시작하지 않으려는 것이다. `**.md`와 `docs/**`만 바뀐 push는 테스트·빌드도 건너뛴다.
- main에서의 수동 실행(workflow_dispatch): 테스트 → 이미지 빌드 → GHCR push → 운영 변경 판정 → SSH로 Vultr에 `deploy.sh deploy`. 실행한 커밋을 그대로 배포한다. 다른 브랜치에서 수동 실행하면 이미지만 올리고(이미 있으면 건너뜀) 배포하지 않는다.
- 운영 변경 판정(`deploy/vultr/deploy_needed.sh`): 서버의 마지막 정상 배포(`/opt/app/.last-good-sha`)의 컨테이너가 healthy 로 돌고 있고, 그 뒤 제외 목록(문서·테스트·`scripts/`·`.gitignore`·로컬 개발 파일) 밖 파일이 바뀌지 않았으면 배포하지 않고 알림(notice)만 남긴다. 지금 컨테이너가 마지막 정상 배포가 아니거나 healthy 가 아니면(실패한 배포가 남긴 컨테이너, 수동 롤백, 정지) 배포한다. 실행한 커밋이 마지막 정상 배포보다 옛 커밋이면 실패로 멈춘다(수동 실행 두 개가 순서가 뒤바뀌어 끝난 경우).
- 수동 실행은 그 커밋의 이미지가 GHCR 에 이미 있으면 다시 빌드하지 않는다(force 여도). 베이스 이미지 태그가 떠 있어 다시 빌드하면 같은 태그의 내용이 바뀌고, 재기동·롤백이 검증하지 않은 이미지를 받기 때문이다. 베이스 이미지 보안 패치는 `Dockerfile` 을 고친 새 커밋으로 받는다 — 새 태그라 헬스체크가 실패하면 직전 정상 SHA 로 자동 롤백한다.
- `force`: 판정 없이 배포한다(이미지는 위 규칙대로). secret 만 바꿨을 때처럼 파일 밖 변경을 반영할 때 쓴다. 이미지와 설정이 그대로면 force 여도 컨테이너를 다시 만들지 않는다(compose 는 바뀐 것이 없으면 그대로 둔다). 단순 재시작은 서버에서 `docker compose restart app` 이다. 수동 롤백(`APP_VERSION=… up -d`) 뒤에는 force 가 필요 없다 — 지금 컨테이너가 마지막 정상 배포가 아니라 판정이 배포한다. 마지막 정상 배포와 같은 커밋을 force 로 다시 배포하면 헬스체크가 실패해도 자동 롤백하지 않는다(롤백 대상이 배포 대상과 같다).
- 이 방식을 되돌릴 때는 커밋을 revert 하지 말고 고쳐서 나간다. revert 하면 push 배포가 되살아나 그 revert push 가 곧바로 main 최신을 배포한다.
- 헬스체크(`/actuator/health`)는 웹·DB 와 함께 실행 중이던 봇의 복원이 끝났는지 본다. 복원 중이면 503(OUT_OF_SERVICE), 재시도를 다 쓰고 복원하지 못한 봇이 있으면 503(DOWN)이다. 그 봇을 손으로 시작하거나 멈추면 다시 200 이 된다. bot state 조회 자체가 끝까지 실패했으면 재시작 전까지 503 이고, `TRADING_AUTO_START=false` 면 복원하지 않으므로 언제나 200 이다.
- 배포 결과(성공·자동 롤백·실패)를 Discord 로 알린다. 성공은 `DISCORD_WEBHOOK_URL`, 롤백·실패는 `DISCORD_ERROR_WEBHOOK_URL`(없으면 일반 webhook)로 간다. 원격 배포 전에 멈춘 실패(설정 검사·preflight·업로드, Actions 의 준비 스텝)는 알리지 않는다 — 운영 컨테이너를 건드리기 전이고 Actions 실패로 보인다.
- 배포 후 180초 안에 헬스체크를 통과하지 못하면 마지막 정상 SHA로 롤백한다. Actions 배포는 서버의 `/opt/app/.last-good-sha`를, 수동 배포는 `deploy/vultr/.state`의 `LAST_GOOD_SHA`를 쓰고, 그 값이 없거나, 배포 대상과 같거나, 그 커밋이 저장소에 없으면 롤백하지 않는다. 새 DB 마이그레이션이 들어간 배포는 구버전이 새 스키마에서 돌 수 있어 자동 롤백하지 않는다. 롤백 대상 SHA 에 V30(거래소 축) 마이그레이션이 없어도 자동 롤백하지 않는다.

필요한 secret은 `VULTR_DEPLOY_ENV`(운영 `.env` 전체), `VULTR_PUBLIC_IP`, `VULTR_SSH_PRIVATE_KEY`, `VULTR_SSH_USER`다.

Actions 배포는 `VULTR_DEPLOY_ENV`를 그대로 `deploy/vultr/.env`로 쓰고 서버 `.env`를 다시 만든다. 로컬 `deploy/vultr/.env`만 고치고 로컬에서 배포하면 다음 Actions 배포 때 secret 값으로 되돌아가므로 secret도 함께 고친다. secret만 바꾼 뒤에는 파일 변경이 없어 판정이 배포를 건너뛰므로 `force`로 실행한다. 배포 계층은 `TRADING_*`·`KIS_TRADING_*`·`WATCHLIST_TICKERS`·`MARKETDATA_WATCHDOG_*` 기본값을 갖지 않아, `.env`에 적은 키만 컨테이너로 넘기고 나머지는 앱 기본값을 쓴다. 앱 기본값에 맡길 키는 secret에서도 지운다. 단 청산 키 5개는 자동매매 배포의 preflight가 요구하므로 지우지 않는다. 넘긴 키 이름은 배포 로그의 `서버 .env override 키:` 줄에서 확인한다.

수동 배포와 운영 명령:

```bash
install -m 600 deploy/vultr/.env.example deploy/vultr/.env
./deploy/vultr/deploy.sh setup     # SSH 키, 방화벽, 인스턴스 생성
./deploy/vultr/deploy.sh deploy
./deploy/vultr/deploy.sh status    # logs, mem, ssh, stop, start, destroy
```

`APP_ENCRYPTION_SECRET`은 자동 생성하지 않는다. 운영 중인 값을 그대로 쓰고 DB 백업과 따로 보관한다. 인스턴스 재구축, 백업 복원, 방화벽 설정, 호스트 이전(같은 계정으로 봇 두 대가 돌면 주문이 이중으로 나간다)은 [`deploy/vultr/README.md`](deploy/vultr/README.md)를 본다.

### 스키마를 바꾸는 배포 (V30 같은)

실거래 테이블의 제약을 바꾸는 마이그레이션은 단독 릴리스로 거래가 적은 시간에 낸다.

1. 운영 DB 에서 읽기 전용 사전 점검. 첫 쿼리는 0, 둘째 쿼리는 0행이어야 한다(V30 의 CHECK·UNIQUE 가 기존 행에 걸린다). 셋째 쿼리에는 `uq_trading_states_user_ticker` 가 있고 V30 의 제약 이름(`uq_trading_states_user_exchange_ticker`, `ck_trading_states_exchange_ticker`)은 없어야 하며, 넷째 쿼리는 29 여야 한다.
   ```sql
   SELECT count(*) FROM trading_states WHERE ticker LIKE 'KRX-%';
   SELECT user_id, ticker, count(*) FROM trading_states GROUP BY 1,2 HAVING count(*) > 1;
   SELECT conname FROM pg_constraint WHERE conrelid = 'trading_states'::regclass AND contype IN ('u', 'c');
   SELECT max(version::int) FROM flyway_schema_history WHERE success;
   ```
   첫 쿼리가 0 이 아니면 Upbit 에 없는 `KRX-` 마켓을 시작 목록에 넣었던 흔적이다. 봇을 멈추고 그 `trading_states` 행과 `bot_state.tickers` 의 해당 티커를 지운 뒤 배포한다.
2. 서버 `/opt/app` 에서 `./backup.sh` 를 손으로 한 번 돌려 백업이 올라갔는지 확인한다. 백업 cron 을 등록해 두었다면(`deploy/vultr/README.md` 6절, KST 03:00) 배포 시각이 그와 겹치지 않게 한다. `pg_dump` 가 잡은 잠금을 V30 의 `ALTER TABLE` 이 기다리는 동안 헬스체크 시간이 지나간다.
3. 되돌리기 SQL(`deploy/vultr/sql/V30_down.sql`)을 미리 읽어 둔다. Flyway 는 이 파일을 쓰지 않는다.
4. 기동이 실패하면 자동 롤백되지 않는다. `docker compose logs app` 로 원인을 보고 V30 이 적용됐는지 확인한다.
   ```bash
   docker compose exec -T postgres psql -U trading -d trading -c "SELECT version, success FROM flyway_schema_history WHERE version = '30'"
   ```
   - 0행이면 마이그레이션이 실패해 Postgres 트랜잭션 DDL 로 V30 전체가 되돌려진 것이다. 봇이 멈춰 있는 동안 보유 포지션의 손절도 돌지 않으므로 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 먼저 되살리고, 원인을 고친 이미지로 다시 배포한다.
   - V30 이 적용돼 있으면 고친 이미지로 앞으로 배포한다.

V30 이후에는 V30 이전 이미지로 롤백하지 않는다. 옛 코드는 거래소 축을 몰라 KIS 행까지 Upbit 상태로 읽는다. 불가피하면 앱을 멈추고 `./backup.sh` 를 돌린 뒤 아래로 KIS 행을 먼저 지우고, `V30_down.sql` 을 실행한 다음 옛 이미지를 올린다. `bot_state` 는 V17 부터 거래소 축이 있어 옛 이미지도 거래소로 걸러 읽으므로 지우지 않는다. `trade_executions` 의 KIS 체결 감사 행은 지우기 전에 복사해 둔다.
```sql
BEGIN;
CREATE TABLE v30_rollback_archive_trade_executions AS SELECT * FROM trade_executions WHERE exchange <> 'UPBIT';
DELETE FROM trade_executions WHERE exchange <> 'UPBIT';
DELETE FROM trade_records WHERE exchange <> 'UPBIT';
DELETE FROM trading_states WHERE exchange <> 'UPBIT';
COMMIT;
```

### V31 (KIS 키 테이블)

V31 은 `user_broker_keys` 테이블만 새로 만들고 기존 테이블은 건드리지 않는다. 기존 행에 걸리는 제약이 없어 사전 점검 쿼리는 없다. 배포 전 `./backup.sh` 는 다른 스키마 배포와 같이 돌리고, 실패하면(버킷 접근에 실패하면 덤프를 남기지 않는다) 같은 `pg_dump` 를 서버 디스크에 먼저 남긴 뒤 배포한다. main 머지는 배포하지 않으므로 백업은 배포(main 수동 실행) 직전에 한다.

- V30 이미지로의 롤백은 그대로 된다. Flyway 기본값(`ignoreMigrationPatterns` `*:future`)으로 V30 이미지는 적용된 V31 을 무시하고 뜨며, 앱 설정에 이 값을 바꾸는 키가 없다. 그래서 `deploy.sh` 의 자동 롤백 하한은 V30 그대로다.
- 다만 V31 을 처음 담은 배포는 migration 변경이 들어 있어 `deploy.sh` 가 자동 롤백하지 않는다. 기동에 실패하면 V31 적용 여부와 관계없이 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 수동 롤백해도 되고, `V31_down.sql` 은 필요 없다.
- V30 이전으로 내려가야 하면 `V31_down.sql` 을 먼저 돌리고 위 V30 절차를 따른다. `V30_down.sql` 은 V30 뒤에 적용된 마이그레이션이 있으면 멈춘다.
- 테이블 자체를 없애야 할 때만 `deploy/vultr/sql/V31_down.sql` 을 쓴다. 저장된 KIS 앱키·앱시크릿·계좌번호가 모두 사라져 사용자가 다시 넣어야 한다. V31 이 마지막 적용 버전일 때만 돌고, 아니면 아무것도 바꾸지 않고 멈춘다. 테이블과 `flyway_schema_history` 의 V31 행을 한 트랜잭션으로 지우므로, 이후 V31 이 든 이미지를 올리면 테이블을 다시 만든다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V31_down.sql
  APP_VERSION=<V31 이전 SHA> docker compose up -d --pull missing
  ```
  `/tmp/V31_down.sql` 은 저장소의 파일을 서버로 올린 것이다. `backup.sh` 가 실패하면 down 은 돌지 않는다. S3 백업을 쓸 수 없으면 `docker compose exec -T postgres pg_dump -U trading -d trading --no-owner | gzip` 으로 덤프를 먼저 남기고 `&&` 뒤 명령만 실행한다.
- `KIS_TRADING_ENABLED=false` 이면 `/api/kis/keys` 가 없어 사용자가 저장한 키를 API 로 지울 수 없다. 그때 한 사용자의 키를 지우려면 `/opt/app` 에서 아래를 실행한다. 한 환경만 지우면 `<환경>` 에 `KIS_PAPER`(모의) 또는 `KIS`(실전)를 넣고, 두 환경을 모두 지우면 `AND exchange = ...` 를 뺀다. 백업 조건은 위 down 과 같다.
  ```bash
  ./backup.sh && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading \
    -c "DELETE FROM user_broker_keys WHERE user_id = <사용자 id> AND exchange = '<환경>'"
  ```
