-- Write-ahead order log (WAL) for KIS stock orders — order-loss prevention / reconcile.
-- A row is INSERTed (SUBMITTING) BEFORE placeOrder is called, in its own committed
-- transaction, so a crash between place and fill-confirm never loses the order.
-- See .claude/plans/2026-06-14-stock-bot-kis/stock-bot-kis-plan.md (D4/D7).
CREATE TABLE stock_order_intent (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    client_ref    VARCHAR(40) NOT NULL,           -- our generated correlation id (dedup / WAL key)
    exchange      VARCHAR(10) NOT NULL DEFAULT 'KIS',
    account_no    VARCHAR(20) NOT NULL,           -- CANO-ACNT_PRDT_CD
    symbol        VARCHAR(20) NOT NULL,           -- KIS PDNO (6-digit ticker)
    side          VARCHAR(4)  NOT NULL,           -- BUY / SELL
    order_type    VARCHAR(10) NOT NULL,           -- LIMIT / MARKET (maps ORD_DVSN 00/01)
    qty           BIGINT NOT NULL,                -- ORD_QTY (integer shares)
    price         NUMERIC(18,2),                  -- ORD_UNPR (KRW; null for market)
    status        VARCHAR(16) NOT NULL,           -- see StockOrderStatus
    odno          VARCHAR(20),                    -- KIS order number (after place)
    org_no        VARCHAR(20),                    -- KRX_FWDG_ORD_ORGNO (for amend/cancel)
    order_date    VARCHAR(8)  NOT NULL,           -- KST YYYYMMDD (inquiry matching window)
    executed_qty  BIGINT NOT NULL DEFAULT 0,      -- filled shares (from reconcile)
    audit_recorded BOOLEAN NOT NULL DEFAULT false,-- true once the fill is written to trade_executions (idempotency)
    fail_reason   VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- client_ref is globally unique (correlation / accidental double-submit guard).
CREATE UNIQUE INDEX uq_stock_order_intent_client_ref ON stock_order_intent(client_ref);

-- Invariant (D4): at most ONE non-terminal order per (user, exchange, account, symbol).
-- DB-enforced so @Scheduled reconcile, manual API, or a second app instance cannot violate it
-- (app-level Mutex does not cover those). Mirrors existing partial-scope uniqueness conventions
-- (positions V11, bot_configs V12).
CREATE UNIQUE INDEX uq_stock_order_intent_active
    ON stock_order_intent(user_id, exchange, account_no, symbol)
    WHERE status IN ('SUBMITTING', 'PLACED', 'PARTIAL', 'UNKNOWN', 'NEEDS_REVIEW');

-- Reconcile scan: fetch non-terminal rows oldest-first for lease processing.
CREATE INDEX idx_stock_order_intent_active
    ON stock_order_intent(updated_at)
    WHERE status IN ('SUBMITTING', 'PLACED', 'PARTIAL', 'UNKNOWN', 'NEEDS_REVIEW');
