---
title: migrate-to-oci — AWS EC2 → Oracle Cloud 서울 Always Free 이전
status: in_progress
started: 2026-07-28
updated: 2026-07-28
---

# Goal

월 $39.29(2026-06 실측) AWS EC2 t4g.medium 배포를 Oracle Cloud 서울 리전
(VM.Standard.A1.Flex, 2 OCPU ARM / 12GB)로 이전해 **Always Free 한도 내 운영($0 목표)** 으로 만든다.
기존 거래 이력·암호화된 Upbit 키는 전량 보존 이전하며, **거래는 항상 단 하나의 인스턴스에서만**
활성화한다.

> "$0 보장" 이 아니라 "무료 한도 내 목표" — idle 회수·용량 부족·한도 초과 과금 리스크가 실재한다
> (Decisions 참조). 실거래 자금 규모가 커지면 유료 인스턴스 재검토가 필요하다.

# Progress

- 2026-07-28: 클라우드 비교 조사 완료(공개 가격 API 실측). Azure Korea Central 은 AWS 보다 비싸
  (B2als_v2 $34.2 + 디스크·IP 별도, ARM 은 D2pls_v6 $59.1 뿐) → 탈락. Vultr 서울 $20/월 all-in 이
  차선, Oracle 서울 Always Free $0 이 최선으로 확정.
- 2026-07-28: 현재 AWS 실청구 확인 — 2026-06 기준 EC2 $29.95 + EC2-Other $2.17 + VPC(IPv4) $3.60
  + Tax $3.57 = **$39.29/월**.
- 2026-07-28: worktree 생성, `deploy/aws/` 자산 정독(deploy.sh 515줄, backup.sh, compose, Caddyfile).
  재사용/재작성 경계 확정. OCI CLI 3.89.3 설치, `--auth instance_principal` 지원 확인.
- 2026-07-28: **codex plan 리뷰 완료 — Critical 5 / Major 8 / Minor 3 접수.** 계획을 전면 개정
  (cutover runbook·2단계 롤백·IAM 선행 리소스·bounded retry·NSG·boot volume 수명주기 추가,
  메모리 상향 철회). 상세는 Decisions·Review Disposition.
- 2026-07-28: `deploy/oci/` 구현 완료 — deploy.sh(8 커맨드·재진입 setup·bounded capacity retry),
  backup.sh(instance principal·업로드 크기 검증·time-created 기준 보존), compose/Caddyfile(복사),
  .env.example, README.md(계정 체크리스트·cutover runbook·2단계 롤백·무료티어 리스크).
  루트 README·PROJECT_ANALYSIS 동기화.
- 2026-07-28: 정적 검증 통과 — `bash -n`(3.2), shellcheck error 0, AWS 잔재 0,
  안전장치 10종 존재, compose 메모리 AWS 판과 동일, `docker compose config` OK.
  **검증이 실결함 2건 검출**: ① `.gitignore` 에 `deploy/oci/` 시크릿 패턴 누락(SSH 개인키·.state 가
  커밋될 수 있었음) → 추가 ② `mapfile` 사용(bash 4+ 전용, 이 macOS 는 3.2 라 런타임 실패) → while read 로 교체.
  OCI CLI 서브커맨드·옵션 22종은 실제 `--help` 로 실존 확인(추측 배제).
- 2026-07-28: **codex 코드 리뷰 완료 — Critical 4 / Major 11 / Minor 3, 전부 수정.**
  최대 결함은 subnet 에 전용 security list 미지정으로 **SSH 가 전 세계에 열려 있던 것**(NSG 와
  security list 는 합집합). 그 밖에 tenancy OCID 조회 실패(추측 작성), `(( attempt++ ))` 가
  `set -e` 와 만나 launch 전 종료, destroy 전 최종 백업 누락 등. 수정 후 신규 CLI 명령 13종
  실존 재확인 + 21개 항목 반영 grep 체크 PASS + shellcheck error 0 + 전체 정적 검증 PASS.
  simplify 체크: 제거 대상 없음(AWS 판과의 중복은 plan 에 기록된 의도적 결정, sunset 조건 있음).

# Next

이번 작업(스크립트 + 정적 검증)은 완료. 아래는 **사용자 계정 준비 후의 후속 작업**이다.

