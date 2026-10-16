-- Stability per operation, alongside the blended figure.
--
-- `source_probe_raw` has always recorded which operation a probe describes - search, details or
-- pages - and the aggregation summed it away before scoring. One number then had to answer three
-- different questions, and the common failure is exactly the one it hides: a source whose search
-- works perfectly and whose page parser is broken scores somewhere in the middle, high enough to
-- keep being offered for reading and low enough to be passed over for search.
--
-- The blended score stays: it is what ranks a source in general, and what an old client reads. This
-- table adds the per-operation detail beside it, so the app can rank search by the search figure and
-- "read now" by the pages one.
--
-- No new telemetry: this is the operation column that was already being collected and discarded.
CREATE TABLE source_score_op (
    source      TEXT        NOT NULL,
    region      TEXT        NOT NULL,
    op          SMALLINT    NOT NULL,   -- 0 search, 1 details, 2 pages, as source_probe_raw.op
    stability   REAL        NOT NULL,
    -- Reporter-days behind this operation alone. Smaller than the blended sample, so the client
    -- needs it to know when to fall back to the median instead of trusting a thin number.
    sample_size INTEGER     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (source, region, op),
    -- Scores are replaced wholesale per region on every recomputation, and a source that drops out
    -- of the blended table must not keep per-operation rows nothing will ever refresh.
    FOREIGN KEY (source, region) REFERENCES source_score (source, region) ON DELETE CASCADE
);

CREATE INDEX source_score_op_region_idx ON source_score_op (region);
