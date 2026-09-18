-- Works waiting for a catalogue to describe them.
--
-- Resolution used to call Kitsu or MangaUpdates while the user waited, so a busy external pool became
-- a 429 on the client and a missing rating row. A work is now created from what the source said and
-- queued here; the enricher fills in the catalogue's titles and ids afterwards, and merges the work
-- away when those ids turn out to belong to one we already had.
--
-- A table rather than an in-memory queue: the whole point is to survive the restart that a deploy or
-- a crash brings, otherwise the work stays provisional forever and splits exactly as before.
CREATE TABLE work_enrichment (
    work_id         BIGINT      PRIMARY KEY REFERENCES work (id) ON DELETE CASCADE,
    title           TEXT        NOT NULL,
    year            SMALLINT,
    content_type    TEXT,
    attempts        SMALLINT    NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The enricher's only query: the oldest few rows that are due.
CREATE INDEX work_enrichment_due_idx ON work_enrichment (next_attempt_at, work_id);