1. OCI 계정 생성 — 홈 리전을 반드시 `South Korea Central (Seoul)` 로(춘천이면 A1 불가, 사후 변경 불가).
   `oci setup config` 로 API 키 설정.
2. (선택) codex 재리뷰 2회차 — 수정 폭이 컸으므로 원하면 실행.
3. `deploy/oci/.env` 작성 — `APP_ENCRYPTION_SECRET` 은 AWS 값 그대로 복사(신규 생성 금지).
   `UPBIT_*` 는 cutover 전까지 비워둘 것.
4. `./deploy/oci/deploy.sh setup` → `deploy` 실행 검증(capacity 확보까지 재시도 필요할 수 있음).
5. README 4절 cutover runbook 대로 데이터 이전 + 단일 실행 보장하에 거래 전환.
6. 7~14일 안정화 후 AWS destroy(별도 승인).

# Decisions

- **대상 = Oracle Cloud 서울(ap-seoul-1) Always Free** (이유: $0, 12GB 로 사양 상승, 국내 리전이라
  업비트 레이턴시·IP 이슈 최소). 차선책 Vultr 서울 $20/월 — 용량 확보 실패 반복 시 전환.
- **Azure 탈락** (이유: Retail Prices API 실측상 Korea Central 4GB 급이 AWS 보다 비쌈. B2als_v2
  $34.2/월 + 디스크·IP 별도, ARM 계열은 D2pls_v6 $59.1/월 뿐이고 B2pls_v2 미제공).
- **`deploy/aws/` 유지** (이유: 롤백 경로). 단 아래 롤백 정의 변경에 따라 **AWS destroy 는 cutover 후
  최소 7~14일 안정화 기간 경과 + 별도 승인** 후로 미룬다.
- **`deploy/oci/` 는 복사로 독립 구성** — 단 codex 지적 수용해 **한시적 결정으로 명시**하고
  AWS 제거를 sunset 조건으로 둔다. 양쪽 공통 로직(보안·헬스체크) 수정 시 동시 반영 규칙 추가.
- **backup.sh 는 OCI 네이티브 + instance principal** (이유: 키 미배포 유지). **변경(codex C4 반영):
  instance principal 은 옵션만으론 권한이 없다** — bucket + dynamic group + 최소권한 policy 를
  setup 이 함께 만들거나 선행 수동 절차로 문서화해야 한다. 이번 구현은 **setup 에 포함**한다.
- **데이터 전량 이전** — `APP_ENCRYPTION_SECRET` 재생성 금지. **변경(codex C2 반영): OCI 쪽
  `ensure_secrets` 는 `APP_ENCRYPTION_SECRET` 이 비면 생성하지 않고 hard fail** 한다(마이그레이션
  모드). AWS↔OCI 키 일치는 SHA-256 지문 비교로만 확인(원문 미출력).
- **롤백 정의 변경(codex C3 반영)**: "AWS 보존 = 롤백" 은 **틀렸다**. 거래 활성화 후 AWS 재기동은
  데이터 분기다. 롤백을 2단계로 구분 — ①거래 활성화 **전**: OCI 중지 + AWS 재기동(역이전 불필요)
  ②거래 활성화 **후**: OCI 거래중지 → 미체결/잔고 스냅샷 → OCI 최종 dump → AWS 재복원 → 대조 →
  AWS 단독 활성화. 양쪽에 쓰기가 생기면 자동 병합 금지·수동 정합성 조사.
- **cutover 단일 실행 보장(codex C1 반영)**: "두 인스턴스가 동시에 거래 활성일 수 없다" 를 승인
  게이트로 둔다. OCI 는 `TRADING_AUTO_START=false` + 거래키 미주입으로 먼저 뜨고, AWS graceful
  stop·tick 종료 확인 후에야 최종 dump→복원→수동 활성화.
- **네트워크는 security list 가 아니라 NSG(codex M1 반영)** — security list 는 subnet 전체 적용이고
  기본 list 와 합집합이라 SSH 제한이 무력화될 수 있다. NSG 가 AWS SG 의미에 가깝다. 또한
  `security-list update` 는 규칙 전체 교체라 "한 건 추가" 식 멱등 호출이 기존 규칙을 지울 위험이 있다.
