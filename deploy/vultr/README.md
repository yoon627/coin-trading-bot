# Vultr 배포 — 서울 리전 $10/월

AWS EC2 t4g.medium(실측 **$39.29/월** — 2026-06 Cost Explorer)에서 Vultr 서울(`icn`)
`vc2-1c-2gb`(1 vCPU x86_64 / 2GB / 55GB SSD / 2TB 대역폭)로 옮겨 **월 $10, -75%** 를 목표로 한다.

> **현재 운영 상태(2026-08-01 확인)**: Vultr에서 거래 중이며 AWS 인스턴스·EBS·EIP는
> 2026-07-31 삭제됐다. 아래 AWS cutover/rollback 절차는 당시 작업의 historical runbook이고,
> 현재 AWS 롤백 경로로 실행하면 안 된다. 현재 복구의 기준은 Vultr DB 백업이다.

| | AWS (historical) | Vultr (현재 운영) |
|---|---|---|
| 사양 | 2 vCPU / 4GB (ARM, 버스트) | 1 vCPU / 2GB (x86_64) |
| 디스크 | EBS 20GB (별도 과금) | 55GB SSD 포함 |
| 공인 IP | $3.65/월 | 포함 |
| 대역폭 | 종량 | 2TB 포함 |
| **월 비용** | **$39.29** | **$10** |

## 2GB로 줄여도 되는 근거

추정이 아니라 **운영 59일차 EC2 실측**이다:

| 컨테이너 | 실사용 | 새 제한 |
|---|---|---|
| app (JVM) | 420 MiB | 832m |
| postgres | 380 MiB | 512m (유지) |
| redis | 3.4 MiB | 64m |
| caddy | 14 MiB | 64m |
| **합계** | **818 MiB** | **1472m** |

호스트 전체도 `used 874MB` / 3835MB 였고 load average 0.00이었다. 2048MB에서 제한 합계 1472m +
OS/docker 약 250MB → 여유 약 320MB. postgres가 실측상 가장 빡빡해 **512m를 그대로 유지**했다.

> ⚠️ 이 예산은 실측 기반 설계값이다. 배포 후 반드시 `./deploy.sh mem` 으로 재확인할 것.
> 부족하면 `vc2-2c-2gb`($15) 또는 `vc2-2c-4gb`($20)로 콘솔에서 리사이즈할 수 있다.

---

## 0. Vultr 상태·변경 게이트

