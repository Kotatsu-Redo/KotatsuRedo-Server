-- A merged work's queue entry can never be worked, so it must not stay queued.
--
-- `dueEnrichments` only ever selects entries whose work is still its own (`merged_into IS NULL`), so
-- once the duplicate sweep merged a work away its entry became unreachable: never picked up, never
-- finished, pending forever. The queue grew a floor of entries nothing could ever clear, and the
-- oldest-entry age climbed without bound - which is how this was noticed.
--
-- New merges close their entry in `mergeWorks` itself. This clears the ones already stranded.
UPDATE work_enrichment
SET done_at = now()
FROM work
WHERE work.id = work_enrichment.work_id
  AND work.merged_into IS NOT NULL
  AND work_enrichment.done_at IS NULL;
