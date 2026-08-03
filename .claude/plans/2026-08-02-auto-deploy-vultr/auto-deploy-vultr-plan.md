---
title: auto-deploy-vultr — main push Vultr SSH 자동 배포
status: done
started: 2026-08-02
updated: 2026-08-02
---

# Goal

`main` push가 테스트와 GHCR 이미지 push를 통과하면 현재 Vultr 운영 인스턴스에 대상 SHA를
고정해 SSH 배포하고, 기존 health check·migration rollback gate를 자동으로 사용한다.
Vultr의 라벨 없는 추가 인스턴스는 운영 대상과 분리해 확인하며, 삭제는 실행 직전 사용자 확인 후에만 한다.

# Progress

- 2026-08-02: GitHub repository secrets 4개(`VULTR_DEPLOY_ENV`, `VULTR_PUBLIC_IP`, `VULTR_SSH_PRIVATE_KEY`, `VULTR_SSH_USER`) 등록을 확인했다.
- 2026-08-02: 현재 workflow에는 `test`와 `build-and-push`만 있고 Vultr deploy job이 없음을 확인했다.
- 2026-08-02: remote `main`은 `2e7a01c…`, Vultr 운영 컨테이너는 `2d125f8…`이며 컨테이너/HTTPS는 healthy다.
- 2026-08-02: `6700ff09-…` 라벨 없는 Vultr 인스턴스가 `active/running`, `auto_backups`, Ubuntu 26.04로 남아 있고 운영 state에 참조되지 않음을 확인했다.
- 2026-08-02: `auto-deploy-vultr` worktree를 `origin/main`에서 생성했다. main worktree의 기존 변경은 보존했다.
- 2026-08-02: `deploy-vultr` job과 secret/state bootstrap, concurrency, cleanup을 구현하고 README·Vultr runbook·deployment wiki를 동기화했다.
- 2026-08-02: YAML 계약·embedded shell·`git diff --check`·기존 배포 스크립트 error-level shellcheck를 통과시켰다. Codex reviewer 실행은 hook 출력 과다로 최종 본문을 회수하지 못해 메인 에이전트가 별도 self-review를 수행한다.
- 2026-08-02: Compose config, Wiki link/extra/smoke 검증과 JDK 21 기반 `./gradlew test`가 통과했다. 기본 JDK 25에서는 Kotlin 2.0.21의 `JavaVersion.parse`가 `25.0.2`를 처리하지 못해 실패했으며, 저장소 문서의 요구사항인 JDK 21로는 성공했다.
- 2026-08-02: 최종 self-review 후 `15597be`(`ci: add automatic Vultr SSH deployment`)로 작업 브랜치에 커밋했다. Vultr 인스턴스 두 개의 상태도 삭제 직전 확인용으로 재조회했다.
- 2026-08-02: pre-push Codex 게이트가 P1 호스트 키 미고정과 P2 Compose/workflow 이미지 저장소 drift를 차단했다. 현재 운영 ED25519 fingerprint와 기존 로컬 known_hosts를 대조한 뒤 고정 파일·strict SSH 옵션·`GHCR_IMAGE` Compose 변수로 수정한다.
- 2026-08-02: P1/P2를 수정하고 workflow 계약·embedded shellcheck·Compose fork-image 해석·`bash -n`/error-level shellcheck·Wiki 검증·JDK 21 `./gradlew test`를 재통과했다.
- 2026-08-02: 재검토에서 stopped app 컨테이너를 `ps -q`가 놓치는 P1을 발견했다. rollback 기준 조회를 `docker compose ps -aq app`로 보강해 장애 후 복구 배포가 사전검사에서 막히지 않게 수정한다.
- 2026-08-02: 재검토에서 concurrency queue 순서 미보장과 stopped/실패 SHA를 `LAST_GOOD_SHA`로 오인할 수 있는 P1을 발견했다. 최신 `origin/main` guard와 원격 성공 SHA 파일(`/opt/app/.last-good-sha`)로 수정한다.
- 2026-08-02: PR #79를 merge했지만 main Actions run `30729505959`의 SSH 접속이 timeout됐다. Vultr cloud firewall의 SSH 규칙이 `203.251.147.201/32`만 허용하는 것을 확인했고, GitHub Actions CIDR 목록은 약 7,297개라 정적 allowlist가 부적합하다.
- 2026-08-02: 운영 SSH 설정을 read-only 점검한 결과 `passwordauthentication yes`, `permitrootlogin yes`, `fail2ban inactive`였다. cloud firewall 공개 전 key-only hardening이 필요하므로 사용자 확인 blocker로 전환한다.
- 2026-08-02: 사용자 확인 후 SSH를 `passwordauthentication no`, `kbdinteractiveauthentication no`, `permitrootlogin prohibit-password`로 harden하고 `sshd -t`·reload·재접속을 확인했다. Vultr firewall에는 `ctb-ssh-github-actions` 22/tcp `0.0.0.0/0`을 추가한 뒤 기존 local-only rule을 제거했다.
- 2026-08-02: Actions attempt 3 성공(`30729505959`): test/GHCR/deploy/cleanup 모두 통과, 대상 SHA `db57ece…`, app/DB/Redis healthy, HTTPS health `UP`, remote `.last-good-sha` 일치.
- 2026-08-02: 추가 인스턴스 `6700ff09-…`를 삭제했고 API GET이 404를 반환했다. 운영 인스턴스 `1063c481-…`는 `active/running`으로 유지됨을 재확인했다.

