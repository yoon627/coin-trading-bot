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

새 버전 배포: `main` push → Actions 빌드 완료 → `./deploy.sh deploy` 재실행(이미지 재pull).

## 3. 비용 (서울 리전, 온디맨드, 대략값 / 1 USD ≈ 1,380원 가정)

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

## 4. 보안 주의

- **TLS 종단**: Caddy 가 443 에서 HTTPS 를 종단(Let's Encrypt 자동 발급/갱신)하고 내부 `app:8080` 으로 프록시한다. `app` 8080 은 호스트에 노출되지 않는다(`expose` 만). 인증서 자동 갱신(ACME HTTP-01)을 위해 **80 포트는 전세계 상시 개방**이 필요하다.
- SSH(22)는 **본인 공인 IP/32 에만** 열린다(`.env` 미설정 시 자동). 443 은 어디서든 접속 가능하도록 기본 `0.0.0.0/0`(TLS 암호화) — 특정 IP 로만 제한하려면 `APP_ALLOW_CIDR` 조정(80 은 ACME 때문에 항상 전체 개방). 로그인 brute-force 는 app 의 IP 기반 rate limit 으로 방어한다(Caddy 가 `X-Forwarded-For` 로 실제 client IP 를 전달).
- HTTPS 종단으로 JWT 쿠키가 암호화 전송된다. 평문 HTTP 로만 임시 운영해야 한다면 `APP_AUTH_COOKIE_FORCE_INSECURE=true`(비권장 — 쿠키가 평문으로 전송됨).
- PostgreSQL/Redis 포트는 호스트에 노출하지 않음(컴포즈 내부 네트워크 전용).
- `prod` 프로파일은 시크릿이 비면 부팅 실패(fail-closed). 자동 생성 시크릿으로 충족됨.

## 5. 트러블슈팅

- `deploy` 헬스체크 실패: `./deploy.sh logs` 로 app 로그 확인. DB 마이그레이션(Flyway)·시크릿 누락이 흔한 원인.
- pull 권한 오류: GHCR 패키지가 private → public 으로 바꾸거나 `GHCR_TOKEN` 설정.
- 메모리 부족(exit 137): `INSTANCE_TYPE` 를 `t4g.medium` 이상으로. compose 의 `mem_limit` 합이 박스 RAM 을 넘지 않게.
