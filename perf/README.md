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

# load 만 (로컬 전용)
k6 run -e ONLY=load perf/load-test.js
```

> ⚠️ `load` 시나리오는 **iteration 마다** `/api/auth/register` 로 계정을 만들고(`k6user_*`) 인증 엔드포인트를 친다.
> **운영 도메인에는 돌리지 않는다** — 로컬(`docker-compose.yml`)이나 일회용 인스턴스에서만.
>
> 현행 `RateLimitFilter` 는 `/api/auth` 30/min, 그 외 60/min 을 **클라이언트 IP 단위**로 센다(버킷 키는 프록시가 부여한 client IP 뿐,
> `/actuator`·`/api/prices` 만 제외). 한 머신에서 뜬 VU 전부가 한 버킷을 공유하므로 `load` 는 VU 수와 무관하게 429 로
> 에러율 임계를 넘는다 — 부하 측정으로 쓰려면 rate limit 을 끄는 로컬 스위치나 VU 별 버킷 분리가 먼저다(#25 잔여).
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

`load` 는 위 경고대로 현행 rate limit 아래에서는 임계를 통과할 수 없어 baseline 이 없다.
