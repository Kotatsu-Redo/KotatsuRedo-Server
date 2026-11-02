-- A small thank-you for donors: a crown next to their name on comments, and nothing else. Set by a
-- moderator from the panel, because nothing links a donation to an account, which is only a key.
ALTER TABLE app_user ADD COLUMN is_supporter BOOLEAN NOT NULL DEFAULT FALSE;
