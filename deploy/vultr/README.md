# Vultr 배포 — 서울 리전 $10/월

AWS EC2 t4g.medium(실측 **$39.29/월** — 2026-06 Cost Explorer)에서 Vultr 서울(`icn`)
`vc2-1c-2gb`(1 vCPU x86_64 / 2GB / 55GB SSD / 2TB 대역폭)로 옮겨 **월 $10, -75%** 를 목표로 한다.

> **현재 운영 상태(2026-08-01 확인)**: Vultr에서 거래 중이며 AWS 인스턴스·EBS·EIP는
> 2026-07-31 삭제됐다 — AWS 로 되돌아가는 경로는 없다. 복구는 Vultr DB 백업(6절)에서 3·5절 순서로 한다.

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
| redis | 3.4 MiB | 없음(2026-10 제거 — rate limit 은 앱 안 카운터) |
| caddy | 14 MiB | 96m |
| **합계** | **818 MiB** | **1440m** |

호스트 전체도 `used 874MB` / 3835MB 였고 load average 0.00이었다. 2048MB에서 제한 합계 1440m +
OS/docker 약 250MB → 여유 약 350MB. postgres가 실측상 가장 빡빡해 **512m를 그대로 유지**했다.

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
# VULTR_API_KEY 와 APP_ENCRYPTION_SECRET(운영 중인 값 — 아래) 을 채운다

./deploy.sh setup      # SSH 키 + 방화벽 + 인스턴스 생성
# cloud-init(Docker·AWS CLI 설치) 2~4분 대기
./deploy.sh deploy     # GHCR pull + compose 기동 + 헬스체크
./deploy.sh mem        # ⚠️ 2GB 여유 확인
```

`.env` 주의사항:

- **`APP_ENCRYPTION_SECRET`은 자동 생성되지 않는다.** 비어 있으면 `setup`·`deploy`가 즉시 실패한다
  (`DB_PASSWORD`·`JWT_SECRET`은 비어 있으면 생성해 `.env`에 적는다). 운영 중인 값을 **그대로** 쓸 것 —
  서버 `/opt/app/.env`, 로컬 `deploy/vultr/.env`, 오프사이트 보관본(6절). GitHub secret `VULTR_DEPLOY_ENV`는
  다시 읽을 수 없어 출처가 될 수 없다. 새로 만들면 앱은 정상 기동하면서 저장된 Upbit 키만 조용히
  복호화 불능이 된다.
- **호스트를 옮기는 중에는 `TRADING_AUTO_START=false`** 로 두고, 옛 앱이 멈춘 것을 확인하기 전에는 UI 에서 봇을 켜지
  않는다(4절). `UPBIT_*` 는 거래에 쓰이지 않는다 — 거래 키는 사용자별로 DB 에 암호화돼 있어, 비워도 이중 거래를 막지 못한다.

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

`Caddyfile` 은 배포가 서버로 복사만 하고 실행 중인 caddy 에 다시 읽히지 않는다 — caddy 컨테이너가 새로 뜰 때
(`caddy:2-alpine` 의 새 digest 를 받은 배포·서버 재부팅) 적용된다. 바로 적용하려면 서버에서
`cd /opt/app && docker compose restart caddy`(수 초 HTTPS 중단, 인증서는 `caddy_data` 볼륨에 남는다). 자동 롤백은 app
헬스만 보고 HTTPS e2e 실패는 경고로 끝나므로, Caddyfile 을 바꿀 때는 머지 전에 같은 이미지로 검증한다
(`{$APP_DOMAIN}` 이 비면 빈 문자열이 되므로 자리 도메인을 준다):

```bash
docker run --rm -e APP_DOMAIN=bot.example.test -v "$PWD/deploy/vultr/Caddyfile":/etc/caddy/Caddyfile:ro \
  caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

### GitHub Actions 자동 배포

`.github/workflows/deploy.yml`의 `deploy-vultr` job은 인스턴스 생성·삭제 없이 현재 운영 호스트에만
SSH로 배포한다. 다음 repository secrets가 필요하다.

