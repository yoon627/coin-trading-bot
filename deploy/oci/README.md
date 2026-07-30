# Oracle Cloud (OCI) 배포 — Always Free 한도 내 운영

AWS EC2(t4g.medium, 실측 **$39.29/월** — 2026-06 Cost Explorer)에서 OCI 서울 리전
`VM.Standard.A1.Flex`(2 OCPU ARM / 12GB)로 옮겨 **무료 한도 내 운영**을 목표로 한다.
메모리는 4GB → 12GB 로 오르지만 compose 의 컨테이너 메모리 제한은 **AWS 판과 동일하게 유지**한다
(이전에서 바꾸는 변수를 하나로 줄이기 위함 — 튜닝은 안정화 후 별도).

> **"$0 보장"이 아니다.** Always Free 는 idle 회수·용량 부족·한도 초과 과금 리스크가 실재한다.
> 9절 "무료 티어 리스크" 를 반드시 읽고 수용 여부를 판단할 것.

| | AWS (현행) | OCI (이전 대상) |
|---|---|---|
| 사양 | 2 vCPU / 4GB (t4g.medium, 버스트) | 2 OCPU / 12GB (A1.Flex, 전용) |
| 디스크 | EBS 20GB | 부트볼륨 50GB |
| 공인 IP | $3.65/월 | 무료 (OCI 가격표에 IPv4 과금 항목 없음) |
| 월 비용 | **$39.29** (실측) | **$0** (무료 한도 내) |

---

## 0. 계정 준비 체크리스트 (되돌릴 수 없는 선택 포함)

1. **홈 리전을 `South Korea Central (Seoul)` 로 지정해 가입한다.** ⚠️ 가장 중요.
   - Always Free 컴퓨트는 **홈 리전에서만** 생성되고, 홈 리전은 **사후 변경이 사실상 불가능**하다.
   - 춘천(`South Korea North`)을 고르면 **Ampere A1 자체를 만들 수 없다**(Oracle 공식 문서 명시).
   - `deploy.sh setup` 이 실제 홈 리전을 조회해 `.env` 의 `OCI_REGION` 과 다르면 중단한다.
2. 카드 인증 필요(과금 아님, 가승인). 가입 후 30일 트라이얼 크레딧이 붙고 종료되면 Always Free 로 전환.
3. **Budget + Cost Alert 를 켠다** — 무료 한도를 넘기면 조용히 과금될 수 있다.
   콘솔 → Billing & Cost Management → Budgets 에서 소액(예: $1) 알림을 건다.
4. 무료 한도를 숙지한다: 컴퓨트 2 OCPU/12GB · 블록스토리지 **합계 200GB** ·
   Object Storage **20GB, 월 5만 API 요청** · 아웃바운드 10TB/월.
5. API 키 발급 후 로컬에 CLI 설정: `oci setup config` (`~/.oci/config` 생성).
6. 필요 도구: `oci`(≥3.x), `jq`, `openssl`, `ssh`, `scp`.

---

## 1. 배포

```bash
cd deploy/oci
install -m 600 .env.example .env   # 값 채우기 (아래 주의사항 참고). 600 으로 만드는 것이 중요 —
                                   # 이 파일에 DB/JWT/암호화 키와 GHCR PAT 가 들어간다.
./deploy.sh setup             # VCN/subnet/NSG + 버킷/IAM + A1.Flex 인스턴스 생성
# cloud-init(도커 설치) 완료까지 2~4분 대기
./deploy.sh deploy            # GHCR pull + compose 기동 + 헬스체크
```

`.env` 주의사항:

- **`APP_ENCRYPTION_SECRET` 은 자동 생성되지 않는다**(AWS 판과 다른 점). 비어 있으면 `setup` 이 즉시
  실패한다. AWS 에서 데이터를 이전한다면 `deploy/aws/.env` 의 값을 **그대로** 복사할 것.
  새로 만들면 앱은 정상 기동하면서 저장된 Upbit 키만 조용히 복호화 불능이 된다.
- **이전 중에는 `UPBIT_*` 를 비우고 `TRADING_AUTO_START=false` 로 둔다** (4절 cutover 참고).

### setup 은 재진입 가능하다

