---
title: testcontainers-harness — 실제 Postgres 로 R2DBC 매핑을 검증하는 통합테스트 하네스 (#53)
status: in_progress
started: 2026-08-26
updated: 2026-08-26
---

# Goal

`trading_states`(V14) ↔ R2DBC 엔티티 매핑을 **실제 Postgres 로** 검증하는 통합테스트를 도입한다. (Testcontainers 로 시작했으나 Docker 29 비호환으로 **외부 제공 DB 방식**으로 전환했다 — 아래 Decisions.) 지금은 mockk 로 repository 를 대체해 컬럼명·타입·제약이 하나도 검증되지 않고, 매핑 오류의 첫 발견 지점이 운영 첫 기동이다. 하네스가 생기면 이후 마이그레이션·제약 변경이 전부 회귀 게이트에 들어온다.

# Progress

- 2026-08-26: Explore 완료.
  - **Testcontainers 의존성 0건** 확인(`grep -rn testcontainers` → 없음). 하네스를 새로 만든다.
  - ⚠️ **이슈 #53 의 전제 하나가 stale** — "`@SpringBootTest` 0건" 이라 적혀 있으나 지금은 3개 있다(`TradingPropertiesBindingTest`·`StrategyConfigTest`·`StrategyMinCandlesTest`). 다만 셋 다 `ApplicationContextRunner` 기반 경량 컨텍스트라 **DB 를 띄우는 테스트는 여전히 0건**이고, 이슈의 핵심(매핑 미검증)은 유효하다.
  - 환경: Spring Boot **3.4.1** / Kotlin 2.1.0 / JDK 21. R2DBC 와 Flyway 가 **서로 다른 URL** 을 쓴다(`spring.r2dbc.url` = `r2dbc:postgresql://…`, `spring.flyway.url` = `jdbc:postgresql://…`) — 테스트에서 둘 다 컨테이너로 돌려야 한다.
  - 검증 대상 매핑: `TradingStateService.toEntity`/`toDomain`(`:45-91`) 20개 필드. `exitParamsJson`(Jackson 직렬화)·`pendingSellReason`(enum↔String)·`buyDate`/`boughtDate`(LocalDate↔DATE)·`pendingSellSince`/`updatedAt`(Instant↔TIMESTAMPTZ)가 타입 경계다.
  - V14 제약: `uq_trading_states_user_ticker` UNIQUE, `uq_trade_executions_order_id` **부분** UNIQUE(`WHERE exchange_order_id IS NOT NULL`), `user_id` → `users(id)` FK.
- 2026-08-26: 의존성(`org.testcontainers:postgresql`·`junit-jupiter`, Boot BOM 이 **1.20.4** 로 해석) 추가하고 `TradingStateRoundTripTest` 3건 작성. 베이스 클래스 없이 단일 클래스로 시작했다 — 두 번째 테스트가 생기면 그때 추출한다(과한 추상화 회피).
- 2026-08-26: **로컬에서 컨테이너 기동 실패 — `Could not find a valid Docker environment`.** 진단 결과 의존성·코드 문제가 아니다:
  - Testcontainers 1.20.4 + docker-java 3.4.0 (정상 조합), 전이 의존 누락 없음
  - Docker Desktop 실행 중이고 CLI 는 정상(`docker ps`·`docker run` 성공 — 같은 세션에서 V22/V23 검증에 실제로 사용했다)
  - `/var/run/docker.sock` → `~/.docker/run/docker.sock` 심볼릭 링크 존재, 대상도 존재
  - 활성 context 는 `desktop-linux`(`unix:///Users/jongyoonlee/.docker/run/docker.sock`)
  - `DOCKER_HOST` 명시해도 동일 실패, `~/.testcontainers.properties` 없음
  - ⚠️ 즉 **JVM(Gradle 테스트 워커)에서만** 소켓을 못 잡는다. 샌드박스 경계 가능성이 있다 — 그렇다면 CI(GitHub Actions ubuntu-latest, 표준 `/var/run/docker.sock`)에서는 정상일 가능성이 높지만, **로컬에서 Green 을 못 본 채로 push 하면 "미검증"** 이다(CLAUDE.md §1).
- 2026-08-26: **근본 원인 확정 — Testcontainers 가 아니라 Docker 29 호환성 문제.** `/v1.32/info` 만 400 이고 1.40·1.44·1.47·1.54 는 전부 200 임을 소켓 직접 호출로 확인했다. docker-java 가 **API 1.32** 로 협상하는데 로컬 Docker 29.3.1 의 최소 지원이 **1.40** 이라 거부된다. 그 400 이 strategy probing 중에 발생해 "Docker 환경 없음"으로 뭉뚱그려진 것이다([testcontainers-java#11212](https://github.com/testcontainers/testcontainers-java/issues/11212) 등 다수 보고). 실패한 우회: 1.20.4→**1.21.3** 업그레이드, `DOCKER_HOST` 3종, `DOCKER_API_VERSION=1.44`, `~/.testcontainers.properties` `api.version`(원복함), Ryuk 비활성화, `--no-daemon`. Maven Central 에 2.x 좌표 없음(최신 1.21.3).
- 2026-08-26: **사용자 결정 — Testcontainers 를 빼고 외부 DB 방식으로 전환.** 오늘 V22·V23 검증에 쓴 "컨테이너를 띄우고 psql 로 검증" 이 잘 동작했으므로, DB 를 테스트가 직접 띄울 이유가 없다. 테스트는 주어진 접속정보를 쓰고 DB 는 CI(`services: postgres`)나 로컬 개발 컨테이너가 제공한다.
- 2026-08-26: **구현·로컬 검증 완료.** 테스트 3건이 실제 Postgres 에서 통과(`tests=3 skipped=0 failures=0`). **안전장치 3경로를 각각 관찰로 확인**했다 — DB 있음 → 3 실행/0 skip, DB 없음 → 3 skip/BUILD SUCCESS, DB 없음+`DB_TESTS_REQUIRED=true` → `IllegalStateException`/BUILD FAILED.
- 2026-08-26: **자체 리뷰에서 결함 1건 발견·수정** — 남의 DB 를 빌려 쓰면서 만든 데이터를 안 지웠다. `@AfterEach` 정리를 넣고 **실제로 지워지는지 관찰**했다(같은 컨테이너에 2회 연속 실행 후 `users/trading_states/trade_executions = 0/0/0`).
- 2026-08-26: **비용 측정** — 하네스 전 13s → DB 없이(skip) 14s → DB 포함 전체 16s(+컨테이너 기동 수 초). **의존성은 순증 0** — Testcontainers 를 넣었다 뺐고 `build.gradle.kts` 는 원복됐다.

# Next

push → CI 에서 이 테스트가 **실제로 실행됐는지**(skip 0) 확인 → PR. 초록불만 보고 통과로 치지 않는다.

# Decisions

- **컨텍스트는 `@DataR2dbcTest` 슬라이스** — 전체 `@SpringBootTest` 는 Upbit 클라이언트·스케줄러·엔진까지 올라와 외부 의존으로 깨지기 쉽고 느리다. 검증 대상이 R2DBC 매핑이므로 슬라이스면 충분하다. Flyway 는 슬라이스가 자동 실행하지 않으므로 **테스트에서 명시적으로 `Flyway.configure().migrate()`** 를 돌린다(우연한 자동설정에 기대지 않는다).
- **~~컨테이너는 Testcontainers 로 띄운다~~ → 외부에서 제공된 DB 를 쓴다로 변경** (이유: 위 Progress 의 Docker 29 ↔ docker-java API floor 비호환. 로컬에서 하네스가 아예 안 뜨는데 CI 통과만 믿고 넘기면 "로컬에서 아무도 못 돌리는 테스트"가 된다). DB 제공은 CI 가 `services: postgres`, 로컬은 개발용 compose 컨테이너나 임시 컨테이너가 맡는다. 부수 효과로 **Docker 버전 비호환에서 영구히 자유로워진다** — 테스트는 그냥 Postgres 에 접속할 뿐이다.
- **DB 가 없으면 skip, 단 CI 에서는 skip 금지** — 로컬에서 DB 없이 `./gradlew test` 를 돌려도 깨지지 않아야 개발이 편하다. 그러나 skip 은 "조용한 미검증"이 될 수 있으므로 CI 는 `DB_TESTS_REQUIRED=true` 로 **skip 대신 실패**하게 만든다.
- **격리는 픽스처 유니크 키로** — 외부 DB 는 기존 데이터가 있을 수 있다. 테스트마다 고유 username/ticker 를 쓰고 검증도 그 범위로 한정한다.
- **연결 정보는 `@DynamicPropertySource` 로 주입** — 시스템 프로퍼티/환경변수에서 읽어 `spring.r2dbc.url` 등에 넣는다.
- **FK 때문에 `users` 행을 먼저 넣는다** — `trading_states.user_id` 가 `users(id)` 를 참조한다. 픽스처가 사용자부터 만든다.

# Key Files

- `bot/src/test/kotlin/com/trading/bot/persistence/TradingStateRoundTripTest.kt` (신규) — 매핑 왕복·제약 검증. 베이스 클래스는 두지 않았다(첫 테스트라 추출할 공통이 아직 없다)
- `scripts/run-db-tests.sh` (신규) — 로컬용. 임시 Postgres 기동 → 테스트 → skip=0 검증 → 정리
- **의존성 변경 없음** — `bot/build.gradle.kts` 는 testcontainers 를 넣었다가 뺐고 최종 diff 에 없다. 진단용으로 만들었던 `logback-test.xml` 도 삭제했다
- `bot/src/main/kotlin/com/trading/bot/persistence/TradingStateService.kt` — 검증 대상(`:45-91` 매핑)
- `bot/src/main/resources/db/migration/V14__create_trading_states_and_drop_positions.sql` — 제약 정의
- `.github/workflows/deploy.yml` — `test` job 이 이 테스트를 돌게 된다(docker 필요)

# Blockers

없음 — 설계 변경으로 해소.

# Acceptance

- [x] **실제 Postgres 에서 마이그레이션이 돈다** — 제공된 DB 에 V1~최신 Flyway 적용 성공(이미 적용됐으면 no-op)
- [x] **매핑 왕복이 값을 보존한다** — `upsert` → `loadStates` 로 20개 필드가 되돌아온다. 타입 경계(JSON·enum·LocalDate·Instant)를 **명시값으로** 채운 픽스처로 검증(전부 null 이면 vacuous)
- [x] **`uq_trading_states_user_ticker` 가 작동한다** — 같은 (user, ticker) 두 번째 insert 가 거부되고, `upsert` 는 그 경로에서 update 로 동작
- [x] **부분 unique index 가 작동한다** — `trade_executions` 의 `exchange_order_id` 가 NULL 이면 중복 허용, non-NULL 이면 거부
- [ ] **CI 통과 + skip 되지 않음** — `services: postgres` 로 DB 가 뜨고, `DB_TESTS_REQUIRED=true` 라 skip 이 실패로 바뀐다. CI 로그에서 이 테스트가 **실제로 실행됐음**을 확인한다(초록불만 보고 통과로 치지 않는다)
- [x] **비용 보고** — 13s(전) → 14s(skip) → 16s(DB 포함). 의존성 순증 0
- [x] **문서 동기화** — README 「빌드와 테스트」에 실행법 추가. wiki 는 갱신 대상 아님(`deployment-stack` 의 test 언급은 배포 타이밍 맥락, `jdk-gradle-toolchain` 은 JDK 호환성 주제). **Testcontainers 를 피한 이유는 wiki ingest 후보** — Report 에서 제안

# Review Disposition

| finding | 처분 | 근거 |
|---|---|---|
| **자체리뷰 1** 외부 DB 를 쓰면서 테스트 데이터를 정리하지 않음 | **fix** | `@AfterEach` 로 자식→부모 순서 삭제. 개발 DB 를 가리키면 쓰레기가 누적된다. 2회 연속 실행 후 0/0/0 로 관찰 검증 |
| **자체리뷰 2** 진단용 `logback-test.xml` 이 남음 | **fix (제거)** | Testcontainers 를 뺀 뒤로는 그 로거 설정이 무의미하다. 범위 밖 추가물이라 삭제 |
| **pre-push codex P2** Gradle daemon 이 환경변수를 고정해 스크립트가 조용히 skip 될 수 있다 | **fix** | 지적이 타당하다 — daemon 이 재사용되면 `DB_TESTS_REQUIRED` 까지 안 보여 강제 실패 장치마저 무력화된다. `--no-daemon` 을 넣고, 더 강한 방어로 **스크립트가 결과 XML 의 skip=0 을 직접 검증**하게 했다(daemon 이든 오타든 모든 조용한 skip 을 잡는다). 판정 로직은 3케이스(0 skip/3 skip/0 실행)로 확인 |
| **pre-push codex P2(2차)** `cleanup` 이 고정 이름 컨테이너를 소유권 확인 없이 `rm -f` | **fix** | 동시 실행 시 남의 DB 를 죽이고 고정 포트도 겹친다. 이름에 PID 를 붙이고(`...-$$`) 포트는 동적 할당(`-p 127.0.0.1::5432` + `docker port`), 내가 띄운 경우에만 지우도록 `CTR_STARTED` 가드를 뒀다 |
| **pre-push codex P2(2차)** plan `# Key Files`·`# Goal` 이 stale | **fix** | 최종 diff 에 없는 testcontainers 의존성과 삭제한 `logback-test.xml` 을 여전히 적고 있었다. 다음 세션이 없는 변경을 쫓지 않도록 실제 구현(외부 DB 방식)에 맞춰 갱신 |
| **자체리뷰 3** Flyway 를 `@BeforeAll` 에서 돌리면 DB 테스트 클래스가 늘 때 중복 실행 | **defer** | Flyway 는 멱등이라 지금은 무해하다. 두 번째 DB 테스트 클래스가 생길 때 공통 베이스로 추출하며 함께 정리 |

# Deferred

- **Testcontainers 재도입 가능성** — Docker 29 를 지원하는 Testcontainers 가 나오면 외부 DB 방식보다 격리가 강하다. 다만 지금 방식도 CI 에서는 매번 새 DB 라 격리가 충분하고, Docker 버전 비호환에서 자유롭다는 이점이 있다
- **DB 테스트 공통 베이스 추출** — 두 번째 DB 테스트 클래스가 생기면 접속 설정·Flyway·정리를 베이스로 뺀다(지금은 하나뿐이라 추출할 공통이 없다)

# Workflow Findings

(없음)
