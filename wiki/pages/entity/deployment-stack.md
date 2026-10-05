---
title: 배포 스택 — Vultr 서울 + Caddy TLS + GHCR
category: entity
created: 2026-07-28
updated: 2026-10-03
claim_state: current
verified: 2026-10-03 — Caddyfile 반영 시점을 `deploy.sh`(scp 뒤 `compose pull`·`up -d` 뿐, e2e 실패는 WARN 후 exit 0)와 compose(`caddy:2-alpine`·`pull_policy: always`·단일 파일 bind mount) 원문으로 확인 · 2026-10-03 — `TradingEnvPassthroughTest` 의 양방향·이름만 단언을 변이 검사로 확인했다 — compose·`deploy.sh` 에 남은 가짜 키, 주석 처리한 실제 키, 값 붙은 compose 항목은 실패로 잡고, 배열 안 주석의 키·괄호는 통과한다(옛 정규식은 앞의 셋을 놓치고 주석 속 괄호에서 배열을 잘라 읽었다) · 2026-10-03 — Redis 제거를 `RateLimitFilter`·compose·`application-prod.yml` 원문과 `RateLimitFilterTest`(고정 시계 행동 테스트)로 확인했다. 필터 순서(Security 가 먼저 — 비인증 일반 API 는 401)는 로컬 컨테이너에서 비인증 `/api/bot/status` 가 401·필터 미도달인 것으로 관찰했다. 롤백 경로(옛 이미지 b8d227e + redis 없는 compose, `deploy.sh:593` 과 같은 `up -d --remove-orphans --pull missing`)를 로컬에서 재현해 redis 컨테이너 제거·볼륨 잔존·health UP 5s·강등 WARN 1줄·인증 경로 31번째 429 를 관찰했다 · 2026-10-01 — preflight 의 필수 선언 키가 5개(차트 청산 키 제거, `preflight_exit_params.sh` `EXIT_PARAM_KEYS`)임을 확인 · 2026-09-30 — 웹 서버 전 동기 예열(#280)을 `RateLimitFilter` 원문과 `RateLimitFilterTest` 로 확인했다. 실제 `GenericApplicationContext` refresh 에서 반응형 웹 서버 phase 대역이 시작될 때 연결이 이미 맺어져 있고, ping 이 실패해도 refresh 가 성공한다. 상한 초과 때 진행 중 호출을 interrupt 하지 않는다. 최소 Boot 반응형 앱과 로컬 redis:7(새 JVM 3회) 실측에서 `연결 준비`(155~181ms)가 `Netty started`·`Started` 보다 먼저 찍혔고, 요청 전에 연결 수가 1→2 가 됐으며, 첫 요청은 새 연결 없이 강등 WARN 0 이었다. 웹 서버 phase 는 Boot 3.4.1 `WebServerStartStopLifecycle` 소스 확인, 운영 첫 연결 2479ms 는 배포 `0cad9c3` 로그 · 2026-09-30 — 기동 예열(#278)을 `RateLimitFilter` 원문과 `RateLimitFilterTest`(예열·판정이 한 공유 연결을 쓰는 fake — 예열이 없으면 첫 요청이 강등되는 운영 결함을 재현)로 확인. 로컬 redis:7 새 JVM 실측에서 예열이 판정과 같은 연결을 맺어 첫 요청이 약 200ms→10ms(연결 수 그대로). 기동 뒤 첫 판정 강등은 운영 배포 `4d271cf` 로그로 관찰, Lettuce 재연결 WARN 은 lettuce 6.4.1 `ConnectionWatchdog` 소스 확인 · 2026-09-30 — Redis 장애 비결합(#229)을 `RateLimitFilter`·`application-prod.yml`·compose 원문과 `RateLimitFilterTest`·`RedisProdSettingsTest`(prod 프로필 로드·compose 파싱)로 확인, 로컬 redis:7 로 pause·stop·닫힌 포트·블랙홀 주소를 재현해 요청이 타임아웃(≈500ms) 안에 통과하고 WARN 1줄·복귀 INFO 가 남는 것을 관찰 · 2026-09-30 — 업로드 전 preflight(#230)를 `deploy.sh`·`preflight_exit_params.sh` 원문과 `ExitParamsPreflightScriptTest` 로 확인, 운영의 렌더된 `.env` 에 새 스크립트를 읽기 전용으로 돌려 통과(서버 bash 5.2·GNU Awk 5.2) · 2026-08-24 — **live Actions 배포를 처음으로 실제 관찰**(2026-08-23 03:40:44 KST, 앱 로그 `Successfully applied 1 migration ... now at version v21`). push 트리거와 stale 가드는 `.github/workflows/deploy.yml:61,75-87` 원문 확인. 배포 계층 기본값 제거(#75)는 2026-08-04 `docker compose config` 실측분, 인프라 구성은 2026-08-02 확인분 유지 · 2026-08-26 — 문서 전용 push 필터 도입, stale 가드를 코드 diff 기준으로 전환. 계기는 plan 커밋 `bcef6ec` 이 봇을 재시작시킨 일이고, 같은 실행 이력(`d21a3eb` skipped / `bcef6ec` success)이 자가치유 전제를 실증했다
sources:
  - bot/src/main/kotlin/com/trading/bot/config/RateLimitFilter.kt
  - bot/src/main/resources/application-prod.yml
  - bot/src/test/kotlin/com/trading/bot/config/TradingEnvPassthroughTest.kt
  - PROJECT_ANALYSIS.md
  - deploy/vultr/
  - .github/workflows/deploy.yml
  - README.md
---

# 배포 스택

| 계층 | 구성 |
|---|---|
| 호스트 | Vultr 서울(`icn`) **vc2-1c-2gb** (x86_64, 2GB) |
| 컨테이너 | Docker Compose (app + postgres + caddy) |
| TLS | **Caddy 2** + Let's Encrypt, sslip.io 자동 도메인 |
| 이미지 | GitHub Actions → **GHCR** (multi-arch push) |
| 배포 | GitHub Actions `main` push → GHCR → SSH → `deploy/vultr/deploy.sh deploy` |

> `verified` 주의: 위 구성과 2026-08-01 운영 상태는 저장소와 실제 `status`/HTTPS 확인으로 대조했고, 2026-08-02 자동 배포 workflow의 YAML·embedded shell 계약도 정적으로 검증했다. live Actions 배포 결과는 merge 후 별도로 관찰해야 한다.
> 배포 판은 Vultr 하나다. AWS 는 2026-07-31 에 자원을 삭제했고 OCI 는 프로비저닝하지 않았으며, 두 판(`deploy/aws`·`deploy/oci`)은 2026-10 에 저장소에서도 지웠다(옛 내용은 git 이력).

## 이 구성이 나온 이유

각 선택이 사고에서 나왔다:

- **TLS 종단(Caddy)** — prod 프로파일이 항상 `Secure` 쿠키를 발급해 평문 HTTP 에서는 브라우저 로그인이 원천적으로 불가능했다([[lesson-secure-cookie-http]]).
- **2GB 인스턴스** — 이전 EC2의 컨테이너 실측 818MiB를 근거로 rightsizing 했다. 지금 제한 합계는 caddy 96m + app 832m + postgres 512m = 1440m 다(redis 를 뺀 뒤). 예전 문서의 "1472m" 는 caddy 를 64m 로 적은 값이다 — compose 의 caddy 는 Vultr 이전 때부터 96m(실측 27MiB 에 64m 는 빠듯해 올림)라 당시 실제 합계는 1504m 였다.
- **x86_64(Vultr)** — AWS arm64 에서 전환했다. GHCR 이미지와 공식 의존성 이미지가 multi-arch 라 그대로 돈다(arm64 빌드는 로컬 Mac 의 루트 compose 가 같은 GHCR 이미지를 받으므로 남겼다).

## 배포 시 주의

- **배포 계층은 앱 설정의 기본값을 갖지 않는다**(#75, 2026-08-04). `deploy.sh` 의 `render_server_env` 는 로컬 `.env` 에 실제로 설정된 `TRADING_*` 만 서버 `.env` 에 쓰고, compose 는 그 키들을 **값 없이 이름만**(`- TRADING_TAKE_PROFIT_PCT`) 선언한다 — 값이 해결되지 않으면 compose 가 변수를 컨테이너에서 제거하므로 `TradingProperties` 기본값이 적용된다. 배포 스크립트에 `${VAR:-기본값}` 폴백을 되살리면 앱 기본값과 갈려 2026-07-30 사고가 재발한다.
  ⚠️ **반대 방향의 함정도 있다** — compose 목록에 **이름을 안 적으면** `.env` 에 적어도 컨테이너에 도달하지 않아
  **조용히 기본값으로 돈다**(켰다고 믿는데 안 켜진 상태). 2026-09-05 에 `TRADING_RECONCILE_HALT_THRESHOLD` 가
  그 상태였음이 발견됐다(운영에서 조정 불가). **전달 화이트리스트는 둘이고 둘 다 통과해야 한다** — `deploy.sh` 의 `TRADING_OVERRIDE_KEYS`(서버 `.env` 에 쓸지)와
  compose 의 `environment:`(컨테이너에 넘길지). 2026-09-05 에 compose 만 고치고 `deploy.sh` 를 빠뜨려 **세 번째** 같은 실패가 났다.
  이제 `TradingEnvPassthroughTest` 가 `trading.*` `@ConfigurationProperties` 생성자 파라미터를 전부 열거해
  **두 목록 모두와 양방향으로** 대조한다 — 새 설정이 목록에 없거나, 지운 설정의 키가 목록에 남으면 깨진다. compose 의
  `TRADING_*` 에 값이 붙어도 깨진다(이름만 — #75). `deploy.sh` 배열은 줄마다 `#` 뒤를 지운 뒤 읽으므로 배열 안 설명
  주석의 키는 목록이 아니고, 주석 처리한 키는 빠진 키로 잡힌다.
  테스트는 목록만 본다 — **실제로 켜졌는지는 기동 로그로 확인한다**(예: `[shadow-exit] 관측 on/off`).
  전달 계층이 넷(앱 기본값 → 시크릿 → 서버 `.env` → compose → 컨테이너)이라, 한 곳만 봐서는 알 수 없다.
- **API rate limit 은 앱 메모리 카운터다** — Redis 는 2026-10 에 뺐다(그 전에는 이 카운터 전용이었다). `RateLimitFilter` 가
  클라이언트 IP(Caddy 가 덮어쓴 `X-Forwarded-For` 첫 값, 없으면 접속 주소)별로 1분 고정 창(UTC 정분)을 센다 — 인증 경로
  (`/api/auth/**`) 30/분, 일반 API 60/분, 넘으면 429 + `Retry-After: 60`. Spring Security(order -100)가 이 필터보다 먼저 돌아
  비인증 일반 API 요청은 401 로 끝나므로, 일반 API 한도는 로그인한 요청에 걸린다. 로그인 무차별 대입 상한은 인증 경로 한도다.
  카운터는 인스턴스마다 따로이고 재시작·배포 때 0 이 된다 — 단일 인스턴스 전제다. 창은 UTC 정분 버킷이다 — 직전 분의 늦은
  요청은 지금 창에 세고, 벽시계가 뒤로 가 두 분 이상 이전인 분이 되면 창을 새로 시작한다(창이 미래 분에 멈추면 시계가 따라잡을
  때까지 잠긴다). 직전 분으로의 역행이면 그 창이 최대 약 2분 이어질 수 있다.
  Redis 시절의 장애 비결합·기동 예열(#229·#278·#280) 서술은 이 페이지의 git 이력에 있다.
- **렌더한 `.env` 는 업로드 전에 preflight 를 거친다**(#179, #230). `deploy.sh` 가 `deploy/vultr/preflight_exit_params.sh` 를
  부른다 — 청산·주문 파라미터의 형식·의미상 구간은 자동매매 여부와 무관하게, 자동매매 배포면 청산 5개 키의 선언까지 본다.
  앱이 아니라 여기서 막는 이유: 기동을 실패시키면 보유 포지션의 청산이 평가되지 않는 공백이 생기고, 자동 롤백은 이미지만
  되돌려 같은 `.env` 로 다시 기동한다. 형식은 앱(Spring)보다 좁고(평범한 소수·소문자 `true`/`false`), 위반 메시지는 키와 허용
  범위만 찍는다(배포 로그는 공개 CI 로그다). 구간의 정의처는 `ExitParamRanges` 이고 스크립트의 표는 사본이다 —
  `ExitParamsPreflightScriptTest` 가 후보값으로 두 표와 실제 Spring 바인딩을 대조한다. 우회 플래그는 없다 — 오탐이면 모든 배포가
  막히므로, 검사를 바꿀 때는 머지 전에 서버의 렌더된 `/opt/app/.env` 에 새 스크립트를 읽기 전용으로 돌려 본다.
- **앱 코드 변경은 이미지 재빌드가 있어야 반영된다.** `deploy.sh deploy`(pull)만으로는 안 바뀐다([[lesson-cors-origin-rebuild]]).
- **Caddyfile 은 배포가 반영하지 않는다.** `deploy.sh` 는 복사만 하고 실행 중인 caddy 에 다시 읽히지 않아, caddy 컨테이너가
  새로 뜰 때(새 이미지 digest·재부팅) 적용된다. 자동 롤백은 app 헬스만 보고 HTTPS e2e 실패는 WARN 이라, Caddyfile 변경의
  게이트는 머지 전 같은 이미지로 돌리는 `caddy validate` 다(명령과 바로 적용하는 방법은 `deploy/vultr/README.md` 2절).
- **자동 배포는 테스트·GHCR push 성공 뒤에만 실행된다.** Actions는 기존 Vultr 인스턴스만 갱신하고,
  고정한 호스트 키와 원격 `/opt/app/.last-good-sha`를 확인한 뒤 기존 migration gate·health check를 재사용한다.
  최초 실행은 healthy 컨테이너에서만 rollback 기준을 bootstrap한다(stale SHA 취급은 아래 두 항목).
- **`main` 머지가 곧 배포 시작이다 — 단 코드가 바뀌었을 때만.** `deploy-vultr` job 은
  `if: github.event_name == 'push'` 라 PR 을 머지하는 순간 파이프라인이 돈다. 다만 2026-08-26 부터
  `on.push.paths-ignore`(`**.md`·`.claude/**`·`wiki/**`·`docs/**`)가 붙어 **문서·plan 만 바뀐 push 는
  워크플로 자체가 생성되지 않는다**. 도입 계기는 plan 커밋 `bcef6ec` 이 배포를 트리거해 실거래 봇을
  재시작시킨 일이다. 문서만 바꾼 뒤 그래도 배포해야 하면 `workflow_dispatch` 로 수동 실행한다. 따라서 **"배포 직전에 무엇을 하겠다"는 절차에는 창이 없다** — 백업·스냅샷은
  머지 전에 끝내야 한다. 머지 후에 확보하려면 `test`·`build-and-push` 가 도는 몇 분이 사실상 마지막 기회다
  (2026-08-23 V21 배포에서 실제로 그 창에서 백업을 확보했다. `deploy/vultr/backup.sh` 는 `BACKUP_S3_BUCKET`
  미설정이면 쓸 수 없어 대상 테이블만 `pg_dump` 했다).
- **머지가 몰리면 그 PR 의 배포 스텝은 skipped 된다.** `Check deployment commit is current main` 이
  `origin/main` 과 `GITHUB_SHA` 를 대조해 다르면 이후 스텝을 전부 건너뛴다. `concurrency: vultr-production` 이
  `cancel-in-progress: false` 라 앞 배포를 기다리는 동안 main 이 앞서가면 이 조건에 걸린다. 2026-08-23 PR #117 이
  그랬고, 그 커밋은 이미 main 에 있었으므로 뒤이어 머지된 #116 의 배포에 함께 실려 적용됐다 — **변경이 누락된 게
  아니라 배포 시점이 뒤 PR 로 밀린 것**이다. 내 PR 의 Actions 가 skipped 라고 배포 실패로 읽지 말고, 후속 배포
  로그에서 반영을 확인한다.

  ⚠️ **이 자가치유는 "뒤이어 도는 실행이 있다"에 기대고 있다.** 그래서 `paths-ignore` 도입(2026-08-26)이
  이 전제를 깰 뻔했다 — 앞선 커밋이 문서 push 에 밀려 skipped 됐는데 그 문서 push 는 실행을 만들지
  않으므로, 구제해 줄 후속 배포가 없어진다(결론만 success 인 채 옛 이미지가 계속 돈다).
  그래서 같은 변경에서 가드를 **SHA 비교가 아니라 코드 diff 비교**로 바꿨다: main 이 앞서 있어도
  그 차이가 전부 배포 무관 경로면 배포를 진행한다. 가드의 제외 목록은 `on.push.paths-ignore` 와
  **쌍으로 유지**해야 하며, 한쪽만 넓히면 그 경로가 다시 조용한 미배포 구간이 된다.
  이 쌍은 주석이 아니라 **테스트가 강제한다**(#151) — `DeployWorkflowPathListTest` 가 두 목록이
  정확히 같은지, 그리고 배포 산출물 경로(`deploy/`·`Dockerfile`·`gradle/`·`bot/` 등)가 제외 목록에
  섞이지 않았는지 확인한다. `./gradlew test` 가 CI 게이트이므로 어긋난 채로는 머지되지 않는다.
- GitHub-hosted runner의 동적 출발 IP 때문에 Vultr cloud firewall의 `ctb-ssh-github-actions` 22/tcp
  `0.0.0.0/0` 규칙이 필요하며, 운영 SSH는 password 금지·root key-only로 hardening되어 있다.
- 수동 SSH는 `SSH_ALLOW_CIDR`로 제한하고, `setup_firewall`은 전용 규칙이 잘못되거나 중복되면
  실패한다. Actions는 추적된 `known_hosts`와 strict host-key checking을 사용한다.
- 배포 스크립트 자체의 셸 함정 두 가지가 수정된 채 보관돼 있다 — 되돌리지 않도록 [[lesson-deploy-script-pitfalls]] 확인.
- 인프라 변경 전에는 [Vultr 상태 페이지](https://status.vultr.com/)의 전역 장애·maintenance를 확인한다.
- 보안그룹이 단일 IP 로 잠겨 있으면 다른 디바이스에서 접근이 안 된다([[lesson-single-point-verification]]).
- 애플리케이션 자체의 구조는 [[architecture-overview]] 참조 — 단일 JVM 이므로 앱 컨테이너는 하나다.
