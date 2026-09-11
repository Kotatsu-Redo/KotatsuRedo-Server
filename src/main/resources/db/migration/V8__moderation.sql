-- M4b: moderation.
--
-- With instant publish, no hold queue and no report button, the panel is not a convenience layer on
-- top of the moderation system - it *is* the moderation system (PLAN.md §6).
--
-- Moderators are a completely separate population from users. A user is a key with no password and
-- no recovery path; a moderator is a named account with a password, TOTP and a role. Nothing joins
-- the two tables, and nothing should: a moderator identity must never be a user identity.

CREATE TABLE moderator (
    id            TEXT        PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,

    -- pbkdf2_sha256$<iterations>$<salt_b64>$<hash_b64>. Self-describing, so the iteration count can
    -- be raised later without invalidating existing passwords.
    password_hash TEXT        NOT NULL,

    -- Base32, RFC 4648. Null until the account enrols; a moderator with no confirmed secret can do
    -- nothing but enrol, which is what makes "TOTP from the start" true rather than aspirational.
    totp_secret   TEXT,
    totp_confirmed BOOLEAN    NOT NULL DEFAULT FALSE,

    -- 0 moderator | 1 admin
    role          SMALLINT    NOT NULL DEFAULT 0,
    is_disabled   BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    invited_by    TEXT        REFERENCES moderator (id) ON DELETE SET NULL
);

-- Usernames are matched case-insensitively at login, so uniqueness has to be too: without this,
-- "Alice" and "alice" are two accounts that log in as each other's near-miss.
CREATE UNIQUE INDEX moderator_username_lower_idx ON moderator (lower(username));

-- Sessions hold no IP address and no user agent. §6's "we log nothing that identifies a request"
-- applies to the panel too - a moderator is a person, and this table would be the one place their
-- movements could be reconstructed.
CREATE TABLE mod_session (
    token_sha256 BYTEA       PRIMARY KEY,
    moderator_id TEXT        NOT NULL REFERENCES moderator (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX mod_session_moderator_idx ON mod_session (moderator_id);
CREATE INDEX mod_session_expiry_idx    ON mod_session (expires_at);

-- A TOTP code is valid for a whole 30-second step, which is long enough for a shoulder-surfed or
-- intercepted code to be replayed. Burning the step on use closes that.
CREATE TABLE mod_totp_use (
    moderator_id TEXT   NOT NULL REFERENCES moderator (id) ON DELETE CASCADE,
    time_step    BIGINT NOT NULL,
    PRIMARY KEY (moderator_id, time_step)
);

-- The audit log. Append-only, attributed to an individual, and never deleted: it is what answers
-- "who removed this, and why" a year later, and what makes a dispute between moderators resolvable.
--
-- `detail` carries a snapshot of what was changed - notably the body of a removed comment, which the
-- comment row itself no longer holds once it is blanked.
CREATE TABLE mod_action (
    id           BIGSERIAL   PRIMARY KEY,
    moderator_id TEXT        NOT NULL REFERENCES moderator (id) ON DELETE RESTRICT,
    action       TEXT        NOT NULL,
    target_type  TEXT        NOT NULL,
    target_id    TEXT        NOT NULL,
    reason       TEXT,
    detail       JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mod_action_recent_idx    ON mod_action (created_at DESC);
CREATE INDEX mod_action_moderator_idx ON mod_action (moderator_id, created_at DESC);
CREATE INDEX mod_action_target_idx    ON mod_action (target_type, target_id);

-- Enforced in the database rather than by convention, because "append-only" that depends on every
-- future caller remembering is not append-only. A moderator with database access can still drop the
-- trigger - but they cannot do it by accident, and they cannot do it through the panel.
CREATE FUNCTION mod_action_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'mod_action is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER mod_action_no_update BEFORE UPDATE OR DELETE ON mod_action
    FOR EACH ROW EXECUTE FUNCTION mod_action_is_append_only();

-- A user's assertion that a work is wrong - two works that should be one, or one that should be two.
-- Also where WorkLinker files the pairs it refuses to merge automatically because both sides already
-- carry user content (§2.7).
CREATE TABLE work_link_dispute (
    id          BIGSERIAL   PRIMARY KEY,
    work_id     BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    other_work  BIGINT      REFERENCES work (id) ON DELETE CASCADE,
    -- same_work | different_works | needs_review
    kind        TEXT        NOT NULL,
    -- Null when the server filed it itself rather than a user.
    reported_by TEXT        REFERENCES app_user (id) ON DELETE SET NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by TEXT        REFERENCES moderator (id) ON DELETE SET NULL
);

CREATE INDEX work_link_dispute_open_idx ON work_link_dispute (created_at) WHERE resolved_at IS NULL;
-- One open dispute per pair, so a hundred users reporting the same bad merge is one queue item.
CREATE UNIQUE INDEX work_link_dispute_pair_idx
    ON work_link_dispute (work_id, COALESCE(other_work, 0), kind) WHERE resolved_at IS NULL;

-- Merges stop destroying the losing work.
--
-- V5 promised "merges never destroy rows, so a bad merge is reversible" and then the code deleted
-- the row anyway, which made `comment.origin_work_id` decoration rather than a mechanism. A merged
-- work now survives as a redirect: content moves, the row stays, and unmerge is possible.
ALTER TABLE work ADD COLUMN merged_into BIGINT REFERENCES work (id) ON DELETE SET NULL;

CREATE INDEX work_merged_idx ON work (merged_into) WHERE merged_into IS NOT NULL;

-- Which rows the merge moved - aliases, external ids and cover hashes - so an unmerge can put back
-- exactly those and not the ones that always belonged to the surviving work. Comments and ratings
-- need no record here: they carry their own origin_work_id.
ALTER TABLE work_merge_log ADD COLUMN moved_rows JSONB;
ALTER TABLE work_merge_log ADD COLUMN undone_at     TIMESTAMPTZ;
ALTER TABLE work_merge_log ADD COLUMN by_moderator  TEXT REFERENCES moderator (id) ON DELETE SET NULL;

-- The same trick as comment.origin_work_id, for the same reason: a merge moves work_id, and this is
-- what says where the row came from.
ALTER TABLE rating ADD COLUMN origin_work_id BIGINT;
UPDATE rating SET origin_work_id = work_id WHERE origin_work_id IS NULL;
ALTER TABLE rating ALTER COLUMN origin_work_id SET NOT NULL;

COMMENT ON COLUMN work.merged_into IS
    'Set when this work was merged away. The row is kept as a redirect: lookups must exclude it, '
    'and clients holding the old id get work_moved rather than silence.';
