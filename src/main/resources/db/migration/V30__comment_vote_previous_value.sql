-- The vote a switch replaced (like -> dislike), so withdrawing the new vote restores the old one
-- instead of wiping both. Null when the current vote replaced nothing.
ALTER TABLE comment_vote
    ADD COLUMN previous_value SMALLINT CHECK (previous_value IN (-1, 1));
