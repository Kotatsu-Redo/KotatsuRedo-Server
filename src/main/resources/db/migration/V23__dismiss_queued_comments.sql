-- "I have looked at this and it is fine."
--
-- The disliked and flagged queues are computed, not curated: a comment sits in them for as long as
-- it has the downvotes or the flag that put it there. So the only way to clear one was to remove a
-- comment that did not deserve removing, and a queue that cannot be emptied stops being read - the
-- ten items nobody can dismiss hide the eleventh that matters.
--
-- Dismissal is per comment and deliberately not per rule: a `watch` term that keeps being wrong is
-- the filter's problem, and the panel already has somewhere to say so.
ALTER TABLE comment
    ADD COLUMN dismissed_at TIMESTAMPTZ,
    ADD COLUMN dismissed_by TEXT REFERENCES moderator (id) ON DELETE SET NULL;

-- Both queues filter on it, and the great majority of comments never carry it.
CREATE INDEX comment_dismissed_idx ON comment (dismissed_at) WHERE dismissed_at IS NOT NULL;
