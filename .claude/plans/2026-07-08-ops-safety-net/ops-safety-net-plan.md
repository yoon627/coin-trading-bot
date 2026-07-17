---
title: ops-safety-net — 운영 안전망 (DB 백업·외부 감시·배포 SHA 고정/롤백·non-root)
status: in_progress
started: 2026-07-08
updated: 2026-07-17
---

# Goal

"프로세스가 죽거나 데이터가 날아가도 알아차리고 복구할 수 있는" 운영 최소 안전망 구축. 감사 발견 4건: DB 백업 전무(거래 이력·암호화 Upbit 키 단일 볼륨 한 부), unhealthy/부팅실패 무음(알림 채널이 앱 자신뿐), :latest 미고정 배포(SHA 기록·롤백 없음), 앱 컨테이너 root 실행.
(주의: 외부 감시는 프로세스/HTTP 생존만 감지 — 엔진 루프 사망·봇 stop 은 UP 으로 보임. '전략 루프 살아있음' 수준의 운영 헬스 지표는 이 plan 범위 밖, 후속 이슈로.)

# Progress

- 2026-07-08: 전방위 감사(멀티에이전트 워크플로 + 메인 세션 grep spot-check) 기반 plan 작성. spot-check: Dockerfile USER 없음, deploy.sh:89 `APP_VERSION=${APP_VERSION:-latest}`, 백업 자동화 0건, stop_grace_period 없음.
- 2026-07-10: plan-review(Claude subagent + codex 병행) 반영 — 자동 롤백에 Flyway forward-compat 게이트 추가, 백업 보안(S3 SSE·시크릿 분리 보관) 명시, prune 인과 교정, pg_dump 실행 방식(compose exec), 감시 커버리지 한계 명시, DISCORD_ERROR_ALERT_ENABLED 실측 항목 추가.
- 2026-07-17: 세션 범위 = **O5 non-root 먼저**(사용자 선택, Acceptance 전건이 실기 EC2/외부 SaaS 검증이라 코딩 세션에선 O5 만 완결·실증 가능). 구현: `Dockerfile` 런타임 스테이지에 `adduser -D -u 1001 app` + `USER app`(ENTRYPOINT 직전). 로컬 Docker 데몬 미기동 → Docker Desktop 기동 후 런타임 유저 로직만 담은 미니 이미지(base+adduser+USER, 앱 컴파일 제외)로 실증: `docker run` → `uid=1001(app) gid=1001(app)` **PASS**. 커밋 15d251d.
- 2026-07-17: **O4 배포 SHA 고정/자동 롤백** 구현(사용자 선택, O5 다음). `deploy/aws/deploy.sh`: (1) `update_state()` 헬퍼(.state 중복 방지), (2) do_deploy 에 대상 SHA 고정(`git fetch origin main`→`rev-parse`, APP_VERSION=latest 대체) + migration 게이트(`git diff last_good..target -- db/migration/` 비었을 때만 `rollback-ok`), (3) remote 블록을 롤백 상태기계로 재작성 — 헬스OK 시 이전 이미지 정리 후 exit 0, 실패+rollback-ok 시 `APP_VERSION=$LAST_GOOD docker compose up -d --pull never`+재헬스체크(exit 2), 실패+blocked(migration 포함/LAST_GOOD 없음) 시 수동안내 exit 1, (4) 로컬에서 exit code 해석해 성공 시 `update_state LAST_GOOD_SHA`. 검증: `bash -n` 통과. 실deploy·롤백은 EC2 핸드오프(Acceptance). CI 태그=전체 SHA(deploy.yml:53-55), migration 경로 13파일 실존 확인. O2/O3 미착수.
- 2026-07-17: O4 리뷰(code-reviewer+codex) 반영 — **Critical**(exec stdin drain → 실패를 성공 오인·LAST_GOOD 오염, code-reviewer 실기재현) 포함 6건 fix 적용. Critical 은 파이프-stdin 대조 harness 로 재현·수정 실증(버그 rc=0/PHASE1만 → 수정 rc=3/실패경로 실행). `bash -n` 통과. 상세·처분은 # Review Disposition. defer 2건은 # Deferred.
- 2026-07-17: **O2 DB 백업** 구현(사용자 "이어서"). `deploy/aws/backup.sh` 신규(pg_dump `docker compose exec -T`(unix socket, local trust)+`</dev/null`→gzip→`aws s3 cp - --sse` + RETENTION 경과 객체 정리). `deploy.sh`: render_server_env 에 BACKUP_S3_* + AWS_REGION 추가, do_deploy 가 backup.sh scp+chmod, do_destroy 에 삭제 전 최종백업 훅(BACKUP_S3_BUCKET 설정 시). `.env.example` 백업 섹션(APP_ENCRYPTION_SECRET 오프사이트 분리 경고). 보안: dump 만 백업(AES 키 분리). `bash -n` 통과(backup.sh·deploy.sh). pg_dump/S3/cron 실행은 EC2 핸드오프. **rebase 예정**: origin/main 이 marketdata-consolidation 머지+README refresh 로 앞섰고 deploy/·Dockerfile 은 미변경이라 무충돌 — rebase 후 refresh 된 README 에 운영 섹션 작성.

