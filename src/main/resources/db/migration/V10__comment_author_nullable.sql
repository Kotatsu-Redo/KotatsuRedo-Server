-- "Delete everything about me" must not delete anyone else's words.
--
-- `comment.user_id` cascaded on the deletion of an account, and `comment.parent_id` cascaded after
-- it, so erasing one identity erased every reply written underneath any of its comments. The privacy
-- notice promises that a deletion tombstones the user's own comments; this is what makes that true
-- rather than aspirational.
--
-- The author becomes NULL instead. The row survives as an empty tombstone holding its place in the
-- thread, and nothing about the person survives with it: no text, no nickname, no id.

ALTER TABLE comment ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE comment DROP CONSTRAINT comment_user_id_fkey;
ALTER TABLE comment ADD CONSTRAINT comment_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL;

COMMENT ON COLUMN comment.user_id IS
    'NULL once the author deleted their account. The row is kept only so the replies below it still '
    'have a thread to hang from - it carries no text and no author.';

-- Votes are a different case and keep cascading on purpose: the privacy notice says votes are
-- dropped, and a vote carries nothing but the fact that somebody cast it.
