-- Baseline. Establishes the extensions the rest of the schema depends on and nothing else, so that
-- M0 proves the migration pipeline end to end before any table exists to get it wrong.
--
-- pg_trgm is not speculative: work_title.title_norm is matched with a trigram GIN index and that one
-- index is the entire search infrastructure for work resolution (PLAN.md §2.1).

CREATE EXTENSION IF NOT EXISTS pg_trgm;
