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

일봉은 기동 때 업비트 일봉 200개로 채우고(seed, 실패한 마켓은 1분부터 최대 30분 간격으로 다시), 이후에는 1분봉을 모아 만든다. 날짜가 바뀌고(09:00 KST) 2분이 지나면 마켓마다 업비트 일봉으로 전일 봉을 대조한다. 같으면 끝이고, 다르거나 store 에 없으면 다음 라운드에 다시 받아 같은 값일 때만 업비트 값으로 바꾼다 — 가격이 달랐으면 ERROR(Discord) 한 줄, 거래량만 달랐으면 INFO. 처음 시도하고 30분이 지나도 대조를 끝내지 못한 마켓이 있으면 그날 ERROR 한 번. 대조가 끝나기 전 몇 분 동안의 매수 판정은 모은 값을 쓴다. 분봉 수집이 멈추면 대조도 함께 멈춘다.

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

### 포맷

Kotlin 포맷 기준은 ktlint(Spotless)이고, 규칙은 루트 `.editorconfig`에 있다. VSCode의 JetBrains Kotlin 확장도 같은 `.editorconfig`를 읽어, 저장 시 포맷(`editor.formatOnSave`)이 ktlint와 같은 결과를 내도록 맞춰 두었다(지금 코드 전체에서 확인). 처음 보는 코드 모양에서 드물게 다르면 CI의 `spotlessCheck`가 알려 주고, `./gradlew spotlessApply`로 맞춘다.

```bash
./gradlew spotlessApply    # 포맷
./gradlew spotlessCheck    # 검사만 (CI가 돌린다)
```

`.editorconfig`를 바꾼 뒤에는 `./gradlew --stop`으로 Gradle daemon을 내리고 `build/spotless-clean`을 지운 다음 돌린다. 그러지 않으면 바꾸기 전 설정으로 포맷한다.

Claude Code 세션에서는 `.claude/settings.json`의 PostToolUse 훅(`scripts/claude-format-hook.sh`)이 Edit·Write 한 이 repo의 `.kt`·`.kts` 파일 하나를 바로 포맷한다(Gradle daemon이 떠 있으면 약 2초). 자동으로 못 고치는 lint 오류는 Claude에게 알리고, Gradle 실패는 편집을 막지 않는다. Bash로 고친 파일과 저장소 여러 개를 쓰는 웹 세션·project thread 에는 훅이 돌지 않으므로 CI의 `spotlessCheck`가 막는다.

## 설정

