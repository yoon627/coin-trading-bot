---
title: auto-deploy-vultr — main push Vultr SSH 자동 배포
status: in_progress
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

# Next

1. 사용자 확인 후 branch push/PR/merge를 실행하고, merge된 Actions run과 운영 SHA/health를 관찰한다.
2. 같은 확인 흐름에서 추가 인스턴스 `6700ff09-…`의 상태를 재조회한 뒤 승인된 경우에만 삭제하고 삭제 후 목록에서 사라짐을 관찰한다.

# Decisions

- `deploy/vultr/deploy.sh deploy`를 재사용한다. 이미 대상 SHA 고정, health check, migration rollback gate, 원격 compose 기동을 구현하고 있어 배포 계약을 중복하지 않는다.
- workflow가 secret의 stale `APP_VERSION`/`GHCR_IMAGE`를 덮어쓰고 `GITHUB_SHA`/현재 repository image를 마지막 설정으로 기록해 main push 대상과 배포 대상을 일치시킨다.
- GitHub Actions는 `setup`/`destroy`를 호출하지 않고 기존 인스턴스에만 SSH 배포한다. 따라서 deploy job에는 Vultr API key를 요구하지 않는다.
- workflow는 배포 전에 현재 운영 컨테이너의 `Config.Image` tag에서 40자리 SHA를 읽어 `.state`의 `LAST_GOOD_SHA`로 만든다. `latest`·digest·비정상 값이면 fail-closed한다.
- deploy job은 `test`와 `build-and-push`에 모두 의존하고, PR에서는 실행하지 않으며, `main` push와 main 대상 수동 실행만 허용한다.
- Actions runner의 임시 파일에는 secret을 쓰되 job 종료 시 명시적인 파일 cleanup을 실행한다. secret 원문은 로그에 출력하지 않는다.
- `VULTR_DEPLOY_ENV`는 기존 production `.env`의 runtime/GHCR 설정을 받는 multiline secret으로 사용한다. `VULTR_API_KEY`는 deploy-only 경로에서 사용하지 않는다.
- 추가 인스턴스 `6700ff09-…`는 라벨·운영 state·현재 workflow 참조가 없고 운영 인스턴스와 생성/OS/backup feature가 다르다. 초기/실패 provisioning 잔여로 판단하지만, API가 생성 주체를 제공하지 않아 원인은 추정으로 기록한다.

# Key Files

- `.github/workflows/deploy.yml` — test/GHCR 뒤 Vultr deploy job을 추가할 핵심 workflow.
- `deploy/vultr/deploy.sh` — 기존 수동 배포 계약과 rollback/health check의 단일 구현.
- `README.md` — CI/CD 공개 동작과 secret/운영 절차.
- `deploy/vultr/README.md` — Vultr 자동 배포 및 수동 복구 runbook.
- `wiki/pages/entity/deployment-stack.md` — 운영 배포 소스와 검증 상태.

# Acceptance

- [x] main push에서 `test → build-and-push → deploy-vultr` 의존성이 보장되고 PR에서는 deploy가 skip된다. (YAML contract check)
- [x] deploy job이 등록된 4개 secret으로 임시 `.env`·SSH key·state를 만들고 원격 현재 SHA를 rollback 기준으로 사용한다. (workflow read/self-review)
- [x] deploy job이 `deploy/vultr/deploy.sh deploy`를 호출하며 concurrency와 cleanup을 적용한다. (YAML contract check)
- [x] workflow YAML/embedded shell 및 기존 배포 스크립트가 정적검사를 통과한다. (`yaml_parse`, embedded `shellcheck`, `bash -n`, error-level `shellcheck`)
- [x] README·Vultr README·deployment wiki가 실제 workflow 동작과 일치한다. (diff/self-review, Wiki checks)
- [ ] 추가 인스턴스 삭제는 대상 ID·상태를 재확인한 뒤 사용자 승인 후 수행하고, 삭제 후 Vultr 목록에서 사라짐을 관찰한다.

# Review Disposition

- Codex plan/code reviewer 실행은 완료했으나 hook 출력이 최종 결과를 가려 회수하지 못했다. 메인 에이전트가 workflow 조건·secret 경계·rollback state·cleanup·문서 정합성을 직접 재검토한다.
- `shellcheck`의 기존 warning/info는 유지하고 error-level 결과만 acceptance에 반영한다. 변경 파일에는 새 shellcheck 대상 스크립트를 추가하지 않았다.

# Blockers

- 코드 구현 자체 blocker 없음.
- live Actions 배포 관찰은 branch push/PR/merge 이후 가능하다.
- 추가 인스턴스 삭제는 되돌리기 어려운 외부 변경이므로 구현·검증 완료 후 실행 직전 사용자 확인 필요.
