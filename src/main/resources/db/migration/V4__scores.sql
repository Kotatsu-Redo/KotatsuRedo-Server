-- M6: source scores.
--
-- Computed from source_probe_raw on a schedule, so that raw per-reporter rows can be purged at 7
-- days (PLAN.md §6) while the derived scores live on indefinitely. Nothing here is per-user.

CREATE TABLE source_score (
    source      TEXT        NOT NULL,
    region      TEXT        NOT NULL,

    stability   REAL        NOT NULL,   -- 0..1, Wilson-bounded success blended with latency
    popularity  REAL        NOT NULL,   -- 0..1, log-scaled distinct reporters, decayed
    composite   REAL        NOT NULL,   -- the *global* half only; the client adds local + affinity

    sample_size INTEGER     NOT NULL,   -- reporter-days behind the score; drives the client's prior
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (source, region)
);

CREATE INDEX source_score_region_idx ON source_score (region);

COMMENT ON COLUMN source_score.composite IS
    'Global component only. The app blends in its own local success history and language affinity.';
COMMENT ON COLUMN source_score.sample_size IS
    'Reporter-days. Below the client threshold a source scores at the median instead of near zero, '
    'so a new source is not starved of the traffic it needs to earn a score.';
