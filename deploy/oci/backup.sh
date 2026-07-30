#!/usr/bin/env bash
set -euo pipefail

# 인스턴스 야간 cron 용 DB 백업: postgres pg_dump → gzip → OCI Object Storage + 오래된 백업 정리.
# postgres 는 host 에 미노출(compose 내부망)이라 반드시 `docker compose exec` 경유.
#
# 실행: /opt/app 에서 `./backup.sh` (deploy 가 compose 파일 옆에 배치). cron 예:
#   0 18 * * *  cd /opt/app && ./backup.sh >> /var/log/db-backup.log 2>&1   # UTC 18:00 = KST 03:00
#
# 인증: **instance principal** — 인스턴스가 dynamic group 에 속하고 그 그룹에 policy 로 버킷 권한이
# 주어져 있어야 한다(deploy.sh setup 이 생성). API 키를 인스턴스에 배포하지 않는 것이 목적이며,
# 키 파일이 없어도 동작하는 것이 정상이다.
#
# 보안: 이 스크립트는 DB dump 만 백업한다. dump 에는 APP_ENCRYPTION_SECRET 으로 암호화된 Upbit 키가
# 들어있으므로, 그 AES 키(.env 의 APP_ENCRYPTION_SECRET)는 이 dump 와 **다른 곳에 오프사이트 보관**해야
# 한다(같은 버킷에 두면 유출 시 즉시 복호화됨).
#
# 설정(/opt/app/.env 또는 환경변수 — deploy 가 렌더):
#   BACKUP_BUCKET          (필수) 백업 대상 버킷 이름
#   BACKUP_PREFIX          (기본 db-backups) 버킷 내 경로 prefix
#   BACKUP_RETENTION_DAYS  (기본 14) 이보다 오래된 백업 삭제
#
# Object Storage 는 서버측 암호화가 항상 적용되므로 AWS 의 --sse 같은 옵션은 필요 없다.
# Always Free 한도(총 20GB, 월 5만 API 요청)를 넘지 않도록 보존 일수를 잡을 것.

cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; . ./.env; set +a; fi

: "${BACKUP_BUCKET:?BACKUP_BUCKET 미설정 (백업할 버킷). deploy/oci/.env 에 추가하세요.}"
PREFIX="${BACKUP_PREFIX:-db-backups}"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
KEY="${PREFIX}/trading-${TS}.sql.gz"

OCI_AUTH=(--auth instance_principal)

command -v oci >/dev/null || {
    echo "[backup] ERROR: oci CLI 없음 — cloud-init 설치 실패 가능. 수동 설치 필요." >&2; exit 1; }
# jq 가 없으면 보존 정리가 조용히 0건으로 끝나 백업이 한도까지 쌓인다 — 먼저 걸러낸다.
command -v jq >/dev/null || {
    echo "[backup] ERROR: jq 없음 — 업로드 검증·보존 정리를 할 수 없습니다. 'dnf install -y jq'." >&2; exit 1; }

# ISO8601 → epoch. GNU(서버 Oracle Linux)와 BSD(macOS 로컬 테스트) 양쪽을 지원한다.
to_epoch() {
    local s="$1" out
    out="$(date -u -d "$s" +%s 2>/dev/null)" && { printf '%s' "$out"; return 0; }
    # BSD date: 분수초·타임존 표기를 잘라 초 단위 UTC 로 맞춘다.
    local trimmed="${s%%.*}"; trimmed="${trimmed%Z}"; trimmed="${trimmed%+00:00}"
    out="$(date -u -j -f "%Y-%m-%dT%H:%M:%S" "$trimmed" +%s 2>/dev/null)" && { printf '%s' "$out"; return 0; }
    return 1
}

# instance principal 이 실제로 동작하는지 먼저 확인한다. dynamic group/policy 미설정이 대표 원인이라
# 여기서 명확히 걸러 로그에 남긴다(이후 호출이 전부 같은 이유로 실패하는 것을 막는다).
if ! NAMESPACE="$(oci os ns get "${OCI_AUTH[@]}" --query 'data' --raw-output 2>/dev/null)"; then
    echo "[backup] ERROR: instance principal 인증 실패 — 이 인스턴스에 Object Storage 권한이 없습니다." >&2
    echo "[backup]        dynamic group 에 인스턴스가 포함됐는지, policy 가 버킷을 허용하는지 확인하세요." >&2
    echo "[backup]        deploy/oci/README.md 의 '백업' 절차를 참고하세요." >&2
    exit 1
fi

echo "[backup] $(date -u +%FT%TZ) pg_dump → gzip → oci://${BACKUP_BUCKET}/${KEY} (ns=${NAMESPACE})"

