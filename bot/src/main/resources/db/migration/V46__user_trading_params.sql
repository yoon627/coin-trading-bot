-- 사용자가 화면에서 바꾼 매매 값. 칼럼이 NULL 이면 서버 설정(env, TradingProperties)을 쓴다. 범위 검사는 코드(ExitParamRanges)가
-- 단일 기준이라 여기서 다시 두지 않는다. 표만 더하므로 직전 이미지는 이 표를 모른 채 그대로 뜬다(새 매수는 env 값으로 돈다).
CREATE TABLE user_trading_params (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    exchange VARCHAR(16) NOT NULL,
    k_value DOUBLE PRECISION,
    invest_ratio DOUBLE PRECISION,
    max_invest_amount DOUBLE PRECISION,
    take_profit_pct DOUBLE PRECISION,
    max_loss_pct DOUBLE PRECISION,
    trailing_stop_pct DOUBLE PRECISION,
    trailing_arm_pct DOUBLE PRECISION,
    max_hold_days INTEGER,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_trading_params_user_exchange UNIQUE (user_id, exchange)
);
