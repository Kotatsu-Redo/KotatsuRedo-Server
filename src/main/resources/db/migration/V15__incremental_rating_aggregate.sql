-- Keep enough exact state to update a work aggregate from the old/new rating delta. This removes
-- the full rating-table scan from every write while retaining recompute as a repair operation.
ALTER TABLE work_rating_agg ADD COLUMN value_sum BIGINT NOT NULL DEFAULT 0;

UPDATE work_rating_agg aggregate
SET value_sum = totals.value_sum
FROM (
    SELECT work_id, coalesce(sum(value), 0)::bigint AS value_sum
    FROM rating
    GROUP BY work_id
) totals
WHERE totals.work_id = aggregate.work_id;

INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram)
SELECT r.work_id,
       count(*)::integer,
       sum(r.value)::bigint,
       avg(r.value)::real,
       0,
       ARRAY[
           count(*) FILTER (WHERE r.value BETWEEN 1 AND 2),
           count(*) FILTER (WHERE r.value BETWEEN 3 AND 4),
           count(*) FILTER (WHERE r.value BETWEEN 5 AND 6),
           count(*) FILTER (WHERE r.value BETWEEN 7 AND 8),
           count(*) FILTER (WHERE r.value BETWEEN 9 AND 10)
       ]::integer[]
FROM rating r
LEFT JOIN work_rating_agg aggregate ON aggregate.work_id = r.work_id
WHERE aggregate.work_id IS NULL
GROUP BY r.work_id;
