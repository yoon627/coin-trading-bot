-- #190 라이브 진입 대조용 추출 (read-only). 금액·수량은 뽑지 않는다 — 결과 TSV 는 저장소 밖 캐시로만 간다.
-- 격리: 운영 봇 사용자(user_id 4) · 운영 TRADING_TICKERS 8마켓 · [2026-07-14, 2026-09-15) UTC. 다른 사용자·전략·기간이 섞이면 76/76 이 아니어도 통과할 수 있다.
-- 실행: docker compose exec -T postgres psql -U trading -d trading -A -F $'\t' < live_entry_trades.sql > live.tsv
--       python3 scripts/collect_live_entry_fixtures.py --trades live.tsv
SELECT id, ticker, side, price,
       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS') AS created_utc,
       coalesce(reason, '') AS reason, pnl_percent
FROM trade_records
WHERE strategy = 'combined'
  AND user_id = 4
  AND ticker IN ('KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE', 'KRW-ADA', 'KRW-AVAX', 'KRW-LINK')
  AND created_at >= '2026-07-14' AND created_at < '2026-09-15'
ORDER BY created_at, id;
