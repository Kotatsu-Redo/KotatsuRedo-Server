-- Rating writes previously updated one singleton row, serialising unrelated works. Keep the O(1)
-- global prior while spreading trigger contention across 64 deterministic user shards.
CREATE TABLE rating_global_shard (
    shard     SMALLINT PRIMARY KEY CHECK (shard BETWEEN 0 AND 63),
    count     BIGINT NOT NULL DEFAULT 0,
    value_sum BIGINT NOT NULL DEFAULT 0
);

INSERT INTO rating_global_shard (shard, count, value_sum)
SELECT shard, count(r.value), coalesce(sum(r.value), 0)
FROM generate_series(0, 63) shard
LEFT JOIN rating r ON ((hashtext(r.user_id)::bigint % 64 + 64) % 64) = shard
GROUP BY shard;

CREATE OR REPLACE FUNCTION maintain_rating_global_agg() RETURNS trigger AS $$
DECLARE
    target_shard SMALLINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        target_shard := ((hashtext(NEW.user_id)::bigint % 64 + 64) % 64)::smallint;
        UPDATE rating_global_shard SET count = count + 1, value_sum = value_sum + NEW.value
        WHERE shard = target_shard;
    ELSIF TG_OP = 'DELETE' THEN
        target_shard := ((hashtext(OLD.user_id)::bigint % 64 + 64) % 64)::smallint;
        UPDATE rating_global_shard SET count = count - 1, value_sum = value_sum - OLD.value
        WHERE shard = target_shard;
    ELSIF NEW.value <> OLD.value THEN
        target_shard := ((hashtext(NEW.user_id)::bigint % 64 + 64) % 64)::smallint;
        UPDATE rating_global_shard SET value_sum = value_sum + NEW.value - OLD.value
        WHERE shard = target_shard;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION reset_rating_global_agg() RETURNS trigger AS $$
BEGIN
    UPDATE rating_global_shard SET count = 0, value_sum = 0;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TABLE rating_global_agg;
