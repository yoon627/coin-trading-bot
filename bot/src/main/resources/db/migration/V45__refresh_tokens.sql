-- 로그인 유지용 리프레시 토큰. 원문은 두지 않고 SHA-256 hex 만 둔다. 쓸 때마다 같은 계열(family_id)의 새 토큰으로 바뀌고,
-- 이미 바뀐 토큰이 다시 오면 계열 전체를 끊는다. 표만 더하므로 직전 이미지는 이 표를 모른 채 그대로 뜬다.
CREATE TABLE refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    remember BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 이 토큰이 쓰일 수 있는 마지막 시각. 유지면 발급마다 30일 뒤로 밀리고, 유지 안 함이면 계열의 첫 로그인 + 24시간 그대로다.
    expires_at TIMESTAMPTZ NOT NULL,
    replaced_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
