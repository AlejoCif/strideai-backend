CREATE TABLE ai_usage_log (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    provider      VARCHAR(50)  NOT NULL,
    input_tokens  INT          NOT NULL,
    output_tokens INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
