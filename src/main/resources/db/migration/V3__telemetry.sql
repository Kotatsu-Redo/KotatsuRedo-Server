-- M5: telemetry ingest.
--
-- Probes describe a *source*, never a person and never a manga. There is no manga title, no query,
-- no URL and no timestamp finer than a day anywhere in this table (PLAN.md §6).
--
-- reporter_day is HMAC(user secret, 'YYYY-MM-DD'). It exists only so that popularity can count
-- distinct reporters and resist brigading - not to attribute source usage to anyone. Because it is
-- keyed by the *secret*, which the server never stores, the mapping from pseudonym back to a user
-- cannot be recomputed after the fact even with the whole database in hand.

CREATE TABLE source_probe_raw (
    source       TEXT        NOT NULL,
    day          DATE        NOT NULL,
    region       TEXT        NOT NULL,
    op           SMALLINT    NOT NULL,   -- 0 search, 1 details, 2 pages
    reporter_day TEXT        NOT NULL,
    tier         SMALLINT    NOT NULL DEFAULT 0,

    ok           INTEGER     NOT NULL DEFAULT 0,
    fail         INTEGER     NOT NULL DEFAULT 0,
    empty        INTEGER     NOT NULL DEFAULT 0,
    cf_blocked   INTEGER     NOT NULL DEFAULT 0,

    latency_p50_ms INTEGER   NOT NULL DEFAULT 0,
    latency_p90_ms INTEGER   NOT NULL DEFAULT 0,

    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (source, day, region, op, reporter_day),

    CONSTRAINT source_probe_counts_non_negative
        CHECK (ok >= 0 AND fail >= 0 AND empty >= 0 AND cf_blocked >= 0)
);

-- The nightly purge scans by day; the rollup (M6) scans by day and source.
CREATE INDEX source_probe_raw_day_idx        ON source_probe_raw (day);
CREATE INDEX source_probe_raw_source_day_idx ON source_probe_raw (source, day);

-- Raw rows exist only until they have been rolled up, then for a short grace window. Retention is
-- part of the privacy promise, not a housekeeping nicety: see TelemetryRetention.
COMMENT ON TABLE source_probe_raw IS 'Per reporter-day probe counts. Purged after 7 days.';
