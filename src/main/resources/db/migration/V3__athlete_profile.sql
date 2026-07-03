CREATE TABLE IF NOT EXISTS athlete_profile (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT        NOT NULL UNIQUE,
    weight_kg           DECIMAL(5,2),
    main_sport          VARCHAR(50),
    goal_event          VARCHAR(200),
    goal_date           DATE,
    equipment           TEXT,
    component_tracking  JSONB,
    notes               TEXT,
    created_at          TIMESTAMPTZ   DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   DEFAULT NOW(),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
