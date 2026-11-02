-- The cover address each account saw on a source entry, so a suggestion can show its cover without the
-- app first asking that site for the whole entry. Other people's apps load these addresses, so one is only
-- ever served once several established accounts have reported exactly the same one: see WorkRepository.
CREATE TABLE work_cover_observation (
    source     TEXT        NOT NULL,
    source_key TEXT        NOT NULL,
    user_id    TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    cover_url  TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_key, user_id)
);

CREATE INDEX work_cover_observation_user_idx ON work_cover_observation (user_id, source, source_key);
