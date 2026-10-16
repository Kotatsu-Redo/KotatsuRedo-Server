-- Look up a genre report by the account that made it.
--
-- `work_genre_observation` is keyed (work_id, genre, user_id), which serves the quorum but not the
-- two questions asked per account: the data export ("everything held about me") and the cascade that
-- deletes an account's reports with it. Without this, both scan the whole table.
CREATE INDEX work_genre_observation_user_idx ON work_genre_observation (user_id);
