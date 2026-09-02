---
title: DB 통합테스트 하네스 — 왜 Testcontainers 가 아니라 외부 제공 DB 인가
category: decision
created: 2026-09-01
updated: 2026-09-01
claim_state: current
verified: 2026-09-01 — 로컬 소켓 직접 호출로 API 버전별 응답 확인(`/v1.32/info` 400, 1.40·1.44·1.47·1.54 는 200), Docker 29.3.1 의 `MinAPIVersion=1.40` 은 `docker version` 으로 확인. PR #153 이 머지돼 CI 에서 `실행 3건 / skip 0건` 관찰
sources:
  - bot/src/test/kotlin/com/trading/bot/persistence/TradingStateRoundTripTest.kt
  - scripts/run-db-tests.sh
  - .github/workflows/deploy.yml
  - https://github.com/testcontainers/testcontainers-java/issues/11212
---

# DB 통합테스트 하네스

`trading_states`(V14) ↔ R2DBC 매핑을 실제 PostgreSQL 로 검증하는 테스트가 있다([[persistence-schema]]). mockk 로 repository 를 대체하면 컬럼명·타입·nullable·unique 제약·`TIMESTAMPTZ` 변환이 하나도 검증되지 않고, 매핑 오류의 첫 발견 지점이 **운영 첫 기동**이 된다.

## Testcontainers 를 쓰지 않는다

시도했으나 **로컬에서 컨테이너가 뜨지 않는다.** "Docker 가 없다"는 메시지로 보이지만 원인은 다르다.

| 요청 | 응답 |
|---|---|
| `/v1.32/info` | **400** |
| `/v1.40/info` · `/v1.44` · `/v1.47` · `/v1.54` | 200 |

docker-java 가 **API 1.32** 로 협상하는데 Docker Engine 29 의 최소 지원이 **1.40** 이라 거부된다. 그 400 이 Testcontainers 의 strategy probing 중에 발생해 *"Could not find a valid Docker environment"* 로 뭉뚱그려지므로, 데몬이 죽은 것처럼 보인다([testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212) 외 다수 보고).

**효과 없었던 우회**: Testcontainers 1.20.4 → 1.21.3, `DOCKER_HOST` 3종(기본 소켓·Desktop 소켓·CLI 프록시), `DOCKER_API_VERSION`, `~/.testcontainers.properties` 의 `api.version`, Ryuk 비활성화, `--no-daemon`.

> [!note]
> Docker CLI 는 정상이고 JVM 에서 소켓 접속 자체도 된다. 그래서 "docker 가 안 뜬다"로 진단하면 길을 잃는다. **API 버전 프리픽스를 붙여 `/info` 를 직접 호출해 보는 것**이 가장 빠른 판별이다.

## 대신 DB 를 외부에서 받는다

테스트는 컨테이너를 띄우지 않고 `TEST_DB_HOST` 등으로 주어진 Postgres 에 접속만 한다.

- **CI** — `deploy.yml` 의 `test` job 이 `services: postgres`(운영과 같은 `postgres:17-alpine`)로 제공
- **로컬** — `scripts/run-db-tests.sh` 가 임시 컨테이너를 띄우고 끝나면 지운다. 이름에 PID, 포트는 동적 할당이라 동시 실행이 서로를 죽이지 않는다

얻은 것: **Docker 버전 비호환에서 자유롭고**(그냥 Postgres 에 접속할 뿐이다), 의존성이 늘지 않는다. 잃은 것: 격리가 약해 테스트가 자기 데이터를 직접 지워야 한다(`@AfterEach` 가 자식→부모 순서로 삭제).

## skip 이 통과로 위장하지 않게

접속 정보가 없으면 테스트는 skip 된다 — 로컬에서 DB 없이 `./gradlew test` 를 돌려도 깨지지 않아야 하기 때문이다. 그런데 **skip 은 초록불로 보인다.** 세 겹으로 막았다([[lesson-skip-is-not-pass]]).

1. `DB_TESTS_REQUIRED=true` — CI 에서는 접속 정보가 없으면 skip 대신 **실패**
2. 로컬 스크립트의 `--no-daemon` — Gradle 데몬이 환경변수를 기동 시점에 고정해, 재사용되면 여기서 세운 값이 테스트에 안 보인다(그러면 1번 장치까지 무력화된다)
3. **결과 XML 의 `skip=0` 직접 검증** — CI 스텝과 로컬 스크립트가 각각 확인한다. gradle 은 성공하면 테스트별 결과를 남기지 않으므로, 이 줄이 없으면 실행 여부를 관찰할 방법이 없다

Testcontainers 가 Docker 29 를 지원하면 격리가 더 강한 쪽으로 되돌릴 수 있다. 다만 지금 방식도 CI 에서는 매번 새 DB 라 격리가 충분하다.