# 로컬 temp 파일로 먼저 덤프한 뒤 성공했을 때만 업로드한다. 스트리밍이면 pg_dump 가 중간 실패해도
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
if ! oci os object put "${OCI_AUTH[@]}" --bucket-name "$BACKUP_BUCKET" --name "$KEY" \
        --file "$TMP" --no-overwrite --no-multipart >/dev/null 2>&1; then
    echo "[backup] ERROR: 업로드 실패" >&2
    exit 1
fi

# 업로드 검증 — "put 이 성공했다" 와 "객체가 온전히 저장됐다" 는 다르다.
# `object head` 는 헤더를 최상위로 반환한다(다른 명령과 달리 data 래퍼가 없다). CLI 버전에 따라
# 래퍼가 붙는 경우까지 함께 받아, 잘못된 경로로 null 을 읽고 매번 "손상" 으로 오판정하는 것을 막는다.
REMOTE_SIZE="$(oci os object head "${OCI_AUTH[@]}" --bucket-name "$BACKUP_BUCKET" --name "$KEY" 2>/dev/null \
    | jq -r '(.["content-length"] // .data["content-length"] // empty) | tostring' 2>/dev/null || echo "")"
if [[ -z "$REMOTE_SIZE" || "$REMOTE_SIZE" == "null" ]]; then
    echo "[backup] ERROR: 업로드 후 객체 조회 실패 — 백업을 신뢰할 수 없음" >&2
    exit 1
fi
if ! [[ "$REMOTE_SIZE" =~ ^[0-9]+$ ]]; then
    echo "[backup] ERROR: 원격 크기를 숫자로 읽지 못함('$REMOTE_SIZE') — 백업을 신뢰할 수 없음" >&2
    exit 1
fi
if [[ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]]; then
    echo "[backup] ERROR: 크기 불일치 (로컬 ${LOCAL_SIZE} vs 원격 ${REMOTE_SIZE}) — 백업 손상 의심" >&2
    exit 1
fi
echo "[backup] 업로드 완료·검증 OK: ${KEY} (${REMOTE_SIZE} bytes)"

# ── 보존 정리 ──
# 파일명 날짜가 아니라 객체의 time-created 를 기준으로 삭제한다(파일명 규칙이 바뀌어도 안전).
# --all 로 pagination 을 처리한다. 정리 실패는 백업 자체를 실패시키지 않되 **반드시 경고로 남긴다**
# (조용히 넘기면 한도에 도달할 때까지 아무도 모른다).
CUTOFF_EPOCH="$(date -u -d "-${RETENTION} days" +%s 2>/dev/null || date -u -v-"${RETENTION}"d +%s)"
# shellcheck disable=SC2050  # 아래 루프는 to_epoch 로 GNU/BSD 를 함께 처리한다
echo "[backup] ${RETENTION}일 경과 백업 정리 (cutoff epoch=${CUTOFF_EPOCH})"

listing="$(oci os object list "${OCI_AUTH[@]}" --bucket-name "$BACKUP_BUCKET" --prefix "${PREFIX}/" \
    --fields name,timeCreated --all --query 'data[].{n:name,t:"time-created"}' 2>/dev/null || echo "")"
if [[ -z "$listing" ]]; then
    echo "[backup] WARN: 객체 목록 조회 실패 — 보존 정리를 건너뜁니다(오래된 백업이 계속 쌓일 수 있음)." >&2
else
    deleted=0 failed=0
    while IFS=$'\t' read -r name created; do
        [[ -z "${name:-}" || -z "${created:-}" ]] && continue
        obj_epoch="$(to_epoch "$created" || echo "")"
        [[ -z "$obj_epoch" ]] && { echo "[backup]   WARN 시각 파싱 실패: $name ($created)" >&2; continue; }
        if (( obj_epoch < CUTOFF_EPOCH )); then
            if oci os object delete "${OCI_AUTH[@]}" --bucket-name "$BACKUP_BUCKET" --name "$name" --force >/dev/null 2>&1; then
                echo "[backup]   삭제: $name"; deleted=$(( deleted + 1 ))
            else
                echo "[backup]   WARN 삭제 실패: $name" >&2; failed=$(( failed + 1 ))
            fi
        fi
    done < <(printf '%s' "$listing" | jq -r '.[]? | [.n, .t] | @tsv' 2>/dev/null || true)
    echo "[backup] 정리 완료 (삭제 ${deleted}건, 실패 ${failed}건)"
    (( failed > 0 )) && echo "[backup] WARN: 삭제 실패가 있습니다 — 버킷 용량을 확인하세요." >&2
fi

echo "[backup] 완료"