- **공인 IP = reserved 우선(codex M2 반영)** — sslip.io 도메인·Upbit IP 화이트리스트·TLS 인증서가
  모두 IP 에 결합돼 있어 인스턴스 재생성 시 변경 비용이 크다. **무료 한도 포함 여부 확인을
  Blockers 로 승격**, 무료면 reserved 기본·OCID 를 `.state` 에 저장, 아니면 ephemeral + IP 변경
  절차 문서화.
- **OS = Oracle Linux 9 ARM 고정, SSH 사용자 `opc`** (이유: AL2023 과 같은 dnf 계열이라 기존
  userdata 이식 비용이 가장 낮다). 이미지는 이름 패턴이 아니라 shape 호환 조회 결과로 OCID 확정.
- **boot volume(codex M4 반영)**: Always Free 인스턴스는 최소 ~47GB 필요, boot+block 합계 200GB 한도.
  `instance terminate` 는 `--preserve-boot-volume` 기본 false 라 **재프로비저닝 시 true 명시**,
  최종 destroy 만 검증된 백업 후 false. boot volume OCID 를 `.state` 에 기록.
- **capacity retry 는 bounded(codex M5 반영)**: 최대 시도·총 시간 상한, exponential backoff + jitter,
  capacity 오류만 재시도(인증·quota·shape/image 오류는 즉시 실패), **launch 전 표시이름/태그로
  기존 PROVISIONING|RUNNING 인스턴스 조회해 중복 생성 방지**, OCID 는 생성 직후 원자적 기록,
  setup 은 단계별 재진입 가능하게.
- **compose 메모리는 현행 유지(codex M7 반영)** — 기존 계획의 "12GB 에 맞춰 상향" 은 **철회**.
  이유: 제한 상향은 성능을 보장하지 않고 메모리 누수를 늦게 드러낸다. 이전은 변수를 줄이고,
  튜닝은 안정화 후 측정 기반 별도 작업.
- **Always Free 운영 리스크 등록(codex C5 반영)**: 7일간 CPU p95·네트워크·A1 메모리가 모두 20%
  미만이면 idle 회수 대상. CPU 인위 소모로 회피하지 않는다. 외부 감시(인스턴스 상태·마지막 거래
  tick·마지막 백업 성공)와 타 클라우드 cold-standby 런북을 문서에 둔다.

# Key Files

- `deploy/aws/deploy.sh` — 원본 515줄. 재사용: state 헬퍼, `ensure_secrets`(fail-fast 로 변형),
  `render_server_env`, `preflight_domain`, `wait_for_docker`, `do_deploy` 전체(SHA 고정·자동 롤백·
  헬스체크 REMOTE 블록), `do_ssh/status/logs/stop/start`.
  재작성: `require_aws`, `_authorize_ingress`/`ensure_sg_rules`(→NSG), `do_setup`(179~277), `do_destroy`(463~502).
- `deploy/aws/backup.sh` — AWS 의존은 `sts get-caller-identity`·`s3 cp/ls/rm`. 정리 실패를 `|| true`
  로 삼키는 구조(70행)는 포팅 시 개선 대상.
- `deploy/aws/docker-compose.prod.yml` — caddy 128m/app 1280m/pg 512m/redis 192m. **그대로 이식**.
- `deploy/aws/Caddyfile` — 클라우드 중립. 그대로 복사.
- `deploy/oci/*` — 이번 작업 산출물.

# Blockers

- **reserved public IP 가 Always Free 한도에 포함되는지 미확인** — 공식 Always Free 문서에 명시
  없음. 구현 전 확인 필요(포함이면 reserved 기본, 아니면 ephemeral + 변경 절차).
- OCI 계정 미생성 — 홈 리전을 **South Korea Central (Seoul)** 로 지정해 생성해야 함(춘천 선택 시
  Ampere A1 생성 불가, 사후 변경 사실상 불가). setup 이 CLI 로 홈리전을 조회해 불일치면 hard fail.
- Always Free ARM 용량 부족("out of host capacity")은 상시 발생 — bounded retry 로 완화하되 보장 불가.

# Acceptance