# Next

없음 — SSH 배포 자동화, 운영 반영, health 확인, 추가 인스턴스 삭제까지 완료했다.

# Decisions

- `deploy/vultr/deploy.sh deploy`를 재사용한다. 이미 대상 SHA 고정, health check, migration rollback gate, 원격 compose 기동을 구현하고 있어 배포 계약을 중복하지 않는다.
- workflow가 secret의 stale `APP_VERSION`/`GHCR_IMAGE`를 덮어쓰고 `GITHUB_SHA`/현재 repository image를 마지막 설정으로 기록해 main push 대상과 배포 대상을 일치시킨다.
- GitHub Actions는 `setup`/`destroy`를 호출하지 않고 기존 인스턴스에만 SSH 배포한다. 따라서 deploy job에는 Vultr API key를 요구하지 않는다.
- workflow는 배포 전에 현재 운영 컨테이너의 `Config.Image` tag에서 40자리 SHA를 읽어 `.state`의 `LAST_GOOD_SHA`로 만든다. `latest`·digest·비정상 값이면 fail-closed한다.
- deploy job은 `test`와 `build-and-push`에 모두 의존하고, PR에서는 실행하지 않으며, `main` push와 main 대상 수동 실행만 허용한다.
- Actions runner의 임시 파일에는 secret을 쓰되 job 종료 시 명시적인 파일 cleanup을 실행한다. secret 원문은 로그에 출력하지 않는다.
- `VULTR_DEPLOY_ENV`는 기존 production `.env`의 runtime/GHCR 설정을 받는 multiline secret으로 사용한다. `VULTR_API_KEY`는 deploy-only 경로에서 사용하지 않는다.
- pre-push P1 처분: 현재 운영 호스트 키를 `deploy/vultr/known_hosts`에 고정하고, workflow 및 `deploy.sh`가 `StrictHostKeyChecking=yes`와 해당 파일을 사용하도록 수정한다. IP/호스트 교체 시 파일을 검증 후 갱신해야 한다.
- pre-push P2 처분: Compose app image를 `${GHCR_IMAGE:-...}:${APP_VERSION}`으로 변경해 workflow가 push한 저장소와 deploy script가 pull하는 저장소를 일치시킨다.
- queued 실행이 `origin/main`과 다른 SHA이면 deploy steps를 skip한다. `cancel-in-progress: false`를 유지해 현재 배포를 중단하지 않으면서 stale 실행의 역전 배포를 막는다.
- rollback 기준은 remote `/opt/app/.last-good-sha`에 health 성공 후에만 기록한다. 파일이 없는 최초 bootstrap은 현재 app container health와 SHA를 함께 검증한다.
- GitHub-hosted runner는 동적 IP를 사용하므로 cloud firewall에 `ctb-ssh-github-actions` 22/tcp `0.0.0.0/0`을 유지한다. 공개 전 SSH를 key-only로 harden하고 `setup_firewall` 재실행 시 전용 규칙을 보존한다.
- 추가 인스턴스 `6700ff09-…`는 라벨·운영 state·현재 workflow 참조가 없고 운영 인스턴스와 생성/OS/backup feature가 다르다. 초기/실패 provisioning 잔여로 판단하지만, API가 생성 주체를 제공하지 않아 원인은 추정으로 기록한다.

