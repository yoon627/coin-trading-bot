#!/usr/bin/env bash
set -euo pipefail

# EC2 야간 cron 용 DB 백업: postgres pg_dump → gzip → S3(SSE) + 오래된 백업 정리.
# postgres 는 host 에 미노출(compose 내부망)이라 반드시 `docker compose exec` 경유(unix socket, local trust).
#
# 실행: /opt/app 에서 `./backup.sh` (deploy 가 compose 파일 옆에 배치). cron 예(운영자가 EC2 에 등록):
#   0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00
#
# 보안(plan-review): 이 스크립트는 DB dump 만 백업한다. dump 에는 APP_ENCRYPTION_SECRET 으로
# 암호화된 Upbit 키가 들어있으므로, 그 AES 키(.env 의 APP_ENCRYPTION_SECRET)는 이 dump 와
# **다른 저장소에 오프사이트 보관**해야 한다(같은 S3 에 두면 유출 시 즉시 복호화됨).
#
# 설정(/opt/app/.env 또는 환경변수 — deploy 가 렌더):
#   BACKUP_S3_BUCKET       (필수) 백업 대상 S3 버킷 이름
#   BACKUP_S3_PREFIX       (기본 db-backups) 버킷 내 경로 prefix
#   BACKUP_RETENTION_DAYS  (기본 14) 이보다 오래된 백업 삭제
#   BACKUP_S3_SSE          (기본 AES256) S3 서버측 암호화(aws:kms 도 가능)
#   AWS_REGION             aws CLI 리전
# 필요: aws CLI + 대상 버킷 put/list/delete 권한(인스턴스 IAM 롤 권장). S3 는 퍼블릭 차단·버전닝 권장.

cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; . ./.env; set +a; fi

: "${BACKUP_S3_BUCKET:?BACKUP_S3_BUCKET 미설정 (백업할 S3 버킷). deploy/aws/.env 에 추가하세요.}"
PREFIX="${BACKUP_S3_PREFIX:-db-backups}"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
SSE="${BACKUP_S3_SSE:-AES256}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
KEY="${PREFIX}/trading-${TS}.sql.gz"
DEST="s3://${BACKUP_S3_BUCKET}/${KEY}"

echo "[backup] $(date -u +%FT%TZ) pg_dump → gzip → ${DEST} (SSE=${SSE})"
# pipefail 로 어느 단계라도 실패하면 중단 — 부분/빈 백업이 "성공"으로 업로드되지 않게.
# </dev/null: exec 가 이 스크립트/cron 의 stdin 을 읽지 않도록 차단(안전 습관).
if ! docker compose exec -T postgres pg_dump -U trading -d trading --no-owner </dev/null \
     | gzip -c \
     | aws s3 cp - "$DEST" --sse "$SSE" --only-show-errors; then
    echo "[backup] ERROR: 백업 실패 (pg_dump/gzip/s3 cp)" >&2
    exit 1
fi
echo "[backup] 업로드 완료: ${KEY}"

# 보존 정리: RETENTION 일 경과 객체 삭제 (S3 lifecycle 정책 병행 권장 — 이건 폴백).
# 리스트를 먼저 받아 파이프(pipefail)·빈 목록이 스크립트를 실패로 만들지 않게 한다.
CUTOFF="$(date -u -d "-${RETENTION} days" +%Y-%m-%d)"
echo "[backup] ${CUTOFF} 이전 백업 정리"
listing="$(aws s3 ls "s3://${BACKUP_S3_BUCKET}/${PREFIX}/" 2>/dev/null || true)"
while read -r day _time _size name; do
    [[ -z "${name:-}" || -z "${day:-}" ]] && continue
    if [[ "$day" < "$CUTOFF" ]]; then
        aws s3 rm "s3://${BACKUP_S3_BUCKET}/${PREFIX}/${name}" --only-show-errors || true
        echo "[backup]   삭제: ${name}"
    fi
done <<< "$listing"
echo "[backup] 완료"