`setup`, `destroy`, 리사이즈 또는 새 인스턴스 생성 전에는 [Vultr 상태 페이지](https://status.vultr.com/)
를 확인한다. 공급자 장애나 예정된 maintenance 중에는 인프라 변경 자동화를 일시 중지한다.

2026-08-01 현재 공식 페이지에는 전역 경보 **ALRT-F83KAW9**(신규 구독/인스턴스 배포의
간헐적 실패)와 2026-08-03 15:00 UTC(한국시간 2026-08-04 00:00) 예정된 전역 DB cutover가
표시돼 있다. 서울 `icn` 지역 장애는 표시되지 않았고 기존 운영 인스턴스가 healthy라면 앱을
재시작하거나 재생성하지 않는다. cutover 전후 1시간은 콘솔/API와 리소스 생성·수정·삭제를
수행하지 않는다.

## 0.1. 계정 준비

1. https://www.vultr.com 가입 후 결제수단 등록.
2. 콘솔 → **Account → API** 에서 **API Key 발급**.
3. ⚠️ **같은 화면의 `Access Control` 에 현재 공인 IP를 추가한다.** Vultr API는 기본적으로 호출 IP를
   화이트리스트로 제한해서, 이걸 빠뜨리면 모든 호출이 401/403으로 실패한다. (`curl -s https://checkip.amazonaws.com`)
4. 필요 도구: `curl`, `jq`, `openssl`, `ssh`, `scp`.

## 1. 배포

```bash
cd deploy/vultr
install -m 600 .env.example .env   # 600 중요 — 시크릿이 들어간다
# VULTR_API_KEY 와 APP_ENCRYPTION_SECRET(AWS 값 복사) 을 채운다

./deploy.sh setup      # SSH 키 + 방화벽 + 인스턴스 생성
# cloud-init(Docker·AWS CLI 설치) 2~4분 대기
./deploy.sh deploy     # GHCR pull + compose 기동 + 헬스체크
./deploy.sh mem        # ⚠️ 2GB 여유 확인
```

`.env` 주의사항:

- **`APP_ENCRYPTION_SECRET`은 자동 생성되지 않는다.** 비어 있으면 `setup`이 즉시 실패한다.
  AWS `deploy/aws/.env`의 값을 **그대로** 복사할 것. 새로 만들면 앱은 정상 기동하면서 저장된
  Upbit 키만 조용히 복호화 불능이 된다.
- **이전 중에는 `UPBIT_*`를 비우고 `TRADING_AUTO_START=false`** 로 둔다(4절 cutover).

`setup`은 재진입 가능하다 — 중간에 실패해도 같은 명령을 다시 실행하면 이미 만든 리소스는 건너뛴다.

## 2. 운영 명령

```bash
./deploy.sh status   # 컨테이너 상태
./deploy.sh logs     # 앱 로그
./deploy.sh mem      # 메모리 실사용 (2GB 박스라 중요)
./deploy.sh ssh      # 접속
./deploy.sh stop     # 중지 (인스턴스는 유지 → 과금 계속)
./deploy.sh start    # 재기동
./deploy.sh destroy  # 삭제 (과금 중단)
```

새 버전 배포: `main` push → Actions 테스트/이미지 push → Vultr SSH deploy job → `./deploy.sh deploy`.
대상 커밋 SHA로 이미지를 고정하고, 헬스체크(180s) 실패 시 직전 정상 SHA로 **자동 롤백**한다.
단 **DB migration이 포함된 배포**가 실패하면 자동 롤백을 건너뛰고 수동 개입을 안내한다.

### GitHub Actions 자동 배포

`.github/workflows/deploy.yml`의 `deploy-vultr` job은 인스턴스 생성·삭제 없이 현재 운영 호스트에만
SSH로 배포한다. 다음 repository secrets가 필요하다.

| Secret | 내용 |
|---|---|
| `VULTR_DEPLOY_ENV` | 운영 `.env` 내용(multiline) |
| `VULTR_PUBLIC_IP` | 현재 운영 인스턴스 공인 IP |
| `VULTR_SSH_PRIVATE_KEY` | `coin-trading-bot-key.pem` 원문 |
| `VULTR_SSH_USER` | Vultr SSH 사용자(현재 `root`) |

운영 호스트 키는 `deploy/vultr/known_hosts`에 고정되어 Actions와 배포 스크립트가 최초 접속부터
검증한다. 운영 IP를 재생성하거나 호스트를 교체할 때는 새 호스트 키를 별도 경로로 확인한 뒤
이 파일을 갱신해야 하며, `accept-new`로 우회하지 않는다. Compose의 `GHCR_IMAGE`도 workflow가
빌드·push한 저장소와 동일하게 주입된다.

job은 원격 `/opt/app/.last-good-sha`의 성공 확인 SHA를 rollback 기준으로 사용한다. 파일이 없는
최초 실행은 현재 app 컨테이너가 healthy이고 40자리 commit SHA일 때만 bootstrap하며, `latest`·digest·
중지/비정상 컨테이너만 남아 있으면 배포를 거부한다. 성공한 배포와 rollback은 이 파일을 갱신한다.
또한 queued 실행의 SHA가 최신 `origin/main`과 다르면 오래된 배포를 건너뛴다. 배포 전후의 임시
`.env`, `.state`, SSH key는 Actions runner에서 삭제한다. 수동 배포와 Actions 배포를 동시에 실행하지 않는다.

## 3. AWS → Vultr 데이터 이전 (완료된 historical runbook)

```bash
# 1) AWS 에서 덤프
./deploy/aws/deploy.sh ssh
  cd /opt/app && docker compose exec -T postgres pg_dump -U trading -d trading --no-owner \
    | gzip -c > /tmp/trading.sql.gz
  exit
scp -i deploy/aws/coin-trading-bot-key.pem ec2-user@<AWS_IP>:/tmp/trading.sql.gz .

# 2) Vultr 로 복원
scp -i deploy/vultr/coin-trading-bot-key.pem trading.sql.gz root@<VULTR_IP>:/tmp/
./deploy/vultr/deploy.sh ssh
  cd /opt/app && gunzip -c /tmp/trading.sql.gz | docker compose exec -T postgres psql -U trading -d trading
```

복원 후 **거래 활성화 전에** 반드시 검증한다:

- 테이블 수·핵심 테이블 행 수가 AWS와 일치하는가
- 최신 거래 시각이 덤프 시점과 맞는가
- **저장된 Upbit 키가 실제로 복호화되는가** (앱 UI에서 키 조회 — 실패면 `APP_ENCRYPTION_SECRET`이
  다른 것이다. 이 경우 **절대 거래를 켜지 말 것**)

키가 같은지 원문 노출 없이 확인하려면 지문만 비교한다(양쪽에서 실행해 해시 일치 확인):

```bash
grep '^APP_ENCRYPTION_SECRET=' .env | cut -d= -f2- | tr -d '\n' | shasum -a 256
```

## 4. Cutover 절차 (⚠️ 단일 실행 보장)

**절대 원칙: 어느 시점에도 거래를 활성화한 인스턴스는 하나뿐이어야 한다.**
같은 Upbit 계정에 두 봇이 붙으면 이중 주문·중복 청산이 발생한다.

1. Vultr에 인프라와 앱만 올린다. `TRADING_AUTO_START=false`, `UPBIT_*` **비움**.
   `setup` → `deploy` 로 헬스체크 통과와 `mem` 여유를 확인한다.
2. AWS 앱 정지: `./deploy/aws/deploy.sh stop`
3. AWS가 완전히 멈췄는지 로그로 확인(진행 중이던 tick·주문 후처리 종료. `stop_grace_period: 40s`).
4. **Upbit에서 미체결 주문·잔고·보유 포지션 스냅샷을 기록한다**(복구 시 대조 기준).
5. 이 시점에 **최종 `pg_dump`** 를 뜬다(3절). 미리 뜬 덤프는 버린다.
6. Vultr에 복원하고 3절 검증을 모두 통과시킨다.
7. Upbit API 키에 허용 IP를 쓰고 있다면 **Vultr IP를 등록**한다(당시에는 AWS IP를 rollback 대비 유지).
8. `.env`에 `UPBIT_*`를 채우고 `./deploy.sh deploy` 후, **UI에서 수동으로** 거래를 켠다.
9. AWS가 여전히 정지 상태인지 다시 확인한다.

> **Historical note**: 위 7~14일 롤백 창구는 2026-07-31 AWS 삭제로 종료됐다. 현재 AWS
> 인스턴스/EBS/EIP를 start하거나 복구 경로로 사용하지 않는다.

## 5. 롤백 — historical reference (현재 AWS 자산 없음)

> **현재 적용 불가**: AWS 롤백 자산이 2026-07-31 삭제됐다. 아래 절차는 삭제 전 cutover 당시의
> historical runbook이다. 현재 장애 복구는 거래 중지·최신 검증 백업 확보·새 Vultr 호스트 복원과
> 수동 정합성 대조를 별도 승인으로 진행한다.

당시 AWS를 남겨둔 것만으로는 롤백이 되지 않았다. **Vultr에서 거래가 시작된 뒤로는 AWS DB에 그
거래 기록이 없기 때문에**, AWS를 그냥 켜는 것은 롤백이 아니라 데이터 분기였다.

**① 거래 활성화 _전_ (안전)**

```bash
./deploy/vultr/deploy.sh stop
./deploy/aws/deploy.sh start
```

**② 거래 활성화 _후_ (신중)**

1. Vultr에서 거래를 끄고(UI) 진행 중 주문이 정리될 때까지 기다린다.
2. Upbit 미체결·잔고 스냅샷을 기록한다.
3. Vultr에서 최종 `pg_dump`를 뜬다.
4. **AWS DB에 그 덤프를 복원한다**(AWS의 옛 데이터를 그대로 쓰면 Vultr 기간의 거래가 사라진다).
5. Upbit 실제 잔고/미체결과 복원된 DB를 대조한다.
6. AWS에서만 거래를 다시 켠다.

> 양쪽 DB에 각각 쓰기가 발생했다면 **자동 병합하지 말 것.** 수동으로 정합성을 조사한다.

## 6. DB 백업

⚠️ Vultr 인스턴스에는 AWS IAM 인스턴스 롤 같은 것이 없어 **액세스 키를 서버에 둬야 한다.**
반드시 **해당 버킷에만 권한이 있는 전용 키**를 발급할 것.

저장소는 S3 호환이면 무엇이든 된다(`.env`의 `BACKUP_S3_ENDPOINT`만 바꾼다):

| 대상 | 엔드포인트 | 비용 |
|---|---|---|
| AWS S3 (기존 계정 재사용) | 비워둠 | 백업 용량이 작아 월 $0.1 미만 |
| Vultr Object Storage | `https://sgp1.vultrobjects.com` | $5/월 250GB |
| Cloudflare R2 | `https://<account>.r2.cloudflarestorage.com` | 10GB 무료 |

```bash
# cron 등록 (./deploy.sh ssh 접속 후)
crontab -e
# 0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00

# 복원 (신규/스테이징 DB 로 — 운영 DB 덮어쓰기 주의)
aws s3 cp s3://<버킷>/db-backups/trading-<TS>.sql.gz - | gunzip \
  | docker compose exec -T postgres psql -U trading -d trading
```

- 업로드는 크기 검증까지 통과해야 성공으로 본다. 실패하면 **exit≠0**으로 끝난다.
- 보존 정리는 파일명이 아니라 객체의 `LastModified` 기준이며 삭제 실패는 경고로 남는다.
- ⚠️ **"업로드 성공"은 "복원 가능"이 아니다.** 주기적으로 실제 복원 시험을 할 것.
- ⚠️ dump에는 `APP_ENCRYPTION_SECRET`으로 암호화된 Upbit 키가 들어있다. 그 AES 키는 **백업과 다른
  곳에 오프사이트 보관** — 같은 곳에 두면 유출 시 즉시 복호화된다.

## 7. 보안

- **TLS 종단**: Caddy가 443에서 HTTPS를 종단(Let's Encrypt 자동 발급/갱신)하고 내부 `app:8080`으로
  프록시한다. 인증서 자동 갱신(ACME HTTP-01)을 위해 **80은 상시 개방**이 필요하다.
- **Vultr 클라우드 방화벽**을 쓴다(AWS security group과 같은 의미). 규칙 없이 그룹만 붙이면 모든
  인바운드가 차단되므로 22/80/443을 명시한다. SSH는 **본인 공인 IP/32**에만 열린다.
- 스크립트가 관리하는 규칙(`notes`가 `ctb-`로 시작)만 교체하므로, SSH 대역이 바뀌어도 옛 규칙이
  남지 않는다. 사람이 직접 추가한 규칙은 건드리지 않는다.
- SSH는 `StrictHostKeyChecking=accept-new` — 최초 접속만 자동 수용, 이후 호스트키 변경은 거부.
- PostgreSQL/Redis는 호스트에 노출하지 않는다(compose 내부망 전용).
- `.env`는 로컬·서버 모두 `600`. 절대 커밋하지 말 것(`.gitignore` 처리됨).

## 8. 트러블슈팅

- **API 호출이 전부 401/403**: `Access Control`에 현재 공인 IP를 추가했는지 확인(가장 흔한 원인).
- **`deploy` 헬스체크 실패**: `./deploy.sh logs`. DB 마이그레이션(Flyway)·시크릿 누락이 흔한 원인.
- **cloud-init 실패(Docker 또는 AWS CLI 없음)**: `./deploy.sh ssh` 후
  `cat /var/log/cloud-init-output.log`. AWS CLI는 [AWS 공식 Linux v2 설치 방식](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)을
  사용하며, 설치가 끝나야 `/opt/app/.userdata-done` 마커가 생긴다.
- **컨테이너가 OOM으로 재시작**: `./deploy.sh mem` 으로 확인 후, 부족하면 콘솔에서 상위 플랜으로
  리사이즈한다(`vc2-2c-2gb` $15 / `vc2-2c-4gb` $20). compose 제한도 함께 올릴 것.
- **HTTPS만 안 됨**: Vultr 방화벽 규칙과 (활성 시) ufw를 함께 확인.
