-- Source keys arrive from clients and cannot be verified against a parser registry on this server.
-- Keep legacy aliases for moderation/recovery, but do not treat them as global truth until distinct
-- accounts corroborate the same source key, title rendering, and work.
ALTER TABLE work_alias ADD COLUMN is_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE work_alias_observation (
    source      TEXT        NOT NULL,
    source_key  TEXT        NOT NULL,
    user_id     TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    work_id     BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    title_keys  TEXT[]      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_key, user_id)
);

CREATE INDEX work_alias_observation_agreement_idx
    ON work_alias_observation (source, source_key, work_id);