| Secret | 내용 |
|---|---|
| `VULTR_DEPLOY_ENV` | 운영 `.env` 내용(multiline) |
| `VULTR_PUBLIC_IP` | 현재 운영 인스턴스 공인 IP |
| `VULTR_SSH_PRIVATE_KEY` | 운영 호스트 SSH 개인키 원문(`deploy/vultr/<APP_NAME>-key.pem`, 기본 `coin-trading-bot-key.pem` — workflow 는 이것을 `coin-trading-bot-key.pem` 이름으로 써서 쓴다) |
| `VULTR_SSH_USER` | Vultr SSH 사용자(현재 `root`) |

운영 호스트 키는 `deploy/vultr/known_hosts`에 고정되어 Actions와 배포 스크립트가 최초 접속부터
검증한다. 운영 IP를 재생성하거나 호스트를 교체할 때는 새 호스트 키를 별도 경로로 확인한 뒤
이 파일을 갱신해야 하며, `accept-new`로 우회하지 않는다. Compose의 `GHCR_IMAGE`도 workflow가
빌드·push한 저장소와 동일하게 주입된다.

GitHub-hosted runner의 SSH 출발 IP는 실행마다 바뀌므로 Vultr cloud firewall에
`ctb-ssh-github-actions` 규칙(22/tcp, `0.0.0.0/0`)을 유지한다. 이 규칙을 열기 전에 운영 SSH는
`PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin prohibit-password`
인 key-only 상태여야 한다. `setup_firewall`을 다시 실행해도 이 전용 규칙은 보존되지만, SSH 설정을
되돌리거나 규칙을 삭제하면 Actions 배포가 timeout된다.

현재 SSH 세션을 유지한 채 운영 호스트에서 hardening을 적용하고 확인한다. `sshd -t`가 실패하면
reload하지 않는다. `<APP_NAME>` 은 그 체크아웃 `.env` 의 값이다(기본 `coin-trading-bot`).

```bash
ssh -i deploy/vultr/<APP_NAME>-key.pem root@<VULTR_PUBLIC_IP> 'sudo tee /etc/ssh/sshd_config.d/00-coin-trading-bot-hardening.conf >/dev/null <<"EOF"
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PermitEmptyPasswords no
PubkeyAuthentication yes
EOF
sudo sshd -t && sudo systemctl reload ssh
sudo sshd -T | grep -E "^(passwordauthentication|kbdinteractiveauthentication|permitemptypasswords|permitrootlogin|pubkeyauthentication) "'
```

기대값은 `passwordauthentication no`, `kbdinteractiveauthentication no`, `permitemptypasswords no`,
`permitrootlogin without-password`, `pubkeyauthentication yes`다. 전용 규칙이 없거나 형식이
`22/tcp`, `0.0.0.0/0`과 다르면 `setup_firewall`은 경고 또는 실패하므로 Vultr 콘솔/API에서 먼저
하나의 정확한 규칙만 만든다.

job은 원격 `/opt/app/.last-good-sha`의 성공 확인 SHA를 rollback 기준으로 사용한다. 파일이 없는
최초 실행은 현재 app 컨테이너가 healthy이고 40자리 commit SHA일 때만 bootstrap하며, `latest`·digest·
중지/비정상 컨테이너만 남아 있으면 배포를 거부한다. 성공한 배포와 rollback은 이 파일을 갱신한다.
또한 queued 실행의 SHA가 최신 `origin/main`과 다르면 오래된 배포를 건너뛴다. 배포 전후의 임시
`.env`, `.state`, SSH key는 Actions runner에서 삭제한다. 수동 배포와 Actions 배포를 동시에 실행하지 않는다.

## 3. 백업 복원

덤프로 DB 를 되살릴 때 쓴다 — 같은 호스트의 DB 를 백업으로 되돌릴 때와 호스트를 옮길 때(4절) 모두.
`deploy.sh ssh` 는 그 체크아웃의 `.state` 가 가리키는 호스트에 붙는다.

