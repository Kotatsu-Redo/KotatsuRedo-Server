-- Startup popularity priors for a new installation with no telemetry yet.
--
-- These are deliberately ordinary source_score rows, not a permanent override:
--   * ON CONFLICT preserves any real score already present during an upgrade;
--   * the first telemetry recomputation for a region replaces the whole region;
--   * the existing 30-day stale-score cleanup removes priors in inactive regions.
--
-- Popularity preserves the supplied ordinal ranking without pretending that the list contains
-- measured traffic volumes. Stability stays neutral. A sample size of five is the client's minimum
-- confidence threshold, so the priors affect initial ordering until reporter data takes over.
--
-- Four entries in the supplied list are intentionally absent because the current parser registry
-- has no stable ID for them: Bookwalker, Rakuten Kobo, XComic, and Scans.gg.

WITH ranked(source, popularity) AS (
    VALUES
        ('COMIX',                1.00::real), --  1. Comix HUB
        ('MANGADOTNET',          0.98::real), --  2. Mangadotnet HUB
        ('ATSUMOE',              0.96::real), --  3. Atsumaru
        ('MANGABALL_EN',         0.94::real), --  4. Mangaball HUB
        ('MANGAFIRE_EN',         0.92::real), --  5. MangaFire
        ('ONISAGA_EN',           0.90::real), --  6. OniSaga
        ('WEEBCENTRAL',          0.88::real), --  7. Weeb Central
        ('MANGAGO',              0.86::real), --  8. Mangago
        ('ALLMANGA',             0.84::real), --  9. MKissa Manga
        ('MANGATARO',            0.78::real), -- 12. MangaTaro
        ('MANGACLOUD',           0.76::real), -- 13. MangaCloud
        ('MANGAKATANA',          0.74::real), -- 14. MangaKatana
        ('MANGAKIO',             0.72::real), -- 15. MangaK
        ('CUBARI',               0.68::real), -- 17. Cubari Proxy MULT
        ('MANHUASCAN',           0.66::real), -- 18. KaliScan
        ('VYMANGA',              0.64::real), -- 19. VyManga
        ('LIKEMANGA',            0.62::real), -- 20. LikeManga
        ('MANGAPLUSPARSER_EN',   0.60::real), -- 21. MANGA Plus
        ('CHIKARI',              0.58::real), -- 22. chikari.moe
        ('MANGAHUB_IO',          0.56::real), -- 23. MangaHub
        ('DYNASTYSCANS',         0.52::real), -- 25. Dynasty Reader HUB
        ('MANGANATO',            0.50::real)  -- 26. Manganato.gg
), regions(region) AS (
    VALUES ('EU'), ('NA'), ('LATAM'), ('APAC'), ('SOUTH_ASIA'), ('MENA'), ('AFRICA'), ('OTHER')
)
INSERT INTO source_score
    (source, region, stability, popularity, composite, sample_size, updated_at)
SELECT ranked.source,
       regions.region,
       0.50::real,
       ranked.popularity,
       ((0.40 * 0.50 + 0.20 * ranked.popularity) / 0.60)::real,
       5,
       now()
FROM ranked
CROSS JOIN regions
ON CONFLICT (source, region) DO NOTHING;
