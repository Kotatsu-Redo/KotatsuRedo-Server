\set ON_ERROR_STOP on
\timing on
BEGIN;

INSERT INTO app_user (id, secret_sha256)
VALUES (
    'benchmark-community-reader',
    decode(md5('benchmark-community-a') || md5('benchmark-community-b'), 'hex')
);

INSERT INTO app_user (id, secret_sha256)
SELECT 'benchmark-rater-' || n,
       decode(md5('benchmark-rater-a-' || n) || md5('benchmark-rater-b-' || n), 'hex')
FROM generate_series(1, 10000) n;

INSERT INTO work (canonical_title, year, content_type, nsfw)
VALUES ('__community_read_benchmark__', 2024, 'manga', FALSE)
RETURNING id AS community_work_id \gset

INSERT INTO work (canonical_title, year, content_type, nsfw)
VALUES ('__rating_read_benchmark__', 2024, 'manga', FALSE)
RETURNING id AS rating_work_id \gset

-- Six thousand roots exercise the maximum supported offset. Four thousand replies make recursive
-- thread loading representative without creating an artificial chain deeper than the API permits.
INSERT INTO comment (
    work_id, origin_work_id, user_id, body, lang, score, up, down, created_at
)
SELECT :community_work_id,
       :community_work_id,
       'benchmark-community-reader',
       'Benchmark root comment ' || n,
       CASE WHEN n % 4 = 0 THEN 'fr' ELSE 'en' END,
       (n % 1000)::real / 1000,
       n % 100,
       n % 10,
       now() - make_interval(secs => n)
FROM generate_series(1, 6000) n;

WITH roots AS (
    SELECT id, row_number() OVER (ORDER BY id) AS n
    FROM comment
    WHERE work_id = :community_work_id AND parent_id IS NULL
    ORDER BY id
    LIMIT 400
)
INSERT INTO comment (
    work_id, origin_work_id, user_id, parent_id, depth, body, lang, created_at
)
SELECT :community_work_id,
       :community_work_id,
       'benchmark-community-reader',
       roots.id,
       1,
       'Benchmark reply ' || roots.n || '-' || reply,
       'en',
       now() - make_interval(secs => roots.n * 10 + reply)
FROM roots
CROSS JOIN generate_series(1, 10) reply;

INSERT INTO rating (work_id, origin_work_id, user_id, value)
SELECT :rating_work_id,
       :rating_work_id,
       'benchmark-rater-' || n,
       ((n - 1) % 10) + 1
FROM generate_series(1, 10000) n;

INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram)
SELECT :rating_work_id,
       count(*)::integer,
       sum(value)::bigint,
       avg(value)::real,
       avg(value)::real,
       ARRAY[
           count(*) FILTER (WHERE value BETWEEN 1 AND 2),
           count(*) FILTER (WHERE value BETWEEN 3 AND 4),
           count(*) FILTER (WHERE value BETWEEN 5 AND 6),
           count(*) FILTER (WHERE value BETWEEN 7 AND 8),
           count(*) FILTER (WHERE value BETWEEN 9 AND 10)
       ]::integer[]
FROM rating
WHERE work_id = :rating_work_id;

ANALYZE comment;
ANALYZE rating;
ANALYZE work_rating_agg;

-- Top-ranked root page, the default comment-screen query.
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM comment
WHERE work_id = :community_work_id
  AND work_chapter_id IS NULL
  AND parent_id IS NULL
  AND (
      state <> 2
      OR EXISTS (
          SELECT 1 FROM comment child
          WHERE child.parent_id = comment.id
            AND child.state <> 2
            AND (child.state <> 1 OR child.user_id = 'benchmark-community-reader')
      )
  )
  AND (state <> 1 OR user_id = 'benchmark-community-reader')
ORDER BY score DESC, created_at DESC
LIMIT 25 OFFSET 0;

-- Worst supported root offset, newest-first.
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM comment
WHERE work_id = :community_work_id
  AND work_chapter_id IS NULL
  AND parent_id IS NULL
  AND state <> 2
  AND (state <> 1 OR user_id = 'benchmark-community-reader')
ORDER BY created_at DESC
LIMIT 25 OFFSET 5000;

-- Descendants for twenty roots in one recursive query.
EXPLAIN (ANALYZE, BUFFERS)
WITH RECURSIVE selected_roots AS (
    SELECT id FROM comment
    WHERE work_id = :community_work_id AND parent_id IS NULL
    ORDER BY id
    LIMIT 20
), thread AS (
    SELECT child.*
    FROM comment child
    WHERE child.parent_id IN (SELECT id FROM selected_roots)
      AND (child.state <> 1 OR child.user_id = 'benchmark-community-reader')
    UNION ALL
    SELECT child.*
    FROM comment child
    JOIN thread ON child.parent_id = thread.id
    WHERE child.state <> 1 OR child.user_id = 'benchmark-community-reader'
)
SELECT * FROM thread LIMIT 1000;

-- Language counts shown beside the thread selector.
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(lang, 'und'), count(*)
FROM comment
WHERE work_id = :community_work_id
  AND work_chapter_id IS NULL
  AND state = 0
GROUP BY COALESCE(lang, 'und');

-- The details screen reads the denormalized row; raw aggregation is retained only for repair.
EXPLAIN (ANALYZE, BUFFERS)
SELECT count, mean, bayesian, histogram
FROM work_rating_agg
WHERE work_id = :rating_work_id;

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), COALESCE(avg(value), 0),
       count(*) FILTER (WHERE value BETWEEN 1 AND 2),
       count(*) FILTER (WHERE value BETWEEN 3 AND 4),
       count(*) FILTER (WHERE value BETWEEN 5 AND 6),
       count(*) FILTER (WHERE value BETWEEN 7 AND 8),
       count(*) FILTER (WHERE value BETWEEN 9 AND 10)
FROM rating
WHERE work_id = :rating_work_id;

ROLLBACK;
