---
title: trailing-defaults-align — env 누락 시 조용한 청산규칙 회귀를 예방+탐지로 막는다 (#179)
status: done
started: 2026-09-06
updated: 2026-09-08
---

# Goal

운영 청산 파라미터가 코드 기본값과 다른 상태에서 **env 한 줄이 사라지면 봇이 조용히 다른 청산 규칙으로
거래하는** 위험을 없앤다. 이슈가 제안한 "코드 기본값 이전"이 아니라 **배포 앞단 차단 + 앱 탐지**로 푼다.

# Progress

- 2026-09-06: Explore 완료. `baselinePoint()` 가 값을 하드코딩하고 `toConfig()` 가 trailing 을 명시 전달함을
  확인(이슈의 금지 조항은 만족 가능). `BacktestEngineTest:185` 의 라이브↔백테 parity 가드 발견.
- 2026-09-06: 이슈안대로 1차 구현·검증(기본값 이전 + 골든 재생성). 990건/skip 21/실패 0.
- 2026-09-06: **plan-reviewer 가 이슈 전제를 반증했고 직접 확인해 사실로 판정.** `/backtest` 는
  `BacktestConfig()` 기본값에 도달하지 않는다 — `StrategyController.kt:75-87` 이 모든 필드를
  `tradingProperties`(env 바인딩 빈)로 폴백한다. 운영 `/backtest` 는 이미 1.5/0 이다.
  추가로 `BacktestConfig()` 기본값을 상속하는 **공표 측정 4건**이 계획에 없었다.
- 2026-09-06: 사용자 판단으로 **fail-fast 선회**. 1차 구현 전부 revert. 기동 차단 가드를 TDD 로 구현.
- 2026-09-08: **code-reviewer + Codex 병행이 그 기동 차단 설계를 반증.** 직접 확인해 둘 다 사실 —
  ① `UserTradingManager.startBot():217` 이 `autoStart` 를 보지 않아 `/api/bot/start` 가 가드를 우회한다
  (내가 KDoc 에 쓴 불변식이 거짓이었다) ② 루트 `docker-compose.yml:20-22` 는 청산 키를 전달할 방법이
  없어 `AUTO_START=true` 면 영구 crash loop. 더 근본적으로 기동을 막으면 **보유 포지션의 손절·트레일링이
  전혀 평가되지 않는 공백**이 생기는데, 기동 실패는 Discord 로도 안 나가고(appender 는 ready 이후 attach)
  자동 롤백은 이미지만 되돌려 결손 `.env` 로 거래를 재개시킨다. 막으려던 손해보다 크다.
- 2026-09-08: simplify(파생 로직의 죽은 방어 코드 제거) 후 커밋 `78e7e44` → PR 로 main 머지.
- 2026-09-08: 사용자 판단으로 **예방+탐지**로 재설계. `preflight_exit_params`(업로드 전 차단) +
  `ExitParamsDeclarationCheck`(로그·ERROR, 차단 없음). preflight 를 실제 운영 `.env` 로 통과 확인하고
  결손·빈값·autoStart off 4케이스로 동작 확인. 전체 1001건 / skip 21 / 실패 0.

# Next

없음 — 머지로 종결. **#179 는 열어 둔다**(기본값 이전은 #184 로 1.5 가 확정된 뒤).

# Decisions

- **기본값을 옮기지 않는다** (2026-09-06 전환). 근거 넷 —
  1. `/backtest` 는 `BacktestConfig()` 기본값에 도달하지 않는다(`StrategyController.kt:75-87`).
     이슈 본문의 "파라미터 없이 호출하면 라이브와 다른 설정으로 돈다"는 성립하지 않는다.
  2. 기본값을 옮기면 `BacktestConfig()` 를 상속하는 **공표 측정 4건**의 정의가 조용히 바뀐다
     (`YearlyStrategyComparison.kt:66` · `DailyResetCounterfactualTest.kt:53-60` ·
     `KneeStrategyComparisonTest.kt:45,48` · `M1ReplayBiasTest.kt:66`) — 이슈가 `baselinePoint()` 에
     대해 경고한 바로 그 함정인데 이 넷은 놓쳤다.
  3. `wiki/pages/concept/backtest-engine.md:54` 가 "기본값을 바꾸면 안 된다"를 현행 규범으로 든다.
  4. 승격값 1.5 는 #184 로 재판정 대기 — 지금 박으면 그때 골든을 또 재생성해야 한다.
- **차단은 배포 앞단, 앱은 탐지만** (2026-09-08 전환. 이유: 기동 차단은 보유 포지션의 청산 평가를 통째로
  없애고, 그 실패는 Discord 로도 안 나가며, 자동 롤백이 결손 `.env` 로 거래를 재개시킨다).
  `deploy.sh` 는 `.env` 를 **업로드하기 전에** 검사하므로 결손 설정이 서버에 닿지 않는다 — 롤백이
  `.env` 를 되돌리지 않는다는 문제도 함께 닫힌다.
- **앱 탐지는 `autoStart` 와 무관하게 돌고 수동 기동 경로에도 건다**(`startBot`). `autoStart` 는 기동 시
  복원 여부만 정할 뿐 거래 시작 조건이 아니다 — 이건 내 오판이었다. 배포 preflight 만 `AUTO_START=true`
  를 게이트로 쓴다(그 시점엔 실제로 자동 거래 배포다).
- **검사는 값이 아니라 선언 여부만 본다.** 특정 값을 강제하면 운영이 값을 바꿀 때마다 코드를 고쳐야 하고,
  그게 지금 없애려는 결합이다. 테스트로 이 계약을 고정했다.
- **요구 키는 `TradingProperties` 프로퍼티에서 파생한다** — 문자열로 적으면 rename 시 옛 키가 계속
  선언돼 있어 검사가 조용히 무력해진다(`TradingEnvPassthroughTest` 와 같은 이유).
- **`chartExitEnabled` 도 요구 목록에 넣는다** — 차트 청산 분기를 직접 켜고 끄므로 같은 침묵 드리프트
  대상이다. 운영 `.env` 에 이미 존재함을 확인해 다음 배포가 막히지 않음을 실측했다.
- **#179 는 닫지 않는다.** 이 작업은 위험을 제거할 뿐 기본값 불일치 자체는 남긴다.
  기본값 이전은 #184 로 1.5 가 확정된 뒤 한 번만 하는 것이 옳다.

# Key Files

- `deploy/vultr/deploy.sh:213` — `preflight_exit_params`, 업로드 **전** 차단(실질 방어선)
- `bot/src/main/kotlin/com/trading/bot/config/ExitParamsDeclarationCheck.kt` — 탐지(신규, 차단 없음)
- `bot/src/test/kotlin/com/trading/bot/config/ExitParamsDeclarationCheckTest.kt` — 계약 11건(신규)
- `bot/src/main/kotlin/com/trading/bot/engine/UserTradingManager.kt:70,238` — 수동 기동 경로 보고
- `docker-compose.yml` — 청산 6키 전달 추가(루트 compose 는 전달 경로 자체가 없었다)
- `README.md` · `.env.example` · `deploy/vultr/.env.example` — "전부 선택" 서술 정정
- `wiki/pages/concept/trading-engine-loop.md` — 표 뒤로 설명 이동(표를 쪼개고 있었다) + sources·verified
- (불변) `TradingProperties.kt` · `BacktestEngine.kt` · `legacy-golden.txt` · `StrategySearchGrid.kt`

# Blockers

없음.

# Acceptance

- [x] preflight: 자동매매 + 6키 전부 선언 → 통과 / 한 줄 누락 → 배포 중단(누락 키 명시) /
      키는 있으나 **빈 값** → 중단 / `AUTO_START=false` → 검사 생략. 4케이스 실측
- [x] preflight: **실제 운영 `deploy/vultr/.env`** 로 통과 확인(다음 배포가 막히지 않음)
- [x] 앱 검사: 키를 하나씩 빼는 parameterized 테스트로 6키 전부 고정
- [x] 앱 검사는 선언 여부만 본다 / **예외를 던지지 않는다**(거래를 막지 않는 것이 계약)
- [x] 요구 키가 `TradingProperties` 프로퍼티에서 파생돼 실제 바인딩 키와 일치
- [x] 수동 기동(`/api/bot/start`) 경로에도 보고가 걸린다
- [x] 루트 `docker-compose.yml` 이 청산 6키를 전달한다
- [x] 기본값·골든·공표 측정을 건드리지 않는다
- [x] `./gradlew build --rerun-tasks` — 실행 1001건 / skip 21건 / 실패 0건 (baseline 990 + 신규 11)
- [x] README·`.env.example` 2곳·wiki 동기화 + wiki 검증 3종 통과 + `bash -n deploy.sh`
- [ ] [post-merge] 배포 로그에 "청산 파라미터 6개 선언 확인" 과 기동 로그의 실효값 줄 확인

# Review Disposition

code-reviewer + Codex 병행(이번엔 Codex 크레딧 가용). Critical 2 · Major 4 · Minor 7 · Nit 4.

- **fix** — C1 기동 차단이 청산 공백 + 무알림을 만든다 / C2 자동 롤백이 결손 `.env` 로 거래를 재개시킨다
  → 설계를 예방(preflight)+탐지(로그·ERROR)로 교체. 기동 차단을 없앴다.
- **fix** — M1 `autoStart` 가 거래 시작 조건이 아니라 `/api/bot/start` 가 우회 → 앱 검사를 `autoStart` 와
  분리하고 `startBot` 에도 보고. 거짓 불변식을 적은 KDoc 삭제.
- **fix** — M2 루트 compose 가 청산 키를 전달 못 함 → 6키 추가.
- **fix** — M3 테스트가 5키 중 3키를 고정 못 함 → 키를 하나씩 빼는 parameterized 테스트로 교체.
- **fix** — M4 `.env.example` 2곳·`README.md` 가 "전부 선택" 이라 새 계약과 모순 → 정정.
- **fix** — Minor: wiki blockquote 가 표를 쪼갬(렌더 실측 지적) / `REQUIRED_KEYS` rename 드리프트 →
  프로퍼티 참조로 파생 / `chartExitEnabled` 누락 → 포함 / wiki `verified` provenance 추가.
- **fix(nit)** — `InitializingBean` → repo 관례인 `@PostConstruct`.
- **defer** — 청산 파라미터 값 검증 부재(음수·NaN) → `# Deferred`. 기존 결함이고 "값은 안 본다" 는 결정.
- **defer** — KIS `StockUserTradingManager` 의 `@PostConstruct` 가 컨텍스트 refresh 중 코루틴을 띄워
  검사와의 순서가 보장되지 않는다(PLAUSIBLE, 미검증). 차단을 없앴으므로 실해는 없고 보고만 늦을 수 있다.
- **refuted(리뷰어 자체 반증)** — 빈 문자열 env 우회는 성립하지 않는다(바인딩이 기동을 실패시키고
  `deploy.sh:186` 이 빈 값을 `.env` 에 쓰지 않는다). preflight 도 `^KEY=.` 로 빈 값을 잡는다.

# Deferred

- **청산 파라미터에 값 검증이 없다** — `TradingProperties.init` 은 `reconcileHaltThreshold`·
  `intervalSeconds` 만 본다. `TRADING_MAX_LOSS_PCT=-5` 는 `deploy.sh` 패턴·바인딩·선언검사를 모두
  통과한 뒤 `PositionManager` 에서 신규 포지션을 즉시 매도시킨다. `NaN` 은 비교를 전부 false 로 만들어
  해당 게이트를 죽인다. 심각도 중, 기존 결함(이번 변경과 무관). (code-reviewer + Codex 합의)
- KIS 국내주식 경로(`StockPositionManager.kt:158-168`)는 `ExitParamsSnapshot` 을 쓰지 않아 보유 중
  전역 파라미터 변경이 즉시 반영된다. Upbit 은 `PositionManager` 가 진입 시점 스냅샷을 고정한다(#177).
  이 비대칭은 이번 범위 밖 — 심각도 중, 별도 판단 필요. (plan-reviewer 지적)