```bash
# 1) 복원할 호스트의 /tmp/trading.sql.gz 에 덤프를 둔다 — 둘 중 하나
./deploy/vultr/deploy.sh ssh
  cd /opt/app
  # a. 지금 DB 에서 뜬다(앱이 쓰지 않을 때 — 호스트 이전이면 옛 앱을 멈춘 뒤 옛 호스트에서)
  docker compose exec -T postgres pg_dump -U trading -d trading --no-owner | gzip -c > /tmp/trading.sql.gz
  # b. 6절의 S3 백업을 받는다(backup.sh 와 같은 자격증명·엔드포인트. 목록은 같은 식으로 s3 ls)
  (set -a; . ./.env; set +a; aws ${BACKUP_S3_ENDPOINT:+--endpoint-url "$BACKUP_S3_ENDPOINT"} \
    s3 cp "s3://$BACKUP_S3_BUCKET/${BACKUP_S3_PREFIX:-db-backups}/trading-<TS>.sql.gz" /tmp/trading.sql.gz)

# 2) 복원 — 앱을 멈추고 DB 를 새로 만든 뒤 넣고, 봇을 멈춘 상태로 바꾼 다음 앱을 띄운다
./deploy/vultr/deploy.sh ssh
  cd /opt/app && docker compose stop app
  docker compose exec -T postgres psql -U trading -d postgres -c 'DROP DATABASE trading' -c 'CREATE DATABASE trading'
  gunzip -c /tmp/trading.sql.gz | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading
  docker compose exec -T postgres psql -U trading -d trading -c 'UPDATE bot_state SET running = false'
  docker compose start app
```

- `UPDATE bot_state SET running = false` 가 없으면 `TRADING_AUTO_START=true` 인 운영 `.env` 에서 앱이 백업 시점의 봇을
  검증 전에 되살린다. 아래 검증을 통과한 뒤 UI 에서 봇을 켠다.
- 앱이 한 번이라도 뜬 DB 에는 Flyway 가 만든 스키마가 있어, 다시 만들지 않고 넣으면 `already exists` 로 멈춘다.
  앱 연결이 남아 있으면 `DROP DATABASE` 가 실패한다 — `app` 이 멈췄는지 확인하고 다시 실행한다.
- 덤프를 다른 호스트로 옮길 때는 저장소 밖(예: `~/ctb-migrate/`)을 거쳐 `scp -i deploy/vultr/<APP_NAME>-key.pem` 으로
  복원할 호스트의 `/tmp/` 에 올린다(`<APP_NAME>` 은 그 체크아웃 `.env` 의 값). 덤프에는 암호화된 Upbit 키와 사용자 데이터가
  들어 있다 — 체크아웃 안의 `*.sql.gz` 는 무시 대상이 아니라 커밋될 수 있다. 끝나면 로컬 `~/ctb-migrate/` 와 서버
  `/tmp/trading.sql.gz` 를 지운다.

복원 후 **거래 활성화 전에** 반드시 검증한다:

- 테이블 수·핵심 테이블 행 수가 옛 호스트(또는 백업 시점)와 일치하는가
- 최신 거래 시각이 덤프 시점과 맞는가
- **저장된 Upbit 키가 실제로 복호화되는가** (앱 UI에서 키 조회 — 실패면 `APP_ENCRYPTION_SECRET`이
  다른 것이다. 이 경우 **절대 거래를 켜지 말 것**)

키가 같은지 원문 노출 없이 확인하려면 지문만 비교한다(양쪽에서 실행해 해시 일치 확인):

```bash
grep '^APP_ENCRYPTION_SECRET=' .env | cut -d= -f2- | tr -d '\n' | shasum -a 256
```

## 4. 호스트 이전 (⚠️ 단일 실행 보장)

**절대 원칙: 어느 시점에도 거래를 활성화한 인스턴스는 하나뿐이어야 한다.**
같은 Upbit 계정에 두 봇이 붙으면 이중 주문·중복 청산이 발생한다. 같은 계정 키를 가진 로컬 실행도 한 인스턴스로 센다.

