# AWS EC2 배포 (월 ~50,000원 예산)

커밋된 `bot` 모듈만 단일 EC2 에 올리는 경량 배포. **caddy + app + PostgreSQL + Redis** 컨테이너를 사용하고
Kafka/collector/모니터링은 제외한다(미커밋 모듈이며 4GB 박스에서 OOM). Caddy 가 443 에서 TLS 를 종단(Let's Encrypt 자동)하고 내부 `app:8080` 으로 프록시한다.

- 이미지: GitHub Actions 가 multi-arch(amd64+arm64)로 빌드 → GHCR push
- 인스턴스: `t4g.medium`(4GB, ARM/Graviton) — pull 만 수행
- 운영(SSH/AWS CLI)은 macOS·Windows(Git Bash) 동일

## 0. 사전 준비

1. **AWS 자격증명**: `aws configure` (IAM 사용자, EC2 권한). 리전 `ap-northeast-2`.
2. **이미지 빌드**: `main` 에 push 하면 GitHub Actions 가 `ghcr.io/yoon627/coin-trading-bot:latest` 를 만든다.
   - GHCR 패키지는 기본 private. 가장 간단한 방법은 GitHub → Packages → 해당 패키지 → **Make public**.
   - private 유지 시 `.env` 의 `GHCR_USERNAME` + `GHCR_TOKEN`(PAT, `read:packages`) 설정.
3. 로컬에 `aws`, `openssl`, `ssh`, `scp` 필요(`docker` 는 인스턴스에서만 쓰므로 로컬엔 불필요).

## 1. 배포

```bash
cd deploy/aws
cp .env.example .env          # 값 채우기 (시크릿·APP_DOMAIN 은 비워두면 자동 생성)
./deploy.sh setup             # 키페어 + VPC + SG + EC2 생성 (1~2분)
# Docker 설치 완료까지 잠시 대기
./deploy.sh deploy            # GHCR pull + compose 기동 + 헬스체크
```

배포 후: `https://<APP_DOMAIN>` (미설정 시 EC2 공인 IP 의 점을 하이픈으로 바꾼 sslip.io 도메인, 예: `1-2-3-4.sslip.io`).
Caddy 가 Let's Encrypt 인증서를 발급할 때까지 최초 ~30초 걸리며, 그 사이 인증서 경고가 보이면 1~2분 후 재시도한다. `app` 8080 은 호스트에 노출되지 않고 Caddy(443) 경유로만 접근한다.
시크릿(`DB_PASSWORD`/`JWT_SECRET`/`APP_ENCRYPTION_SECRET`)은 비워두면 자동 생성되어 `.env` 에 저장된다.
**`.env` 는 절대 커밋 금지** (이미 `.gitignore` 처리). 특히 `APP_ENCRYPTION_SECRET` 은 백업하고 이후 변경하지 말 것 —
저장된 Upbit API 키를 복호화하는 AES 키라 바뀌면 기존 키가 모두 무효화된다.

## 2. 운영 명령

```bash
./deploy.sh status   # 컨테이너 상태
./deploy.sh logs     # 앱 로그
./deploy.sh ssh      # 인스턴스 접속
./deploy.sh stop     # 중지 (인스턴스는 유지 → EC2/EBS 과금 계속)
./deploy.sh start    # 재기동
./deploy.sh destroy  # 전체 삭제 (과금 중단)
```

새 버전 배포: `main` push → Actions 빌드(`:latest` + `:<sha>`) 완료 → `./deploy.sh deploy`.
`deploy` 는 `:latest` 대신 대상 커밋 SHA(`git rev-parse origin/main`, 또는 `APP_VERSION=<sha>`)로 이미지를 고정하고, 헬스체크(180s) 실패 시 **직전 정상 SHA(`.state` 의 `LAST_GOOD_SHA`)로 자동 롤백**한다. 단 **DB migration 이 포함된 배포**가 실패하면(스키마가 이미 바뀌었을 수 있어) 자동 롤백을 건너뛰고 수동 개입을 안내한다. CI 이미지 빌드가 끝나기 전 배포하면 `docker compose pull` 이 실패하므로 빌드 완료 후 실행한다.

## 3. DB 백업 / 복원

거래 이력과 (암호화된) Upbit 키는 Postgres 볼륨 하나에 있다. `backup.sh` 가 `pg_dump → gzip → S3`(서버측 암호화)로 스냅샷을 남긴다.

```bash
# 1) 대상 설정 (.env) — 버킷은 미리 생성(퍼블릭 차단·버전닝 권장), EC2 롤에 put/list/delete 권한 부여
BACKUP_S3_BUCKET=<버킷명>            # 비우면 백업 비활성 (destroy 시 경고)
# BACKUP_S3_PREFIX / BACKUP_RETENTION_DAYS(기본 14) / BACKUP_S3_SSE(기본 AES256) 는 기본값 존재
./deploy.sh deploy                  # backup.sh 와 설정을 EC2 /opt/app 에 배치

# 2) 야간 cron 등록 (./deploy.sh ssh 로 접속 후)
crontab -e
# 0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00

# 3) 복원 (신규/스테이징 DB 로 — 운영 DB 덮어쓰기 주의)
aws s3 cp s3://<버킷>/db-backups/trading-<TS>.sql.gz - | gunzip \
  | docker compose exec -T postgres psql -U trading -d trading
```

- `destroy` 는 `BACKUP_S3_BUCKET` 설정 시 삭제 전 **최종 백업**을 수행한다(미설정 시 경고 후 진행).
- ⚠️ dump 에는 `APP_ENCRYPTION_SECRET` 으로 암호화된 Upbit 키가 들어있다. 그 AES 키(`.env`)는 **백업 버킷과 다른 곳에 오프사이트 보관** — 같은 곳에 두면 유출 시 즉시 복호화된다.

## 4. 비용 (서울 리전, 온디맨드, 대략값 / 1 USD ≈ 1,380원 가정)

| 항목 | t4g.medium(4GB) | t4g.small(2GB) | t3.small(2GB, x86) |
|---|---|---|---|
| EC2 (730h) | ~$31.5 | ~$15.8 | ~$21.0 |
| EBS gp3 20GB | ~$1.8 | ~$1.8 | ~$1.8 |
| 공인 IPv4 1개 | ~$3.65 | ~$3.65 | ~$3.65 |
| 데이터 전송(소량) | ~$1 | ~$1 | ~$1 |
| **합계/월** | **~$38 (≈₩52k)** | **~$22 (≈₩30k)** | **~$27 (≈₩38k)** |

- `t4g.medium` 온디맨드는 예산 상한에 근접. **1년 Compute Savings Plan(no-upfront, 약 -30%)** 적용 시 ~$28(≈₩39k)로 예산 내.
- 2GB(`t4g.small`)는 예산 여유가 크지만 JVM+PG+Redis 메모리가 빠듯 → 단일 사용자면 가능. `INSTANCE_TYPE` 만 바꾸면 됨.
- 가격은 변동되므로 [AWS Pricing Calculator](https://calculator.aws/) 로 최종 확인 권장.

## 5. 보안 주의

- **TLS 종단**: Caddy 가 443 에서 HTTPS 를 종단(Let's Encrypt 자동 발급/갱신)하고 내부 `app:8080` 으로 프록시한다. `app` 8080 은 호스트에 노출되지 않는다(`expose` 만). 인증서 자동 갱신(ACME HTTP-01)을 위해 **80 포트는 전세계 상시 개방**이 필요하다.
- SSH(22)는 **본인 공인 IP/32 에만** 열린다(`.env` 미설정 시 자동). 443 은 어디서든 접속 가능하도록 기본 `0.0.0.0/0`(TLS 암호화) — 특정 IP 로만 제한하려면 `APP_ALLOW_CIDR` 조정(80 은 ACME 때문에 항상 전체 개방). 로그인 brute-force 는 app 의 IP 기반 rate limit 으로 방어한다(Caddy 가 `X-Forwarded-For` 로 실제 client IP 를 전달).
- HTTPS 종단으로 JWT 쿠키가 암호화 전송된다. 평문 HTTP 로만 임시 운영해야 한다면 `APP_AUTH_COOKIE_FORCE_INSECURE=true`(비권장 — 쿠키가 평문으로 전송됨).
- PostgreSQL/Redis 포트는 호스트에 노출하지 않음(컴포즈 내부 네트워크 전용).
- `prod` 프로파일은 시크릿이 비면 부팅 실패(fail-closed). 자동 생성 시크릿으로 충족됨.

## 6. 트러블슈팅

- `deploy` 헬스체크 실패: `./deploy.sh logs` 로 app 로그 확인. DB 마이그레이션(Flyway)·시크릿 누락이 흔한 원인.
- pull 권한 오류: GHCR 패키지가 private → public 으로 바꾸거나 `GHCR_TOKEN` 설정.
- 메모리 부족(exit 137): `INSTANCE_TYPE` 를 `t4g.medium` 이상으로. compose 의 `mem_limit` 합이 박스 RAM 을 넘지 않게.
