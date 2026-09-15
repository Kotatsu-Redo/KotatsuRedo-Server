-- V12 put lang before both ordering columns, so PostgreSQL cannot use it to order the common
-- unfiltered query, nor the language-filtered newest-first query.
CREATE INDEX comment_root_score_all_languages_idx
    ON comment (work_id, work_chapter_id, score DESC, created_at DESC)
    WHERE parent_id IS NULL;

CREATE INDEX comment_root_new_all_languages_idx
    ON comment (work_id, work_chapter_id, created_at DESC)
    WHERE parent_id IS NULL;

CREATE INDEX comment_root_new_language_idx
    ON comment (work_id, work_chapter_id, lang, created_at DESC)
    WHERE parent_id IS NULL;
