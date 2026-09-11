-- M4a.2: the word filter.
--
-- Runs synchronously in the POST handler. Pass and the comment is published immediately; fail and it
-- is rejected with the offending term named, so the user rewrites deliberately instead of guessing
-- why the app broke (PLAN.md §6).
--
-- The lists live in the database rather than in a resource file because they are *configuration to be
-- tuned from the panel*, not a dependency to be consumed as-is. A moderator seeing `assassin` blocked
-- has to be able to fix it permanently in one click, and a rule that keeps misfiring has to be able
-- to demote itself - neither is possible if the list only exists in the jar.

CREATE TABLE filter_rule (
    id          BIGSERIAL   PRIMARY KEY,

    -- Stored already normalised (set A), because matching normalises the text and the two have to
    -- meet in the same space. `term_raw` keeps what a human typed, for the panel.
    term        TEXT        NOT NULL,
    term_raw    TEXT        NOT NULL,

    -- 0 severe | 1 profanity | 2 watch
    tier        SMALLINT    NOT NULL,

    -- NULL means every language. Only `severe` is global: a word innocuous in one language is
    -- profane in another, and unioning fifteen lists would block a great deal of ordinary text.
    lang        TEXT,

    is_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Set when the rule demoted itself after too many confirmed false positives. Distinct from
    -- `is_enabled` so the panel can tell "a human turned this off" from "this rule misfires".
    demoted_at  TIMESTAMPTZ,

    source      TEXT        NOT NULL DEFAULT 'manual',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One rule per (term, tier, language). COALESCE because NULL never equals NULL in a unique index,
-- which would otherwise let the same global term be inserted any number of times.
CREATE UNIQUE INDEX filter_rule_unique_idx ON filter_rule (term, tier, COALESCE(lang, '*'));
CREATE INDEX filter_rule_active_idx ON filter_rule (tier, lang) WHERE is_enabled;

-- Words that must never be blocked, whatever the lists say.
--
-- Three origins, and the distinction matters for the panel: `catalogue` is derived from work titles
-- and regenerates itself, `dictionary` comes from the bundled word lists, and `manual` is a decision
-- a moderator made and must survive everything.
CREATE TABLE filter_allow (
    term         TEXT        PRIMARY KEY,
    origin       TEXT        NOT NULL DEFAULT 'manual',
    note         TEXT,
    by_moderator TEXT        REFERENCES moderator (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every block, so the list can be tuned from real data rather than from a guess made once.
--
-- This table is the entire feedback loop: without it, nobody ever finds out that the `pt` list is
-- broken, and the feature quietly stops working for Portuguese.
CREATE TABLE filter_block (
    id           BIGSERIAL   PRIMARY KEY,
    rule_id      BIGINT      REFERENCES filter_rule (id) ON DELETE SET NULL,
    term         TEXT        NOT NULL,
    tier         SMALLINT    NOT NULL,
    lang         TEXT,

    -- comment | reply | nickname
    surface      TEXT        NOT NULL,
    user_id      TEXT        REFERENCES app_user (id) ON DELETE SET NULL,

    -- What the user was trying to say. Retained only for the review window and then nulled: the
    -- privacy notice says so, and a permanent archive of everything anyone was stopped from saying
    -- is not something this server should hold. The row itself stays, because the counts are what
    -- tune the lists.
    context      TEXT,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The user pressed "this was wrong" in the rejection dialog.
    disputed_at  TIMESTAMPTZ,
    reviewed_at  TIMESTAMPTZ,
    -- upheld | allowlisted | demoted
    resolution   TEXT
);

CREATE INDEX filter_block_recent_idx   ON filter_block (created_at DESC);
CREATE INDEX filter_block_open_idx     ON filter_block (created_at DESC) WHERE reviewed_at IS NULL;
-- The panel puts user-reported false positives first, because those are the ones known to be wrong.
CREATE INDEX filter_block_disputed_idx ON filter_block (disputed_at DESC) WHERE disputed_at IS NOT NULL;
CREATE INDEX filter_block_rule_idx     ON filter_block (rule_id);
CREATE INDEX filter_block_lang_idx     ON filter_block (lang, created_at);

-- A `watch` match publishes and flags. Kept on the comment rather than in its own queue table so the
-- panel can rank flagged comments alongside disliked ones.
ALTER TABLE comment ADD COLUMN flagged_rule TEXT;

CREATE INDEX comment_flagged_idx ON comment (created_at DESC) WHERE flagged_rule IS NOT NULL;

COMMENT ON COLUMN filter_rule.lang IS
    'NULL = every language. Only severe is ever global; profanity and watch are scoped to the '
    'comment''s detected language, because unioning lists across languages blocks ordinary text.';
COMMENT ON COLUMN filter_block.context IS
    'Nulled once the review window passes. The row survives for the per-rule and per-language rates.';
