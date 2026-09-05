---
title: shadow-config — 그림자 관측을 실제로 켤 수 있게 전달 경로를 뚫고 회귀를 가둔다
status: done
started: 2026-09-05
updated: 2026-09-05
---

# Goal

PR #171 이 넣은 그림자 관측기(`trading.shadow-exit.*`, 기본 off)를 **운영에서 실제로 켤 수 있게** 한다.
배포 계층이 `TRADING_*` 를 이름으로 나열해야 전달하므로(#75), 그 목록에 없으면 `.env` 에 적어도 앱에 도달하지 않는다.

# Progress

- 2026-09-05 — worktree 생성(base `main@d7a1b05`). **관측기가 켜질 수 없는 상태였음을 확인** —
  `docker-compose.prod.yml` 전달 목록에 shadow 관련 이름이 없었다.
- 2026-09-05 — env 이름이 `trading.shadow-exit.*` 에 실제로 바인딩되는지 **실측**
  (`ShadowExitPropertiesBindingTest`, 실제 `SystemEnvironmentPropertySource` 사용, 3건 통과).
  Spring relaxed binding 이 `_` 를 `.`·`-` 양쪽으로 보므로 문서만으로는 확정할 수 없어 테스트로 못 박았다.
- 2026-09-05 — compose·`.env.example` 에 shadow 3종 추가.
- 2026-09-05 — **회귀 가드 신설**(`TradingEnvPassthroughTest`): `trading.*` `@ConfigurationProperties` 의
  **생성자 파라미터**를 전부 열거해 compose 전달 목록과 대조한다. 그 가드가 **기존 누락 1건을 즉시 발견** —
  `TRADING_RECONCILE_HALT_THRESHOLD`(#19 halt 임계)가 목록에 없어 **운영에서 조정 불가**였다. 함께 추가했다.

# Next

없음 — 닫혔다. 관측을 실제로 켜는 것(`.env` 에 `TRADING_SHADOW_EXIT_ENABLED=true`)은 사람이 한다.

# Decisions

## 1) 문서가 아니라 테스트로 가둔다

이 함정은 이미 두 번 났다(#75, 그리고 이번 shadow). "compose 목록에 추가할 것"을 주석·wiki 에 적는 것으로는
막히지 않는다 — 새 설정을 추가하는 사람이 그 문장을 읽는다는 보장이 없다. 생성자 파라미터를 열거해
대조하면 **추가하는 순간 테스트가 깨진다**.

## 2) 열거 대상은 생성자 파라미터다 (member property 아님)

`AccumulateProperties.params` 같은 **private 파생 캐시**는 설정 입력이 아니다. member property 로 열거하면
그런 것까지 잡혀 면제 목록이 자라고, 면제 목록이 자라면 가드가 무력해진다.

# Key Files

- `deploy/vultr/docker-compose.prod.yml` — 전달 목록(단일 소스)
- `deploy/vultr/.env.example` — 켜는 법·의미
- `bot/src/test/kotlin/com/trading/bot/config/TradingEnvPassthroughTest.kt` — 회귀 가드
- `bot/src/test/kotlin/com/trading/bot/config/ShadowExitPropertiesBindingTest.kt` — env 이름 바인딩 실측

# Blockers

없음.

# Acceptance

1. ✅ `TRADING_SHADOW_EXIT_*` 3종이 compose 전달 목록과 `.env.example` 에 있다.
2. ✅ 그 env 이름이 `trading.shadow-exit.*` 에 실제로 바인딩됨을 실제 환경변수 소스로 확인(3건, skip 0).
3. ✅ 새 `trading.*` 설정이 전달 목록에서 빠지면 테스트가 깨진다.
4. ✅ 그 가드가 발견한 기존 누락(`TRADING_RECONCILE_HALT_THRESHOLD`)을 함께 고쳤다.
5. ✅ `./gradlew build` 통과(실행 980 / skip 19 / 실패 0), wiki 검증 3종 통과.
6. ✅ 라이브 파라미터 무변경 — 기본값은 여전히 off.

# Deferred

- **관측 켜기** — 운영 `.env` 에 `TRADING_SHADOW_EXIT_ENABLED=true` 후 재배포. 사람이 결정한다. (사용자)
