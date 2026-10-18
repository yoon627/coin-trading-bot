# Vultr 배포 운영 절차

운영 호스트는 Vultr 서울(`icn`) `vc2-1c-2gb`(1 vCPU x86_64 / 2GB / 55GB SSD) 한 대다. caddy + app + postgres 를
Docker Compose 로 띄운다. DB 를 잃으면 6절 백업에서 3·5절 순서로 복구한다.

메모리 제한은 app 832m / postgres 512m / caddy 96m, 합계 1440m 이다. 나머지가 OS·docker 몫이므로 배포 뒤
`./deploy.sh mem` 으로 실사용량을 확인한다. 부족하면 콘솔에서 `vc2-2c-2gb` 나 `vc2-2c-4gb` 로 리사이즈하고
compose 제한도 함께 올린다.

## 0. 변경 전 확인

`setup`·`destroy`·리사이즈·새 인스턴스 생성 전에는 [Vultr 상태 페이지](https://status.vultr.com/)를 본다.
공급자 장애나 예정된 maintenance 중에는 인프라를 바꾸지 않는다. 운영 인스턴스가 healthy 라면 그 사이 앱을
재시작하거나 재생성하지 않는다.

## 0.1. 계정 준비

1. https://www.vultr.com 가입 후 결제수단을 등록한다.
2. 콘솔 Account → API 에서 API Key 를 발급한다.
3. 같은 화면의 `Access Control` 에 현재 공인 IP 를 넣는다(`curl -s https://checkip.amazonaws.com`).
   빠뜨리면 모든 API 호출이 401/403 으로 실패한다.
4. 로컬에 `curl`, `jq`, `openssl`, `ssh`, `scp` 가 필요하다.

## 1. 배포

```bash
cd deploy/vultr
install -m 600 .env.example .env   # 시크릿이 들어가므로 600
# VULTR_API_KEY 와 APP_ENCRYPTION_SECRET(운영 중인 값) 을 채운다

./deploy.sh setup      # SSH 키 + 방화벽 + 인스턴스 생성
# cloud-init(Docker·AWS CLI 설치)에 2~4분 걸린다
./deploy.sh deploy     # GHCR pull + compose 기동 + 헬스체크
./deploy.sh mem        # 메모리 여유 확인
```

`setup` 은 다시 실행해도 된다. 이미 만든 리소스는 건너뛰고 남은 단계부터 진행한다.

`.env` 주의사항:

- `APP_ENCRYPTION_SECRET` 은 자동 생성되지 않으며 비어 있으면 `setup`·`deploy` 가 실패한다. 저장된 Upbit 키를
  복호화하는 키라 새로 만들면 앱은 정상 기동하지만 키를 읽지 못한다. 운영 중인 값을 그대로 쓴다. 출처는 서버
  `/opt/app/.env`, 로컬 `deploy/vultr/.env`, 오프사이트 보관본이다. GitHub secret `VULTR_DEPLOY_ENV` 는 다시 읽을
  수 없다.
- `DB_PASSWORD`·`JWT_SECRET` 은 `setup` 이 인스턴스를 새로 만들 때만 비어 있으면 생성해 `.env` 에 적는다. 기존
  인스턴스에 대한 `setup`·`deploy` 는 비어 있으면 실패한다. 새 `DB_PASSWORD` 는 기존 DB 볼륨과 맞지 않아 앱과
  자동 롤백이 모두 뜨지 못한다.
- 거래 키는 사용자별로 DB 에 암호화돼 있어 환경변수로 이중 거래를 막을 수 없다. 호스트를 옮기는 동안에는
  `TRADING_AUTO_START=false` 로 두고, 옛 앱이 멈춘 것을 확인하기 전에는 UI 에서 봇을 켜지 않는다(4절).

## 2. 운영 명령

```bash
./deploy.sh status   # 컨테이너 상태
./deploy.sh logs     # 앱 로그
./deploy.sh mem      # 메모리 실사용
./deploy.sh ssh      # 접속
./deploy.sh stop     # docker compose down (인스턴스 과금은 계속)
./deploy.sh start    # 재기동
./deploy.sh destroy  # 인스턴스·방화벽 삭제
```

새 버전은 `main` push → Actions 테스트·이미지 push → `deploy-vultr` job 이 `./deploy.sh deploy` 를 실행하는
순서로 나간다. 이미지는 대상 커밋 SHA 로 고정하고, 헬스체크(180s)가 실패하면 직전 정상 SHA 로 자동 롤백한다.
직전 정상 SHA 와의 차이에 DB migration 이 있으면 자동 롤백하지 않고 수동 개입을 안내한다.

`Caddyfile` 은 서버로 복사만 되고 실행 중인 caddy 가 다시 읽지 않는다. caddy 컨테이너가 새로 뜰 때(새 이미지
digest, 서버 재부팅) 적용된다. 바로 적용하려면 서버에서 `cd /opt/app && docker compose restart caddy` 를
실행한다(수 초간 HTTPS 중단, 인증서는 `caddy_data` 볼륨에 남는다). 자동 롤백은 app 헬스만 보고 HTTPS 실패는
경고로 끝나므로, Caddyfile 을 바꿀 때는 머지 전에 검증한다(`APP_DOMAIN` 이 비면 빈 문자열이 되므로 임의 도메인을 준다):

```bash
docker run --rm -e APP_DOMAIN=bot.example.test -v "$PWD/deploy/vultr/Caddyfile":/etc/caddy/Caddyfile:ro \
  caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

### GitHub Actions 자동 배포

`.github/workflows/deploy.yml` 의 `deploy-vultr` job 은 인스턴스를 만들거나 지우지 않고 현재 운영 호스트에
SSH 로 배포한다. 필요한 repository secret:

| Secret | 내용 |
|---|---|
| `VULTR_DEPLOY_ENV` | 운영 `.env` 내용(여러 줄) |
| `VULTR_PUBLIC_IP` | 운영 인스턴스 공인 IP |
| `VULTR_SSH_PRIVATE_KEY` | 운영 호스트 SSH 개인키 원문(`deploy/vultr/<APP_NAME>-key.pem`). workflow 는 `coin-trading-bot-key.pem` 이름으로 저장해 쓴다 |
| `VULTR_SSH_USER` | SSH 사용자(`root`) |

운영 호스트 키는 `deploy/vultr/known_hosts` 에 고정돼 있어 Actions 는 최초 접속부터 검증한다. 호스트를 바꾸면
새 호스트 키를 별도 경로로 확인한 뒤 이 파일을 갱신한다. `accept-new` 로 우회하지 않는다.

GitHub-hosted runner 의 출발 IP 는 매번 바뀌므로 Vultr 방화벽에 `ctb-ssh-github-actions` 규칙(22/tcp,
`0.0.0.0/0`)을 하나만 둔다. `setup_firewall` 은 이 규칙을 보존하고, 없으면 경고, 형식이 다르거나 중복이면
실패한다. 스크립트는 이 규칙을 만들지 않으므로 콘솔이나 API 로 직접 만든다. 규칙을 열기 전에 운영 SSH 를
key-only 로 만든다. 현재 SSH 세션을 유지한 채 적용하고, `sshd -t` 가 실패하면 reload 하지 않는다.
`<APP_NAME>` 은 그 체크아웃 `.env` 의 값이다(기본 `coin-trading-bot`).

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
`permitrootlogin without-password`, `pubkeyauthentication yes` 다.

job 동작:

- 롤백 기준은 원격 `/opt/app/.last-good-sha` 다. 파일이 없으면 현재 app 컨테이너가 healthy 이고 이미지 태그가
  40자리 SHA 일 때만 그 값을 쓰고, 그 밖의 경우(`latest`·digest·중지된 컨테이너)는 배포를 거부한다. 성공한 배포와
  롤백이 이 파일을 갱신한다.
- 대기하던 실행의 SHA 가 최신 `origin/main` 과 다르면 건너뛴다. 단 차이가 문서(`**.md`·`docs/**`)뿐이면 배포한다.
- 임시 `.env`·`.state`·SSH 키는 끝나면 runner 에서 지운다.
- 수동 배포와 Actions 배포를 동시에 돌리지 않는다.

## 3. 백업 복원

같은 호스트의 DB 를 백업 시점으로 되돌릴 때와 호스트를 옮길 때(4절) 모두 쓴다. `deploy.sh ssh` 는 그 체크아웃의
`.state` 가 가리키는 호스트에 접속한다.

```bash
# 1) 복원할 호스트의 /tmp/trading.sql.gz 에 덤프를 둔다 (a 또는 b)
./deploy/vultr/deploy.sh ssh
  cd /opt/app
  # a. 현재 DB 에서 뜬다. 앱이 쓰지 않을 때만(호스트 이전이면 옛 앱을 멈춘 뒤 옛 호스트에서)
  docker compose exec -T postgres pg_dump -U trading -d trading --no-owner | gzip -c > /tmp/trading.sql.gz
  # b. 6절의 S3 백업을 받는다. 목록은 같은 방식으로 s3 ls
  (set -a; . ./.env; set +a; aws ${BACKUP_S3_ENDPOINT:+--endpoint-url "$BACKUP_S3_ENDPOINT"} \
    s3 cp "s3://$BACKUP_S3_BUCKET/${BACKUP_S3_PREFIX:-db-backups}/trading-<TS>.sql.gz" /tmp/trading.sql.gz)

# 2) 앱을 멈추고 DB 를 새로 만든 뒤 넣고, 봇을 멈춘 상태로 바꾼 다음 앱을 띄운다
./deploy/vultr/deploy.sh ssh
  cd /opt/app && docker compose stop app
  docker compose exec -T postgres psql -U trading -d postgres -c 'DROP DATABASE trading' -c 'CREATE DATABASE trading'
  gunzip -c /tmp/trading.sql.gz | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U trading -d trading
  docker compose exec -T postgres psql -U trading -d trading -c 'UPDATE bot_state SET running = false'
  docker compose start app
```

- `UPDATE bot_state SET running = false` 를 빼면 `TRADING_AUTO_START=true` 인 환경에서 앱이 백업 시점의 봇을
  검증 전에 다시 켠다. 아래 검증을 통과한 뒤 UI 에서 봇을 켠다.
- 앱이 한 번이라도 뜬 DB 에는 Flyway 스키마가 있어 DB 를 새로 만들지 않고 넣으면 `already exists` 로 멈춘다.
  앱 연결이 남아 있으면 `DROP DATABASE` 가 실패하므로 `app` 이 멈췄는지 확인하고 다시 실행한다.
- 덤프를 다른 호스트로 옮길 때는 저장소 밖(예: `~/ctb-migrate/`)을 거쳐 `scp -i deploy/vultr/<APP_NAME>-key.pem` 으로
  복원할 호스트의 `/tmp/` 에 올린다. 덤프에는 암호화된 Upbit 키와 사용자 데이터가 있고, 체크아웃 안의 `*.sql.gz` 는
  gitignore 대상이 아니라 커밋될 수 있다. 끝나면 로컬 `~/ctb-migrate/` 와 서버 `/tmp/trading.sql.gz` 를 지운다.

복원 후 거래를 켜기 전에 확인한다:

- 테이블 수와 핵심 테이블 행 수가 옛 호스트(또는 백업 시점)와 같은가
- 최신 거래 시각이 덤프 시점과 맞는가
- 저장된 Upbit 키가 복호화되는가(앱 UI 에서 키 조회). 실패하면 `APP_ENCRYPTION_SECRET` 이 다른 것이니 거래를 켜지 않는다.

키가 같은지는 원문을 보이지 않고 양쪽 해시로 비교한다:

```bash
grep '^APP_ENCRYPTION_SECRET=' .env | cut -d= -f2- | tr -d '\n' | shasum -a 256
```

## 4. 호스트 이전

어느 시점에도 거래를 켠 인스턴스는 하나뿐이어야 한다. 같은 Upbit 계정에 봇이 둘 붙으면 주문과 청산이 이중으로
나간다. 같은 키를 쓰는 로컬 실행도 한 인스턴스로 센다.

옛 호스트를 살려 둔 채 새 호스트로 옮기는 절차는 스크립트가 지원하지 않는다. 이전이 필요하면 절차를 먼저 만들어
리허설한다. 아래는 그때 지킬 제약이다.

`deploy.sh` 는 체크아웃 하나에서 호스트 하나만 다룬다:

- `ssh`·`stop`·`deploy`·`destroy` 는 `deploy/vultr/.state` 의 호스트에 작동하고, `destroy` 는 그 인스턴스와 방화벽
  그룹을 지운다.
- `setup` 은 `.state` 의 인스턴스를, 없으면 label 이 `APP_NAME` 인 인스턴스를 다시 쓴다. 같은 체크아웃에서 새 호스트를
  만들려 하면 운영 호스트에 배포된다.
- 스크립트가 `.env` 를 먼저 읽으므로 `.env` 의 값이 명령줄 환경변수보다 우선한다. label·SSH 키(`<APP_NAME>-key.pem`)·
  방화벽 그룹 이름이 `APP_NAME` 을 따르므로 두 호스트를 함께 다루려면 별도 체크아웃과 다른 `APP_NAME` 이 필요하다.
- Actions 배포 대상은 `.state` 가 아니라 secret `VULTR_PUBLIC_IP`·`VULTR_SSH_PRIVATE_KEY` 와 `deploy/vultr/known_hosts` 다.

빠뜨리면 안 되는 항목:

- 배포 동결: 이전 동안 main 머지를 멈추고 Actions 배포를 끈다. 켜 두면 secret 의 옛 IP 로 배포해 멈춘 옛 호스트를
  다시 띄운다. 그동안 PR 테스트·이미지 빌드도 멈추므로 수동 `deploy` 는 `.env` 의 `APP_VERSION` 을 이미지가 있는
  SHA(서버 `/opt/app/.last-good-sha`)로 고정한다.
- 새 호스트는 거래 없이 먼저 띄운다: `TRADING_AUTO_START=false`, 복원·검증 전까지 `APP_ALLOW_CIDR` 를 운영자 IP/32 로
  둔다(7절 회원가입 참고). `APP_ENCRYPTION_SECRET` 은 운영 값 그대로 쓴다.
- 옛 호스트는 `docker compose stop app` 으로 앱만 멈춘다. `./deploy.sh stop` 은 `docker compose down` 이라 postgres
  까지 내려 덤프를 뜰 수 없다. 진행 중이던 tick·주문 후처리가 끝났는지 로그로 확인하고(`stop_grace_period: 40s`),
  Upbit 의 미체결 주문·잔고·보유 포지션을 기록해 둔다. 그 뒤 최종 덤프(3절 1 a)를 뜨고, 미리 뜬 덤프는 버린다.
- 3절로 복원·검증하고, Upbit API 키에 허용 IP 를 쓰면 새 호스트 IP 를 등록한 뒤 UI 에서 직접 거래를 켠다.
- 백업 cron: 스크립트는 cron 을 등록하지 않는다. 옛 호스트의 cron 을 끄고(두면 멈춘 옛 DB 가 S3 의 최신 객체가 된다)
  새 호스트에 6절 cron 을 등록한 뒤 `./backup.sh` 를 한 번 돌려 확인한다. 옛 호스트 `destroy` 는
  `BACKUP_S3_BUCKET` 이 있으면 최종 백업을 한 번 더 올리므로 옛 체크아웃 `.env` 의 `BACKUP_S3_BUCKET` 을 비운다.
- Actions 대상 전환: 새 호스트에 2절 SSH hardening 을 적용·확인하고 새 방화벽 그룹에 `ctb-ssh-github-actions` 규칙을
  넣은 뒤, secret `VULTR_PUBLIC_IP`·`VULTR_SSH_PRIVATE_KEY` 와 `deploy/vultr/known_hosts` 를 바꾸고 배포를 다시 켠다.
- 도메인: `APP_DOMAIN` 이 비어 있으면 주소가 새 IP 의 sslip.io 도메인으로 바뀐다. 보유 도메인을 쓰면 검증 동안은
  비워 두고, 끝나면 DNS A 레코드를 새 IP 로 바꾼 뒤 다시 넣고 `deploy` 한다.
- 옛 호스트 정리: 옛 앱이 멈춰 있는지 확인하고 옛 호스트의 `.state` 로 `destroy` 한다. 새 호스트의 무시 파일
  (`.env`·`.state`·SSH 키)은 평소 쓰는 체크아웃으로 옮기고 오프사이트에도 보관한다.

## 5. 장애 복구

거래 중지 → 최신 검증 백업 확보 → 새 Vultr 호스트에 복원(3·4절) → Upbit 실제 잔고·미체결과 수동 대조 순서로
진행한다. 두 DB 에 각각 쓰기가 있었다면 자동 병합하지 말고 수동으로 맞춘다. 받은 S3 객체가 운영 호스트의 최신
백업인지(이전 직후라면 옛 호스트의 것이 아닌지) 확인한다.

옛 인스턴스가 Vultr 에 남아 있지 않으면(콘솔·API 에서 label 이 `APP_NAME` 인 인스턴스가 없음을 확인) 낡은 `.state`
를 다른 이름으로 옮겨 두고, 같은 체크아웃에서 4절의 "거래 없이 먼저" 조건으로 `setup` → `deploy` 한 뒤 3절로
복원한다(덤프는 3절 1 b). 응답만 없는 인스턴스가 남아 있으면 `setup` 이 그것을 다시 쓰므로 4절 제약을 따른다.
백업 cron·Actions 대상·도메인은 4절과 같다.

## 6. DB 백업

Vultr 에는 인스턴스 롤이 없어 액세스 키를 서버에 둔다. 해당 버킷에만 권한이 있는 전용 키를 쓴다.
저장소는 S3 호환이면 되고 `.env` 의 `BACKUP_S3_ENDPOINT` 만 바꾼다:

| 대상 | `BACKUP_S3_ENDPOINT` |
|---|---|
| AWS S3 | 비워 둔다 |
| Vultr Object Storage | `https://sgp1.vultrobjects.com` |
| Cloudflare R2 | `https://<account>.r2.cloudflarestorage.com` |

```bash
# ./deploy.sh ssh 접속 후 cron 등록
crontab -e
# 0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00
```

- 복원은 3절을 따른다. 앱이 띄운 DB 에 덤프를 그대로 넣으면 Flyway 스키마와 충돌한다.
- 업로드 뒤 원격 객체 크기까지 맞아야 성공이며, 실패하면 0 이 아닌 코드로 끝난다.
- 보존 정리는 객체의 `LastModified` 기준이고 삭제 실패는 경고로 남는다.
- 업로드 성공이 복원 가능을 뜻하지는 않는다. 주기적으로 실제 복원을 시험한다.
- 덤프에는 `APP_ENCRYPTION_SECRET` 으로 암호화된 Upbit 키가 있다. 그 키는 백업과 다른 곳에 보관한다.

## 7. 보안

- Caddy 가 443 에서 TLS 를 종단(Let's Encrypt 자동 발급·갱신)하고 내부 `app:8080` 으로 프록시한다. ACME HTTP-01
  때문에 80 은 계속 열어 둔다.
- Vultr 방화벽 그룹은 규칙이 없으면 모든 인바운드를 막으므로 22/80/443 을 명시한다. 수동 SSH 는 `SSH_ALLOW_CIDR`
  로 제한하고, Actions 는 `ctb-ssh-github-actions` 규칙을 쓴다.
- 스크립트는 `notes` 가 `ctb-` 로 시작하는 규칙만 교체하므로 SSH 대역이 바뀌어도 옛 규칙이 남지 않고, 직접 추가한
  규칙은 건드리지 않는다.
- 수동 배포 SSH 는 `StrictHostKeyChecking=accept-new`, Actions 배포는 `deploy/vultr/known_hosts` 와
  `StrictHostKeyChecking=yes` 를 쓴다.
- PostgreSQL 은 호스트에 노출하지 않는다.
- 회원가입은 계정이 하나도 없을 때만 열린다(첫 계정이 소유자). 빈 DB 로 443 이 열리면 먼저 가입한 사람이 유일한
  사용자가 되므로, 백업을 복원한 뒤 공개하거나 배포 직후 바로 가입한다.
- `.env` 는 로컬·서버 모두 `600` 이며 gitignore 돼 있다.

## 8. 트러블슈팅

- API 호출이 전부 401/403: `Access Control` 에 현재 공인 IP 가 있는지 본다.
- `deploy` 헬스체크 실패: `./deploy.sh logs`. Flyway 마이그레이션 오류나 시크릿 누락이 흔하다.
- cloud-init 실패(Docker 또는 AWS CLI 없음): `./deploy.sh ssh` 후 `cat /var/log/cloud-init-output.log`. AWS CLI 는
  [공식 Linux v2 설치 방식](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)으로 깔리고,
  설치가 끝나야 `/opt/app/.userdata-done` 마커가 생긴다.
- 컨테이너가 OOM 으로 재시작: `./deploy.sh mem` 으로 확인하고 상위 플랜으로 리사이즈한 뒤 compose 제한도 올린다.
- HTTPS 만 안 됨: Vultr 방화벽 규칙과 (켜져 있다면) ufw 를 함께 본다.
