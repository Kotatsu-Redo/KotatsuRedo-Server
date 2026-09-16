\set ON_ERROR_STOP on
\timing on
BEGIN;

-- 200 sources x 100 reporters x 10 days = 200,000 raw daily snapshots.
INSERT INTO source_probe_raw (
    source, day, region, op, reporter_day, tier,
    ok, fail, empty, cf_blocked, latency_p50_ms, latency_p90_ms
)
SELECT 'BENCHMARK_SOURCE_' || source_id,
       CURRENT_DATE - day_offset,
       CASE source_id % 4 WHEN 0 THEN 'EU' WHEN 1 THEN 'NA' WHEN 2 THEN 'APAC' ELSE 'OTHER' END,
       (source_id % 3)::smallint,
       'benchmark-reporter-' || reporter_id || '-' || day_offset,
       2,
       18,
       2,
       CASE WHEN source_id % 5 = 0 THEN 1 ELSE 0 END,
       CASE WHEN source_id % 11 = 0 THEN 1 ELSE 0 END,
       100 + source_id,
       180 + source_id
FROM generate_series(1, 200) source_id
CROSS JOIN generate_series(1, 100) reporter_id
CROSS JOIN generate_series(0, 9) day_offset;

ANALYZE source_probe_raw;

-- Production score aggregation query.
EXPLAIN (ANALYZE, BUFFERS)
WITH decayed AS (
    SELECT source, region, day, reporter_day, tier,
           ok, fail, empty, cf_blocked, latency_p50_ms,
           power(0.5, (CURRENT_DATE - day)::numeric / 7.0::numeric) AS w
    FROM source_probe_raw
    WHERE day >= CURRENT_DATE - 30::int AND tier >= 2
), reporter_samples AS (
    SELECT source, region, day, reporter_day, MAX(tier) AS tier, MAX(w) AS w,
           SUM(ok)::numeric AS ok,
           SUM(fail)::numeric AS fail,
           SUM(empty)::numeric AS empty,
           SUM(cf_blocked)::numeric AS cf_blocked,
           COALESCE(
               SUM(latency_p50_ms::numeric * (ok + fail)) / NULLIF(SUM(ok + fail), 0),
               0
           ) AS latency
    FROM decayed
    GROUP BY source, region, day, reporter_day
), aggregated AS (
    SELECT source, region,
           SUM((ok / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS ok_w,
           SUM((fail / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS fail_w,
           SUM((empty / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS empty_w,
           SUM((cf_blocked / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS cf_w,
           SUM(latency * w * (0.5 + tier * 0.25)) AS lat_num,
           SUM(w * (0.5 + tier * 0.25)) AS weight,
           COUNT(*) AS sample_size
    FROM reporter_samples
    GROUP BY source, region
)
SELECT source, region,
       ok_w::float8,
       fail_w::float8,
       empty_w::float8,
       cf_w::float8,
       COALESCE(lat_num / NULLIF(weight, 0), 0)::float8,
       weight::float8,
       sample_size
FROM aggregated;

-- Retry the same 1,000-row daily batch to measure idempotent upsert cost.
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO source_probe_raw (
    source, day, region, op, reporter_day, tier,
    ok, fail, empty, cf_blocked, latency_p50_ms, latency_p90_ms
)
SELECT 'BENCHMARK_SOURCE_' || source_id,
       CURRENT_DATE,
       CASE source_id % 4 WHEN 0 THEN 'EU' WHEN 1 THEN 'NA' WHEN 2 THEN 'APAC' ELSE 'OTHER' END,
       (source_id % 3)::smallint,
       'benchmark-reporter-' || reporter_id || '-0',
       2,
       19, 1, 0, 0, 120, 210
FROM generate_series(1, 10) source_id
CROSS JOIN generate_series(1, 100) reporter_id
ON CONFLICT (source, day, region, op, reporter_day) DO UPDATE SET
    ok = EXCLUDED.ok,
    fail = EXCLUDED.fail,
    empty = EXCLUDED.empty,
    cf_blocked = EXCLUDED.cf_blocked,
    latency_p50_ms = EXCLUDED.latency_p50_ms,
    latency_p90_ms = EXCLUDED.latency_p90_ms,
    tier = EXCLUDED.tier,
    updated_at = now();

ROLLBACK;