Always Free ARM 은 `Out of host capacity` 가 상시 발생한다. `setup` 은 AD 를 순환하며
exponential backoff + jitter 로 재시도하되 **상한이 있고**(기본 30회 / 1시간), 상한에 걸려 중단돼도
이미 만든 네트워크 리소스는 유지된다. 같은 명령을 다시 실행하면 **인스턴스 단계부터 이어서** 진행한다.
capacity 이외의 오류(인증·quota·shape/image 불일치)는 재시도하지 않고 즉시 실패한다.

---

## 2. 운영 명령

```bash
./deploy.sh status   # 컨테이너 상태
./deploy.sh logs     # 앱 로그
./deploy.sh ssh      # 인스턴스 접속
./deploy.sh stop     # 중지 (인스턴스는 유지)
./deploy.sh start    # 재기동
./deploy.sh destroy  # 삭제 (부트볼륨 보존 여부를 별도로 묻는다)
```

새 버전 배포: `main` push → Actions 빌드 완료 → `./deploy.sh deploy`.
`deploy` 는 `:latest` 대신 대상 커밋 SHA 로 이미지를 고정하고, 헬스체크(180s) 실패 시 직전 정상
SHA(`.state` 의 `LAST_GOOD_SHA`)로 **자동 롤백**한다. 단 **DB migration 이 포함된 배포**가 실패하면
자동 롤백을 건너뛰고 수동 개입을 안내한다(스키마가 이미 바뀌었을 수 있으므로).

---

## 3. AWS → OCI 데이터 이전

거래 이력과 (암호화된) Upbit 키는 Postgres 볼륨 하나에 있다. `pg_dump` → 복원으로 옮긴다.

```bash
# 1) AWS 에서 덤프 (AWS worktree/체크아웃에서)
./deploy/aws/deploy.sh ssh
  cd /opt/app && docker compose exec -T postgres pg_dump -U trading -d trading --no-owner \
    | gzip -c > /tmp/trading.sql.gz
  exit
scp -i deploy/aws/coin-trading-bot-key.pem ec2-user@<AWS_IP>:/tmp/trading.sql.gz .

# 2) OCI 로 복원
scp -i deploy/oci/coin-trading-bot-key.pem trading.sql.gz opc@<OCI_IP>:/tmp/
./deploy/oci/deploy.sh ssh
  cd /opt/app && gunzip -c /tmp/trading.sql.gz | docker compose exec -T postgres psql -U trading -d trading
```

복원 후 **거래 활성화 전에** 반드시 검증한다:

- 테이블 수·핵심 테이블 행 수가 AWS 와 일치하는가
- 최신 거래 시각이 덤프 시점과 맞는가
- **저장된 Upbit 키가 실제로 복호화되는가** (앱 UI 에서 키 조회 — 복호화 실패면
  `APP_ENCRYPTION_SECRET` 이 다른 것이다. 이 경우 절대 거래를 켜지 말 것)

`APP_ENCRYPTION_SECRET` 이 양쪽에서 같은지 원문 노출 없이 확인하려면 지문만 비교한다:

```bash
# 양쪽에서 실행해 해시가 같은지만 본다 (원문은 출력하지 않는다)
grep '^APP_ENCRYPTION_SECRET=' .env | cut -d= -f2- | tr -d '\n' | shasum -a 256
```

---

## 4. Cutover 절차 (⚠️ 단일 실행 보장)

**절대 원칙: 어느 시점에도 거래를 활성화한 인스턴스는 하나뿐이어야 한다.**
같은 Upbit 계정에 두 봇이 붙으면 이중 주문·중복 청산이 발생한다.

1. OCI 인프라와 DB만 준비한다. `.env` 는 `TRADING_AUTO_START=false`, `UPBIT_*` **비움**.
   `./deploy.sh setup && ./deploy.sh deploy` 로 앱이 뜨고 헬스체크가 통과하는 것만 확인한다.
2. AWS 앱을 정지한다: `./deploy/aws/deploy.sh stop`.
3. AWS 가 완전히 멈췄는지 확인한다 — 컨테이너가 내려갔고, 진행 중이던 tick·주문 후처리가
   끝났는지 로그로 확인(`stop_grace_period: 40s` 만큼 여유를 준다).