| # | 충족 조건 | 검증 방법 | 통과 기준 |
|---|---|---|---|
| 1 | `deploy/oci/deploy.sh` 가 8개 서브커맨드 제공 | `bash -n` + 케이스 분기 확인 | 문법 오류 0, 미구현 커맨드 0 |
| 2 | 문법·정적 결함 없음 | `bash -n`, `shellcheck`(필수) | `bash -n` 통과, shellcheck error 0 |
| 3 | capacity retry 가 bounded | 코드 확인 | 시도·총시간 상한 존재, backoff+jitter, capacity 외 오류 즉시 실패 |
| 4 | 중복 인스턴스 생성 방지 | 코드 확인 | launch 전 기존 PROVISIONING/RUNNING 조회 분기 존재 |
| 5 | `APP_ENCRYPTION_SECRET` fail-fast | 코드 확인 | 비어 있으면 생성하지 않고 exit≠0 |
| 6 | backup 이 키 배포 없이 동작하는 구조 | 코드 확인 | `--auth instance_principal`, 시크릿 파일 배포 없음, 업로드 실패 시 exit≠0 |
| 7 | IAM 선행 리소스가 setup 에 포함 | 코드 확인 | bucket·dynamic group·policy 생성 경로 존재 |
| 8 | destroy 의 boot volume 정책이 명시적 | 코드 확인 | `--preserve-boot-volume` 명시, 최종 삭제는 확인 절차 후 |
| 9 | AWS 잔재 없음 | `grep -nE 'AWS_|EC2_|ec2-user|require_aws|s3://'` on `deploy/oci/` | 의도된 주석 외 매치 0 |
| 10 | compose 유효성 | `docker compose -f deploy/oci/docker-compose.prod.yml config` (샘플 env) | 파싱 성공, 메모리 값이 AWS 판과 동일 |
| 11 | 문서 동기화 | `deploy/oci/README.md` 신규 + 루트 `README.md`·`PROJECT_ANALYSIS.md` 갱신 | 절차·비용·리전이 스크립트와 일치 |
| 12 | **cutover runbook** 문서화 | `deploy/oci/README.md` 확인 | 단일 실행 게이트·AWS 정지 확인·최종 dump→복원→검증→수동 활성화 순서 포함 |
| 13 | **2단계 롤백** 문서화 | 〃 | 거래 활성화 전/후 절차가 분리 기술, 자동 병합 금지 명시 |
| 14 | 계정 준비 체크리스트 | 〃 | 홈리전 서울 고정, budget/cost alert, 무료 한도(Object Storage 20GB·API 5만/월 포함) 안내 |

**실행 미검증(후속 작업)**: 실제 setup/deploy 실행, 인스턴스 기동, Caddy TLS 발급, 업비트 IP
화이트리스트 통과, 데이터 복원 정합성, 백업 복원 시험.

# Review Disposition

## codex 코드 리뷰 (2026-07-28, 구현 후) — Critical 4 / Major 11 / Minor 3

전부 **fix**. 실행 검증이 불가능한 상태라 정적으로 잡히는 결함의 가치가 특히 컸다.

- **C1 SSH 전세계 개방** → fix. subnet 에 `--security-list-ids` 미지정 시 VCN 기본 security list
  (`0.0.0.0/0 → 22`)가 붙고, security list 와 NSG 는 **합집합**이라 NSG 로 좁혀도 무력화됐다.
  빈 전용 security list 를 만들어 subnet 에 명시하고 허용은 NSG 로만.
- **C2 tenancy OCID 조회 실패** → fix. `region-subscription list` 응답에 `tenancy-id` 필드가 없다
  (추측으로 작성한 부분). `.env TENANCY_ID` → `OCI_CLI_TENANCY` → `~/.oci/config` 순 해석으로 교체.
- **C3 `(( attempt++ ))` + `set -e`** → fix. 증가 전 값 0 을 반환해 exit status 1 → launch 호출 전 종료.
  `attempt=$(( attempt + 1 ))` 로 교체. (같은 항목의 `mapfile` 지적은 자체 점검에서 먼저 발견·수정)
- **C4 destroy 전 최종 백업 누락** → fix. AWS 판의 데이터 보호 계약이 빠져 있었다. 백업 실패 시 중단,
  미설정 + 볼륨 삭제 시 `delete-my-data` 확인 게이트 추가.
