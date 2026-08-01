---
title: migrate-to-oci — AWS EC2 탈출 (OCI 시도 → Vultr 서울 2GB 확정)
status: done
started: 2026-07-28
updated: 2026-08-01
---

> **방향 전환(2026-07-30)**: OCI 가입이 홈 리전 문제로 막혀(아래 Decisions) **Vultr 서울 2GB**로
> 목표를 변경했다. 브랜치·slug 은 `migrate-to-oci` 그대로 유지한다(§10 rename 금지).
> `deploy/oci/` 는 완성 상태로 보존 — 나중에 OCI 서울 계정이 생기면 그대로 쓸 수 있다.

# Goal

**달성(2026-07-30)**: 월 $39.29(2026-06 실측) AWS EC2 t4g.medium 배포를 **Vultr 서울 `vc2-1c-2gb`
(1 vCPU x86_64 / 2GB) 월 $10** 으로 이전 완료 — **-75%**. 거래 이력·`bot_state`·시세 데이터를
전량 보존 이전했고, **거래는 항상 단 하나의 인스턴스에서만** 활성화한다는 원칙을 지켜 cutover 했다.

당초 목표였던 Oracle Cloud 서울 Always Free($0, 12GB)는 가입 단계에서 홈 리전이 춘천으로 확정돼
불가해졌다(Decisions). `deploy/oci/` 는 실행 미검증 상태로 보존 — 서울 계정이 생기면 재사용 가능.

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
- 2026-07-30: OCI 가입 2회 실패(이메일 인증 → 홈 리전 춘천 확정)로 **Vultr 서울 2GB 로 방향 전환**.
  전환 근거로 운영 EC2 실사용량을 실측(818MiB — 4GB 과잉 확인)하고 Azure 총액도 재조사해 탈락 확정.
  `deploy/vultr/` 신규 작성 — deploy.sh(REST API 프로비저닝·방화벽 멱등 교체·`mem` 커맨드 추가),
  backup.sh(S3 호환 엔드포인트로 AWS S3/Vultr/R2 지원), compose(2GB 예산 1472m), .env.example, README
  (cutover·2단계 롤백 포함). 루트 README·PROJECT_ANALYSIS 동기화.
  **Vultr API 필드·타입은 공식 govultr SDK 소스에서 확정**(`Port string`·`SubnetSize int`·
  `SSHKeys []string`·`OsID int` 등) — 추측 배제. 정적 검증 8개 항목 PASS(문법·shellcheck error 0·
  bash 3.2 호환·서브커맨드 9종·API 필드·안전장치 11종·메모리 합계·gitignore) + `docker compose config` OK.
  ⚠️ **codex 코드 리뷰 미실시 — 크레딧 소진**("workspace is out of credits"). CLAUDE.md §9 규약대로
  생략하고 자체 검토로 대체(OCI 판 codex 지적 15항목을 체크리스트로 재적용). 자체 검토에서
  방화벽 규칙 삭제 실패를 `|| true` 로 삼키던 것을 경고로 승격, aws 호출을 `s3()` 헬퍼로 모아
  빈 배열(`set -u`)·stdin 삼킴을 한 곳에서 차단. `(( failed > 0 )) && ...` 의 set -e 조기종료
  의심은 **실제 실행으로 오탐 확인**(exit=0) 후 수정하지 않음.