4. **Upbit 에서 미체결 주문·잔고·보유 포지션 스냅샷을 따로 기록한다**(복구 시 대조 기준).
5. 이 시점에 **최종 `pg_dump`** 를 뜬다(3절). 이전 단계에서 미리 뜬 덤프는 버린다.
6. OCI 에 복원하고 3절의 검증을 모두 통과시킨다.
7. Upbit API 키의 **허용 IP 목록에 OCI 공인 IP 를 등록**한다(기존 AWS IP 는 롤백 대비 당분간 유지).
8. OCI `.env` 에 `UPBIT_*` 를 채우고 `./deploy.sh deploy` 후, **UI 에서 수동으로** 거래를 켠다.
9. AWS 가 여전히 정지 상태인지 다시 확인한다.

> AWS 인스턴스는 **cutover 후에도 최소 7~14일 유지**한다(정지 상태). 곧바로 destroy 하지 않는다.

---

## 5. 롤백 — 거래 활성화 전/후가 다르다

`deploy/aws/` 를 남겨둔 것만으로는 롤백이 되지 않는다. **OCI 에서 거래가 시작된 뒤로는
AWS DB 에 그 거래 기록이 없기 때문에**, 그냥 AWS 를 켜는 것은 롤백이 아니라 데이터 분기다.

**① 거래 활성화 _전_ (안전)**

```bash
./deploy/oci/deploy.sh stop     # OCI 중지
./deploy/aws/deploy.sh start    # AWS 재기동
```
데이터 역이전 불필요. OCI 쪽은 그대로 두고 나중에 재시도하거나 destroy 한다.

**② 거래 활성화 _후_ (신중)**

1. OCI 에서 거래를 끈다(UI) → 진행 중 주문이 정리될 때까지 기다린다.
2. Upbit 미체결 주문·잔고 스냅샷을 기록한다.
3. OCI 에서 최종 `pg_dump` 를 뜬다.
4. **AWS DB 에 그 덤프를 복원한다**(AWS 의 옛 데이터를 그대로 쓰면 OCI 기간의 거래가 사라진다).
5. Upbit 실제 잔고/미체결과 복원된 DB 상태를 대조한다.
6. AWS 에서만 거래를 다시 켠다.

> 양쪽 DB 에 각각 쓰기가 발생했다면 **자동 병합하지 말 것.** 수동으로 정합성을 조사한다.

---

## 6. DB 백업 / 복원

`.env` 에 `BACKUP_BUCKET` 을 설정하면 `setup` 이 버킷 + dynamic group + policy 를 함께 만든다.
인스턴스는 **instance principal** 로 인증하므로 **API 키를 서버에 두지 않는다**
(AWS 판의 IAM 인스턴스 롤과 같은 방식).

```bash
# cron 등록 (./deploy.sh ssh 로 접속 후)
crontab -e
# 0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00

# 복원 (신규/스테이징 DB 로 — 운영 DB 덮어쓰기 주의)
oci os object get --bucket-name <버킷> --name db-backups/trading-<TS>.sql.gz --file - \
  | gunzip | docker compose exec -T postgres psql -U trading -d trading
```

- 업로드는 크기 검증까지 통과해야 성공으로 본다. 실패하면 **exit≠0** 으로 끝난다(조용한 실패 없음).
- 보존 정리는 파일명이 아니라 객체의 `time-created` 기준이며, 삭제 실패는 경고로 남는다.
- ⚠️ **"업로드 성공"은 "복원 가능"이 아니다.** 주기적으로 실제 복원 시험을 할 것.
- ⚠️ dump 에는 `APP_ENCRYPTION_SECRET` 으로 암호화된 Upbit 키가 들어있다. 그 AES 키는 **버킷과 다른
  곳에 오프사이트 보관** — 같은 곳에 두면 유출 시 즉시 복호화된다.
- ⚠️ Always Free Object Storage 는 총 20GB · 월 5만 요청 한도. 보존 일수를 그 안에서 잡을 것.

만약 dynamic group/policy 자동 생성이 실패하면(루트 권한 없음, 또는 Identity Domains 테넌시에서
레거시 IAM API 가 막힌 경우) `setup` 이 경고와 함께 수동 생성용 policy 문장을 출력한다.

