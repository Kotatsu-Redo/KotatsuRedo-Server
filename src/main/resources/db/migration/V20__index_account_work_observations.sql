-- Cross-source resolution asks "has this account already seen this exact title anywhere?".
-- The source-key primary key cannot answer that ordering, so without this index the private
-- anti-poisoning lookup becomes a table scan as community observations grow.
CREATE INDEX work_alias_observation_user_idx
    ON work_alias_observation (user_id, work_id);
