-- Works described before catalogue genres were kept.
--
-- Genres arrived after most of the library had already been described, and a finished enrichment is
-- never asked about again, so those works would stay without catalogue genres for good - the popular
-- ones most of all, being the first described. They are reopened once, a few per sweep, through the
-- same path a partial answer is rechecked by. This marks the ones already reopened, so a work whose
-- catalogue lists no genres is asked once rather than forever.
ALTER TABLE work_enrichment ADD COLUMN genres_rechecked_at TIMESTAMPTZ;