> ⚠️ 옛 호스트를 살려 둔 채 새 호스트로 옮기는 절차는 **스크립트가 지원하지 않고 리허설한 적도 없다**(#295).
> 이전이 필요하면 먼저 #295 에서 절차를 만들어 리허설한다. 아래는 그때 지킬 제약과 빠뜨리면 안 되는 항목이다.

`deploy.sh` 는 체크아웃 하나에서 호스트 하나만 다룬다(2026-10-03 코드 확인):

- `ssh`·`stop`·`deploy`·`destroy` 는 `deploy/vultr/.state` 가 가리키는 호스트에 작동하고, `destroy` 는 그 인스턴스와
  방화벽 그룹을 지운다.
- `setup` 은 `.state` 의 인스턴스를, 없으면 label 이 `APP_NAME` 인 인스턴스를 다시 쓴다 — 같은 체크아웃에서 새 호스트를
  만들려 하면 운영 호스트에 배포된다.
- `.env` 의 값이 명령줄 환경변수보다 우선한다(스크립트가 `.env` 를 먼저 읽고, 템플릿에 `APP_NAME`·`APP_VERSION` 이 들어
  있다). label·SSH 키(`<APP_NAME>-key.pem`)·방화벽 그룹 이름이 `APP_NAME` 을 따르므로, 두 호스트를 함께 다루려면 별도
  체크아웃과 다른 `APP_NAME` 이 필요하다.
- Actions 배포 대상은 `.state` 가 아니라 secret `VULTR_PUBLIC_IP`·`VULTR_SSH_PRIVATE_KEY` 와 `deploy/vultr/known_hosts` 다.

빠뜨리면 안 되는 항목:

- **배포 동결** — 이전 동안 main 머지를 멈추고 Actions 배포를 끈다. 켜 두면 push 가 secret 의 옛 IP 로 배포해 멈춘 옛
  호스트를 운영 `.env` 로 다시 띄운다. 배포 workflow 를 끄면 PR 테스트·이미지 빌드도 멈추므로, 그동안의 수동 `deploy` 는
  `.env` 의 `APP_VERSION` 을 이미지가 있는 SHA(서버 `/opt/app/.last-good-sha`)로 고정한다.
- **새 호스트는 거래 없이 먼저** — `TRADING_AUTO_START=false`. 복원·검증 전까지 `APP_ALLOW_CIDR` 를 운영자 IP/32 로 둔다
  (빈 DB 로 443 이 열리면 먼저 가입한 사람이 유일한 사용자가 된다 — 7절). `APP_ENCRYPTION_SECRET` 은 운영 값 그대로.
- **옛 앱만 멈추고 최종 덤프** — 옛 호스트는 `docker compose stop app` 으로 앱만 멈춘다(`./deploy.sh stop` 은
  `docker compose down` 이라 postgres 까지 내려 덤프를 뜰 수 없다). 진행 중이던 tick·주문 후처리가 끝났는지 로그로
  확인하고(`stop_grace_period: 40s`), **Upbit에서 미체결 주문·잔고·보유 포지션 스냅샷을 기록한다**(복구 시 대조 기준).
  그 뒤에 최종 덤프(3절 1 a)를 뜬다. 미리 뜬 덤프는 버린다.
- **복원·검증 뒤에만 거래** — 3절로 복원·검증하고, Upbit API 키에 허용 IP 를 쓰면 새 호스트 IP 를 등록한 뒤 UI 에서
  수동으로 켠다.
- **백업 cron 이전** — 스크립트는 cron 을 등록하지 않는다(6절은 수동). 옛 호스트의 cron 을 끄고(두면 멈춘 옛 DB 가 S3 의
  최신 객체가 된다), 새 호스트에 6절 cron 을 등록해 `./backup.sh` 를 한 번 돌려 확인한다. 옛 호스트 `destroy` 는
  `BACKUP_S3_BUCKET` 이 있으면 최종 백업을 한 번 더 올리므로, 그때는 옛 체크아웃 `.env` 의 `BACKUP_S3_BUCKET` 을 비운다
  (옛 DB 는 최종 덤프로 이미 남아 있다).
- **Actions 대상 전환** — 새 호스트에 2절 SSH hardening 을 적용·확인한 뒤 새 방화벽 그룹에 `ctb-ssh-github-actions` 규칙을
  넣고(스크립트가 만들지 않는다), secret `VULTR_PUBLIC_IP`·`VULTR_SSH_PRIVATE_KEY` 와 `deploy/vultr/known_hosts`(2절)를
  새 호스트로 바꾼 다음 배포를 다시 켠다.
- **도메인** — `APP_DOMAIN` 을 비워 두면 주소가 새 IP 의 sslip 도메인으로 바뀐다. 보유 도메인을 쓰면 검증 동안은 비워
  sslip 으로 보고, 끝나면 DNS A 레코드를 새 IP 로 바꾼 뒤 다시 넣고 `deploy` 한다.
- **옛 호스트 정리** — 옛 앱이 여전히 멈춰 있는지 확인한 뒤 옛 호스트의 `.state` 로 `destroy` 한다. 새 호스트의 무시
  파일(`.env`·`.state`·SSH 키)은 평소 쓰는 체크아웃으로 옮기고 오프사이트에도 보관한다.

## 5. 장애 복구

장애 복구는 거래 중지 → 최신 검증 백업 확보 → 새 Vultr 호스트 복원(3·4절) → Upbit 실제 잔고·미체결과의
수동 정합성 대조 순서로, 별도 승인을 받아 진행한다. 두 DB 에 각각 쓰기가 발생했다면 **자동 병합하지 말 것** —
수동으로 정합성을 조사한다. 받은 S3 객체가 운영 호스트의 최신 백업인지(이전 직후라면 옛 호스트의 것이 아닌지) 확인한다.

옛 인스턴스가 Vultr 에 남아 있지 않으면(콘솔·API 에서 label 이 `APP_NAME` 인 인스턴스가 없음을 확인) 별도 체크아웃 없이,
낡은 `.state` 를 다른 이름으로 옮겨 둔 뒤 같은 체크아웃에서 4절의 "새 호스트는 거래 없이 먼저" 조건으로 `setup` →
`deploy` 하고 3절로 복원한다(덤프는 3절 1 b). 응답만 없는 인스턴스가 남아 있으면 `setup` 이 그것을 다시 쓰므로 4절의
제약을 따른다. 백업 cron·Actions 대상·도메인은 4절 항목과 같다.

> 이력: 2026-07-30 AWS → Vultr 이전과 그 롤백 창구(AWS 인스턴스 정지 보존)는 2026-07-31 AWS 자원 삭제로
> 끝났고, `deploy/aws`·`deploy/oci` 판은 2026-10 에 저장소에서도 지웠다. 당시 이전·롤백 런북은
> `git show 83fd87e:deploy/vultr/README.md` 의 3~5절에 있다.

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
```

- 복원은 3절을 따른다(객체 받기는 3절 1 b). 앱이 띄운 DB 에 덤프를 그대로 흘려 넣으면 Flyway 스키마와 충돌한다.
- 업로드는 크기 검증까지 통과해야 성공으로 본다. 실패하면 **exit≠0**으로 끝난다.
- 보존 정리는 파일명이 아니라 객체의 `LastModified` 기준이며 삭제 실패는 경고로 남는다.
- ⚠️ **"업로드 성공"은 "복원 가능"이 아니다.** 주기적으로 실제 복원 시험을 할 것.
- ⚠️ dump에는 `APP_ENCRYPTION_SECRET`으로 암호화된 Upbit 키가 들어있다. 그 AES 키는 **백업과 다른
  곳에 오프사이트 보관** — 같은 곳에 두면 유출 시 즉시 복호화된다.

## 7. 보안

- **TLS 종단**: Caddy가 443에서 HTTPS를 종단(Let's Encrypt 자동 발급/갱신)하고 내부 `app:8080`으로
  프록시한다. 인증서 자동 갱신(ACME HTTP-01)을 위해 **80은 상시 개방**이 필요하다.
- **Vultr 클라우드 방화벽**을 쓴다(AWS security group과 같은 의미). 규칙 없이 그룹만 붙이면 모든
  인바운드가 차단되므로 22/80/443을 명시한다. 수동 SSH는 `SSH_ALLOW_CIDR`로 제한하고,
  GitHub Actions는 `ctb-ssh-github-actions` 전용 22/tcp `0.0.0.0/0` 규칙을 사용한다.
- 스크립트가 관리하는 규칙(`notes`가 `ctb-`로 시작)만 교체하므로, SSH 대역이 바뀌어도 옛 규칙이
  남지 않는다. 사람이 직접 추가한 규칙은 건드리지 않는다.
- 수동 배포 SSH는 `StrictHostKeyChecking=accept-new`, Actions 배포는 추적 중인
  `deploy/vultr/known_hosts`와 `StrictHostKeyChecking=yes`를 사용한다.
- PostgreSQL은 호스트에 노출하지 않는다(compose 내부망 전용).
- **회원가입은 계정이 하나도 없는 서버에서만 열린다**(첫 계정 = 소유자). 새 서버를 빈 DB 로 띄우면 443 이 열린 순간
  먼저 가입한 사람이 유일한 사용자가 된다 — DB 를 백업에서 복원한 뒤 공개하거나, 배포 직후 곧바로 가입한다.
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