- 2026-07-30: **Vultr 실행 검증 + cutover 완료.** 인스턴스 `1063c481-…`(icn, vc2-1c-2gb),
  IP `158.247.242.126`. setup → deploy → HTTPS(Let's Encrypt) 발급까지 1회 성공.
  실행 중 발견·수정: 신규 스크립트에 **실행 권한 누락**(commit 30f7699), caddy 제한 64m→96m
  (실측 27MiB 로 42% 였음).
  **cutover**: 포지션 0건 확인 후 AWS app 정지 → 최종 덤프(8.9MB gz) → Vultr 복원 → 검증 →
  Upbit 키 이전 → 거래 활성화. `Restored bot for user 4: strategy=combined,
  tickers=[KRW-BTC,KRW-XRP,KRW-SOL,KRW-ETH]` 로그로 확인.
  **복원 중 실패 2회와 원인**: ① 원격 명령에 `</dev/null` 을 붙여 `gzip -dc | psql` 파이프가 끊겨
  psql 이 **빈 입력으로 exit 0** → "복원 완료" 로 오보고. 검증 쿼리도 같은 이유로 침묵.
  ② Vultr 가 AWS 보다 최신 스키마(`trading_states`·`stock_*`)라 덤프의 `--clean` 이 `users_pkey`
  FK 의존성에 막힘. 해결: `DROP SCHEMA public CASCADE` 후 AWS 상태를 그대로 복원하고 앱 기동 시
  Flyway 가 v13→v18 **전진 적용**(5개). 이때 v14 가 `positions` 를 drop 하므로 **포지션 0 이었던
  것이 결정적**이었다(보유 중이었다면 별도 이관 설계가 필요했음).
  **2GB 예산 실측 검증**: 데이터 복원 후 app 289MiB/832m · postgres 87MiB/512m · caddy 27MiB/96m ·
  redis 9MiB/64m, host used 818MB/1962MB(available 1143MB) + swap 5.4GB → 설계값 유효 확인.
  ⚠️ 내 검증 오류 1건: flyway `max(version)` 을 **문자열 비교**해 양쪽 "9" 로 같다고 판단했으나
  `"9" > "10"` 이었다(실제로는 Vultr 가 더 최신). 버전 비교는 숫자/행수로 해야 한다.
- 2026-07-30: **업비트 private API 검증 완료** — 서버 안에서 JWT 를 직접 서명해 `/v1/accounts` 호출,
  `HTTP 200`. 이 서버 IP 가 허용되며(IP 제한 미사용) 주문 가능 상태. 보유 자산은 KRW 1종(코인 미보유)
  으로 포지션 0 과 일치. 키는 서버 밖으로 내보내지 않고 잔고 금액도 출력하지 않았다.
- 2026-07-30: **AWS 정리 1단계** — `docker compose down` 후 EC2 인스턴스 `i-05575e4603c9c1f63` **stop**
  (삭제 아님). EC2 컴퓨트 요금 중단, EBS·EIP 만 남음(월 $5 내외). EIP `13.125.170.147` 유지되므로
  롤백 시 IP·도메인이 동일하다. 7~14일 롤백 창구로 두고 그 뒤 destroy.
- 2026-07-31: **후속 운영 변경 완료** — ① 보유 도메인 `do-anything.cloud` 연결(A 레코드가 이미
  Vultr 를 가리키고 있었고 `APP_DOMAIN` 만 비어 있었다 → 설정·재배포 후 Let's Encrypt 인증서
  `CN=do-anything.cloud` 발급, 외부 HTTPS 200) ② 투자비율 0.1→0.15(1회 21,790→32,685원)
  ③ 거래종목 4→8개(BTC·ETH·XRP·SOL·DOGE·ADA·AVAX·LINK, watchlist 13개 내에서 선택)
  ④ **AWS 완전 삭제** — 인스턴스·EBS·EIP 전부 제거, 사후 조회 전부 `[]` → AWS 과금 $0
  (롤백 창구 없어짐, 백업이 유일 안전망) ⑤ 맥 로컬 백업 launchd 등록(매일 03:10) + **복원 시험 통과**.
  ⚠️ 이 과정에서 **"고쳤는데 반영 안 됨"이 두 번** 났다 — 트레일링(배포 계층 하드코딩)과
  투자비율(render_server_env·compose 화이트리스트 누락, PR #76). 둘 다 issue #75 의 증상이다.
  ⚠️ 거래종목은 `bot_state`(DB)가 부팅 시 복원되므로 `.env` 만 바꾸면 안 되고 DB UPDATE 가 필요했다.
- 2026-08-01: **읽기 전용 운영·저장소 재검증** — Vultr `status` 는 app/caddy/postgres/redis
  모두 정상( app 이미지 SHA=`2d125f8` ), `do-anything.cloud` A 레코드와 HTTPS 200·Let's Encrypt
  인증서를 확인했다. 서버 `.env` 에 8종목·`TRADING_INVEST_RATIO=0.15`·TP5/trailing2/arm3 이
  주입돼 있다. 정확한 `HTTP 429`/`Too Many Requests`/`rate limit` 검색은 0건이었고, `age 429xxms`
  를 429로 세던 기존 집계는 오탐이었다. stale store fallback 경고는 남아 있다.
  AWS 구 인스턴스 조회 결과와 EIP 조회 결과는 비어 있었으며, 이 세션에서는 AWS/Vultr/DNS에 쓰기를
  수행하지 않았다. 로컬 launchd 백업은 성공 덤프 3개 뒤 최신 회차가 `Connection reset by peer` 로
  실패했고, 원격 S3 백업 설정은 비어 있다. `compileKotlin`, `test`, 배포 스크립트 정적검사와
  3개 Compose config 검증은 통과했다.
- 2026-08-01: **Vultr 공급자 경보 확인** — 공식 상태 페이지의 `ALRT-F83KAW9`가 전역
  `ongoing`으로 표시되어 신규 구독/인스턴스 배포가 간헐적으로 실패할 수 있다. 서울 `icn`에는
  지역 장애가 표시되지 않았고 현재 운영 인스턴스의 SSH·HTTPS·컨테이너 상태도 정상이다.
  별도 전역 DB cutover가 2026-08-03 15:00 UTC(한국시간 2026-08-04 00:00)부터 1시간 예정되어
  콘솔/API와 리소스 생성·수정·삭제를 중단할 수 있다. 이 세션에서는 재배포·재생성·리소스 변경을
  하지 않았다.
- 2026-08-01: **cloud-init 보강 완료** — Ubuntu 24.04에서 `apt-get install awscli || true`가
  실패해도 성공처럼 보이던 경로를 제거했다. 공식 AWS CLI v2 설치 파일을 `amd64`/`arm64`에
  맞춰 설치하고 `aws --version`까지 확인한 뒤에만 `.userdata-done` 마커를 남긴다. 현재 운영
  인스턴스에는 외부 변경을 가하지 않았고, 추출 user-data `bash -n`/`shellcheck`, 전체 배포
  스크립트 정적검사, JDK 21 `compileKotlin`·`test`·`build`, Compose·wiki 검증을 통과했다.
  백업 버킷·자격증명 작업은 별도 후속으로 남겼다.

# Next

**마이그레이션 목표는 완료(`status: done`)이며, 아래는 별도 후속 작업이다.**

1. **오프사이트 백업 도입** — 현재 맥 launchd 는 임시 로컬 백업이다. 성공 덤프가 있어도 최신
   회차가 SSH 연결 리셋으로 실패할 수 있다. `deploy/vultr/.env` 에 버킷은 설정돼 있지만
   버킷전용 `BACKUP_ACCESS_KEY_ID`/`BACKUP_SECRET_ACCESS_KEY` 는 비어 있고, 서버에도 백업
   환경변수가 주입되지 않았다. 버킷·최소권한 키 준비는 사용자 확인이 필요한 외부 작업이다.
2. **issue #75 후속 설계** — `application.yml`·`TradingProperties`·각 compose·각 deploy.sh에
   기본값이 중복된다. #76은 누락된 전달만 고쳤고 기본값 단일화는 아직 하지 않았다.
3. stale store fallback과 `#Deferred`의 `maxHoldDays`/`marketFilter`는 실거래 영향과 백테스트 결과를
   확인한 별도 작업으로 재검토한다.
4. **Vultr 공급자 변경 게이트** — `ALRT-F83KAW9`가 ongoing인 동안 새 인스턴스 생성·삭제·리사이즈를
   실행하지 않는다. 2026-08-04 00:00~01:00 KST 전후에는 공식 상태 페이지를 재확인하고, 자동화된
   인프라 변경을 일시 중지한다. 현재 기존 인스턴스는 정상이라 앱 재시작은 필요하지 않다.

# Decisions

- **최종 대상 = Vultr 서울(icn) `vc2-1c-2gb` $10/월 로 변경 (2026-07-30)**.
  이유 3가지: ① OCI 가입이 두 번 막힘 — 1차 이메일 인증 실패, 2차는 가입 폼 기본값 때문에 홈 리전이
  **춘천(YNY=ap-chuncheon-1)** 으로 확정됐고 춘천은 Ampere A1 생성 제외 리전이라 계획 자체가 불가.
  홈 리전은 사후 변경 불가. ② **실측으로 4GB 가 과잉임이 확인됨** — 운영 59일차 EC2 에서
  app 420MiB / postgres 380MiB / redis 3.4MiB / caddy 14MiB = **합계 818MiB**, 호스트 used 874MB,
  load average 0.00. 2GB 로 충분. ③ Azure 는 총액 재조사 결과 AWS 보다 비쌈(아래).
  → $39.29 → **$10/월(-75%)**.
- **Azure 최종 탈락(2026-07-30 총액 재조사)**: Korea Central 기준 VM `B2als_v2` $34.16 +
  Disk E6 LRS 64GB $5.43 + Standard Static IPv4 $3.65 = **$43.24/월(세금 별도)** 로 AWS($39.29 세금
  포함)보다 비싸다. 무료 티어의 B1s 는 1GB 라 이 스택(실사용 818MiB + OS)이 안 들어간다.
- **Oracle 도쿄 미채택**: A1 생성은 가능하지만 **업비트 API 의 해외 IP 허용 여부가 미검증**이라
  실거래 봇에 얹기엔 위험이 크다(막히면 주문 불가로 전부 무의미). 서울이면 없는 리스크.
- **아키텍처 전환 arm64 → x86_64**: Vultr `vc2` 는 x86_64 다(Ubuntu 이미지도 x64 만 제공).
  GHCR 앱 이미지가 multi-arch(amd64+arm64)로 빌드되고 postgres/redis/caddy 도 공식 multi-arch 라
  그대로 pull 된다. 별도 대응 불필요하나 이전 후 첫 기동에서 확인 대상.
- **2GB 메모리 예산 재조정**: 실측(app 420·pg 380·redis 3.4·caddy 14 MiB) 기반으로 제한을 낮춘다.
  AWS/OCI 판의 "AWS 와 동일 유지" 결정은 4GB 박스 전제였으므로 여기선 적용하지 않는다.
- **(보류) Oracle Cloud 서울 Always Free** — 가입만 뚫리면 $0 에 12GB 라 여전히 최선이다.
  `deploy/oci/` 를 지우지 않고 보존하는 이유. 나중에 서울 계정이 생기면 재검토.
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
- `deploy/oci/*` — OCI 경로 보존 산출물(실행 미검증).
- `deploy/vultr/deploy.sh` — 현재 운영 배포 경로. render/env 전달, cloud-init, SHA 고정 배포,
  status/mem/stop/start/destroy 명령을 포함한다.
- `deploy/vultr/backup.sh`, `deploy/vultr/README.md` — S3 호환 백업 계약과 로컬/오프사이트 백업
  후속 절차. 현재 서버의 백업 환경변수는 비어 있다.
- `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt` — #74에서
  캔들 요청 간격·429 재시도를 고친 수집 경로.
- `README.md`, `PROJECT_ANALYSIS.md`, `wiki/pages/entity/deployment-stack.md` — 현재 Vultr 운영과
  삭제된 AWS 경로, 공급자 상태 페이지 변경 게이트를 반영한 문서.

# Blockers

- 마이그레이션 목표 자체에는 현재 blocker가 없다. OCI의 reserved IP·홈 리전·ARM 용량 항목은
  현재 Vultr 운영과 무관한 역사적 blocker로 보존한다.
- 오프사이트 백업은 버킷전용 자격증명과 외부 버킷 준비가 필요하다. 현재 로컬 키 칸이 비어 있어
  에이전트가 추측하거나 외부 계정을 변경할 수 없다.
- 로컬 launchd 최신 백업 회차는 `Connection reset by peer`로 실패했다. 원인 확정 전에는
  복원 가능한 최신 백업을 보장한다고 기록하지 않는다.
- Vultr 전역 배포 장애 경보(`ALRT-F83KAW9`)와 예정된 전역 DB cutover가 진행 중이다. 서울
  `icn`의 현재 인스턴스 장애는 확인되지 않았지만, 신규 프로비저닝·삭제·리사이즈와 자동 변경은
  상태 페이지 해소 확인 전 보류한다.

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
| 15 | Vultr cloud-init 의 AWS CLI 의존성 준비 | `bash -n`·추출 user-data 정적검사·`shellcheck` | `amd64`/`arm64` 공식 CLI v2 설치 경로, 설치 후 `aws --version` 확인, 검증 전 `.userdata-done` 미생성 |

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

**이번 cutover 중 발견한 범위 밖 운영 이슈 (이전과 무관 — AWS 에서도 동일하게 발생 중이었음)**

- **[완료, #74] 업비트 캔들 수집 429 Too Many Requests** — 요청 간격과 제한된 429 재시도를
  적용했고, 현재 Vultr 로그에서 정확한 HTTP 429/rate-limit 문자열은 0건이다. stale fallback 경고는
  별도 잔여 이슈로 남아 있다.
  파일: `bot/src/main/kotlin/com/trading/bot/marketdata/MarketDataIngestionService.kt`.
- **시세 저장소 stale 경고** (심각도 중) — AWS 에서 `Stale store price for KRW-BTC (age
  849143658ms)` = 약 9.8일. 매 tick WS/REST 폴백으로 동작 중이라 기능은 유지되나 저장소 갱신
  경로가 끊긴 상태. 429 와 같은 원인일 가능성 있음(같이 조사).
- **[완료, #74] 트레일링 스톱 dead 설정** — TP5/trailing2/arm3이 코드·배포 예제·실제 Vultr
  서버에 정합하게 주입됐고, 현재 dead 경고는 0건이다.
- **[종료, AWS 삭제 완료] AWS 배포가 `APP_VERSION=latest`** — AWS 운영 경로가 제거됐고 현재
  Vultr는 이미지 SHA=`2d125f8`로 운영한다.
- **`users_bak_20260602` 테이블 잔존** (심각도 하) — 임시 백업 테이블 3행이 덤프에 포함돼 이전됨.

**조사 관련**

- Hetzner·Contabo 가격 미확인(JS 렌더링). 아시아 리전이 싱가포르뿐이라 제외했으나 Oracle·Vultr 모두
  막히면 재조사.
- OCI CLI mock 기반 자동 테스트(codex M8) — 후속 실행 검증 작업에서 재검토.
- compose 메모리 튜닝(12GB 활용) — 안정화 후 측정 기반 별도 작업.