- M1 `object head` 의 `content-length` 쿼리 경로 오류(항상 "손상" 판정) → fix(래퍼 유무 양쪽 처리 + 숫자 검증)
- M2 `OCI_REGION` 이 CLI 에 미적용 → fix(`export OCI_CLI_REGION`)
- M3 IGW 삭제 전 route rule 미제거 + 실패 은폐 후 state 삭제 → fix(route 비우기, 삭제 검증, 실패 시 state 보존)
- M4 버킷 versioning Enabled 와 보존 정리 충돌(delete marker 만 남아 20GB 한도 도달) → fix(Disabled)
- M5 policy 실패 후 재실행이 복구 불가 → fix(dynamic group / policy 독립 단계)
- M6 백업 권한이 compartment 전체 인스턴스에 부여 → fix(`instance.id` 매칭, setup 순서 변경)
- M7 cloud-init 이 의존성 미보장 상태로 완료 마커 생성 → fix(공식 RPM·jq·docker 검증 후 마커)
- M8 state 유실 시 public IP 재생성 거부 → fix(기존 할당 조회·재사용). 전 리소스 tag adopt 는 **defer**
- M9 GHCR 토큰이 `ps` 노출 → fix(별도 호출 stdin)
- M10 로컬 `.env` 권한 미보호 → fix(`umask 077` + `chmod 600` + 안내를 `install -m 600` 으로)
- M11 NSG 재진입이 "규칙 1건이면 통과" → fix(`ctb-` prefix 규칙만 교체해 CIDR 변경 반영)
- m1 `trap RETURN` 이 exit 경로 미정리 → fix(EXIT) / m2 BSD date 파싱 → fix(`to_epoch`) /
  m3 개인키만 있을 때 재진입 불가 → fix(`ssh-keygen -y` 로 공개키 파생)

**미실시**: 수정 후 codex 재리뷰(fix loop 2회차). 수정 폭이 커 재리뷰 가치가 있으나 1회 10분+ 소요라
Report 에서 사용자 판단으로 넘긴다. 대신 수정 반영 여부를 21개 항목 grep 체크로 전수 확인(PASS).

## codex plan 리뷰 (2026-07-28, 계획 단계) 처분:

- C1 cutover 순서 → **fix** (Decisions + Acceptance 12)
- C2 암호화키 방어 → **fix** (fail-fast, Acceptance 5)
- C3 롤백 정의 → **fix** (2단계 롤백, Acceptance 13)
- C4 IAM 선행 리소스 → **fix** (setup 에 포함, Acceptance 7)
- C5 Always Free 운영 리스크 → **fix** (Goal 문구 완화 + 리스크 등록)
- M1 NSG → **fix** / M2 reserved IP → **fix**(Blockers 승격) / M3 이미지·SSH 사용자 → **fix**
- M3 중 "postgres/redis/caddy arm64 manifest 검증" → **false-positive**: 현재 동일 compose 가
  t4g.medium(ARM64) 에서 운영 중이므로 이미 입증됨.
- M4 boot volume → **fix** / M5 bounded retry → **fix** / M6 백업 보존·가시성 → **fix**
- M7 메모리 상향 → **fix**(상향 철회) / M8 Acceptance 강화 → **부분 fix**
- M8 중 "OCI CLI mock 기반 bats/shunit 테스트" → **defer**: 이번 범위(스크립트+정적검증)를 크게
  넘고 실행 검증 후속 작업과 중복. 후속에서 재검토.
- Minor 1 복사 결정 → **fix**(한시적 명시 + sunset 조건) / Minor 1 중 `StrictHostKeyChecking=no`
  → **fix**(`accept-new` 로 변경) / Minor 2 홈리전 CLI 검증 → **fix** / Minor 3 비용 가드레일 → **fix**(문서)

# Deferred

- Hetzner·Contabo 가격 미확인(JS 렌더링). 아시아 리전이 싱가포르뿐이라 제외했으나 Oracle·Vultr 모두
  막히면 재조사.
- OCI CLI mock 기반 자동 테스트(codex M8) — 후속 실행 검증 작업에서 재검토.
- compose 메모리 튜닝(12GB 활용) — 안정화 후 측정 기반 별도 작업.
