-- Device restore: a phone that lost its key gets its account back.
--
-- Until now an identity was exactly one secret, held in app_user.secret_sha256. That makes losing
-- the key final - and clearing app data loses it - so a single phone accumulated an account per
-- wipe. Restoring means letting a *new* secret speak for an existing account.
--
-- Two ways to do that. Overwrite the old hash, or keep both. This keeps both, because the recovery
-- key is explicitly allowed to live on two phones at once ("they are the same user"), and
-- overwriting would have silently locked out the other one the moment somebody restored here.
--
-- app_user.secret_sha256 stays as the first secret an account ever had, so nothing that reads it
-- changes meaning; user_secret is what authentication actually resolves against.

CREATE TABLE user_secret (
    secret_sha256 BYTEA       PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 'signup' for the key an account was created with, 'device_restore' for one adopted later.
    -- Kept so a moderator looking at a suspicious account can see it changed hands.
    origin        TEXT        NOT NULL DEFAULT 'signup',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX user_secret_user_idx ON user_secret (user_id);

-- Every existing account keeps working: its one secret becomes its first user_secret row.
INSERT INTO user_secret (secret_sha256, user_id, origin, created_at)
SELECT secret_sha256, id, 'signup', created_at FROM app_user;
