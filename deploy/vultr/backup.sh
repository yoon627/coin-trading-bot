#!/usr/bin/env bash
set -euo pipefail

# 야간 cron 용 DB 백업: postgres pg_dump → gzip → S3 호환 오브젝트 스토리지 + 오래된 백업 정리.
# postgres 는 host 에 미노출(compose 내부망)이라 반드시 `docker compose exec` 경유.
#
# 실행: /opt/app 에서 `./backup.sh` (deploy 가 compose 파일 옆에 배치). cron 예:
#   0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00
#
# 저장소는 S3 호환이면 무엇이든 된다 — 엔드포인트만 바꾼다:
#   - AWS S3            : BACKUP_S3_ENDPOINT 를 비워둠 (기존 AWS 계정 재사용 시 가장 간단)
#   - Vultr Object Storage : https://sgp1.vultrobjects.com 등
#   - Cloudflare R2     : https://<account>.r2.cloudflarestorage.com
#
# ⚠️ Vultr 인스턴스에는 AWS 의 IAM 인스턴스 롤 같은 것이 없다. 액세스 키를 서버에 둬야 하므로
#    **해당 버킷에만 권한이 있는 전용 키**를 발급해 쓸 것(전체 권한 키 금지).
#
# 보안: dump 에는 APP_ENCRYPTION_SECRET 으로 암호화된 Upbit 키가 들어있다. 그 AES 키(.env)는
# 이 백업과 **다른 곳에 오프사이트 보관** — 같은 곳에 두면 유출 시 즉시 복호화된다.
#
# 설정(/opt/app/.env — deploy 가 렌더):
#   BACKUP_S3_BUCKET       (필수) 대상 버킷
#   BACKUP_S3_PREFIX       (기본 db-backups)
#   BACKUP_S3_ENDPOINT     (선택) S3 호환 엔드포인트. 비우면 AWS S3
#   BACKUP_RETENTION_DAYS  (기본 14)
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_DEFAULT_REGION

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

# 엔드포인트 옵션을 배열로 조립 — 빈 문자열을 인자로 넘기면 aws CLI 가 오류를 낸다.
S3OPT=()
[[ -n "${BACKUP_S3_ENDPOINT:-}" ]] && S3OPT=(--endpoint-url "$BACKUP_S3_ENDPOINT")

# aws 호출은 이 함수만 거친다. 두 가지를 한 곳에서 보장한다:
#  1) `${S3OPT[@]+...}` — set -u 에서 빈 배열 확장이 unbound 로 죽는 것을 막는다(구형 bash).
#  2) `</dev/null` — 이 스크립트는 cron 과 `bash -s` 파이프로도 실행된다. aws 가 표준입력을
#     읽어 남은 스크립트를 삼키면 이후 로직이 통째로 사라지므로 stdin 을 명시적으로 끊는다.
s3() { aws ${S3OPT[@]+"${S3OPT[@]}"} "$@" </dev/null; }

# 자격증명/버킷 접근을 먼저 확인한다. STS 는 S3 호환 스토리지에 없을 수 있으므로
# sts get-caller-identity 대신 실제 버킷 조회로 검증한다.
if ! s3 s3 ls "s3://${BACKUP_S3_BUCKET}/" >/dev/null 2>&1; then
    echo "[backup] ERROR: 버킷 접근 실패 — 자격증명 또는 버킷 이름을 확인하세요." >&2
    echo "[backup]        (키는 이 버킷에만 권한이 있는 전용 키여야 합니다)" >&2
    exit 1
fi

echo "[backup] $(date -u +%FT%TZ) pg_dump → gzip → ${DEST}"

# 로컬 temp 로 먼저 덤프하고 성공했을 때만 업로드한다. 스트리밍이면 pg_dump 가 중간 실패해도
# 부분 데이터가 최종 키에 올라가, 복원이 손상본을 최신으로 고를 수 있다.
TMP="$(mktemp "${TMPDIR:-/tmp}/trading-backup.XXXXXX.sql.gz")"
trap 'rm -f "$TMP"' EXIT
if ! docker compose exec -T postgres pg_dump -U trading -d trading --no-owner </dev/null | gzip -c > "$TMP"; then
    echo "[backup] ERROR: pg_dump/gzip 실패 (부분 파일 폐기)" >&2
    exit 1
fi
# 빈 덤프 검출: gzip 은 빈 입력이어도 헤더(~20B)를 쓰므로 파일 크기로는 못 잡는다.
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

# 업로드 검증 — "cp 가 성공했다" 와 "객체가 온전히 저장됐다" 는 다르다.
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

# ── 보존 정리 ──
# 파일명이 아니라 객체의 LastModified 기준으로 지운다(파일명 규칙이 바뀌어도 안전).
# 정리 실패는 백업 자체를 실패시키지 않되 반드시 경고로 남긴다(조용히 넘기면 용량이 계속 쌓인다).
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
