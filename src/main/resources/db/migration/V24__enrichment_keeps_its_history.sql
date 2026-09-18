-- A finished entry is marked, not deleted.
--
-- Deleting it lost the one fact worth keeping: that this work has already been asked about. A work
-- that no catalogue lists, or whose record carried no identifiers, went straight back to looking
-- exactly like a work nobody had ever looked up - so the backfill queued it again, every sweep,
-- forever, asking three providers the same question they had already answered.
ALTER TABLE work_enrichment ADD COLUMN done_at TIMESTAMPTZ;

-- The enricher only ever wants the unfinished ones.
DROP INDEX IF EXISTS work_enrichment_due_idx;
CREATE INDEX work_enrichment_due_idx ON work_enrichment (next_attempt_at, work_id) WHERE done_at IS NULL;