기본값은 `TradingProperties.kt`, `KisTradingProperties.kt`, `AppConfig.kt`, `application.yml`에 있다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5433` / `trading` / `trading` / `trading` | prod 프로필의 기본 포트는 5432 |
| `JWT_SECRET` | 없음 | 바꾸면 기존 세션이 끊긴다 |
| `APP_ENCRYPTION_SECRET` | 없음 | 바꾸면 저장된 API 키를 복호화할 수 없다 |
| `TRADING_TICKERS` | `KRW-BTC` | 거래 종목, 쉼표 구분한 대문자 Upbit 마켓 코드(예: `KRW-BTC`). 형식이 틀리면 배포 preflight 가 막는다. 앱은 목록을 바꾸지 않고 형식이 틀린 항목이나 빈 목록을 기동 뒤 ERROR 로 알린다(복원되는 봇은 저장된 목록을 쓴다) |
| `TRADING_AUTO_START` | `false` | 기동 시 봇 자동 시작 |
| `TRADING_INVEST_RATIO` | `0.1` | 주문당 투자 비율 |
| `TRADING_MAX_INVEST_AMOUNT` | `100000` | 주문당 최대 금액(KRW). 허용 `[5000, ∞)` — Upbit 최소 주문 미만이면 모든 매수가 건너뛰어진다 |
| `TRADING_K_VALUE` | `0.5` | 변동성 돌파 계수. 허용 `[0, 2]` |
| `TRADING_TAKE_PROFIT_PCT` | `5.0` | 익절(%) |
| `TRADING_MAX_LOSS_PCT` | `5.0` | 손절(%) |
| `TRADING_TRAILING_STOP_PCT` | `2.0` | 고점 대비 하락 폭(%) |
| `TRADING_TRAILING_ARM_PCT` | `3.0` | 트레일링을 켜는 고점 수익률(%) |
| `TRADING_MAX_HOLD_DAYS` | `1` | 보유 상한(거래일, KST 09:00 경계) |
| `TRADING_ROUND_TRIP_FEE_RATE` | `0.001` | 기록용 수익률에서 빼는 왕복 수수료율 |
| `TRADING_INTERVAL_SECONDS` | `10` | 공용 시계 주기(초). 허용 `[1, 60]` — 바퀴마다 작업자가 체결 확인·일일 리셋·보유일 청산을 하고, 체결이 30초 넘게 없는 종목은 이 주기로 손절·익절·트레일링·매수를 평가한다 |
| `TRADING_RECONCILE_ALERT_THRESHOLD` | `20` | 미해소 주문(매수·매도)이 이 값 × 매매 주기(초) 넘게 확정되지 않으면 ERROR 로 한 번 알린다(주문 시각·알림 여부는 저장돼 재시작해도 같은 주문을 다시 알리지 않는다). 그동안 미해소 주문이 그 종목의 매매를 막는다. 허용 `[1, 100]` |
| `WATCHLIST_TICKERS` | 주요 KRW 13종목 | WebSocket 시세 수집 대상. 형식은 `TRADING_TICKERS`와 같고 배포 preflight 가 막는다. 앱은 형식이 틀린 항목을 구독에서 빼고 기동 뒤 ERROR 로 알린다 |
| `MARKETDATA_WATCHDOG_ENABLED` | `true` | WebSocket 무수신 감지·재연결 |
| `TRADING_PRICE_EVENTS_ENABLED` | `true` | 시세 수집이 업비트 체결(`trade`)도 같은 연결로 받고, 종목 작업자가 체결마다 청산·매수를 판단한다(아래 매매 엔진 절). 끄면 ticker 만 받고 엔진도 체결을 받지 않아 바퀴만 판단한다 — 문제가 생겼을 때 env 로 끄는 비상용(재시작만으로 되돌림) |
| `KIS_TRADING_ENABLED` / `KIS_TRADING_LIVE_ENABLED` | `false` / `false` | KIS 주식 매매, 실전 계좌 사용. 엔진에는 아직 연결되지 않았다. 켰을 때 KIS 설정 오류는 앱 기동을 막지 않고 ERROR 로그로 알린 뒤 KIS 클라이언트만 만들지 않는다 |
| `KIS_TRADING_INVEST_RATIO`, `KIS_TRADING_INTERVAL_SECONDS`, 청산 키 5개(`KIS_TRADING_TAKE_PROFIT_PCT` 등) | 같은 이름의 `TRADING_*` 기본값 | KIS 프로파일. 허용 구간도 `TRADING_*`와 같다 |
| `KIS_TRADING_MAX_INVEST_AMOUNT` / `KIS_TRADING_MAX_ORDER_AMOUNT` | `1000000` / `2000000` | KIS 1회 투자·주문 금액 상한(KRW) |
| `KIS_TRADING_PAPER_MIN_REQUEST_INTERVAL_MS` / `KIS_TRADING_LIVE_MIN_REQUEST_INTERVAL_MS` | `667` / `60` | 모의·실전 KIS 요청 사이 최소 간격(ms) |
| `KIS_TRADING_COMMISSION_RATE` / `KIS_TRADING_SELL_TAX_RATE` | `0.0002` / `0.002` | KIS 편도 수수료율(매수·매도 양쪽), 매도 증권거래세율. 퍼센트가 아니라 비율이고 허용 구간은 둘 다 `[0, 0.01)`. ETF 거래세 면제는 반영하지 않는다 |
| `DISCORD_WEBHOOK_URL` | 없음 | 거래(체결) 알림 전용 |
| `DISCORD_DEPLOY_WEBHOOK_URL` | 없음 | 배포 결과 알림(배포 스크립트만 읽는다 — 앱 컨테이너에는 넘기지 않는다) |
| `DISCORD_ERROR_ALERT_ENABLED` / `DISCORD_ERROR_WEBHOOK_URL` | `false` / 없음 | 서버 ERROR 로그 알림(`error_alert_outbox` 를 거쳐 보낸다 — 아래 ERROR 알림 절). 스위치는 배포 preflight 가 소문자 `true`/`false` 만 받는다(오기면 새 컨테이너와 롤백이 함께 기동에 실패한다) |
| `APP_DOMAIN` | 없음 | 운영 CORS origin과 Caddy TLS 도메인 |

## API

요청·응답 JSON 필드는 `snake_case`다. `/api/auth/*`와 `/actuator/health`, `/actuator/info` 외에는 인증이 필요하다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | 첫 계정 생성, 로그인, 로그아웃 |
| GET | `/api/user/me` | 내 정보 |
| POST | `/api/user/keys`, `/api/user/settings` | Upbit 키, 사용자 설정 저장(저장 뒤 등록된 모든 거래소 엔진이 다시 읽는다) |
| POST | `/api/bot/start`, `/api/bot/stop` | 봇 시작·정지 (`exchange`) |
| GET | `/api/bot/status` | 봇 상태, 엔진 생존(`loop`), 신규 매수 중지(`entries_paused`), 포지션(상태 저장 충돌 `version_conflict`, 확인 조회 연속 실패 `lookup_failures`·다음 조회 시각 `next_lookup_at`, 미해소 주문 시각 `pending_buy.since`·`pending_sell.since` 포함), 미해소 주문 (`exchange`) |
| POST | `/api/bot/entries` | 신규 매수 중지·재개 `{"paused": true\|false}` — 매도는 그대로 (`exchange`) |
| GET | `/api/account`, `/api/portfolio` | 잔고, 포트폴리오 (`exchange`, `UPBIT` 만) |
| GET | `/api/trades`, `/api/trades/roundtrips` | 한 거래소의 거래 이력, 라운드트립(`total` 도 그 거래소 기준) (`exchange`) |
| GET | `/api/kis/keys` | KIS 모의(`KIS_PAPER`)·실전(`KIS`) 키 저장 여부, 마스킹한 계좌(`****5678-01`), 갱신 시각(`updated_at`, UTC ISO-8601) |
| PUT, DELETE | `/api/kis/keys/{KIS_PAPER\|KIS}` | KIS 키 저장(`app_key`, `app_secret`, `account_number`)·삭제 |

표에 `exchange` 가 붙은 API 는 쿼리 파라미터 `exchange`(`UPBIT`·`KIS_PAPER`·`KIS`, 철자 그대로)로 거래소를 고른다. 없거나 빈 값이면 `UPBIT` 다. 모르는 값은 400 이다. 제어 API(`/api/bot/*`)는 그 거래소의 매니저가 등록돼 있어야 하고 아니면 400 이다 — 지금은 `UPBIT` 만 등록돼 있다. KIS 티커는 `KRX-` 와 종목코드 6~7자(영문 대문자·숫자)다. 앞뒤 공백은 빼고 소문자는 대문자로 바꿔 받는다(`krx-005930` → `KRX-005930`). `/api/account`·`/api/portfolio` 는 `UPBIT` 가 아니면 400 이다. 거래 이력은 등록과 상관없이 그 거래소의 기록을 돌려준다.

`/api/kis/keys`는 `KIS_TRADING_ENABLED=true`일 때만 있다(꺼져 있으면 404). 세 값은 `APP_ENCRYPTION_SECRET`으로 각각 암호화해 `user_broker_keys`에 두고, 응답에는 앱키·앱시크릿을 어떤 형태로도 내지 않는다. 앱키는 128자, 앱시크릿은 512자 이하의 공백 없는 ASCII, 계좌번호는 `12345678-01` 또는 `1234567801`이다. 형식이 틀리거나 경로가 `KIS_PAPER`·`KIS`가 아니면 400, `KIS_TRADING_LIVE_ENABLED=false`에서 실전(`KIS`) 키를 저장하면 409, DB 가 10초 안에 답하지 않거나 DB 오류면 503 이다(저장 요청이었다면 반영됐는지 상태 조회로 확인한다). 삭제는 실전 키도 받는다. 저장·삭제 뒤 그 환경의 매니저가 등록돼 있으면 도는 엔진이 키를 다시 읽는다. 지금은 KIS 매니저가 없어 저장·삭제만 한다. 다시 읽기에 실패하면 503 이고 저장·삭제는 이미 반영된 상태다. `APP_ENCRYPTION_SECRET`을 바꾸면 저장된 KIS 키를 복호화하지 못하므로 키를 다시 저장한다. 그때 상태 조회는 `configured: true`에 `account: null`로 보인다.

`running` 은 시작·정지 상태이고, 엔진이 실제로 도는지는 `loop` 로 본다 — `alive`(공용 시계와 종목 작업자가 정지 없이 끝나지 않았는지 — 봇이 정지돼 있으면 false)와 `last_progress_at`(시계와 활성 작업자 중 가장 오래 진행하지 않은 쪽의 시각, UTC, 정지돼 있으면 null — 매수 창구 앞에서 기다리는 종목은 뺀다). 한 바퀴가 오류로 끝나는 종목도 진행으로 보이지만 그때는 그 바퀴마다 오류 ERROR 가 따로 나간다. 시계나 작업자가 정지 없이 끝나면 바로 어느 쪽인지 붙여 `[ENGINE DEAD]`, 5분 넘게 진행이 없으면 `[ENGINE STALLED]` ERROR(Discord)가 한 번 나간다(회복하면 INFO). 둘 다 그 사용자의 손절·청산 평가가 멈췄다는 뜻이라 봇을 정지한 뒤 다시 시작하고, 정지가 끝나지 않으면 컨테이너를 재시작한다. 목록 밖 종목을 더 관리할 것이 없어 정리할 때 그 작업자가 끝나는 것은 정상이다. 설정 저장도 엔진을 새로 띄운다.

### 매매 엔진

사용자마다 엔진 하나가 돈다. 종목마다 작업자 하나가 그 종목의 상태(보유·미해소 주문·고점·일일 리셋)를 혼자 맡고, 공용 시계가 `TRADING_INTERVAL_SECONDS` 마다 모든 작업자에게 바퀴를 보낸다. 바퀴에는 거래일과, 시세가 낡은 종목들의 REST 가격을 한 번에 묶어 받은 결과가 실린다(묶음이 실패한 바퀴는 따로 다시 묻지 않는다 — IP 차단 방지). 작업자는 바퀴를 차례로 처리하고, 처리하는 동안 온 바퀴는 가장 새 것 하나로 합친다.

- 종목끼리 기다리지 않는다 — 한 종목의 주문이 오래 걸려도(장애 때 30초+) 다른 종목은 그 바퀴에 손절을 평가하고 판다.
- 매수만 엔진에서 한 번에 하나 나간다(원화 잔고를 나눠 쓰므로 잔고 조회부터 체결 확인까지).
- 업비트 private 요청은 계좌마다 어떤 1초 창에서도 주문 10·그 밖 25 를 넘지 않게 기다렸다 보낸다(공식 한도 12·30). 엔진·포트폴리오 화면·잔고 API·09:05 대조가 같은 계좌의 한도를 함께 쓴다. 공개 시세 API 는 따로 센다.
- 바깥(상태 API·관리자)은 종목 상태를 읽기만 한다. 정지는 모든 작업자가 끝날 때까지(주문 중이면 그 주문이 끝날 때까지) 기다린다.

시세 수집은 ticker 와 함께 업비트 체결(`trade`)을 같은 WebSocket 연결·같은 구독 요청으로 받는다. ticker 는 체결을 건너뛰어(실측: BTC 체결 100건 중 14건이 ticker 에 안 보였다) 체결마다 판단하려면 `trade` 가 필요하다. 받은 체결은 체결 번호(`sequential_id`)로 중복만 거른 뒤 저장소의 체결 흐름에 싣는다(번호가 연속이 아니라 빠진 체결은 알 수 없다). ticker 는 체결과 다른 길로 받는 즉시 저장소에 넣어, 체결이 밀려도 최신 ticker 를 잃지 않는다(밀린 체결은 오래된 것부터 버린다). 5분마다 INFO `[trade intake]` 한 줄에 마켓별 받은 수·중복·피드에서 밀려 버린 수·읽지 못한 프레임 수가 남는다.

체결로 판단한다(`TRADING_PRICE_EVENTS_ENABLED`, 기본 켬). 엔진마다 수집기 하나가 작업자가 뜬 뒤 저장소의 체결 흐름을 구독해 자기 거래소의 체결을 그 종목 작업자에게 넣는다(정리된 종목의 체결은 버린다). 수집기가 엔진이 도는 중에 끝나면 `[ENGINE DEAD] trade collector` 다.

- 작업자는 체결을 받은 순서대로 모두 처리한다 — 잠깐 손절선 아래로 내려간 체결에서 팔고, 중간 고점으로 트레일링 기준을 올린다. 보유 종목은 그 체결 가격으로 손절·트레일링·익절을 판단하고(그림자 관측도 체결마다 같은 순서), 보유가 없는 종목은 오늘 일봉 종가를 체결 가격으로 바꾼 사본으로 MA·RSI 를 계산해 매수 창구로 산다(저장소 일봉은 1분봉 polling 이라 약 1분 늦다 — 매수 신호가 예전 백테스트와 조금 다르다). 저장소 일봉이 없거나 모자라거나 오늘 봉이 없으면(경계 유예 뒤) 체결로는 사지 않고 WARN 을 1분에 한 번 남기며, 바퀴가 지금처럼 REST 일봉으로 마지막 체결 가격에서 판단한다. 팔지 못한 dust 보유는 같은 체결에서 매수(흡수) 판단으로 넘어간다.
- 체결 경로는 주문 말고는 거래소·DB 를 부르지 않는다. 미해소 주문·unsynced·장외면 바로 끝나고, 새 고점은 메모리에만 올려 다음 바퀴가 저장한다. 체결 시각이 30초보다 오래된 체결은 쓰지 않는다.
- 주문 중에 온 체결은 주문이 끝난 뒤 처리하지만 주문 결과(또는 체결 확인·주문·동기화로 보유나 미해소 주문을 바꾼 바퀴, 시작 바퀴)보다 먼저 받은 체결로는 새 주문을 내지 않고 고점만 올린다. 아무것도 바꾸지 않은 바퀴 뒤에 기다리던 체결은 그대로 판단한다.
- 새 거래일의 리셋을 저장하지 못했으면 그 리셋이 저장될 때까지 체결로는 사지 않는다(재시작하면 어제의 매수 기록이 돌아온다). 청산은 계속한다.
- 체결 시각이 이 호스트 시계보다 5초 넘게 앞서거나, 1분 동안 받은 체결이 모두 30초 넘게 낡았으면 WARN 을 1분에 한 번 남긴다(호스트 시계·피드 지연 확인).
- 체결로 낸 주문이 결과 없이 끝나면(거절·잔고 부족·주문 크기 없음·매도할 잔고 없음 등) 그 종목은 다음 바퀴까지 체결로 다시 주문하지 않는다(체결마다 잔고 조회가 나가지 않게).
- 바퀴는 체결 확인·저장 재시도·고점 저장·일일 리셋·보유일 청산을 한다. 그 작업자가 받은 마지막 체결이 30초 안이면 바퀴는 저장소·REST 가격으로 손절·익절·트레일링·매수를 하지 않는다(가격 순서가 뒤바뀌지 않게). 체결이 30초 넘게 없는 종목(거래 적음·연결 끊김)과 시작 직후 첫 바퀴는 지금처럼 저장소·REST 가격으로 판단한다 — 시작하면 최신 가격으로 한 번 판단하고 그 뒤로는 체결로 판단한다.
- 체결 우편함은 종목마다 2,400개(실측 최대 초당 40건 × 60초)다. 작업자가 밀려 넘치면 오래된 체결부터 버리고 WARN 을 1분에 한 번 버린 수와 함께 남긴다(체결을 처리할 때와 바퀴를 받을 때). 바퀴는 따로 받아 버리지 않는다(둘 다 와 있으면 바퀴가 먼저).
- 정지하면 우편함에 남은 체결로는 주문하지 않는다.

### 신규 매수 중지

급락장·상장폐지 공지·거래소 장애 직후처럼 새로 사지는 말고 보유분은 계속 관리해야 할 때 `POST /api/bot/entries {"paused": true}` 로 신규 매수만 멈춘다. 봇은 계속 돌고 손절·익절·트레일링·보유 기한 매도와 미해소 주문 확정은 그대로 한다. `{"paused": false}` 로 다시 연다.

- 다음 매수부터 적용된다. 이미 주문 단계에 들어간 매수는 끝까지 간다.
- 값은 `bot_state` 에 사용자·거래소별로 저장돼 봇 정지·시작, 설정 저장, 재시작·배포 뒤에도 유지된다. 봇을 한 번도 시작하지 않았어도 켤 수 있고, 그러면 다음 시작부터 적용된다.
- `GET /api/bot/status` 의 `entries_paused`(읽지 못했으면 null)와 `entries_paused_changed_at`(마지막으로 바뀐 시각, UTC)으로 확인한다. 같은 값을 다시 보내면 시각은 바뀌지 않는다.
- `paused` 가 빠졌거나 참·거짓으로 읽을 수 없는 값이면 400 이다. 저장이 실패하면 503 이고 값은 바뀌지 않는다. 저장이 10초 안에 끝나지 않아도 503 인데, 이때는 DB 에 반영됐는지 알 수 없으니 상태를 확인하고 같은 요청을 다시 보낸다(같은 값이면 저장된 값을 그대로 적용한다). 봇 정지·설정 저장이 진행 중이면 그것이 끝날 때까지 기다린다.
- DB 를 SQL 로 직접 고친 값은 재시작 전까지 도는 봇에 반영되지 않는다. API 를 쓴다.
- V32 이전 이미지로 앱을 롤백하면 이 값을 읽지 않아 매수가 다시 나간다. 롤백할 때는 봇을 정지한다.

실행 중인 봇에 다른 종목 목록으로 `/api/bot/start`를 보내면 409다. 목록을 바꾸려면 정지 후 다시 시작한다. 목록에서 뺀 종목도 이미 보유한 포지션은 청산될 때까지 관리한다.

### 미해소 주문 대사

응답을 못 받은 주문은 주문 전에 남긴 identifier 로 접수 여부를 확인해 확정한다. 매도는 실패하면 같은 identifier 로 1초·2초 뒤 최대 2번 다시 보낸다 — 업비트가 같은 identifier 의 두 번째 주문을 거절하므로 주문은 하나다.

- 연결도 서기 전의 실패(연결 거부·연결 시한 초과·호스트 이름 해석 실패)는 항상 다시 보낸다.
- 닿았을 수 있는 일시 장애(응답 시한 초과·연결 끊김·5xx·429)는 잔고 전부를 파는 매도만 다시 보낸다. 소유자가 따로 산 코인이 남는 매도는 다시 보내지 않는다 — 중복 방지가 새면 그 코인까지 팔린다.
- 다시 보낸 요청이 `duplicated_identifier` 로 거절되면 앞 요청이 접수된 것이라 그 주문을 조회해 이어받는다. 닿았을 수 있는 실패 뒤의 다른 거절은 확정으로 읽지 않고 identifier 로 확정한다.
- 인증 실패·해석할 수 없는 응답은 다시 보내지 않는다. 매수는 어떤 실패에도 다시 보내지 않는다.
- 업비트가 응답하지 않으면 매도 한 건이 보통 33초(매번 새 연결이면 60~78초) 걸리고, 그동안 같은 라운드의 뒤 종목 판단(손절 포함)이 그만큼 늦는다.

결과를 모르는 주문(pending)은 사람이 풀지 않는다. 대사(거래소 조회)로만 성공·실패가 되고, 그때까지 그 종목은 매수·매도하지 않는다. `GET /api/bot/status`의 `positions[].pending_buy`·`pending_sell`로 보인다.

1. identifier 로 찾으면 그 주문으로 이어받는다 — 체결량이 있으면 체결로 기록하고, 체결 없이 끝났으면(취소) pending 을 비운다.
2. 404(그런 주문 없음)인데 잔고가 주문 전과 같으면 연속 2번·첫 404 뒤 60초가 지나 미접수로 확정한다. 조회 실패·"아직 안 보임"·잔고 조회 실패가 끼면 다시 센다.
3. 404 인데 잔고가 주문 전과 다르면(매수는 보유가 늘었고, 매도는 free 가 줄었다) 첫 흔적 뒤 30분이 지난 404 에서 업비트 종료 주문 목록(`GET /v1/orders/closed`, done·cancel)을 다시 보고 identifier 를 맞춰 본다. 매도는 주문 시각(1분 앞당김)부터, 매수는 업비트가 허용하는 7일 창 전체를 본다(V38 전에 낸 매수는 주문 시각이 없어 확인한 때로 채워지므로 창에 쓰지 않는다).
   - 목록에 있으면 그 주문을 다시 읽어 1 처럼 이어받는다.
   - 없으면 미접수로 확정하고 `[UNKNOWN ORDER]` ERROR 를 한 번 낸다. 거래 기록은 남기지 않는다. 매수는 그 거래일에 다시 사지 않는다(실제로 체결됐다면 그 코인은 봇 장부 밖에 남는다). 매도는 줄어든 잔고 min(주문 수량, 주문 전 free − 지금 free)만큼 봇 장부를 줄인다 — 실제로 팔렸는데 못 찾은 경우 소유자 코인을 팔지 않는다(소유자가 판 것이었다면 그만큼 봇 코인이 장부 밖에 남는다). 주문 수량이 없는 옛 매도 행은 장부를 그대로 두고 미접수로 푼다(WARN).
   - 30분 타이머는 첫 흔적을 본 때부터 재고, 조회 실패·잔고 조회 실패·흔적이 사라진 조회가 끼어도 끊지 않는다. 메모리에만 있어 재시작하면 다시 센다(늦어지는 쪽). 확정은 잔고를 본 조회에서만 하고, 목록·주문 조회가 실패하면 다음 조회에서 다시 본다.
4. 조회 자체가 계속 실패하는 동안은 pending 이다. 이번 확인에서 거래소 호출(주문 조회·잔고 조회·종료 주문 목록, 체결량을 읽는 잔고 조회 포함)이 하나라도 실패하면 다음 조회까지 간격을 매매 주기 → 2배 → 4배 … 로 늘리고(상한 5분) 그사이 바퀴에서는 그 주문을 묻지 않는다. 체결을 DB 에 기록하지 못한 것은 늘리지 않고 다음 바퀴에 다시 해 본다. 거래소가 답하면(404·"아직 안 보임" 포함) 간격이 원래대로 돌아오고, 재시작하면 바로 다시 본다. 막힌 시간은 조회 간격과 무관하게 주문 시각부터 재므로, 매수·매도 모두 `TRADING_RECONCILE_ALERT_THRESHOLD` × 매매 주기가 지나면 ERROR(매수는 `[RECONCILE]`)를 한 번 낸다 — 2·3 의 흔적 대기 중에도 낸다.

identifier 로 주문을 찾지 못하는 거래소(`UnknownOrderPolicy.supportsClientOrderId=false`)는 결과를 모르는 주문을 확정할 수 없어 엔진을 만들지 않는다(시작 거부).

### 상태 저장 충돌

`trading_states` 는 엔진이 마지막으로 읽거나 저장한 버전 그대로일 때만 저장된다(V37). 그사이 다른 쓰기(실수로 두 번 뜬 컨테이너 등)가 행을 바꿨으면 그 행을 덮지 않는다. 한 프로세스 안에서는 종목마다 작업자 하나만 그 종목의 상태를 쓰므로 평소에는 일어나지 않는다.

- 충돌한 종목은 `[CONFLICT]` ERROR 를 한 번 낸다. 그 뒤로는 그 종목을 더 저장하지 않고, 매수 진입 검사가 그 종목의 신규 매수를 막는다(`GET /api/bot/status` 의 `positions[].version_conflict`). 손절 매도는 기록이 막혀도 나간다.
- 미해소 주문이 걸려 있으면 그 체결을 기록할 수 없어, 그 종목은 다시 시작할 때까지 확정에서 멈춘다(미해소 매수면 손절 판단도 멈춘다).
- 복구: 봇을 정지한 뒤 다시 시작한다. 정지가 DB 에 없을 수 있는 매도를 ERROR 로 알리고, 시작이 DB 의 행을 다시 읽는다. 그 매도는 Upbit 주문 내역과 대조해 거래 기록을 맞춘다. 봇이 도는 동안 충돌한 매도가 남아 있으면 설정 저장(reload)은 실패한다.
- 프로세스가 둘이면 늦은 쪽의 저장은 막히지만 그쪽 주문은 거래소로 나간다 — 이중 주문은 막지 못한다(#328).

### 체결 기록 대조

매일 09:05 KST(업비트 거래일이 바뀌는 09:00 직후)에 한 번 봇 사용자마다 업비트의 최근 48시간 안에 만든 종료 주문(`GET /v1/orders/closed`, 막 만든 1시간은 제외 — 그 1시간은 다음 날 대조에 든다)을 받아, 봇이 낸 주문(identifier `ctb-`) 중 체결됐는데 `trade_executions` 에 없는 것을 사용자별 ERROR `[ORDER RECONCILE]` 한 줄(앞 8건의 시장·매수/매도·체결량·만든 시각·uuid, 나머지 uuid 는 로그 파일의 WARN)로 알린다. 아직 처리 중인 pending 주문은 뺀다. 고치지는 않는다.

- 보정할 때는 그 체결의 거래 기록 행을 `exchange_order_id = <uuid>` 로 넣는다. 그래야 다음 대조에서 빠진다. 같은 주문은 한 프로세스에서 한 번만 알리고, 재시작하면 48시간 안에서 한 번 더 알린다.
- 거짓 경보가 나는 경우: 같은 업비트 키를 다른 환경(로컬 실행 등)이 써서 그쪽 DB 에 기록된 주문, 같은 키를 두 사용자가 등록한 경우.
- 키가 없으면 INFO 로, 만료·잘못된 키·허용 IP 밖(401·403)·복호화 실패면 WARN 으로 그 사용자를 건너뛴다. 키가 있어 시도한 사용자가 모두 실패하면 ERROR 한 줄. 서명 오류(`invalid_query_payload` 등)는 ERROR 다. 키에 "주문조회" 권한이 필요하다.

### 체결 알림

체결 알림은 체결 기록과 같은 트랜잭션에 `trade_notification_outbox` 행으로 남고, 발송기가 보낸 뒤 결과를 적는다. 커밋 직후 앱이 재시작돼도(배포 포함) 기동하면 남은 알림부터 보낸다. 주문번호가 있는 체결은 행이 하나뿐이고(주문번호 없는 복구 기록은 지금도 중복 기록될 수 있다), 보낸 알림은 다시 보내지 않는다.

- 커밋 직후 바로 보내고, 실패한 알림은 5초마다 다시 본다. 다시 보내는 간격은 5초부터 두 배씩 늘어 최대 10분이다.
- 429·5xx·연결 오류는 생성 뒤 24시간까지 다시 보내고, 그런 실패가 나면 그 회차는 거기서 멈춘다(장애 중 몰아 보내지 않음). 없어진 webhook(404)·권한 오류·잘못된 webhook 주소는 바로 실패로 둔다. 실패로 끝나면 ERROR(Discord 오류 채널) 한 줄을 남긴다.
- 사용자 webhook 이 없으면 `DISCORD_WEBHOOK_URL` 로 보낸다. 둘 다 없으면 보내지 않고 `SKIPPED` 로 적는다.
- 보낸 직후 기록하기 전에 앱이 꺼지면 같은 알림이 한 번 더 갈 수 있다. 알림의 `주문` 칸으로 같은 체결인지 구분한다.
- 10분 넘게 늦은 알림에는 잔고 칸을 싣지 않는다(체결 직후 잔고가 아니다).

확인:

```sql
SELECT status, count(*) FROM trade_notification_outbox GROUP BY status;
SELECT id, attempts, next_attempt_at, last_error FROM trade_notification_outbox WHERE status IN ('PENDING', 'FAILED') ORDER BY id DESC LIMIT 20;
```

### ERROR 알림

서버 ERROR 로그 알림(분당 5건, 넘으면 60초 뒤 요약 1건)도 `error_alert_outbox` 에 한 행으로 남은 뒤 보낸다. 로그 쪽은 메모리 큐에 넣기만 하고 발송 루프가 DB 에 넣어 보낸 뒤 결과를 적는다. 발송 규칙(5초 주기·backoff·24시간·실패 시 회차 멈춤·한 번 더 갈 수 있음)은 체결 알림과 같다. 알림의 시각은 ERROR 가 난 시각이다.

- DB 에 넣지 못하거나 5초 안에 끝나지 않으면, 또 발송 루프가 2분 넘게 돌지 않으면 그 알림은 예전처럼 바로 보낸다(DB 장애를 알리는 ERROR 도 간다).
- 종료 때 남은 큐와 보류된 요약은 DB 에 넣고(최대 2초) 다음 기동에 보낸다. 그 안에 넣지 못한 것은 로그 파일에만 남는다.
- `DISCORD_ERROR_ALERT_ENABLED=false` 면 남은 행은 `SKIPPED` 로 둔다. 끝난 행은 7일 뒤 지운다.

```sql
SELECT status, count(*) FROM error_alert_outbox GROUP BY status;
```

### 봇 종목을 앱에서 직접 사고팔 때

봇은 자기 장부 수량까지만 판다. 봇이 사면 이번에 산 만큼(주문 체결량)만 장부에 더하고 평단은 체결가로 잡아(체결 내역이 없으면 주문 전 보유가 없을 때는 계좌 평단, 있으면 그때 가격), 그 전부터 있던 코인은 장부에 들지 않는다. 봇이 보유 중인 종목을 앱에서 더 사도 다음 청산에서 그 코인은 팔지 않고 봇이 산 만큼만 판다(WARN 한 줄). 장부와 잔고의 차이가 최소 주문(5,000원) 미만이면 팔 수 없는 자투리를 남기지 않도록 같이 판다. 체결 뒤 남은 코인과 응답 없는 매도의 확정도 이번 주문 수량 기준이라, 남은 코인을 봇 포지션으로 다시 잡지 않는다.

봇 장부는 `trading_states` 에 저장된다(V34). 재시작·설정 저장(reload)·재동기화는 저장된 장부를 잔고 합(free+locked)만큼 줄이기만 하고 늘리지 않는다 — 앱에서 산 코인은 재시작 뒤에도 장부에 들지 않는다. 우리 것이 아닌 잠금(수동 주문·출금 대기)은 잔고 합에 들어가 장부를 줄이지 않는다. 팔 수 있는 잔고가 없으면 장부로 손절 평가를 계속하되 새로 사지 않는다.

장부를 저장하기 전에 쌓인 행(이 변경 이전)은 첫 동기화에서 한 번 정한다:

- 봇이 산 흔적(진입 전략·매수일·미해소 주문)이 있으면 그 종목 잔고 전체를 봇 장부로 편입한다(WARN 한 줄). 우리 것이 아닌 잠금이 있으면 풀릴 때까지 미룬다. 그때 손으로 산 코인이 섞여 있었으면 나눌 수 없다. 미해소 매도가 있으면 그 주문 수량을 장부로 쓴다.
- 흔적이 없으면 편입하지 않는다(팔 수 있는 잔고가 있으면 WARN 한 줄) — 손으로 산 코인으로 보고 봇이 손절·청산하지 않는다.

그래도 봇이 산 코인 일부를 앱에서 팔면 장부가 그만큼 줄 뿐 기록(SELL)은 남지 않는다. 봇이 다루는 종목은 앱에서 직접 사고팔지 않는 것이 안전하다. V34 이전 버전으로 롤백할 때는 미해소 매도가 없을 때 하고, 롤백한 동안에는 옛 코드가 잔고 전체를 봇 포지션으로 잡는다.

## 리스크 관리

- 익절은 트레일링 폭과 활성 수익률보다 커야 한다. 작으면 익절이 항상 먼저 걸려 트레일링이 동작하지 않는다.
- 엔진을 시작할 때 설정 조합을 알린다. 익절이 트레일링 폭·활성 수익률 이하이거나 왕복 수수료 이하면 WARN 이다. 1회 매수 상한이 최소 주문에 못 미치거나 손절가에서 팔 때 최소 주문 미만이 돼 모든 매수가 건너뛰어지는 조합이면 ERROR(Discord)다.
- 매도 기록의 `pnl_percent`는 수수료를 뺀 값이고, 청산 판정은 수수료 전 수익률로 한다.
- 청산·주문·주기 파라미터의 허용 구간은 `ExitParamRanges.kt`에 있다. 손절을 `-5`처럼 부호를 틀리거나, 주기 `10`초를 ms 로 `10000`처럼 단위·자릿수를 틀려 적는 실수를 거른다. 익절·arm 상한 100, 돌파 계수 상한 2, 주기 상한 60초, 미확인 주문 알림 임계 상한 100 은 판정식이 아니라 이런 오기를 거르는 경계다.
- `deploy/vultr/preflight_exit_params.sh`가 배포 전에 형식과 구간을 검사한다. `TRADING_AUTO_START`가 켜져 있으면 청산 키 5개(`TAKE_PROFIT_PCT`, `MAX_LOSS_PCT`, `TRAILING_STOP_PCT`, `TRAILING_ARM_PCT`, `MAX_HOLD_DAYS`)를 `.env`에 명시하지 않은 배포도 막는다. `KIS_TRADING_*` 청산 키는 같은 구간으로, 비용 요율 키 2개는 `[0, 0.01)`로 검사하지만 선언은 요구하지 않는다. 앱은 KIS 값을 기동 로그에 남기지 않고, `KIS_TRADING_ENABLED=true` 일 때만 구간 위반을 ERROR 로 알린다.
- 앱은 기동 시 실효값을 로그로 남기고, 미선언이거나 구간 밖이면 ERROR로 알린다. 보유 포지션의 손절이 멈추지 않도록 거래는 막지 않는다.

## 배포

`.github/workflows/deploy.yml`

- PR: 테스트만 실행
- main push(머지): 테스트 → multi-arch 이미지 빌드 → GHCR push → 운영 변경 판정 → 자동 배포 판정 → SSH로 Vultr에 `deploy.sh deploy`. 배포가 곧 엔진 재시작(그동안 손절 평가가 잠깐 멈춘다)이라 운영에 들어가는 파일이 바뀐 push 만 배포한다. `**.md`와 `docs/**`만 바뀐 push는 테스트·빌드도 건너뛴다.
- 자동 배포 판정(`deploy/vultr/auto_deploy_gate.sh`): 운영 compose(`deploy/vultr/docker-compose.prod.yml`)에서 app 밖(postgres·caddy 서비스, 볼륨·네트워크 등)이 마지막 정상 배포와 다르면 배포하지 않고 경고(warning)를 남긴다 — 서버의 `docker compose up -d` 가 그 컨테이너를 다시 만들어 DB·HTTPS 가 재시작되므로, 봇을 멈출지 정한 뒤 수동 실행으로 배포한다. 마지막 정상 배포 커밋이 없거나 compose 를 읽지 못해도 자동 배포하지 않는다.
- main에서의 수동 실행(workflow_dispatch): push 와 같지만 자동 배포 판정을 거치지 않는다. 실행한 커밋을 그대로 배포한다. 다른 브랜치에서 수동 실행하면 이미지만 올리고(이미 있으면 건너뜀) 배포하지 않는다.
- 운영 변경 판정(`deploy/vultr/deploy_needed.sh`): 서버의 마지막 정상 배포(`/opt/app/.last-good-sha`)의 컨테이너가 healthy 로 돌고 있고, 그 뒤 제외 목록(문서·테스트·`scripts/`·`.gitignore`·로컬 개발 파일) 밖 파일이 바뀌지 않았으면 배포하지 않고 알림(notice)만 남긴다. 지금 컨테이너가 마지막 정상 배포가 아니거나 healthy 가 아니면(실패한 배포가 남긴 컨테이너, 수동 롤백, 정지) 배포한다. 실행한 커밋이 마지막 정상 배포보다 옛 커밋이면 실패로 멈춘다(배포 두 개가 순서가 뒤바뀌어 끝난 경우).
- push·수동 실행 모두 그 커밋의 이미지가 GHCR 에 이미 있으면 다시 빌드하지 않는다(force 여도). 베이스 이미지 태그가 떠 있어 다시 빌드하면 같은 태그의 내용이 바뀌고, 재기동·롤백이 검증하지 않은 이미지를 받기 때문이다. 베이스 이미지 보안 패치는 `Dockerfile` 을 고친 새 커밋으로 받는다 — 새 태그라 헬스체크가 실패하면 직전 정상 SHA 로 자동 롤백한다.
- `force`: 판정 없이 배포한다(이미지는 위 규칙대로). secret 만 바꿨을 때처럼 파일 밖 변경을 반영할 때 쓴다. 이미지와 설정이 그대로면 force 여도 컨테이너를 다시 만들지 않는다(compose 는 바뀐 것이 없으면 그대로 둔다). 단순 재시작은 서버에서 `docker compose restart app` 이다. 수동 롤백(`APP_VERSION=… up -d`) 뒤에는 force 가 필요 없다 — 지금 컨테이너가 마지막 정상 배포가 아니라 판정이 배포한다. 마지막 정상 배포와 같은 커밋을 force 로 다시 배포하면 헬스체크가 실패해도 자동 롤백하지 않는다(롤백 대상이 배포 대상과 같다).
- push 배포는 main 이 이미 앞서 있으면(문서만 다른 경우 빼고) 배포하지 않고 최신 커밋의 실행에 맡긴다 — 테스트·빌드가 끝난 순서로 줄을 서서 옛 커밋이 엔진을 한 번 더 재시작하거나 옛 커밋 거부로 실패하지 않게 한다. 자동 배포를 보류하면 배포 webhook 으로도 알린다(보류가 풀릴 때까지 뒤 push 도 같은 이유로 보류된다). secret(`VULTR_DEPLOY_ENV`)만 바꾼 것은 판정에 보이지 않아 다음 자동 배포에 함께 들어간다 — 바로 반영하려면 force 로 수동 실행한다.
- 헬스체크(`/actuator/health`)는 웹·DB 와 함께 실행 중이던 봇의 복원이 끝났는지 본다. 복원 중이면 503(OUT_OF_SERVICE), 재시도를 다 쓰고 복원하지 못한 봇이 있으면 503(DOWN)이다. 그 봇을 손으로 시작하거나 멈추면 다시 200 이 된다. bot state 조회 자체가 끝까지 실패했으면 재시작 전까지 503 이고, `TRADING_AUTO_START=false` 면 복원하지 않으므로 언제나 200 이다.
- 배포 결과(성공·자동 롤백·실패)를 Discord 로 알린다. 모두 `DISCORD_DEPLOY_WEBHOOK_URL`(배포 webhook, `VULTR_DEPLOY_ENV` 시크릿에 넣는다)로 간다. 배포 webhook 이 없으면 롤백·실패만 `DISCORD_ERROR_WEBHOOK_URL` 로 가고 성공은 보내지 않는다. 거래 webhook(`DISCORD_WEBHOOK_URL`)은 체결 알림 전용이라 배포 알림을 보내지 않는다. 원격 배포 전에 멈춘 실패(설정 검사·preflight·업로드, Actions 의 준비 스텝)는 알리지 않는다 — 운영 컨테이너를 건드리기 전이고 Actions 실패로 보인다.
- 배포 후 180초 안에 헬스체크를 통과하지 못하면 마지막 정상 SHA로 롤백한다. Actions 배포는 서버의 `/opt/app/.last-good-sha`를, 수동 배포는 `deploy/vultr/.state`의 `LAST_GOOD_SHA`를 쓰고, 그 값이 없거나, 배포 대상과 같거나, 그 커밋이 저장소에 없으면 롤백하지 않는다. 새 DB 마이그레이션이 들어간 배포는 구버전이 새 스키마에서 돌지 못할 수 있어 자동 롤백하지 않는다. 다만 롤백 호환 검사(아래 "스키마 변경 규칙")를 통과하면 자동 롤백한다. 어느 쪽이든 새 앱을 띄우기 전에 DB 를 덤프한다. 롤백 대상 SHA 에 V30(거래소 축) 마이그레이션이 없어도 자동 롤백하지 않는다.

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

### 스키마 변경 규칙 (expand/contract)

새 마이그레이션은 직전 이미지가 그 스키마 위에서도 돌게 만든다. 그래야 배포가 실패했을 때 직전 이미지로 되돌릴 수 있다.

- **expand**: 테이블·칼럼·인덱스·제약을 더하기만 한다. 새 칼럼은 기본값이나 NULL 을 받아 옛 코드의 insert 가 깨지지 않게 하고, 기존 행에 걸 CHECK 는 `NOT VALID` 로 건 뒤 다음 배포에서 `VALIDATE CONSTRAINT` 한다.
- **migrate**: 새 코드가 새 칼럼을 읽고 쓴다. 옛 이미지가 잘못 읽을 새 데이터(예: 다른 거래소의 행)는 기능 플래그를 켜기 전까지 만들지 않는다.
- **contract**: 옛 칼럼·제약을 지우는 마이그레이션은 직전 이미지로 돌아갈 일이 없을 만큼 지난 뒤 따로 낸다. 이 배포만 되돌릴 수 없고, 바뀌는 동작이 없어야 한다.
- 한 마이그레이션에서 더하고 지우는 것을 함께 하지 않는다(V30 이 그렇게 했고, V30 이전 이미지로는 롤백하지 않는다).

롤백 호환 검사: Actions 배포는 마이그레이션이 바뀌었으면 runner 의 임시 Postgres(운영과 같은 이미지)에 새 이미지를 띄워 마이그레이션한 뒤, 직전 정상 이미지를 그 스키마 위에 띄워 기동과 봇 복원 조회(`bot_state`)가 healthy 로 끝나는지 본다(`deploy/vultr/rollback_compat.sh`, 더미 시크릿·빈 DB·자동 시작 켬 — 복원할 봇이 없어 주문은 나가지 않는다). 통과하면 그 배포도 헬스체크 실패 때 직전 이미지로 자동 롤백하고, 실패하거나 검사하지 못하면 경고만 남기고 자동 롤백 없이 배포한다. 빈 DB 라 쓰기 경로(체결·주문 기록 INSERT)와 옛 코드가 새 데이터를 잘못 읽는 비호환은 잡지 못하므로 위 규칙이 먼저다 — 특히 기본값 없는 NOT NULL 칼럼 추가는 검사를 통과해도 롤백한 옛 코드의 쓰기를 깬다. V30 이전 이미지로는 검사와 상관없이 자동 롤백하지 않는다. 수동 실행의 `rollback_check` 를 켜면 마이그레이션이 없어도 검사만 돌려 볼 수 있다.

`deploy.sh` 는 직전 정상 배포와 비교해 `db/migration/` 이 바뀌었거나 비교할 수 없으면, 새 이미지를 받은 뒤 새 앱을 띄우기 직전에 서버에서 DB 를 `/opt/app/backups/pre-deploy-<SHA 12자리>-<UTC 시각>.sql.gz` 로 덤프한다(배포 계정 전용, 최근 5개 보존). 멈춰 있는 postgres 는 먼저 띄운다. postgres 를 띄우지 못하거나 덤프가 완전하지 않으면 앱 컨테이너를 바꾸지 않고 배포를 멈춘다(postgres 정의가 바뀐 배포면 postgres 는 이미 새로 만들어졌을 수 있다) — 이때 서버의 `.env`·compose 파일은 이미 새 배포 것이라 `docker compose up` 을 손으로 돌리면 덤프 없이 마이그레이션된다. 같은 디스크라 호스트 유실에는 쓸모가 없다 — 오프사이트 백업은 따로 둔다.

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
2. 배포가 마이그레이션 전 덤프를 서버 디스크에 남긴다(위 절). 오프사이트 사본이 필요하면 서버 `/opt/app` 에서 `./backup.sh` 를 손으로 한 번 돌린다. 백업 cron 을 등록해 두었다면(`deploy/vultr/README.md` 6절, KST 03:00) 배포 시각이 그와 겹치지 않게 한다. `pg_dump` 가 잡은 잠금을 V30 의 `ALTER TABLE` 이 기다리는 동안 헬스체크 시간이 지나간다.
3. 되돌리기 SQL(`deploy/vultr/sql/V30_down.sql`)을 미리 읽어 둔다. Flyway 는 이 파일을 쓰지 않는다.
4. 기동이 실패하면 자동 롤백되지 않는다. `docker compose logs app` 로 원인을 보고 V30 이 적용됐는지 확인한다.
   ```bash
   docker compose exec -T postgres psql -U trading -d trading -c "SELECT version, success FROM flyway_schema_history WHERE version = '30'"
   ```
   - 0행이면 마이그레이션이 실패해 Postgres 트랜잭션 DDL 로 V30 전체가 되돌려진 것이다. 봇이 멈춰 있는 동안 보유 포지션의 손절도 돌지 않으므로 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 먼저 되살리고, 원인을 고친 이미지로 다시 배포한다.
   - V30 이 적용돼 있으면 고친 이미지로 앞으로 배포한다.

V30 이후에는 V30 이전 이미지로 롤백하지 않는다. 옛 코드는 거래소 축을 몰라 KIS 행까지 Upbit 상태로 읽는다. 불가피하면 앱을 멈추고 `./dump_local.sh before-V30-down` 을 돌린 뒤 아래로 KIS 행을 먼저 지우고, `V30_down.sql` 을 실행한 다음 옛 이미지를 올린다. `bot_state` 는 V17 부터 거래소 축이 있어 옛 이미지도 거래소로 걸러 읽으므로 지우지 않는다. `trade_executions` 의 KIS 체결 감사 행은 지우기 전에 복사해 둔다.
```sql
BEGIN;
CREATE TABLE v30_rollback_archive_trade_executions AS SELECT * FROM trade_executions WHERE exchange <> 'UPBIT';
DELETE FROM trade_executions WHERE exchange <> 'UPBIT';
DELETE FROM trade_records WHERE exchange <> 'UPBIT';
DELETE FROM trading_states WHERE exchange <> 'UPBIT';
COMMIT;
```

### V31 (KIS 키 테이블)

V31 은 `user_broker_keys` 테이블만 새로 만들고 기존 테이블은 건드리지 않는다. 기존 행에 걸리는 제약이 없어 사전 점검 쿼리는 없다. 배포 직전 백업은 배포가 서버 디스크에 남기는 마이그레이션 전 덤프(위 "스키마 변경 규칙")로 한다.

- V30 이미지로의 롤백은 그대로 된다. Flyway 기본값(`ignoreMigrationPatterns` `*:future`)으로 V30 이미지는 적용된 V31 을 무시하고 뜨며, 앱 설정에 이 값을 바꾸는 키가 없다. 그래서 `deploy.sh` 의 자동 롤백 하한은 V30 그대로다.
- 다만 V31 을 처음 담은 배포는 migration 변경이 들어 있어 `deploy.sh` 가 자동 롤백하지 않는다. 기동에 실패하면 V31 적용 여부와 관계없이 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 수동 롤백해도 되고, `V31_down.sql` 은 필요 없다.
- V30 이전으로 내려가야 하면 (V32 가 적용돼 있으면 `V32_down.sql` 부터) `V31_down.sql` 을 먼저 돌리고 위 V30 절차를 따른다. `V30_down.sql` 은 V30 뒤에 적용된 마이그레이션이 있으면 멈춘다.
- 테이블 자체를 없애야 할 때만 `deploy/vultr/sql/V31_down.sql` 을 쓴다. 저장된 KIS 앱키·앱시크릿·계좌번호가 모두 사라져 사용자가 다시 넣어야 한다. V31 이 마지막 적용 버전일 때만 돌고, 아니면 아무것도 바꾸지 않고 멈춘다. 테이블과 `flyway_schema_history` 의 V31 행을 한 트랜잭션으로 지우므로, 이후 V31 이 든 이미지를 올리면 테이블을 다시 만든다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V31-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V31_down.sql
  APP_VERSION=<V31 이전 SHA> docker compose up -d --pull missing
  ```
  `/tmp/V31_down.sql` 은 저장소의 파일을 서버로 올린 것이다. `dump_local.sh` 는 덤프를 서버의 `/opt/app/backups/manual-*.sql.gz` 에 남기고(이름표마다 최근 5개), 덤프가 끝까지 써지지 않으면 실패해 down 이 돌지 않는다. 외부 사본은 S3 백업(`./backup.sh`)을 설정했을 때만 받을 수 있다.
- `KIS_TRADING_ENABLED=false` 이면 `/api/kis/keys` 가 없어 사용자가 저장한 키를 API 로 지울 수 없다. 그때 한 사용자의 키를 지우려면 `/opt/app` 에서 아래를 실행한다. 한 환경만 지우면 `<환경>` 에 `KIS_PAPER`(모의) 또는 `KIS`(실전)를 넣고, 두 환경을 모두 지우면 `AND exchange = ...` 를 뺀다. 백업 조건은 위 down 과 같다.
  ```bash
  ./dump_local.sh before-kis-key-delete && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading \
    -c "DELETE FROM user_broker_keys WHERE user_id = <사용자 id> AND exchange = '<환경>'"
  ```

### V32 (신규 매수 중지)

V32 는 `bot_state` 에 `entries_paused`(기본 false)·`entries_paused_changed_at` 칼럼만 더한다. 기존 행은 중지되지 않은 상태로 읽히며 사전 점검 쿼리는 없다. 배포 직전 백업은 V31 과 같다.

- V31 이미지로의 롤백은 그대로 뜬다(Flyway `*:future`). 다만 그 이미지는 중지 값을 읽지 않아 켜 둔 중지가 풀린 것처럼 매수가 나가므로, 롤백했으면 봇을 정지한다.
- V32 를 처음 담은 배포는 migration 변경이 들어 있어 `deploy.sh` 가 자동 롤백하지 않는다. 기동에 실패하면 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 수동 롤백한다. `V32_down.sql` 은 필요 없다.
- V31 이전으로 내려가야 하면 (V38·V37·V36·V35·V34·V33 이 적용돼 있으면 `V38_down.sql`·`V37_down.sql`·`V36_down.sql`·`V35_down.sql`·`V34_down.sql`·`V33_down.sql` 부터) `V32_down.sql` 을 먼저 돌린다. `V31_down.sql` 은 V31 뒤에 적용된 마이그레이션이 있으면 멈춘다.
- 칼럼 자체를 없애야 할 때만 `deploy/vultr/sql/V32_down.sql` 을 쓴다. 켜 둔 중지 값이 함께 사라진다. V32 가 마지막 적용 버전일 때만 돌고, 칼럼과 `flyway_schema_history` 의 V32 행을 한 트랜잭션으로 지운다. 백업 조건은 V31 down 과 같다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V32-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V32_down.sql
  APP_VERSION=<V32 이전 SHA> docker compose up -d --pull missing
  ```

### V33 (체결 알림 outbox)

V33 은 `trade_notification_outbox` 테이블만 새로 만든다. 사전 점검 쿼리는 없고, 배포 직전 백업은 V31 과 같다.

- V32 이미지로의 롤백은 그대로 뜬다(Flyway `*:future`). 그 이미지는 옛 방식(커밋 뒤 바로 발송)으로 알리고 테이블은 건드리지 않는다. 롤백 전에 남은 `PENDING` 은 다시 올렸을 때 보내지거나 24시간이 지났으면 실패로 끝난다. 늦은 알림이 필요 없으면 다시 올리기 전에 `UPDATE trade_notification_outbox SET status = 'SKIPPED' WHERE status = 'PENDING';` 를 돌린다.
- V33 을 처음 담은 배포는 migration 변경이 들어 있어 `deploy.sh` 가 자동 롤백하지 않는다. 기동에 실패하면 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 수동 롤백한다. `V33_down.sql` 은 필요 없다.
- 테이블 자체를 없애야 할 때만 `deploy/vultr/sql/V33_down.sql` 을 쓴다(V33 이 마지막 적용 버전일 때만 돌고, 테이블과 `flyway_schema_history` 의 V33 행을 한 트랜잭션으로 지운다).
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V33-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V33_down.sql
  APP_VERSION=<V33 이전 SHA> docker compose up -d --pull missing
  ```

### V34 (봇 장부)

V34 는 `trading_states` 에 봇 장부 칼럼(`book_volume`·`book_avg_price`·`book_written_at`), 미해소 매수의 주문 때 장부(`pending_buy_book_*`)와 미해소 주문 정리 기준값(`adopt_baseline_volume`·`sell_clear_*`, 지금 앱은 읽고 쓰지 않는다)을 더한다. 기존 행은 장부가 비어 첫 동기화가 위 규칙(흔적이 있으면 한 번 전량 편입)으로 정한다. 그래서 배포 첫 동기화가 되돌릴 수 없는 단계다 — 배포 전에 로컬 백업을 받고, `trading_states` 의 흔적 있는 행과 `GET /api/bot/status` 의 positions 를 남겨 편입·미편입 WARN 과 대조한다.

- V33 이미지로의 롤백은 그대로 뜬다(Flyway `*:future`). 장부 칼럼은 건드리지 않으므로 `V34_down.sql` 은 돌리지 않는다 — 돌리면 장부가 사라진다. 롤백한 동안 저장된 행은 `updated_at` 만 바뀌어, 다시 올리면 그 행의 장부는 낡은 것으로 보고 비운 채 위 첫 동기화 규칙을 다시 탄다.
- V34 를 처음 담은 배포는 migration 변경이 들어 있어 `deploy.sh` 가 자동 롤백하지 않는다. 기동에 실패하면 `APP_VERSION=<직전 정상 SHA> docker compose up -d --pull missing` 으로 수동 롤백한다.
- 칼럼 자체를 없애야 할 때만 `deploy/vultr/sql/V34_down.sql` 을 쓴다(V34 가 마지막 적용 버전일 때만 돈다 — V35 가 적용돼 있으면 `V35_down.sql` 부터).
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V34-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V34_down.sql
  APP_VERSION=<V34 이전 SHA> docker compose up -d --pull missing
  ```

### V35 (수동 개입 기록)

V35 는 `manual_interventions` 테이블을 더한다. 예전 pending 수동 해제·halt 해제가 상태 저장과 같은 트랜잭션에 한 행(사용자·거래소·종목·action·해제 전 값 JSON)을 남겼다. 지금 앱은 이 테이블을 쓰지 않는다 — pending 해제·halt 해제 API 와 사람 확인 필요 상태를 없앴다(미해소 주문은 위 대사로만 확정한다). 테이블은 V39 가 지웠다.

앱은 `trading_states` 의 `halted`·`halt_reason`·`reconcile_failure_count`·`review_reason`·`adopt_baseline_volume`·`sell_clear_prior_volume`·`sell_clear_volume` 을 읽고 쓰지 않는다. 칼럼은 V39 가 지웠다.

- V39 이전 스키마에서는 V34 이미지로의 롤백이 그대로 떴다(Flyway `*:future`). V39 뒤에는 아래 V39 절의 되돌리기를 먼저 한다.
- 테이블 자체를 없애야 할 때만 `deploy/vultr/sql/V35_down.sql` 을 쓴다(V35 가 마지막 적용 버전일 때만 돈다, 기록이 사라진다). V35 를 쓰는 옛 이미지가 떠 있는 동안 돌리면 그 이미지의 해제가 모두 503 이 되므로 앱을 멈춘 뒤 돌린다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V35-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V35_down.sql
  APP_VERSION=<V35 이전 SHA> docker compose up -d --pull missing
  ```

### V36 (ERROR 알림 outbox)

V36 은 `error_alert_outbox` 테이블을 더한다(위 ERROR 알림 절).

- V35 이미지로의 롤백은 그대로 뜬다(Flyway `*:future`). 롤백한 동안 ERROR 알림은 예전처럼 바로 간다. 남은 `PENDING` 은 다시 올리면 보내므로, 오래된 알림을 보내지 않으려면 올리기 전에 `UPDATE error_alert_outbox SET status = 'SKIPPED' WHERE status = 'PENDING';` 를 돌린다.
- 테이블 자체를 없애야 할 때만 `deploy/vultr/sql/V36_down.sql` 을 쓴다(V36 이 마지막 적용 버전일 때만 돈다, 보내지 못한 알림이 사라진다). 앱을 멈춘 뒤 돌린다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V36-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V36_down.sql
  APP_VERSION=<V36 이전 SHA> docker compose up -d --pull missing
  ```

### V37 (상태 저장 버전)

V37 은 `trading_states.version`(기존 행 0)을 더한다(위 상태 저장 충돌 절).

- V36 이미지로의 롤백은 그대로 뜬다(Flyway `*:future`, 칼럼 기본값). 롤백한 동안은 버전을 올리지 않아 덮어쓰기 방지가 없다(V37 이전과 같다). 다시 올리면 시작이 행의 버전을 읽으므로 거짓 충돌은 없다.
- 칼럼 자체를 없애야 할 때만 `deploy/vultr/sql/V37_down.sql` 을 쓴다(V37 이 마지막 적용 버전일 때만 돈다). V37 이미지가 떠 있는 동안 돌리면 상태 저장이 모두 실패하므로 앱을 멈춘 뒤 돌린다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V37-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V37_down.sql
  APP_VERSION=<V37 이전 SHA> docker compose up -d --pull missing
  ```

### V38 (막힌 매수 알림 시각)

V38 은 `trading_states.pending_buy_since`·`pending_buy_alerted`(기존 행 null·false)를 더한다(위 미해소 주문 대사 4). 매수 알림이 조회 실패 횟수 대신 주문 시각부터의 경과 시간으로 바뀌어 `reconcile_failure_count` 는 더 읽고 쓰지 않는다(칼럼은 V39 가 지웠다).

- V39 이전 스키마에서는 V37 이미지로의 롤백이 그대로 뜬다(V39 뒤 #372 이전 이미지는 아래 V39 절. 칼럼이 nullable·기본값이고 옛 이미지는 건드리지 않는다). 롤백한 동안 생긴 매수 pending 은 시작 시각이 없어 다시 올리면 그때부터 잰다. 롤백 순간 매수 pending 이 열려 있었고 옛 이미지가 그것을 확정한 뒤 같은 종목에 새로 샀다면, 다시 올렸을 때 그 새 pending 이 옛 시각·알림 여부를 물려받아 바로 알리거나 알리지 않는다(주문에는 영향 없음). 옛 이미지는 `reconcile_failure_count` 를 V38 전 마지막 값부터 다시 센다.
- 칼럼 자체를 없애야 할 때만 `deploy/vultr/sql/V38_down.sql` 을 쓴다(V38 이 마지막 적용 버전일 때만 돈다). V38 이미지가 떠 있는 동안 돌리면 상태 저장이 모두 실패하므로 앱을 멈춘 뒤 돌린다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V38-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V38_down.sql
  APP_VERSION=<V38 이전 SHA> docker compose up -d --pull missing
  ```

### V39 (쓰지 않는 테이블·칼럼 삭제)

V39 는 코드가 더 쓰지 않는 것을 지운다. 지운 행 수는 기동 로그에 `V39 drop:` 으로 남는다.

- 테이블: `manual_interventions`(V35), V21 백필 전 값 `trade_records_v21_backup`·`trade_executions_v21_backup`, 손으로 만든 `users_bak_20260602`.
- `trading_states` 칼럼: `halted`·`halt_reason`·`reconcile_failure_count`·`review_reason`·`adopt_baseline_volume`·`sell_clear_prior_volume`·`sell_clear_volume`.
- 같은 배포에서 `users.public_profile`·`public_strategy`·`admin` 과 `trade_executions.order_type`·`status` 를 엔티티에서 뺐다. 칼럼은 V40 이 지웠다.

롤백:
- 자동 롤백(마지막 정상 배포 이미지)은 halt 제거(#372) 이후 이미지라 V39 위에서 그대로 뜬다.
- **#372 이전 이미지로는 그대로 내려갈 수 없다.** 그 이미지는 지운 칼럼을 쓴다. 꼭 내려가야 하면 앱을 멈추고 `deploy/vultr/sql/V39_down.sql` 로 칼럼(기본값)과 빈 `manual_interventions` 를 되살린 뒤, V39 이전 이미지만 띄운다. V39 가 든 이미지가 다시 뜨면 V39 가 또 적용돼 되살린 칼럼을 지운다. 지운 값은 배포 전 덤프에만 있다. 그 아래 버전으로 더 내려가면 V38·V37… 의 down 을 차례로 돌린다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V39-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V39_down.sql
  APP_VERSION=<V39 이전 SHA> docker compose up -d --pull missing
  ```

### V40 (매핑하지 않는 사용자·체결 칼럼 삭제)

V40 은 `users.public_profile`·`public_strategy`·`admin` 과 `trade_executions.order_type`·`status` 를 지운다. V39 와 같은 배포부터 앱이 매핑하지 않았다. 기본값이 아니던 행 수는 기동 로그에 `V40 drop:` 으로 남는다.

- V39 이미지로의 롤백은 그대로 뜨고 쓴다(그 이미지는 이 칼럼을 쓰지 않는다).
- V39 이전 이미지는 이 칼럼을 넣으므로 V40 스키마에서 가입·체결 기록이 실패한다. 그 이미지로 내려가야 하면 앱을 멈추고 `deploy/vultr/sql/V40_down.sql` 로 칼럼(기본값)을 되살린 뒤 V40 이전 이미지만 띄운다. V40 이 든 이미지가 다시 뜨면 V40 이 또 적용돼 되살린 칼럼을 지운다. 더 내려가면 이어서 위 V39 절차를 따른다.
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V40-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V40_down.sql
  APP_VERSION=<V40 이전 SHA> docker compose up -d --pull missing
  ```

### V41 (주문 표 `orders`)

V41 은 주문마다 한 줄인 `orders` 표를 만들고 옛 체결을 `trade_executions` 기준으로 옮긴다. 옮긴 수와 짝을 못 찾은 수는 기동 로그에 `V41 orders:` 로 남는다.

- 지금은 같은 체결을 `trade_records`(화면)와 `trade_executions`(중복 방지·대사)에 두 번 적는다. 엔진 매수 줄의 가격·수량·총액은 이 주문이 아니라 체결 뒤 보유 전체다. `orders` 는 이 주문의 체결 수량·대금·평균가(`filled_*`·`avg_fill_price`)와 체결 뒤 장부(`position_volume_after`·`book_avg_price`)를 따로 적는다. `fill_source` 가 값의 출처(주문 응답·잔고 복원·옛 기록)다.
- 옛 엔진 매수는 이 주문 하나의 수량을 알 수 없어 비워 두고(`BACKFILL_SNAPSHOT`), 보유 전체 값을 장부 칼럼에 둔다.
- 아직 아무도 `orders` 를 읽지 않는다. 화면·대사·체결 알림은 지금처럼 옛 두 표를 쓴다.
- V40 이미지로의 롤백은 그대로 뜬다(새 표만 더한다). 그동안의 체결은 `orders` 에 남지 않고, 읽는 쪽을 `orders` 로 바꾸는 다음 단계가 마이그레이션의 옮기기 부분을 다시 돌려 메운다.
- 표 자체를 없애야 할 때만 `deploy/vultr/sql/V41_down.sql` 을 쓴다(V41 이 마지막 적용 버전일 때만 돈다).
  ```bash
  cd /opt/app
  docker compose stop app
  ./dump_local.sh before-V41-down && docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading < /tmp/V41_down.sql
  APP_VERSION=<V41 이전 SHA> docker compose up -d --pull missing
  ```
