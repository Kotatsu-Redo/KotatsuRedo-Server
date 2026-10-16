-- "Readers who opened this also opened that", as counts and nothing else.
--
-- Genres say what a work is about; they cannot say that people who read one thing tend to read
-- another, which is what makes a recommendation feel like one. That signal is already implied by
-- work_alias_observation - which accounts opened which works - and this table is the aggregate of
-- it: for a pair of works, how many accounts opened both.
--
-- There is no account column, by design, and a pair is only stored once at least ten accounts
-- share it (WorkRepository.CO_READ_MIN_READERS), so no row describes anyone's reading. It is rebuilt
-- from scratch every day by CoReading, so an account deleted or banned today is gone from it
-- tomorrow. work_a < work_b keeps each unordered pair to one row.
CREATE TABLE work_coread (
    work_a       BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    work_b       BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    readers      INTEGER     NOT NULL,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_a, work_b),
    CHECK (work_a < work_b)
);

-- The primary key serves lookups from work_a; this serves them from work_b.
CREATE INDEX work_coread_b_idx ON work_coread (work_b);
