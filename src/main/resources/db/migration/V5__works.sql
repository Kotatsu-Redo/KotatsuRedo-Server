-- M2a: the work catalogue.
--
-- A "work" is a story, independent of which source hosts it. This is the table that makes a comment
-- on Chainsaw Man from MangaDex and one from Comick land in the same thread (PLAN.md §2).

CREATE TABLE work (
    id              BIGSERIAL   PRIMARY KEY,
    canonical_title TEXT        NOT NULL,
    year            SMALLINT,
    content_type    TEXT,
    nsfw            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Many titles per work, which is the whole point: romaji, native, English, publisher synonyms, and
-- whatever odd string a given source happens to use.
--
-- `kind` = canonical | romaji | native | english | synonym | source_observed
-- `weight` lets a low-confidence observed title speed up lookups without ever driving a merge.
CREATE TABLE work_title (
    id         BIGSERIAL PRIMARY KEY,
    work_id    BIGINT    NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    title_raw  TEXT      NOT NULL,
    title_norm TEXT      NOT NULL,
    lang       TEXT,
    kind       TEXT      NOT NULL,
    weight     REAL      NOT NULL DEFAULT 1.0
);

-- The single most important index in the system. Exact lookups on title_norm answer most queries;
-- the trigram index catches the rest without a table scan (PLAN.md §2.2).
CREATE INDEX work_title_norm_idx      ON work_title (title_norm);
CREATE INDEX work_title_trgm_idx      ON work_title USING GIN (title_norm gin_trgm_ops);
CREATE INDEX work_title_work_idx      ON work_title (work_id);
CREATE UNIQUE INDEX work_title_uniq   ON work_title (work_id, title_norm, kind);

-- source_key is the parser's url or slug, never Manga.id: the latter changes if the parser's
-- hashing ever changes, which would orphan every alias at once.
CREATE TABLE work_alias (
    source     TEXT        NOT NULL,
    source_key TEXT        NOT NULL,
    work_id    BIGINT      NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    confidence REAL        NOT NULL DEFAULT 1.0,
    evidence   TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_key)
);

CREATE INDEX work_alias_work_idx ON work_alias (work_id);

CREATE TABLE work_cover_hash (
    work_id BIGINT NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    phash   BIGINT NOT NULL,
    source  TEXT   NOT NULL,
    PRIMARY KEY (work_id, source)
);

CREATE INDEX work_cover_hash_work_idx ON work_cover_hash (work_id);

-- Gold-standard anchors. A client with a scrobbler link resolves in one hop.
CREATE TABLE work_external_id (
    provider    TEXT   NOT NULL,   -- anilist | mal | mangaupdates | kitsu | shikimori
    external_id TEXT   NOT NULL,
    work_id     BIGINT NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    PRIMARY KEY (provider, external_id)
);

CREATE INDEX work_external_work_idx ON work_external_id (work_id);

-- Sequels, side stories and alternative versions stay separate works (PLAN.md §2.5) - this is what
-- lets the UI still offer "Part 2 discussion", recovering the discoverability that splitting costs.
CREATE TABLE work_relation (
    from_work      BIGINT NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    to_work        BIGINT NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    type           TEXT   NOT NULL,
    chapter_offset REAL,
    PRIMARY KEY (from_work, to_work, type)
);

-- Merges move aliases and re-point content; they never destroy rows, so a bad merge is reversible.
CREATE TABLE work_merge_log (
    id         BIGSERIAL   PRIMARY KEY,
    from_work  BIGINT      NOT NULL,
    into_work  BIGINT      NOT NULL,
    reason     TEXT,
    merged_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Progress of the seed importer, so a run that dies halfway resumes instead of restarting.
CREATE TABLE seed_progress (
    provider    TEXT        PRIMARY KEY,
    last_page    INTEGER     NOT NULL DEFAULT 0,
    last_run_at TIMESTAMPTZ,
    completed   BOOLEAN     NOT NULL DEFAULT FALSE
);
