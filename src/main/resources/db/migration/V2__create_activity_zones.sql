CREATE TABLE IF NOT EXISTS activity_zones (
    strava_activity_id BIGINT PRIMARY KEY,
    user_id            BIGINT  NOT NULL,
    zones_json         TEXT    NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);
