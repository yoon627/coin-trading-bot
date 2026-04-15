-- Per-user exchange API credentials
CREATE TABLE user_exchange_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    access_key VARCHAR(512) NOT NULL,
    secret_key VARCHAR(512) NOT NULL,
    label VARCHAR(50),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, exchange)
);

-- Bot configurations (per-user, per-market strategy settings)
CREATE TABLE bot_configs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    exchange VARCHAR(10) NOT NULL,
    market VARCHAR(20) NOT NULL,
    strategy VARCHAR(50) NOT NULL,
    parameters JSONB DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, exchange, market)
);
CREATE INDEX idx_bot_configs_user ON bot_configs(user_id, enabled);
