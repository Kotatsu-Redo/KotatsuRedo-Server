-- Look up a work's observations by the work.
--
-- `work_alias_observation` is keyed (source, source_key, user_id) and indexed for agreement and for
-- accounts, so every query that starts from a work has always been a full scan of the table. That
-- was tolerable while only the resolver read it. It is not now: the per-work source list asks
-- exactly this question on a hot path, and the backfill's readership count asked it sixteen
-- thousand times a sweep, which is how it overran the statement timeout.
CREATE INDEX work_alias_observation_work_idx ON work_alias_observation (work_id, source);
