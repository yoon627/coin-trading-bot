#!/usr/bin/env bash
# 운영 DB(Vultr) 매매 성과 조회 — 읽기 전용 SELECT 11종.
#
# 원본은 2026-09-02 세션의 scratchpad 에 있었고 세션 종료로 소실됐다. 이 파일은
# 스키마(V1/V11/V21)와 SellReason enum 을 근거로 재작성한 것이라 쿼리 문구는 원본과 다를 수 있다.
#
# 안전장치: 각 쿼리를 READ ONLY 트랜잭션으로 열어 쓰기를 물리적으로 막는다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
VULTR_DIR="$REPO_ROOT/deploy/vultr"

[[ -f "$VULTR_DIR/.state" ]] || { echo "ERROR: $VULTR_DIR/.state 없음 — deploy.sh setup 이 만든 상태파일이 필요합니다." >&2; exit 1; }
# shellcheck disable=SC1091
source "$VULTR_DIR/.state"
: "${PUBLIC_IP:?PUBLIC_IP 가 .state 에 없습니다}"

KEY_PEM="$VULTR_DIR/coin-trading-bot-key.pem"
[[ -r "$KEY_PEM" ]] || { echo "ERROR: SSH 키 없음: $KEY_PEM" >&2; exit 1; }

q() {
  local title="$1" sql="$2"
  echo
  echo "════════ $title ════════"
  ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -i "$KEY_PEM" \
      "root@${PUBLIC_IP}" \
      "cd /opt/app && docker compose exec -T postgres psql -U trading -d trading -v ON_ERROR_STOP=1 -P pager=off" \
      <<SQL
BEGIN TRANSACTION READ ONLY;
$sql
COMMIT;
SQL
}

q "[1] 데이터 범위 — 기간·건수" "
SELECT side, count(*) AS cnt, min(created_at) AS first_at, max(created_at) AS last_at
FROM trade_records GROUP BY side ORDER BY side;
"

q "[2] 전체 매도 성과 (gross, pnl_percent 기준)" "
SELECT count(*) AS sells,
       count(*) FILTER (WHERE pnl_percent > 0) AS wins,
       round((100.0 * count(*) FILTER (WHERE pnl_percent > 0) / NULLIF(count(*),0))::numeric, 1) AS win_rate_pct,
       round(avg(pnl_percent)::numeric, 3)                                    AS avg_pnl_pct,
       round(avg(pnl_percent) FILTER (WHERE pnl_percent > 0)::numeric, 3)     AS avg_win_pct,
       round(avg(pnl_percent) FILTER (WHERE pnl_percent <= 0)::numeric, 3)    AS avg_loss_pct,
       round(sum(pnl_amount)::numeric, 0)                                     AS total_pnl_krw
FROM trade_records WHERE side = 'SELL';
"

q "[3] ★청산 사유 분포 — 핵심 가설(DAILY_RESET 비중)" "
SELECT COALESCE(reason,'(null)') AS reason, count(*) AS cnt,
       round((100.0 * count(*) / SUM(count(*)) OVER ())::numeric, 1) AS share_pct,
       round(avg(pnl_percent)::numeric, 3) AS avg_pnl_pct,
       round(sum(pnl_amount)::numeric, 0)  AS sum_pnl_krw
FROM trade_records WHERE side = 'SELL'
GROUP BY reason ORDER BY cnt DESC;
"

q "[4] 사유별 승/패 — DAILY_RESET 의 기대값이 0 에 수렴하는가" "
SELECT COALESCE(reason,'(null)') AS reason,
       count(*) FILTER (WHERE pnl_percent > 0)  AS wins,
       count(*) FILTER (WHERE pnl_percent <= 0) AS losses,
       round((100.0 * count(*) FILTER (WHERE pnl_percent > 0) / NULLIF(count(*),0))::numeric, 1) AS win_rate_pct,
       round(min(pnl_percent)::numeric, 2) AS min_pct,
       round(max(pnl_percent)::numeric, 2) AS max_pct,
       round(stddev_samp(pnl_percent)::numeric, 3) AS sd_pct
