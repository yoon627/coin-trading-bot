#!/usr/bin/env bash
set -euo pipefail

# pg_dump 를 S3 호환 스토리지에 올리고 보존 기간이 지난 백업을 지운다. /opt/app 에서 cron 으로 돈다.
# 덤프에는 암호화된 Upbit 키가 들어 있으므로 APP_ENCRYPTION_SECRET 은 백업과 다른 곳에 보관한다.

cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; . ./.env; set +a; fi

: "${BACKUP_S3_BUCKET:?BACKUP_S3_BUCKET 미설정. deploy/vultr/.env 에 추가하세요.}"
PREFIX="${BACKUP_S3_PREFIX:-db-backups}"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
KEY="${PREFIX}/trading-${TS}.sql.gz"
DEST="s3://${BACKUP_S3_BUCKET}/${KEY}"

command -v aws >/dev/null || {
    echo "[backup] ERROR: aws CLI 없음 — cloud-init 설치 실패 가능. 'apt-get install -y awscli'." >&2; exit 1; }

# 빈 문자열을 인자로 넘기면 aws CLI 가 오류를 내므로 배열로 조립한다.
S3OPT=()
[[ -n "${BACKUP_S3_ENDPOINT:-}" ]] && S3OPT=(--endpoint-url "$BACKUP_S3_ENDPOINT")

# 구형 bash 의 set -u 는 빈 배열 확장을 unbound 로 본다.
# bash -s 파이프로 실행될 때 aws 가 남은 스크립트를 stdin 으로 읽지 않도록 끊는다.
s3() { aws ${S3OPT[@]+"${S3OPT[@]}"} "$@" </dev/null; }

# S3 호환 스토리지에는 STS 가 없을 수 있어 버킷 조회로 자격증명을 확인한다.
if ! s3 s3 ls "s3://${BACKUP_S3_BUCKET}/" >/dev/null 2>&1; then
    echo "[backup] ERROR: 버킷 접근 실패 — 자격증명 또는 버킷 이름을 확인하세요." >&2
    echo "[backup]        (키는 이 버킷에만 권한이 있는 전용 키여야 합니다)" >&2
    exit 1
fi

echo "[backup] $(date -u +%FT%TZ) pg_dump → gzip → ${DEST}"

# 스트리밍 업로드는 pg_dump 가 중간에 실패해도 잘린 덤프를 최신 백업으로 남기므로 로컬에 먼저 받는다.
TMP="$(mktemp "${TMPDIR:-/tmp}/trading-backup.XXXXXX.sql.gz")"
trap 'rm -f "$TMP"' EXIT
if ! docker compose exec -T postgres pg_dump -U trading -d trading --no-owner </dev/null | gzip -c > "$TMP"; then
    echo "[backup] ERROR: pg_dump/gzip 실패 (부분 파일 폐기)" >&2
    exit 1
fi
# gzip 은 빈 입력에도 헤더를 쓰므로 파일 크기로는 빈 덤프를 잡지 못한다.
if [[ "$(gzip -dc "$TMP" 2>/dev/null | head -c 1 | wc -c)" -eq 0 ]]; then
    echo "[backup] ERROR: 덤프 내용이 비었음 — 업로드 중단" >&2
    exit 1
fi

LOCAL_SIZE="$(wc -c < "$TMP" | tr -d '[:space:]')"
echo "[backup] 업로드 → ${KEY} ($(du -h "$TMP" | cut -f1))"
if ! s3 s3 cp "$TMP" "$DEST" --only-show-errors; then
    echo "[backup] ERROR: 업로드 실패" >&2
    exit 1
fi

REMOTE_SIZE="$(s3 s3api head-object --bucket "$BACKUP_S3_BUCKET" --key "$KEY" \
    --query 'ContentLength' --output text 2>/dev/null || echo "")"
if [[ -z "$REMOTE_SIZE" || "$REMOTE_SIZE" == "None" ]]; then
    echo "[backup] ERROR: 업로드 후 객체 조회 실패 — 백업을 신뢰할 수 없음" >&2
    exit 1
fi
if [[ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]]; then
    echo "[backup] ERROR: 크기 불일치 (로컬 ${LOCAL_SIZE} vs 원격 ${REMOTE_SIZE}) — 백업 손상 의심" >&2
    exit 1
fi
echo "[backup] 업로드 완료·검증 OK: ${KEY} (${REMOTE_SIZE} bytes)"

# 보존 정리는 파일명이 아니라 LastModified 기준이다. 정리 실패는 백업을 실패시키지 않고 경고만 남긴다.
CUTOFF_EPOCH="$(date -u -d "-${RETENTION} days" +%s 2>/dev/null || date -u -v-"${RETENTION}"d +%s)"
echo "[backup] ${RETENTION}일 경과 백업 정리"

listing="$(s3 s3api list-objects-v2 --bucket "$BACKUP_S3_BUCKET" --prefix "${PREFIX}/" \
    --query 'Contents[].[Key,LastModified]' --output text 2>/dev/null || echo "")"
if [[ -z "$listing" ]]; then
    echo "[backup] WARN: 객체 목록 조회 실패 또는 비어 있음 — 보존 정리 생략." >&2
else
    deleted=0; failed=0
    while IFS=$'\t' read -r name modified; do
        [[ -z "${name:-}" || -z "${modified:-}" || "$name" == "None" ]] && continue
        obj_epoch="$(date -u -d "$modified" +%s 2>/dev/null || echo "")"
        [[ -z "$obj_epoch" ]] && { echo "[backup]   WARN 시각 파싱 실패: $name ($modified)" >&2; continue; }
        if (( obj_epoch < CUTOFF_EPOCH )); then
            if s3 s3 rm "s3://${BACKUP_S3_BUCKET}/${name}" --only-show-errors; then
                echo "[backup]   삭제: $name"; deleted=$(( deleted + 1 ))
            else
                echo "[backup]   WARN 삭제 실패: $name" >&2; failed=$(( failed + 1 ))
            fi
        fi
    done <<< "$listing"
    echo "[backup] 정리 완료 (삭제 ${deleted}건, 실패 ${failed}건)"
    (( failed > 0 )) && echo "[backup] WARN: 삭제 실패가 있습니다 — 버킷 용량을 확인하세요." >&2
fi

echo "[backup] 완료"
