---
title: env-passthrough-gap — 전달 화이트리스트가 둘이었다, 가드가 한쪽만 봤다
status: done
started: 2026-09-05
updated: 2026-09-05
---

# Goal

관측을 켜려다 발견한 것을 고친다. PR #172 가 compose 전달 목록을 고쳤지만 **`deploy.sh` 의
`TRADING_OVERRIDE_KEYS` 라는 두 번째 화이트리스트**가 있고, 거기 없으면 서버 `.env` 에 줄 자체가 안 써진다.
즉 관측은 **여전히 켜지지 않는 상태**였고, PR #172 의 가드는 compose 만 봐서 이를 놓쳤다.

# Progress

- 2026-09-05 — 관측 활성화 경로를 추적하다 `deploy.sh:164` 의 `TRADING_OVERRIDE_KEYS` 를 발견.
  shadow 3종과 `TRADING_RECONCILE_HALT_THRESHOLD` 가 빠져 있었다(후자는 PR #172 가 compose 에만 추가했다).
- 2026-09-05 — 두 목록 모두 채우고, 가드를 **두 축 모두 검사**하도록 재작성.
- 2026-09-05 — **변이 검사로 가드가 실제로 잡는지 확인**. 첫 편집은 문자열 치환이 조용히 실패해
  테스트가 compose 만 보던 상태였고 그대로 통과했다 — 변이 검사가 없었으면 못 잡았을 부분이다.
  파일을 다시 쓰고 deploy.sh 축 변이 주입 → **CAUGHT**.

# Next

없음 — PR #174 로 닫혔다. 관측을 켜는 것은 `VULTR_DEPLOY_ENV` 시크릿 갱신이 필요하고 그건 사람이 한다(아래 Decisions 2).

# Decisions

## 1) 가드는 두 목록을 **모두** 봐야 한다

값이 앱에 닿으려면 `deploy.sh`(서버 `.env` 에 쓸지)와 compose(컨테이너에 넘길지)를 둘 다 통과해야 한다.
한쪽만 검사하는 가드는 "통과했으니 켜진다"는 잘못된 확신을 준다 — 이번이 정확히 그 사례다.

## 2) 관측 활성화는 내가 할 수 없다 — GitHub 시크릿이 필요하다

CI 배포는 서버 `.env` 를 **`VULTR_DEPLOY_ENV` 시크릿에서 전부 다시 렌더링**한다(`render_server_env` 가 `cat >` 로 덮어쓴다).
따라서 로컬 `.env` 만 고쳐 로컬 배포하면 **다음 CI 배포에서 조용히 꺼진다**. 시크릿은 write-only 라
현재 값을 보존한 채 한 줄만 추가할 방법이 없다. → 사람이 GitHub 설정에서 갱신해야 한다.

# Key Files

- `deploy/vultr/deploy.sh` — `TRADING_OVERRIDE_KEYS`(두 번째 화이트리스트)
- `deploy/vultr/docker-compose.prod.yml` — `environment:`(첫 번째)
- `bot/src/test/kotlin/com/trading/bot/config/TradingEnvPassthroughTest.kt` — 두 축 가드

# Blockers

없음.

# Acceptance

1. ✅ shadow 3종 + `TRADING_RECONCILE_HALT_THRESHOLD` 가 **두 목록 모두**에 있다.
2. ✅ 가드가 두 목록을 각각 대조하고, 파싱 실패 시 조용히 통과하지 않는다(하한 검사).
3. ✅ **변이 검사로 deploy.sh 축이 실제로 CAUGHT** 됨을 확인.
4. ✅ `./gradlew build` 통과, wiki `deployment-stack` 동기화.
5. ✅ 라이브 무변경.

# Deferred

- **관측 켜기** — `VULTR_DEPLOY_ENV` 시크릿에 `TRADING_SHADOW_EXIT_ENABLED=true` 추가. 사람만 가능. (사용자)