# Next

O5(non-root)·O4(배포 롤백) 완료(2026-07-17, 코드+로컬검증). O4 는 리뷰 반영 후 커밋 확정 예정. 남은 2건:
- **O2 DB 백업 스크립트** (레포 측 저작 가능, 다음 우선 후보): EC2 cron pg_dump(compose exec)→S3(SSE·시크릿 분리). 스크립트 저작·shellcheck 가능, cron 실행·S3 는 EC2 핸드오프. destroy 경로에 최종 백업 훅.
- **O3 외부 감시** (대부분 외부·수동): healthchecks.io/UptimeRobot 등록 + Discord — SaaS 등록은 사용자 몫, 레포 측은 host-cron fallback 스크립트 + README 운영 섹션.
- 실기 검증(EC2/외부 SaaS) 전건은 사용자 핸드오프 체크리스트로 별도 정리 필요(Acceptance 참조).

# Decisions

- **감시(O3)**: 1순위 외부 SaaS 감시(+Discord), 보조로 EC2 host cron 이 health 실패 시 Discord webhook 직접 POST(앱 밖 경로). autoheal 컨테이너는 선택. (이유: DiscordErrorLogAppender 는 in-process + ApplicationReadyEvent 후 attach — 부팅 실패·행·크래시루프 전부 무음. 포지션 보유 중 앱 사망 = 손절 미작동인데 알 방법 없음)
- **백업(O2)**: EC2 cron 야간 `docker compose exec -T postgres pg_dump -U trading trading | gzip` → S3 업로드(보존 7~30일) — postgres 는 호스트 미노출(expose only)이라 반드시 compose exec 경유. **보안**: S3 SSE 암호화 + 퍼블릭 액세스 차단 + 수명주기 정책, **APP_ENCRYPTION_SECRET(키 복호화 AES)은 dump 와 분리 보관**(같은 저장소면 유출 시 즉시 복호화 — plan-review major). destroy 경로에 '최종 pg_dump 후 삭제' 단계 삽입. 오프사이트 보관 절차 README 명시.
- **배포 고정/롤백(O4)**: deploy 시 대상 SHA 명시(`git rev-parse origin/main` 또는 인자) → APP_VERSION 렌더, 헬스체크 통과 시 `.state` 에 LAST_GOOD_SHA 기록, 실패 시 LAST_GOOD_SHA 자동 재기동. **Flyway forward-compat 게이트**: 실패한 새 SHA 가 이미 migration 을 적용했을 수 있음 — **migration 포함 배포는 자동 롤백 제외(수동 개입 안내 출력)**, 미포함 배포만 자동 롤백(plan-review major). prune 인과 정확화: prune 은 헬스 통과 후 실행되므로 문제는 '이번 성공 배포가 직전 이미지를 지워 **다음** 배포 실패 시 롤백 이미지 부재' — LAST_GOOD 이미지는 prune 대상에서 보존(태그 유지). CI 는 이미 SHA 태그 push(deploy.yml:53-55) — 소비만 변경.
- **non-root(O5)**: Dockerfile 런타임 스테이지 `adduser -D -u 1001 app` + `USER app`. 8080 비특권 포트, 파일 쓰기 불필요(로그 stdout·상태 DB/Redis)라 영향 없음.
- **스코프 경계**: graceful shutdown 은 engine-lifecycle plan 소관. SG 0.0.0.0/0·데스크탑 키 미결(memory: EC2 재배포 핸드오프)은 범위 밖 — # Deferred.

# Key Files

- `deploy/aws/deploy.sh` — :89(APP_VERSION), :311(up -d), :322-343(헬스실패·prune), :360-381(destroy)
- `deploy/aws/docker-compose.prod.yml` — :39-40(image/pull_policy), :59(DISCORD_ERROR_ALERT_ENABLED 기본 false), :80-88(healthcheck), :101-102(postgres expose), :111-112(pgdata)
- `Dockerfile` — :14-22(런타임 스테이지)
- `.github/workflows/deploy.yml` — :53-55(SHA 태그)
- `README.md` — 운영 섹션(백업·복원·롤백·감시 갱신 절차 — 문서 동기화 대상)

# Acceptance

인프라 작업이라 실기(EC2) 검증 절차 명시 — 코드만으로 "완료" 선언 금지:

- [ ] 외부 감시 등록 + 앱 컨테이너 수동 stop 시 Discord 알림 실측 수신 (실행·관찰)
- [ ] `DISCORD_ERROR_ALERT_ENABLED=true` 운영 설정 확인 + ERROR 로그 1건 실측 도달 (다른 plan 들의 log.error 승격 전제 충족 확인)
- [ ] 백업 cron 1회 실행 → S3 객체 생성(SSE 적용 확인) + `gunzip -t` 무결성 + 복원 리허설(스테이징 DB pg_restore) 1회 성공
- [~] deploy.sh: SHA 렌더·.state 기록 확인, 고의 깨진 이미지(migration 미포함) 배포 → LAST_GOOD_SHA 자동 롤백 동작 확인, migration 포함 케이스 → 자동 롤백 제외·수동 안내 출력 확인 — **구현 완료**(`bash -n` 통과), 실deploy 3케이스(성공/자동롤백/게이트차단) 실측은 EC2 핸드오프
- [~] `docker exec app id` → uid≠0, 앱 정상 기동·거래 조회 정상 — **uid≠0 로컬 실증 완료**(미니 이미지 `docker run` → `uid=1001(app)`), 앱 실기동 non-root 는 CI 풀빌드+EC2 실기로 승계
- [ ] README 운영 섹션 반영 (문서 동기화 게이트)

# Blockers

(없음) — 단, EC2 실기 접근 필요(deploy/aws/.env·pem 로컬 보유 전제, memory: EC2 재배포 2026-05-31 핸드오프 참조).

# Deferred

- SG 0.0.0.0/0 개방·데스크탑 pem 키 미등록 (memory 핸드오프의 미결 — 별도 보안 작업, severity: medium)
- 운영 헬스 지표(엔진 루프 생존·마지막 tick 시각 노출)와 그 감시 — 외부 감시의 커버리지 한계 보완 (severity: low~medium)
- O4 리뷰 defer: LAST_GOOD_SHA 가 운영자 로컬 `.state` 에만 존재 → 다중 머신/운영자 배포 시 자동롤백 무력·불일치 가능. 서버측 상태(실행 컨테이너 라벨 등)로 이전 검토 (severity: low, 단일 운영자면 무영향)
- O4 리뷰 defer: push 직후 CI(deploy.yml) 이미지 빌드 완료 전 `deploy` 실행 시 `docker compose pull` 실패로 클린 exit 1 — 버그 아님이나 운영 footgun. README 운영 섹션에 "CI 빌드 완료 후 배포" 명시 (O3/README 작업 시)

# Review Disposition

O4(6b33546) 리뷰(2026-07-17) — code-reviewer(Claude) + codex 병행. **전건 fix 적용·재검증 완료**(후속 커밋):
- `fix` **Critical** (code-reviewer, codex 놓침, 실기 재현) deploy.sh health_ok: `docker compose exec -T` 가 `bash -s` 파이프 stdin 을 drain → 헬스실패 경로(롤백·게이트) 전체 스킵 + 원격 bash exit 0 → 실패를 성공으로 오인하고 **깨진 SHA 를 LAST_GOOD 로 승격**. → exec 에 `</dev/null`. **재현**: 파이프-stdin 대조 harness — 버그=PHASE1만·rc=0, 수정=실패경로 실행·rc=3(scratchpad/stdin-drain-repro.sh).
- `fix` **Major/Medium** (양쪽 합의) 롤백 후 서버 `/opt/app/.env` 에 실패 SHA 잔존 → 이후 `start`/수동 up 이 실패본 재배포. → 롤백 성공 후 `sed -i` 로 .env APP_VERSION=LAST_GOOD 갱신.
- `fix` **High** (codex) migration 게이트: target_sha 가 로컬 git 부재 시 `git diff` exit 128·빈출력 → 위험하게 rollback-ok. → target_sha 도 `cat-file -e` 검증, 부재 시 blocked.
- `fix` **Medium/Low** (codex) 롤백 이미지 부재: `--pull never` → `--pull missing`(GHCR 재pull), exit 3 폴백 유지.
- `fix` **Minor** (code-reviewer) 동일 SHA 재배포(target==last_good) → 무의미한 자기-롤백. → 게이트에서 blocked 처리.
- `defer` **Minor** (양쪽) LAST_GOOD 가 운영자 로컬 `.state` 에만 존재 → 다중 머신 배포 시 롤백 불가/불일치. 서버측 상태(컨테이너 라벨 등) 이전은 별도 작업(# Deferred).
- `defer` **informational** (code-reviewer) push 직후 CI 이미지 빌드 전 배포 시 `docker compose pull` 실패 → 클린 exit 1(버그 아님). CI 완료 대기를 README 운영 섹션에 명시(O3/README 작업 시).
- `false-positive` (양쪽 확인): `ssh ... <<REMOTE || deploy_rc=$?` exit code 포착 정상, 성공 후 이미지 정리가 다음 롤백 대상을 안 지움, migration pathspec(`-C repo_root` 상대경로) 정확.
