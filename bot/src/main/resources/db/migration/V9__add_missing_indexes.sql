-- trade_records: user_id + created_at 복합 인덱스 (거래 내역 조회 성능)
CREATE INDEX idx_trade_records_user_created ON trade_records(user_id, created_at DESC);

-- trade_records: side + pnl_percent 인덱스 (리더보드 쿼리 성능)
CREATE INDEX idx_trade_records_side_pnl ON trade_records(side, pnl_percent) WHERE pnl_percent IS NOT NULL;

-- price_snapshots: 오래된 데이터 정리용 인덱스
CREATE INDEX idx_price_snapshots_captured_at_only ON price_snapshots(captured_at);
