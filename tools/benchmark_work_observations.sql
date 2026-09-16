\set ON_ERROR_STOP on
\timing on
BEGIN;

INSERT INTO app_user (id, secret_sha256, created_at)
SELECT 'benchmark-user-' || n,
       decode(md5('benchmark-a-' || n) || md5('benchmark-b-' || n), 'hex'),
       now() - INTERVAL '100 days'
FROM generate_series(1, 1000) n
ON CONFLICT (id) DO NOTHING;

INSERT INTO work (canonical_title, year, content_type, nsfw)
VALUES ('__work_observation_benchmark__', 2024, 'manga', FALSE);

INSERT INTO work_alias_observation (source, source_key, user_id, work_id, title_keys)
SELECT 'BENCHMARK',
       '/benchmark/' || account || '/' || item,
       'benchmark-user-' || account,
       (SELECT id FROM work WHERE canonical_title = '__work_observation_benchmark__' ORDER BY id DESC LIMIT 1),
       ARRAY[CASE WHEN item = 200 THEN 'needle' ELSE 'title-' || item END]
FROM generate_series(1, 1000) account
CROSS JOIN generate_series(1, 200) item;

ANALYZE work_alias_observation;

-- Repeat-source hot path: primary-key lookup used whenever the app reopens an existing source entry.
EXPLAIN (ANALYZE, BUFFERS)
SELECT work_id FROM work_alias_observation
WHERE source = 'BENCHMARK'
  AND source_key = '/benchmark/500/200'
  AND user_id = 'benchmark-user-500'
  AND title_keys && ARRAY['needle']::text[];

-- New-source path: this account's matching observation from any other source.
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT observation.work_id
FROM work_alias_observation observation
JOIN work ON work.id = observation.work_id AND work.merged_into IS NULL
WHERE observation.user_id = 'benchmark-user-500'
  AND observation.title_keys && ARRAY['needle']::text[]
ORDER BY observation.work_id
LIMIT 20;

-- Deliberately disable indexes to show the scan cost the user index avoids at this table size.
SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT observation.work_id
FROM work_alias_observation observation
JOIN work ON work.id = observation.work_id AND work.merged_into IS NULL
WHERE observation.user_id = 'benchmark-user-500'
  AND observation.title_keys && ARRAY['needle']::text[]
ORDER BY observation.work_id
LIMIT 20;

ROLLBACK;
