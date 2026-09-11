-- M4a: comments.
--
-- Published instantly, with no hold queue and no approval step: the word filter runs in the POST
-- handler and moderators handle what it cannot catch (PLAN.md §6).

CREATE TABLE comment (
    id              BIGSERIAL   PRIMARY KEY,
    work_id         BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,

    -- The work this was posted against, which NEVER changes. A merge moves work_id; an unmerge
    -- restores from here. That one column turns "undoing a bad merge" into an UPDATE instead of an
    -- archaeology exercise (PLAN.md §9).
    origin_work_id  BIGINT      NOT NULL,

    work_chapter_id BIGINT,
    user_id         TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    parent_id       BIGINT      REFERENCES comment (id) ON DELETE CASCADE,

    -- Denormalised so rendering a thread does not walk the parent chain per row.
    depth           SMALLINT    NOT NULL DEFAULT 0,

    body            TEXT        NOT NULL,
    is_spoiler      BOOLEAN     NOT NULL DEFAULT FALSE,
    lang            TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Recorded for moderation and audit but never surfaced: with a five-minute window almost nothing
    -- accrues, so an "edited" badge would be noise.
    edited_at       TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,

    -- 0 visible | 1 shadowed | 2 removed
    state           SMALLINT    NOT NULL DEFAULT 0,

    -- Wilson lower bound over (up, up + down): a heavily disliked comment sinks out of view rather
    -- than sitting at the top being argued with.
    score           REAL        NOT NULL DEFAULT 0,
    up              INTEGER     NOT NULL DEFAULT 0,
    down            INTEGER     NOT NULL DEFAULT 0,

    -- A removed comment is blanked in place rather than deleted, so the replies underneath it
    -- keep their thread. It is the one state in which an empty body is the correct content.
    CONSTRAINT comment_body_not_empty CHECK (state = 2 OR length(btrim(body)) > 0)
);

CREATE INDEX comment_work_idx    ON comment (work_id, state, score DESC);
CREATE INDEX comment_chapter_idx ON comment (work_chapter_id) WHERE work_chapter_id IS NOT NULL;
CREATE INDEX comment_user_idx    ON comment (user_id);
CREATE INDEX comment_parent_idx  ON comment (parent_id) WHERE parent_id IS NOT NULL;
-- Notifications ask "replies to my comments since X" and the moderation firehose asks "newest".
CREATE INDEX comment_recent_idx  ON comment (created_at DESC);

CREATE TABLE comment_vote (
    comment_id BIGINT      NOT NULL REFERENCES comment (id) ON DELETE CASCADE,
    user_id    TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- +1 like, -1 dislike. Both counts are shown; only ranking uses the Wilson bound.
    value      SMALLINT    NOT NULL CHECK (value IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id)
);

CREATE INDEX comment_vote_user_idx ON comment_vote (user_id);

COMMENT ON COLUMN comment.state IS
    'shadowed means visible to its author and nobody else - it must never be revealed by any '
    'response, including notifications, or the shadowban is pointless.';
