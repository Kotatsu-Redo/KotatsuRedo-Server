-- Security and hot-path hardening.

-- One open brigade item per work. Keep the newest existing item before enforcing it.
DELETE FROM rating_brigade_flag older
USING rating_brigade_flag newer
WHERE older.work_id = newer.work_id
  AND older.reviewed_at IS NULL
  AND newer.reviewed_at IS NULL
  AND older.id < newer.id;

CREATE UNIQUE INDEX rating_brigade_one_open_idx
    ON rating_brigade_flag (work_id)
    WHERE reviewed_at IS NULL;

-- Maintain the global prior in O(1) rather than scanning every rating on each write.
CREATE TABLE rating_global_agg (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    count      BIGINT NOT NULL,
    value_sum  BIGINT NOT NULL
);

INSERT INTO rating_global_agg (singleton, count, value_sum)
SELECT TRUE, count(*), coalesce(sum(value), 0) FROM rating;

CREATE FUNCTION maintain_rating_global_agg() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE rating_global_agg SET count = count + 1, value_sum = value_sum + NEW.value;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE rating_global_agg SET count = count - 1, value_sum = value_sum - OLD.value;
    ELSIF NEW.value <> OLD.value THEN
        UPDATE rating_global_agg SET value_sum = value_sum + NEW.value - OLD.value;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rating_global_agg_trigger
AFTER INSERT OR UPDATE OR DELETE ON rating
FOR EACH ROW EXECUTE FUNCTION maintain_rating_global_agg();

CREATE FUNCTION reset_rating_global_agg() RETURNS trigger AS $$
BEGIN
    UPDATE rating_global_agg SET count = 0, value_sum = 0;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rating_global_agg_truncate_trigger
AFTER TRUNCATE ON rating
FOR EACH STATEMENT EXECUTE FUNCTION reset_rating_global_agg();

-- Match the actual root-thread, child traversal, and dashboard time-range queries.
CREATE INDEX comment_root_score_idx
    ON comment (work_id, work_chapter_id, lang, score DESC, created_at DESC)
    WHERE parent_id IS NULL;
CREATE INDEX comment_parent_created_idx
    ON comment (parent_id, created_at)
    WHERE parent_id IS NOT NULL;
CREATE INDEX app_user_created_idx ON app_user (created_at);
CREATE INDEX rating_created_idx ON rating (created_at);
