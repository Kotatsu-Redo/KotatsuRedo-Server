-- M1: identity.
--
-- An identity is a 32-byte secret generated on the device. The server stores only sha256(secret),
-- exactly like a hashed API key, so a database leak exposes no credentials (PLAN.md §1).
-- There is no email, no password, and no IP address anywhere in this schema.

CREATE TABLE app_user (
    id              TEXT        PRIMARY KEY,
    secret_sha256   BYTEA       NOT NULL UNIQUE,
    nickname        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_banned       BOOLEAN     NOT NULL DEFAULT FALSE,
    ban_reason      TEXT,
    is_shadowbanned BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Trust tiers are derived from "days on which this user did something", not from a counter that
-- could be inflated by one busy afternoon.
CREATE TABLE user_active_day (
    user_id TEXT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    day     DATE NOT NULL,
    PRIMARY KEY (user_id, day)
);

-- Device identifiers, captured once at signup and used for nothing but ban enforcement.
--
-- ssaid_hash  - peppered hash of ANDROID_ID. Survives reinstall, reset by factory reset, and does
--               NOT collide. This is the only value safe to ban on automatically.
-- drm_hash    - peppered hash of the MediaDrm/Widevine device id. Often survives a factory reset,
--               but a measurable share of devices share one, and degoogled ROMs have none at all.
--               Never ban on this; it only raises a flag for a human (PLAN.md §1).
CREATE TABLE app_device (
    user_id       TEXT        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    ssaid_hash    BYTEA       NOT NULL,
    drm_hash      BYTEA,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX app_device_ssaid_idx ON app_device (ssaid_hash);
CREATE INDEX app_device_drm_idx   ON app_device (drm_hash) WHERE drm_hash IS NOT NULL;

CREATE TABLE device_ban (
    ssaid_hash   BYTEA       PRIMARY KEY,
    drm_hash     BYTEA,
    banned_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    by_moderator TEXT,
    reason       TEXT
);

CREATE INDEX device_ban_drm_idx ON device_ban (drm_hash) WHERE drm_hash IS NOT NULL;

-- A new identity whose drm_hash matches a banned device but whose ssaid_hash does not: the
-- factory-reset case. Deliberately NOT a block, because drm_hash collides - a moderator confirms
-- from behaviour, and a collision costs an innocent user nothing.
CREATE TABLE ban_evasion_flag (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    matched_drm_hash BYTEA       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at      TIMESTAMPTZ
);

CREATE INDEX ban_evasion_unreviewed_idx ON ban_evasion_flag (created_at) WHERE reviewed_at IS NULL;

-- Trust tiers as a view rather than a subsystem (PLAN.md §A). Tiers gate rate limits and telemetry
-- weight only - they never gate publication.
--
-- Tier 2 currently means "old and never sanctioned"; once mod_action exists (M4b) the condition
-- should become "no moderator action against this user" rather than the ban flags alone.
CREATE VIEW user_trust AS
SELECT u.id AS user_id,
       CASE
           WHEN u.created_at > now() - INTERVAL '24 hours'
                OR (SELECT count(*) FROM user_active_day d WHERE d.user_id = u.id) < 3 THEN 0
           WHEN u.created_at < now() - INTERVAL '90 days'
                AND NOT u.is_banned AND NOT u.is_shadowbanned THEN 2
           ELSE 1
       END AS tier
FROM app_user u;
