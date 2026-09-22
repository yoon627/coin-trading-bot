#!/usr/bin/env bash
# 재조회(2026-09-23): 2026-09-06 청산 파라미터 변경(트레일링 2.0/arm3 → 1.5/arm0)을 경계로 표본을 갈라 본다.
# 읽기 전용 — 트랜잭션을 READ ONLY 로 연다. 엔진 거래만(strategy <> 'manual').
set -euo pipefail
VULTR_DIR=/Users/jongyoonlee/Repos/coin-trading-bot/deploy/vultr
source "$VULTR_DIR/.state"
KEY_PEM=$(ls "$VULTR_DIR"/*.pem | head -1)

q() {
  echo; echo "════════ $1 ════════"
  ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -i "$KEY_PEM" "root@${PUBLIC_IP}" \
    "cd /opt/app && docker compose exec -T postgres psql -U trading -d trading -v ON_ERROR_STOP=1 -P pager=off" <<SQL
BEGIN TRANSACTION READ ONLY;
$2
COMMIT;
SQL
}

SELLS="SELECT *, CASE WHEN created_at < '2026-09-06' THEN 'A_pre0906' ELSE 'B_post0906' END AS period
       FROM trade_records WHERE side = 'SELL' AND COALESCE(strategy,'') <> 'manual'"

q "[S1] 기간별 전체 — 건수·승률·평균(gross)·표준오차·합계" "
WITH s AS ($SELLS)
SELECT period, count(*) AS sells, min(created_at)::date AS first_d, max(created_at)::date AS last_d,
       round((100.0*count(*) FILTER (WHERE pnl_percent>0)/count(*))::numeric,1) AS win_pct,
       round(avg(pnl_percent)::numeric,3) AS avg_pct,
       round((stddev_samp(pnl_percent)/sqrt(count(*)))::numeric,3) AS se_pct,
       round(avg(pnl_percent) FILTER (WHERE pnl_percent>0)::numeric,3) AS avg_win,
       round(avg(pnl_percent) FILTER (WHERE pnl_percent<=0)::numeric,3) AS avg_loss,
       round(sum(pnl_amount)::numeric,0) AS sum_krw
FROM s GROUP BY period ORDER BY period;"

q "[S2] 기간×사유 — 비중·EV·표준오차·합계" "
WITH s AS ($SELLS)
SELECT period, COALESCE(reason,'(null)') AS reason, count(*) AS cnt,
       round((100.0*count(*)/SUM(count(*)) OVER (PARTITION BY period))::numeric,1) AS share_pct,
       round(avg(pnl_percent)::numeric,3) AS avg_pct,
       round((stddev_samp(pnl_percent)/sqrt(count(*)))::numeric,3) AS se_pct,
       round((100.0*count(*) FILTER (WHERE pnl_percent>0)/count(*))::numeric,1) AS win_pct,
       round(sum(pnl_amount)::numeric,0) AS sum_krw
FROM s GROUP BY period, reason ORDER BY period, cnt DESC;"

q "[S3] 기간×티커 (B 만, 건수 순)" "
WITH s AS ($SELLS)
SELECT ticker, count(*) AS cnt, round(avg(pnl_percent)::numeric,3) AS avg_pct, round(sum(pnl_amount)::numeric,0) AS sum_krw
FROM s WHERE period='B_post0906' GROUP BY ticker ORDER BY cnt DESC, sum_krw;"

q "[S4] 보유시간 — 직전 엔진 BUY(수동 제외) 매칭, 기간×사유" "
WITH s AS (
  SELECT t.*, CASE WHEN t.created_at < '2026-09-06' THEN 'A_pre0906' ELSE 'B_post0906' END AS period,
         (SELECT max(b.created_at) FROM trade_records b
          WHERE b.ticker=t.ticker AND b.side='BUY' AND COALESCE(b.strategy,'')<>'manual' AND b.id<t.id) AS buy_at
  FROM trade_records t WHERE t.side='SELL' AND COALESCE(t.strategy,'')<>'manual')
SELECT period, COALESCE(reason,'(null)') AS reason, count(*) AS cnt,
       round(avg(EXTRACT(EPOCH FROM (created_at-buy_at))/3600.0)::numeric,1) AS avg_h,
       round(max(EXTRACT(EPOCH FROM (created_at-buy_at))/3600.0)::numeric,1) AS max_h,
       count(*) FILTER (WHERE created_at-buy_at > interval '30 hours') AS over_30h
FROM s WHERE buy_at IS NOT NULL GROUP BY period, reason ORDER BY period, cnt DESC;"

q "[S5] 주별 추이 (KST 주 시작일)" "
WITH s AS ($SELLS)
SELECT date_trunc('week', created_at AT TIME ZONE 'Asia/Seoul')::date AS week_kst, count(*) AS sells,
       round(avg(pnl_percent)::numeric,3) AS avg_pct, round(sum(pnl_amount)::numeric,0) AS sum_krw
FROM s GROUP BY 1 ORDER BY 1;"

q "[S6] 그림자 관측(#178 — 판정 아님, N 만)" "
SELECT trailing_stop_pct, trailing_arm_pct, count(*) AS n,
       round(avg(100.0*(modeled_exit_price-observed_tick_price)/modeled_exit_price)::numeric,3) AS model_overshoot_pct,
       count(live_exit_vwap) AS with_vwap,
       min(fired_at)::date AS first_d, max(fired_at)::date AS last_d
FROM shadow_exit_observation GROUP BY 1,2;"