# Key Files

- `.github/workflows/deploy.yml` — test/GHCR 뒤 Vultr deploy job을 추가할 핵심 workflow.
- `deploy/vultr/deploy.sh` — 기존 수동 배포 계약과 rollback/health check의 단일 구현.
- `deploy/vultr/docker-compose.prod.yml` — workflow가 주입하는 `GHCR_IMAGE`를 app image에 반영.
- `deploy/vultr/known_hosts` — 현재 운영 Vultr SSH ED25519 host key pin.
- `README.md` — CI/CD 공개 동작과 secret/운영 절차.
- `deploy/vultr/README.md` — Vultr 자동 배포 및 수동 복구 runbook.
- `wiki/pages/entity/deployment-stack.md` — 운영 배포 소스와 검증 상태.

# Acceptance

- [x] main push에서 `test → build-and-push → deploy-vultr` 의존성이 보장되고 PR에서는 deploy가 skip된다. (YAML contract check)
- [x] deploy job이 등록된 4개 secret으로 임시 `.env`·SSH key·state를 만들고 원격 현재 SHA를 rollback 기준으로 사용한다. (workflow read/self-review)
- [x] deploy job이 `deploy/vultr/deploy.sh deploy`를 호출하며 concurrency와 cleanup을 적용한다. (YAML contract check)
- [x] workflow YAML/embedded shell 및 기존 배포 스크립트가 정적검사를 통과한다. (`yaml_parse`, embedded `shellcheck`, `bash -n`, error-level `shellcheck`)
- [x] README·Vultr README·deployment wiki가 실제 workflow 동작과 일치한다. (diff/self-review, Wiki checks)
- [x] CI 및 배포 스크립트가 검증된 운영 host key를 strict checking으로 사용하고, Compose image 저장소가 workflow image와 일치한다. (fingerprint 대조, workflow/Compose checks)
- [x] queued stale SHA가 배포되지 않고, 성공 확인 SHA가 원격 persistent state로 보존되어 연속 실패에도 rollback 기준이 유지된다. (Actions attempt 3, remote `.last-good-sha` 대조)
- [x] 추가 인스턴스 삭제는 대상 ID·상태를 재확인한 뒤 사용자 승인 후 수행하고, 삭제 후 Vultr 목록에서 사라짐을 관찰한다. (DELETE 204, GET 404; production instance retained)

- [x] SSH key-only hardening과 GitHub-hosted runner용 cloud firewall 규칙을 적용하고 재접속/Actions 배포를 확인한다. (effective `sshd -T`, firewall API, Actions attempt 3)

# Review Disposition

- Codex plan/code reviewer 실행은 완료했으나 hook 출력이 최종 결과를 가려 회수하지 못했다. 메인 에이전트가 workflow 조건·secret 경계·rollback state·cleanup·문서 정합성을 직접 재검토한다.
- 후속 Codex code review의 Minor 지적 2건은 전용 firewall 규칙 형식·중복 검증과 SSH hardening 적용/검증 명령 문서화로 수정 완료했다.
- 후속 Codex code review의 Minor 지적 처분: firewall 규칙 검증은 `fix`, hardening runbook 보강은 `fix`.
- pre-push Codex finding `[P1]` 호스트 키 미고정과 `[P2]` 이미지 저장소 drift는 고정 known_hosts·strict checking·`GHCR_IMAGE` Compose 변수로 수정 완료했다.
- 추가 pre-push finding `[P1]` stopped app 컨테이너 조회 누락은 `docker compose ps -aq app`로 수정한다.
- 추가 pre-push findings `[P1]` concurrency 순서 drift와 실패 SHA 오인은 최신 `origin/main` guard 및 `/opt/app/.last-good-sha` 성공 후 기록으로 수정한다.
- `shellcheck`의 기존 warning/info는 유지하고 error-level 결과만 acceptance에 반영한다.

# Blockers

없음.
