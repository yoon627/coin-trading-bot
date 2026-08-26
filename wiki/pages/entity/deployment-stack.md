---
title: 배포 스택 — Vultr 서울 + Caddy TLS + GHCR
category: entity
created: 2026-07-28
updated: 2026-08-26
claim_state: current
verified: 2026-08-24 — **live Actions 배포를 처음으로 실제 관찰**(2026-08-23 03:40:44 KST, 앱 로그 `Successfully applied 1 migration ... now at version v21`). push 트리거와 stale 가드는 `.github/workflows/deploy.yml:61,75-87` 원문 확인. 배포 계층 기본값 제거(#75)는 2026-08-04 `docker compose config` 실측분, 인프라 구성은 2026-08-02 확인분 유지 · 2026-08-26 — 문서 전용 push 필터 도입, stale 가드를 코드 diff 기준으로 전환. 계기는 plan 커밋 `bcef6ec` 이 봇을 재시작시킨 일이고, 같은 실행 이력(`d21a3eb` skipped / `bcef6ec` success)이 자가치유 전제를 실증했다
sources:
  - PROJECT_ANALYSIS.md
  - deploy/vultr/
  - .github/workflows/deploy.yml
  - README.md
---

# 배포 스택

| 계층 | 구성 |
|---|---|
| 호스트 | Vultr 서울(`icn`) **vc2-1c-2gb** (x86_64, 2GB) |
| 컨테이너 | Docker Compose (app + postgres + redis + caddy) |
| TLS | **Caddy 2** + Let's Encrypt, sslip.io 자동 도메인 |
| 이미지 | GitHub Actions → **GHCR** (multi-arch push) |
| 배포 | GitHub Actions `main` push → GHCR → SSH → `deploy/vultr/deploy.sh deploy` |

> `verified` 주의: 위 구성과 2026-08-01 운영 상태는 저장소와 실제 `status`/HTTPS 확인으로 대조했고, 2026-08-02 자동 배포 workflow의 YAML·embedded shell 계약도 정적으로 검증했다. live Actions 배포 결과는 merge 후 별도로 관찰해야 한다.
> AWS 경로는 2026-07-31 삭제된 historical reference이고, OCI는 보류 경로다.

## 이 구성이 나온 이유

각 선택이 사고에서 나왔다:

- **TLS 종단(Caddy)** — prod 프로파일이 항상 `Secure` 쿠키를 발급해 평문 HTTP 에서는 브라우저 로그인이 원천적으로 불가능했다([[lesson-secure-cookie-http]]).
- **2GB 인스턴스** — 이전 EC2의 컨테이너 실측 818MiB를 근거로 제한 합계 1472m로 rightsizing 했다.
- **x86_64(Vultr)** — AWS arm64에서 전환하지만 GHCR 이미지와 공식 의존성 이미지가 multi-arch다.

## 배포 시 주의

- **배포 계층은 앱 설정의 기본값을 갖지 않는다**(#75, 2026-08-04). `deploy.sh` 의 `render_server_env` 는 로컬 `.env` 에 실제로 설정된 `TRADING_*` 만 서버 `.env` 에 쓰고, compose 는 그 키들을 **값 없이 이름만**(`- TRADING_TAKE_PROFIT_PCT`) 선언한다 — 값이 해결되지 않으면 compose 가 변수를 컨테이너에서 제거하므로 `TradingProperties` 기본값이 적용된다. 배포 스크립트에 `${VAR:-기본값}` 폴백을 되살리면 앱 기본값과 갈려 2026-07-30 사고가 재발한다.
- **앱 코드 변경은 이미지 재빌드가 있어야 반영된다.** `deploy.sh deploy`(pull)만으로는 안 바뀐다([[lesson-cors-origin-rebuild]]).
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
- GitHub-hosted runner의 동적 출발 IP 때문에 Vultr cloud firewall의 `ctb-ssh-github-actions` 22/tcp
  `0.0.0.0/0` 규칙이 필요하며, 운영 SSH는 password 금지·root key-only로 hardening되어 있다.
- 수동 SSH는 `SSH_ALLOW_CIDR`로 제한하고, `setup_firewall`은 전용 규칙이 잘못되거나 중복되면
  실패한다. Actions는 추적된 `known_hosts`와 strict host-key checking을 사용한다.
- 배포 스크립트 자체의 셸 함정 두 가지가 수정된 채 보관돼 있다 — 되돌리지 않도록 [[lesson-deploy-script-pitfalls]] 확인.
- 인프라 변경 전에는 [Vultr 상태 페이지](https://status.vultr.com/)의 전역 장애·maintenance를 확인한다.
- 보안그룹이 단일 IP 로 잠겨 있으면 다른 디바이스에서 접근이 안 된다([[lesson-single-point-verification]]).
- 애플리케이션 자체의 구조는 [[architecture-overview]] 참조 — 단일 JVM 이므로 앱 컨테이너는 하나다.
