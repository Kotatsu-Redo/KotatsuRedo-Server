-- Brigade detection treats an edited rating as fresh activity, so support the per-work time window.
CREATE INDEX rating_work_updated_idx
    ON rating (work_id, updated_at)
    INCLUDE (value, user_id);
