-- Categories, from what readers' sources say and from what the catalogues already told us.
--
-- Most works are in no catalogue at all - three in five of everything the enricher finishes - so
-- catalogue tags alone would describe only the part of the library that needs describing least.
-- Sources, on the other hand, always list genres, and the app parses them anyway; a reader opening
-- a manga is the moment its categories become knowable.
--
-- That makes these user-supplied strings, so two rules apply. Only terms in the server's closed
-- vocabulary (`Genres.CANONICAL`) ever get written, and a reader-supplied genre is published only
-- once independent accounts agree on it. Nothing here describes a person: a genre is a fact about
-- the manga, recorded the same way a title rendering is.

-- One account's claim. Promoted into work_genre once enough accounts agree.
CREATE TABLE work_genre_observation (
    work_id    BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    genre      TEXT        NOT NULL,
    user_id    TEXT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_id, genre, user_id)
);

-- The published set: what the API serves and what similarity is computed from.
CREATE TABLE work_genre (
    work_id   BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    genre     TEXT        NOT NULL,
    -- 'catalogue' (curated, trusted on arrival) or 'readers' (reached the agreement quorum).
    origin    TEXT        NOT NULL,
    reporters INTEGER     NOT NULL DEFAULT 0,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_id, genre)
);

CREATE INDEX work_genre_genre_idx ON work_genre (genre);
