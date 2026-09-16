-- pgbench workload for the authenticated first-time creation transaction. Seed an app_user named
-- benchmark-creation-user first. It deliberately writes __work_creation_benchmark__ works; delete
-- those works and the benchmark account after a run (dependent rows cascade).
\set benchmark_lock 1263683407
BEGIN;
SELECT txid_current() AS request_id \gset
\if :use_lock
SELECT pg_advisory_xact_lock(:benchmark_lock);
\endif

SELECT work_id
FROM work_external_id
WHERE provider = 'benchmark' AND external_id = CAST(:request_id AS text);

SELECT DISTINCT work.id
FROM work_alias_observation observation
JOIN work ON work.id = observation.work_id AND work.merged_into IS NULL
WHERE observation.user_id = 'benchmark-creation-user'
  AND observation.title_keys && ARRAY['benchmark-' || CAST(:request_id AS text)]::text[]
ORDER BY work.id
LIMIT 20;

INSERT INTO work (canonical_title, year, content_type, nsfw)
VALUES ('__work_creation_benchmark__', 2024, 'manga', FALSE)
RETURNING id AS work_id \gset

INSERT INTO work_title (work_id, title_raw, title_norm, kind, weight)
VALUES (:work_id, 'Benchmark Work', 'benchmark work', 'catalogue', 1.0);

INSERT INTO work_external_id (provider, external_id, work_id)
VALUES ('benchmark', CAST(:request_id AS text), :work_id);

INSERT INTO work_alias_observation (source, source_key, user_id, work_id, title_keys)
VALUES (
    'BENCHMARK_CREATE',
    '/benchmark/' || CAST(:work_id AS text),
    'benchmark-creation-user',
    :work_id,
    ARRAY['benchmark-' || CAST(:request_id AS text)]::text[]
);

COMMIT;
