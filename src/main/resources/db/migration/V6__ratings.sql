-- M3: ratings.
--
-- The app shows five stars. Values are stored in half-star steps (2 = 1 star ... 10 = 5 stars) so
-- that adding half-stars later is a UI change rather than a migration. Costs nothing now.
--
-- These are the app's own users rating for themselves. Nothing is imported from a tracker - seeding
-- it with someone else's numbers would make this a worse copy of a tracker (PLAN.md §2.3).

CREATE TABLE rating (
    work_id    BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    user_id    TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    value      SMALLINT    NOT NULL CHECK (value BETWEEN 1 AND 10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_id, user_id)
);

CREATE INDEX rating_work_idx ON rating (work_id);
CREATE INDEX rating_user_idx ON rating (user_id);
-- Brigade detection scans by recency.
CREATE INDEX rating_recent_idx ON rating (work_id, created_at);

-- Denormalised so the details screen costs one indexed read rather than an aggregate over every
-- rating a popular work has ever received.
CREATE TABLE work_rating_agg (
    work_id    BIGINT      PRIMARY KEY REFERENCES work (id) ON DELETE CASCADE,
    count      INTEGER     NOT NULL DEFAULT 0,
    mean       REAL        NOT NULL DEFAULT 0,
    -- What ranking uses. A raw mean lets one 5-star rating outrank a 500-vote 4.4.
    bayesian   REAL        NOT NULL DEFAULT 0,
    -- Five buckets, one per whole star. The distribution is the interesting part: it separates
    -- "everyone likes it" from "half love it, half hate it", which a mean hides entirely.
    histogram  INTEGER[]   NOT NULL DEFAULT ARRAY[0,0,0,0,0],
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A coordinated review-bomb is invisible to the Bayesian prior: the prior blunts its effect but says
-- nothing about it having happened. Flagged for a human; nothing is ever reverted automatically.
CREATE TABLE rating_brigade_flag (
    id           BIGSERIAL   PRIMARY KEY,
    work_id      BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    recent_count INTEGER     NOT NULL,
    baseline     REAL        NOT NULL,
    extreme_share REAL       NOT NULL,
    new_user_share REAL      NOT NULL,
    reviewed_at  TIMESTAMPTZ
);

CREATE INDEX rating_brigade_unreviewed_idx ON rating_brigade_flag (detected_at) WHERE reviewed_at IS NULL;
