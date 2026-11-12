-- "Not interested": a reader telling the app a suggested manga is not for them. It used to stay on the
-- phone; for accounts that turned the community features on it is now kept here too, under the account
-- like a rating, so it goes with the account and is in the account's export.
--
-- Used only in aggregate: a work that at least ten accounts passed on is lowered a little in "similar
-- works", and an account's pass keeps that work out of what the account contributes to co-reading. It is
-- never shown to anyone. Saying "not interested" is not opening or reading the work, so writing a pass
-- creates no work, alias or observation (see WorkRepository.applyPass).
CREATE TABLE work_pass (
    work_id        BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    user_id        TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The work the pass was made on. A merge moves work_id and never this, so an unmerge can put each
    -- pass back where it belongs: the same trick as rating.origin_work_id (no key, like that one).
    origin_work_id BIGINT,
    PRIMARY KEY (work_id, user_id)
);

CREATE INDEX work_pass_user_idx ON work_pass (user_id);
