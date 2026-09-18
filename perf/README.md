# Performance Testing

[k6](https://grafana.com/docs/k6/) 기반 부하 테스트입니다.

## 설치

```bash
brew install k6
```

## 실행

```bash
# 로컬 서버 대상 (기본)
k6 run perf/load-test.js

# smoke 만 (공개 GET 3종·check 4개·1 VU·30s) — 운영 도메인에 돌려도 되는 유일한 시나리오
# (k6 에는 시나리오 선택 플래그가 없어 스크립트가 ONLY 환경변수로 고른다 — grafana/k6#3054)
k6 run -e ONLY=smoke -e BASE_URL=https://do-anything.cloud perf/load-test.js

# load 만 (로컬 전용) — LOCAL_CLIENT_IPS=1 로 iteration 마다 다른 client IP 를 줘야 rate limit 에 안 막힌다(아래 경고)
k6 run -e ONLY=load -e LOCAL_CLIENT_IPS=1 -e BASE_URL=http://localhost:18080 perf/load-test.js
```

### 로컬 대상 띄우기

사용자의 개발 DB(5433)를 k6 계정으로 채우지 않도록 **일회용 Postgres** 를 따로 띄운다. 거래소 키·웹훅은 비우고
시크릿은 로컬 전용 더미를 쓴다(운영 값을 넣지 말 것 — 새 DB 에는 키 가진 사용자가 없어 주문 경로가 없다).

```bash
docker run -d --rm --name k6-baseline-pg -e POSTGRES_DB=trading -e POSTGRES_USER=trading \
  -e POSTGRES_PASSWORD=k6local -p 55433:5432 postgres:17-alpine
SERVER_PORT=18080 DB_PORT=55433 DB_PASSWORD=k6local UPBIT_ACCESS_KEY= UPBIT_SECRET_KEY= \
  DISCORD_WEBHOOK_URL= DISCORD_ERROR_WEBHOOK_URL= TRADING_AUTO_START=false \
  JWT_SECRET=<로컬 더미 32자+> APP_ENCRYPTION_SECRET=<로컬 더미> ./gradlew :bot:bootRun
# 끝나면: docker stop k6-baseline-pg
```

> ⚠️ `load` 시나리오는 **iteration 마다** `/api/auth/register` 로 계정을 만들고(`k6user_*`) 인증 엔드포인트를 친다.
> **운영 도메인에는 돌리지 않는다** — 로컬(`docker-compose.yml`)이나 일회용 인스턴스에서만.
>
> 현행 `RateLimitFilter` 는 `/api/auth` 30/min, 그 외 60/min 을 **클라이언트 IP 단위**로 센다(버킷 키는 (인증/일반) × 프록시가 부여한 client IP — 두 카운터는 따로 돈다,
> `/actuator`·`/api/prices` 만 제외). 한 머신에서 뜬 VU 전부가 같은 두 버킷을 공유하므로 그대로면 `load` 는 VU 수와 무관하게 429 로
> 에러율 임계를 넘는다. 로컬은 프록시가 없어 앱이 `X-Forwarded-For` 를 그대로 믿으므로, `LOCAL_CLIENT_IPS=1` 이 iteration 마다
> 다른 주소를 붙여 버킷을 나눈다(rate limit 필터 자체는 켜진 채로 측정된다). **운영에서는 Caddy 가 이 헤더를 실제 peer IP 로
> 덮어써 아무 효과가 없다** — 우회 수단이 아니라 로컬 측정용이다.
> `/api/portfolio` 는 Upbit 키 없는 k6 유저에게 400 이 정상이라 check 가 그 값을 허용한다(`http_req_failed` 에는 잡힌다).

## 시나리오

| 시나리오 | VU | 시간 | 설명 |
|---------|-----|------|------|
| smoke | 1 | 30s | 공개 GET 3종 (`/actuator/health`, `/api/leaderboard`, `/api/prices/status`), check 4개 |
| load | 0→50 | 8m | 점진적 부하 증가, 인증 포함 (`/api/user/me`, `/api/bot/status`, `/api/strategies`, `/api/trades`, `/api/portfolio`) |

## Threshold

- P95 응답 시간 < 500ms
- P99 응답 시간 < 1500ms
- 에러율 < 5%

## Baseline

실행할 때마다 한 줄씩 추가한다(날짜·대상·시나리오·결과).

| 날짜 | 대상 | 시나리오 | iterations / http_reqs | checks | http_req_failed | avg / p95 / max (ms) | 임계 |
|---|---|---|---|---|---|---|---|
| 2026-09-16 | `https://do-anything.cloud` (Vultr, V27 배포 직후) | smoke (1 VU·30s) | 28 / 84 | 112 통과 / 0 실패 | 0% | 29.5 / 55.6 / 77.5 | 전부 통과 |

| 2026-09-18 | 로컬 `bootRun`(M-series Mac, 일회용 Postgres 17, Redis 없음 = in-memory rate limit, 시세 수집 13마켓 동시 가동) | smoke (1 VU·30s) | 30 / 90 | 120 통과 / 0 실패 | 0% | 5.7 / 10.1 / 62.5 | 전부 통과 |
| 2026-09-18 | 위와 같음, `LOCAL_CLIENT_IPS=1` | load (0→50 VU·8m) | 6,136 / 61,360 (118.8 req/s) | 55,224 통과 / 0 실패 | 0% | 23.9 / 119.3 / 305.1 (p99 170.8) | 전부 통과 |

load 메모: 중앙값 1.3ms 에 p90 79ms — 꼬리는 iteration 마다 도는 register+login 의 비밀번호 해시(login avg 77 / p95 136ms)다. JVM RSS 는
452~454MB 로 8분간 평탄(누수 징후 없음), CPU 피크 ≈ 460%(코어 4.6개분 — 50 VU 에서 해시가 CPU 를 다 쓴다). 첫 실행에서는 login 1건이
6.2초(JVM 워밍업성, 재실행에서 미재현)였다. **운영 인스턴스(2 vCPU·2GB)의 한계치는 이 수치로 추정하지 말 것** — 코어 수가 다르다.

미측정(#25 잔여): 이 스크립트는 REST 만 친다. 시세 수집 파이프라인 backpressure·다종목×다유저 tick 평가·고빈도 DB write 는 합성 ticker 주입
하네스가 있어야 잰다.