---

## 7. 보안

- **TLS 종단**: Caddy 가 443 에서 HTTPS 를 종단(Let's Encrypt 자동 발급/갱신)하고 내부 `app:8080` 으로
  프록시한다. 인증서 자동 갱신(ACME HTTP-01)을 위해 **80 은 전세계 상시 개방**이 필요하다.
- **NSG 를 쓴다**(security list 아님). security list 는 subnet 의 모든 VNIC 에 적용되고 VCN 기본
  security list 와 **합집합**으로 동작해, SSH 를 좁혀도 기본 규칙이 남으면 제한이 무력화된다.
- SSH(22)는 **본인 공인 IP/32 에만** 열린다(`.env` 미설정 시 자동 감지). 443 은 기본 `0.0.0.0/0`.
- **호스트 방화벽도 연다**: OCI 의 Oracle Linux 이미지는 firewalld/iptables 가 켜져 있어 NSG 만
  열어서는 트래픽이 막힌다. cloud-init 이 80/443 을 호스트에서도 연다.
- SSH 는 `StrictHostKeyChecking=accept-new` — 최초 접속만 자동 수용하고 이후 호스트키 변경은 거부한다.
- PostgreSQL/Redis 는 호스트에 노출하지 않는다(compose 내부망 전용).
- `.env` 파일은 로컬·서버 모두 `600`. 절대 커밋하지 말 것(이미 `.gitignore` 처리).

---

## 8. 트러블슈팅

- **`Out of host capacity`**: Always Free ARM 의 상시 이슈. `setup` 을 다시 실행하면 인스턴스 단계부터
  이어서 재시도한다. 시간대를 바꿔 시도하는 편이 확률이 높다.
- **홈 리전 불일치로 setup 중단**: `.env` 의 `OCI_REGION` 을 실제 홈 리전으로 맞추거나, 홈 리전이
  서울인 테넌시를 쓴다. 춘천이면 A1 자체가 불가하다.
- **`deploy` 헬스체크 실패**: `./deploy.sh logs`. DB 마이그레이션(Flyway)·시크릿 누락이 흔한 원인.
- **cloud-init 실패(도커 없음)**: `./deploy.sh ssh` 후 `sudo cat /var/log/cloud-init-output.log`.
- **HTTPS 만 안 됨**: 호스트 방화벽(80/443)과 NSG 규칙을 함께 확인. `sudo firewall-cmd --list-ports`.
- **백업 인증 실패**: dynamic group 에 인스턴스가 포함됐는지, policy 가 해당 버킷을 허용하는지 확인.

---

## 9. 무료 티어 리스크 (수용 여부를 판단할 것)

실거래 봇을 무료 티어에 올리는 것은 공짜인 만큼의 위험이 있다.

- **idle 회수**: Oracle 은 7일간 CPU p95·네트워크·A1 메모리 사용률이 **모두 20% 미만**인 Always Free
  인스턴스를 회수할 수 있다. 이 봇은 상시 WS·폴링을 돌려 회수 대상이 될 가능성은 낮지만 보장은 없다.
  ⚠️ CPU 를 인위적으로 소모해 회피하는 방식은 쓰지 않는다(약관 취지에 반한다).
- **용량 부족**: 인스턴스가 종료되면 같은 사양을 다시 확보하지 못할 수 있다. 그래서 `destroy` 는
  부트볼륨 보존 여부를 따로 묻고, 재프로비저닝 시 보존된 볼륨을 쓸 수 있게 한다.
- **한도 초과 과금 / 계정 정지**: 결제 실패나 한도 초과 시 리소스가 정지될 수 있다.
  Budget·Cost Alert 를 반드시 켜고, **백업은 OCI 테넌시 밖에도** 하나 둘 것.
- **외부 감시 권장**: 인스턴스 상태·마지막 거래 tick·마지막 백업 성공 시각을 OCI 밖에서 감시한다
  (Discord 웹훅 알림이 이미 있으므로 활용).
- 운용 자금이 의미 있는 규모가 되면 무료 티어 대신 유료 인스턴스(또는 Vultr 서울 $20/월)를
  재검토하는 편이 합리적이다.
