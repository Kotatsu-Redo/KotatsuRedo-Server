-- The daily rollup M6 anticipated, and the thing that makes the weights in §4 tunable.
--
-- Raw probe rows are deleted at seven days, which is a promise in the privacy notice and stays
-- exactly as it is. Seven days is also far too short a window to tell whether a scoring change
-- helped: the honest way to judge one is to replay months of history and ask whether the ranking
-- predicted what happened next, and there is currently nothing left to replay.
--
-- So: roll each day up before it is purged, and keep the rollup. **It has no reporter column at
-- all.** That is the point - it holds strictly less about people than the table it outlives, it
-- cannot be linked back to a device or an account even in principle, and `reporters` is a count of
-- daily pseudonyms that were already rotating.
CREATE TABLE source_probe_daily (
    source         TEXT     NOT NULL,
    day            DATE     NOT NULL,
    region         TEXT     NOT NULL,
    op             SMALLINT NOT NULL,

    -- How many distinct daily pseudonyms reported, which is what popularity counts. Not identities:
    -- the pseudonym rotates every day and is unlinkable across days by construction.
    reporters      INTEGER  NOT NULL,

    ok             BIGINT   NOT NULL,
    fail           BIGINT   NOT NULL,
    empty          BIGINT   NOT NULL,
    cf_blocked     BIGINT   NOT NULL,

    -- Attempt-weighted mean of the reporters' own p50s. Not a true percentile over the population,
    -- and named as a mean so nobody later mistakes it for one.
    latency_mean_ms INTEGER NOT NULL,

    rolled_up_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (source, day, region, op)
);

-- The harness walks day by day across every source.
CREATE INDEX source_probe_daily_day_idx ON source_probe_daily (day);
