---
title: risk-defaults-single-source — 리스크 파라미터 기본값 단일화 (#75)
status: done
started: 2026-08-04
updated: 2026-08-04
---

# Goal

`TRADING_*` 리스크 파라미터의 **기본값 정의처를 `TradingProperties.kt` 한 곳으로 줄인다.** 현재 4계층(kt / application.yml / deploy.sh render_server_env / docker-compose.prod.yml)에 흩어져 있고 이미 drift 가 발생해 **운영이 구값으로 도는 것으로 의심**된다. 배포 계층은 "사용자가 명시한 값만 전달"하는 통로가 되고, 아무도 지정하지 않으면 앱 기본값이 이긴다.

# Progress

- 2026-08-04: Explore 완료. drift 전모 확인 — `TRADING_TAKE_PROFIT_PCT` 는 kt/yml `5.0` vs deploy.sh·compose `2.0`, `TRADING_TRAILING_ARM_PCT` 는 kt/yml `3.0` vs deploy.sh·compose `0.0`. 배포 계층 조합(TP 2.0 = trail 2.0)은 `TradingEngine.warnIfExitConfigInert()`(TradingEngine.kt:97) 의 `takeProfitPct <= trailingStopPct` 경고 조건에 정확히 걸린다 — memory `config-defaults-multi-layer` 에 기록된 2026-07-30 실사고와 동일 구조.
- 2026-08-04: **선례 발견** — `TRADING_K_VALUE`·`TRADING_INTERVAL_SECONDS`·`reconcileHaltThreshold` 는 배포 계층에 아예 없어 앱 기본값이 그대로 적용된다. 목표 상태가 이미 repo 안에 존재하므로 나머지 11개를 같은 형태로 맞춘다.
- 2026-08-04: 사용자 결정 — ① `deploy/aws/` 도 함께 수정(삭제하지 않음) ② 배포 전 **현재 운영 서버 상태부터 확인**. SSH 조회는 권한 분류기에 차단돼 사용자 직접 실행 대기(`scratchpad/check_server_env.sh`).
- 2026-08-04: **위험도 반증** — 로컬 배포 `.env` 실측 결과 운영(vultr)은 TP 5.0·arm 3.0 이 명시돼 있어 폴백이 발동하지 않는다. 2026-07-30 사고는 `.env` 명시로 우회된 상태이며 이번 작업은 재발 방지다. 루트 `.env` 는 TP 2.0 구값 잔존, `deploy/aws/.env` 는 리스크 키가 없어 폴백 경로였다.
- 2026-08-04: codex plan-review(medium) Critical 3/Major 6/Minor 3 반영. **C2·M2·M3 는 설계 변경으로 소멸** — `env_file` 신규 파일 대신 compose `environment:` 리스트 형식(값 생략)을 쓰면 미해결 변수가 컨테이너에서 제거된다(공식 스펙 확인 + `docker compose config` 실측).
- 2026-08-04: TDD Red→Green. 첫 시도의 `withPropertyValues` 는 relaxed binding 경로를 안 타 3건 실패 → `SystemEnvironmentPropertySource` 로 교체해 통과. 이 과정 자체가 "환경변수 매핑은 property source 종류에 의존한다"를 입증했다.
- 2026-08-04: 구현 완료 — `application.yml` `trading:` 블록 제거, 3 provider `deploy.sh` 에 `append_trading_overrides`(값 형식 검증 + `set -e` 방어), compose 4종 리스트 전환, `.env.example` 4종 주석화, README·wiki 갱신.
- 2026-08-04: codex code-review(high) **P0 0** / P1 1 / P2 3 / P3 1 → 전량 처분. P2 fix 후 581 tests green, 셸·compose·단일소스 검증 재실행 통과. simplify 로 중복 테스트 1건 병합.

# Next

**코드 작업은 끝났다(PR #87 머지 → main `43a5038`, 자동 배포 3 job 전부 success).** 남은 것은 사용자 액션 1건뿐이며, 이제는 **아무 때나 안전하게** 할 수 있다(새 `deploy.sh` 에 폴백이 없으므로 순서 제약이 해소됐다):

1. `VULTR_DEPLOY_ENV` secret 에서 앱 기본값과 값이 같은 6줄 삭제 — `TAKE_PROFIT_PCT`·`MAX_LOSS_PCT`·`TRAILING_STOP_PCT`·`TRAILING_ARM_PCT`·`MAX_HOLD_DAYS`·`ROUND_TRIP_FEE_RATE`. 운영 고유값(`TICKERS` 8종·`STRATEGY`·`INVEST_RATIO 0.15`·`AUTO_START true`)은 남긴다. 지워도 값이 같아 동작 변화는 0이고, 이후 `TradingProperties.kt` 변경이 운영에 자동 반영된다.
2. (선택) `scratchpad/check_server_env.sh` 로 서버 `/opt/app/.env`·부팅 로그 확인 — 세션에서는 SSH 가 권한 분류기에 차단돼 **끝내 미검증으로 남았다**.

> 순서 제약은 머지 전에만 유효했다: 구 `deploy.sh` 의 `${TRADING_TAKE_PROFIT_PCT:-2.0}` 폴백 때문에 secret 을 먼저 지웠다면 TP 2.0 / arm 0.0 이 실제로 적용됐을 것이다. 머지가 끝나 그 위험은 사라졌다.

# Decisions

## 단일 소스 = `TradingProperties.kt` data class 기본값

`application.yml` 의 `${TRADING_X:기본값}` 도 중복 정의처다. **`trading:` 블록에서 해당 키 줄을 제거**하면 Spring Boot relaxed binding 이 환경변수 `TRADING_TAKE_PROFIT_PCT` 를 `trading.take-profit-pct` 에 직접 매핑하고, 환경변수가 없으면 data class 기본값이 남는다. 즉 정의처가 1곳으로 준다.

- ⚠️ **검증 필요 가정**: "yml 에서 키를 빼도 env 가 `@ConfigurationProperties` 에 바인딩된다" — 구현 시 테스트로 먼저 입증한다(TDD Red). 입증 실패하면 대안은 yml 유지 + 배포 계층만 정리(효과 축소, 정의처 2곳).
- `tickers`·`strategy` 처럼 문자열 키도 동일 처리한다.

## 배포 계층 = "설정된 값만 전달"

- **`render_server_env`**: `TRADING_X=${TRADING_X:-구값}` 을 없애고, 로컬 `.env` 에 그 키가 **있을 때만** 해당 줄을 서버 `.env` 에 쓴다. 없으면 줄 자체가 없다.
- **`docker-compose.prod.yml`**: `environment:` 를 **리스트 형식 + 값 생략**(`- TRADING_TAKE_PROFIT_PCT`)으로 바꾼다. 값이 `.env`/호스트에 없으면 compose 가 그 변수를 **컨테이너 환경에서 제거**한다 → 앱 기본값이 이긴다.
  - ✅ **공식 스펙으로 확인**(2026-08-04, docs.docker.com compose-file/services #environment): "If the value is not resolved, the variable is unset and is removed from the service container environment." 로컬 `docker compose config` 실측도 미설정 키를 `null` 로 렌더.
  - **`env_file` 신규 파일 안 만든다** — codex C2(업로드 원자성)·M2(env_file 은 compose interpolation 에 공급 안 됨)·M3(시크릿 경계) 가 이 형태에선 발생하지 않는다. 배포 스크립트의 업로드 대상 파일 수도 그대로다.
  - ⚠️ compose 는 `environment:` 를 map 또는 list 중 **하나만** 받는다. 현재 map 이므로 해당 서비스 블록 전체를 list 형식(`- KEY=value`)으로 변환해야 한다 — 기계적 변환이지만 diff 가 커진다.

## 빈 문자열 주입 금지

`${TRADING_X:-}` 처럼 빈 값을 넣는 방식은 채택하지 않는다. Spring 은 "빈 문자열로 정의됨"으로 보고 `Double` 바인딩에서 기동 실패할 수 있다. **줄을 아예 쓰지 않는 것**이 유일하게 안전한 형태다.

## 범위

- 대상 3경로 전부: `deploy/{aws,oci,vultr}/`(사용자 결정 — aws 유지·수정).
- `.env.example` 4개(루트·aws·oci·vultr)의 값도 앱 기본값과 일치시킨다(현재 루트만 구값 2.0/0.0).
- `README.md` 파라미터 표(148~154, 208~212행)를 앱 기본값과 대조·정정.
- 로컬 `docker-compose.yml`(개발용)은 `TRADING_TICKERS`·`STRATEGY`·`AUTO_START` 3개만 있고 리스크 파라미터가 없다 → 동일 원칙 적용 여부를 구현 시 판단(운영 영향 없음).

# Key Files

- `common/src/main/kotlin/com/trading/common/config/TradingProperties.kt` — **단일 소스**(기본값 data class)
- `bot/src/main/resources/application.yml:65-83` — `trading:` 블록, 중복 기본값 제거 대상
- `bot/src/main/kotlin/com/trading/bot/engine/TradingEngine.kt:95-109` — `warnIfExitConfigInert()`, 검증 신호원
- `deploy/{aws,oci,vultr}/deploy.sh` — `render_server_env`(vultr 141-152 / oci 155-166 / aws 108-119)
- `deploy/{aws,oci,vultr}/docker-compose.prod.yml` — `environment:` 의 `TRADING_*`
- `.env.example`, `deploy/*/.env.example` — 예시 값 정합
- `README.md:148-154, 208-212` — 파라미터 표

# Blockers

- 운영 서버 SSH 조회가 권한 분류기에 차단됨 → 사용자 직접 실행 필요. 구현을 막지는 않으나 "사고 재현 입증"은 이 결과에 의존한다.

# Acceptance

- [x] **env 없이 앱 기본값**: `TradingPropertiesBindingTest` — 전 14필드가 data class 기본값(TP 5.0 / trail 2.0 / arm 3.0 …)
- [x] **env 있으면 오버라이드**: 14개 `TRADING_*` 전부를 `SystemEnvironmentPropertySource` 로 주입해 오버라이드 확인. **relaxed binding 가정 입증됨** — `withPropertyValues` 로는 재현 안 되고 환경변수 property source 여야 매핑된다는 것도 함께 확인
- [x] **경고 무발화**: 기본값 조합이 `warnIfExitConfigInert()` 의 두 조건(`TP<=trail`, `TP<=arm`)을 모두 벗어남을 단언
- [x] **빈 문자열 거부**: 빈 값 주입 시 `BindException` + `'trading.take-profit-pct'` 로 기동 실패 — "줄을 아예 안 쓴다" 설계의 근거를 테스트로 고정
- [x] **렌더 결과**: `append_trading_overrides` 격리 실행 — 값 2개만 설정 시 2줄, 아무것도 없으면 0줄 + `set -e` 하에서 rc=0(deploy-script 함정 회귀 방어). 3 provider 모두 `bash -n` 통과
- [x] **compose 미주입**: `docker compose config` 실측 — `.env` 에 있는 키만 값 전달, 없는 키는 `null`(= 컨테이너에서 제거). vultr 11전달/3미주입, aws 3전달/11미주입, oci 0전달/14미주입
- [x] **정의처 grep(전 14키)**: `verify_single_source.sh` — 실행 경로(`application.yml`·compose 4종·deploy.sh 3종)에 기본값 정의 0건
- [x] **빌드·테스트**: JDK 21 `./gradlew build` — **581 tests, 0 failures**
- [x] **문서 동기화**: `README.md` 표 값이 앱 기본값과 일치(drift 없었음) + 단일 소스 경로·배포 주의 추가. `wiki/pages/entity/deployment-stack.md` 배포 계약 갱신, wiki 검증 3종 통과(link clean / 28 pages / smoke 10-0)
- [x] **배포**: PR #87 머지(main `43a5038`) → CI/CD 워크플로 `test`·`build-and-push`·`deploy-vultr` 3 job 전부 success
- [ ] **운영 반영 마무리**: `VULTR_DEPLOY_ENV` secret 6줄 정리 + 서버 `.env`·부팅 로그 확인 — **사용자 액션 필요, 미완**(SSH 차단으로 세션에서 검증 불가)

# Review Disposition

codex plan-review (2026-08-04, effort=medium) — Critical 3 / Major 6 / Minor 3.

| # | finding | 처분 |
|---|---|---|
| C1 | 기존 운영 `.env` 의 구값이 계속 우선 — 마이그레이션 정책 부재 | **fix** — 로컬 `.env` 실측으로 **위험 반증**: 운영(vultr)은 TP 5.0·arm 3.0 이 이미 명시돼 정상값. 사용자 결정으로 **앱 기본값과 동일한 6줄은 삭제**해 위임하고, 운영 고유값(INVEST_RATIO 0.15·TICKERS 8종·STRATEGY·AUTO_START)은 보존 |
| C2 | `env_file` 업로드 원자성·롤백 미정의 | **해소(설계 변경)** — `env_file` 신규 파일을 안 만드는 리스트 형식으로 전환해 문제 자체가 소멸 |
| C3 | `BacktestConfig` 가 같은 파라미터 기본값을 따로 정의 | **defer(사용자 결정)** — 이번 범위 제외. 백테스트는 의도적으로 독립 실험 파라미터를 쓴다. parity 테스트로 감시 유지, 제외 사유를 여기 명시 |
| M1 | 단위 테스트로는 relaxed binding 보증 못 함 | **fix** — `ApplicationContextRunner`/`Binder` 테스트로 acceptance 고정(env 없음 / 일부만 / 오버라이드) |
| M2 | `env_file` 은 compose interpolation 에 공급 안 됨 | **해소** — C2 와 동일(설계 변경으로 소멸) |
| M3 | `app-trading.env` 의 변수 범위 불명확 | **해소** — 파일을 만들지 않음 |
| M4 | GitHub Actions `VULTR_DEPLOY_ENV` 경로 미반영 | **fix** — 운영 `.env` 는 Actions secret 에서 생성되므로 **secret 갱신이 사용자 액션으로 필요**. Report 에 명시하고 README 에 절차 기록 |
| M5 | 이미지/compose 조합별 위험이 acceptance 에 없음 | **fix(축소)** — Docker daemon 미가동으로 실컨테이너 검증 불가. `docker compose config` 정적 검증 + 조합별 위험 서술을 acceptance 로. 실기동 확인은 배포 관찰로 이관 |
| M6 | 로컬 compose·API 경로 parity 미입증 | **fix** — 루트 `docker-compose.yml` 도 같은 형식으로 전환하고 `StrategyController` 의 `TradingProperties` 폴백 경로를 테스트로 확인 |
| m1 | grep acceptance 가 키 1개만 검사 | **fix** — 대상 11개 키 전량 검사 스크립트로 |
| m2 | render acceptance 대상 파일이 Decisions 와 충돌 | **해소** — `app-trading.env` 폐기로 서버 `.env` 단일 대상 |
| m3 | provider README 3종 동기화 누락 | **fix(축소)** — `.env.example` 3종에 계약+소스 경로를 넣고 루트 README 에 배포 주의를 명시. provider README 는 `.env.example` 을 가리키므로 중복 서술하지 않음 |

## code-review (codex, 2026-08-04, effort=high) — P0 0 / P1 1 / P2 3 / P3 1, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P1 | CI `VULTR_DEPLOY_ENV` secret 에 구 `TRADING_*` 가 남아 있으면 계속 덮어쓴다 | **fix(문서·절차)** — 코드로는 해결 불가(secret 은 내가 못 본다). README 에 "앱 기본값에 위임할 키는 secret 에서도 지울 것" 명시 + `# Next` 와 Report 에 사용자 액션으로 올림 |
| P2-a | `printf` 가 dotenv escaping 없이 값을 씀 — 개행·`#`·`$`·따옴표가 `.env` 를 깨거나 보간을 바꿈 | **fix** — 값 형식 검증(`^[A-Za-z0-9._,-]+$`) 추가, 위반 시 배포 중단. 이 키들은 숫자·boolean·티커 CSV·전략명뿐이라 충분 |
| P2-b | `README.md:222` 가 "앱 기본값은 `application.yml`" 이라 이번 변경과 모순 | **fix** — `TradingProperties.kt` 로 정정 |
| P2-c | 테스트가 `hasFailed()` 만 보고 일부 필드만 검증 | **fix** — 실패를 `BindException` + `'trading.take-profit-pct'` 스택으로 특정하고, 14개 키 전량의 오버라이드·기본값 검증 추가. rootCause 는 `IllegalArgumentException`(primitive null) 이라 체인 단언으로 교정 |
| P3 | `.env.example` 주석화로 신규 사용자가 값을 모름 | **fix** — 값 중복 없이 `TradingProperties.kt` 경로 + README 표 포인터를 4개 파일에 추가 |

## pre-push codex review (2026-08-04, high) — P2 1건, 미해결 0

| # | finding | 처분 |
|---|---|---|
| P2 | 값 검증 정규식이 공백을 거부해 `TRADING_TICKERS="KRW-BTC, KRW-ETH"` 형태로 배포가 즉시 실패. `tickerList()` 는 `split(",").map { it.trim() }` 이라 그 형태를 지원한다 | **fix** — 패턴에 공백 추가(`^[A-Za-z0-9._, -]+$`). bash `[[ =~ ]]` 는 따옴표로 감싼 패턴을 리터럴로 보므로 `TRADING_VALUE_PATTERN` 변수에 담아 사용. 개행·`$()`·`#` 은 여전히 거부됨을 회귀 테스트로 고정(개행 거부는 `.env` 에 `DB_PASSWORD=` 를 주입하는 경로를 막는다) |

# Deferred

- **`BacktestConfig` 기본값 이중 정의**(codex C3, 사용자 결정으로 범위 제외): `bot/.../engine/BacktestEngine.kt` 의 `BacktestConfig` 가 `takeProfitPct`·`trailingStopPct`·`trailingArmPct`·`maxHoldDays` 기본값을 따로 갖는다. `TradingProperties` 를 바꿔도 자동 동기화되지 않는다. 백테스트는 독립 실험 파라미터를 쓰는 것이 의도이므로 통합하지 않되, parity 테스트가 drift 를 잡는지 확인할 것. 후속 이슈 제안 대상.

# Workflow Findings

- **운영 서버 SSH 조회가 권한 분류기에 차단**(2026-08-04): 읽기 전용 `grep "^TRADING_" /opt/app/.env` 조회도 막혀 사용자 직접 실행으로 우회했다. 배포형 repo 에서 "재배포 후 실제 상태 확인"(memory `config-defaults-multi-layer`)이 규약인데 그 확인 경로가 세션에서 닫혀 있다 — 반복되면 settings 의 Bash 허용 규칙 추가를 검토할 것.