FROM trade_records WHERE side = 'SELL'
GROUP BY reason ORDER BY count(*) DESC;
"

q "[5] 티커별 성과" "
SELECT ticker, count(*) AS sells,
       round((100.0 * count(*) FILTER (WHERE pnl_percent > 0) / NULLIF(count(*),0))::numeric, 1) AS win_rate_pct,
       round(avg(pnl_percent)::numeric, 3) AS avg_pnl_pct,
       round(sum(pnl_amount)::numeric, 0)  AS sum_pnl_krw
FROM trade_records WHERE side = 'SELL'
GROUP BY ticker ORDER BY sum_pnl_krw NULLS LAST;
"

q "[6] 전략별 성과 (V21 backfill 이후 strategy 귀속)" "
SELECT COALESCE(strategy,'(null)') AS strategy, count(*) AS sells,
       round((100.0 * count(*) FILTER (WHERE pnl_percent > 0) / NULLIF(count(*),0))::numeric, 1) AS win_rate_pct,
       round(avg(pnl_percent)::numeric, 3) AS avg_pnl_pct,
       round(sum(pnl_amount)::numeric, 0)  AS sum_pnl_krw
FROM trade_records WHERE side = 'SELL'
GROUP BY strategy ORDER BY sells DESC;
"

q "[7] 수동 vs 엔진 (strategy='manual' 로 구분 — volume 의미가 다르다)" "
SELECT side, (strategy = 'manual') AS is_manual, count(*) AS cnt,
       round(avg(total_amount)::numeric, 0) AS avg_amount_krw
FROM trade_records GROUP BY side, is_manual ORDER BY side, is_manual;
"

q "[8] pnl_percent 분포 (버킷) — 익절/손절선에 몰리는가" "
SELECT width_bucket(pnl_percent, -8, 8, 16) AS bucket,
       round(min(pnl_percent)::numeric, 2) AS lo,
       round(max(pnl_percent)::numeric, 2) AS hi,
       count(*) AS cnt
FROM trade_records WHERE side = 'SELL' AND pnl_percent IS NOT NULL
GROUP BY bucket ORDER BY bucket;
"

q "[9] 일자별 (KST) 매도 건수·손익 — 거래빈도 실측" "
SELECT (created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::date AS kst_date,
       count(*) AS sells,
       round(avg(pnl_percent)::numeric, 3) AS avg_pnl_pct,
       round(sum(pnl_amount)::numeric, 0)  AS sum_pnl_krw
FROM trade_records WHERE side = 'SELL'
GROUP BY kst_date ORDER BY kst_date DESC LIMIT 30;
"

q "[10] 보유시간 — 직전 BUY 대비 SELL 시각 차 (엔진 거래만)" "
WITH s AS (
  SELECT id, ticker, created_at, reason, pnl_percent,
         (SELECT max(b.created_at) FROM trade_records b
          WHERE b.ticker = trade_records.ticker AND b.side = 'BUY' AND b.id < trade_records.id) AS buy_at
  FROM trade_records WHERE side = 'SELL' AND COALESCE(strategy,'') <> 'manual'
)
SELECT COALESCE(reason,'(null)') AS reason, count(*) AS cnt,
       round(avg(EXTRACT(EPOCH FROM (created_at - buy_at)) / 3600.0)::numeric, 2) AS avg_hold_hours,
       round(max(EXTRACT(EPOCH FROM (created_at - buy_at)) / 3600.0)::numeric, 2) AS max_hold_hours
FROM s WHERE buy_at IS NOT NULL GROUP BY reason ORDER BY cnt DESC;
"

q "[11] 최근 20건 원시 레코드" "
SELECT id, created_at, ticker, side, round(price::numeric,2) AS price,
       round(total_amount::numeric,0) AS amount, round(pnl_percent::numeric,2) AS pnl_pct,
       round(pnl_amount::numeric,0) AS pnl_krw, reason, strategy
FROM trade_records ORDER BY id DESC LIMIT 20;
"

echo
echo "완료 — 위 출력을 그대로 붙여넣어 주세요."
