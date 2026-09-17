-- Restores accounts that lost their nickname to a second, anonymous account on the same phone.
--
-- The failure: a user picks a nickname, then the app talks to the server with a different key. Every
-- key is its own account, device-only recovery is disabled, so the phone ends up with one named
-- account and one or more `anon#xxxx` accounts holding everything written afterwards.
--
-- The repair, per phone (same peppered ANDROID_ID hash) with exactly one named account:
--   * every anonymous account on that phone is folded into the named one;
--   * the anonymous account's keys are attached to the named account, so the key the app uses
--     today authenticates as the named account from the next request on;
--   * comments, votes, ratings, observations and filter history move with it, which is what makes
--     old `anon#xxxx` comments render under the restored name;
--   * the emptied anonymous account is deleted.
--
-- Skipped and reported instead: phones with two or more named accounts (no way to tell which is
-- the person's), and any banned or shadowbanned account on either side.
--
-- DRY RUN BY DEFAULT. Everything runs in one transaction and is rolled back unless `apply` is set:
--
--   docker compose exec -T postgres psql -U kotatsuredo kotatsuredo < restore_anon_accounts.sql
--   docker compose exec -T postgres psql -U kotatsuredo kotatsuredo -v apply=true < restore_anon_accounts.sql
--
-- Take a dump first.

\set ON_ERROR_STOP on
\if :{?apply}
\else
	\set apply false
\endif

BEGIN;

-- Serialise against live writes by the accounts being merged.
LOCK TABLE app_user, user_secret, comment, comment_vote, rating IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE device_accounts ON COMMIT DROP AS
SELECT d.ssaid_hash, u.id, u.nickname, u.created_at, u.is_banned OR u.is_shadowbanned AS sanctioned
FROM app_device d
JOIN app_user u ON u.id = d.user_id;

CREATE TEMP TABLE restore_pair ON COMMIT DROP AS
SELECT anon.id AS anon_id, named.id AS named_id
FROM device_accounts named
JOIN device_accounts anon ON anon.ssaid_hash = named.ssaid_hash AND anon.nickname IS NULL
WHERE named.nickname IS NOT NULL
  AND NOT named.sanctioned
  AND NOT anon.sanctioned
  AND (SELECT count(*) FROM device_accounts other
       WHERE other.ssaid_hash = named.ssaid_hash AND other.nickname IS NOT NULL) = 1;

\echo
\echo '== Accounts to restore (anonymous -> named)'
SELECT 'anon#' || lower(left(p.anon_id, 4))                  AS from_account,
       n.nickname || '#' || lower(left(p.named_id, 4))       AS to_account,
       a.created_at                                          AS anon_created,
       n.created_at                                          AS named_created,
       (SELECT count(*) FROM comment c WHERE c.user_id = p.anon_id)        AS comments,
       (SELECT count(*) FROM rating r WHERE r.user_id = p.anon_id)         AS ratings,
       (SELECT count(*) FROM comment_vote v WHERE v.user_id = p.anon_id)   AS votes
FROM restore_pair p
JOIN app_user a ON a.id = p.anon_id
JOIN app_user n ON n.id = p.named_id
ORDER BY n.nickname, a.created_at;

\echo '== Skipped: phones with more than one named account, or a sanctioned account'
SELECT count(DISTINCT ssaid_hash) AS phones_skipped
FROM device_accounts d
WHERE EXISTS (SELECT 1 FROM device_accounts x WHERE x.ssaid_hash = d.ssaid_hash AND x.nickname IS NULL)
  AND EXISTS (SELECT 1 FROM device_accounts x WHERE x.ssaid_hash = d.ssaid_hash AND x.nickname IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM restore_pair p JOIN device_accounts x ON x.id = p.named_id
                  WHERE x.ssaid_hash = d.ssaid_hash);

-- Keys: the key the app holds today must authenticate as the named account.
UPDATE user_secret s SET user_id = p.named_id, origin = 'account_restore'
FROM restore_pair p WHERE s.user_id = p.anon_id;

-- Comments carry only the author id; the display name is looked up at read time.
UPDATE comment c SET user_id = p.named_id FROM restore_pair p WHERE c.user_id = p.anon_id;

-- Votes: one vote per comment per account. Where both accounts voted, keep the named account's.
CREATE TEMP TABLE revoted ON COMMIT DROP AS
SELECT DISTINCT v.comment_id
FROM comment_vote v
JOIN restore_pair p ON v.user_id = p.anon_id
WHERE EXISTS (SELECT 1 FROM comment_vote o WHERE o.comment_id = v.comment_id AND o.user_id = p.named_id);

DELETE FROM comment_vote v USING restore_pair p
WHERE v.user_id = p.anon_id
  AND EXISTS (SELECT 1 FROM comment_vote o WHERE o.comment_id = v.comment_id AND o.user_id = p.named_id);
UPDATE comment_vote v SET user_id = p.named_id FROM restore_pair p WHERE v.user_id = p.anon_id;

-- Recount only comments that lost a duplicate vote, with the same rules as account deletion
-- (shadowbanned voters excluded, Wilson lower bound at z = 1.96).
UPDATE comment c
SET up = counts.up, down = counts.down,
    score = CASE WHEN counts.up + counts.down = 0 THEN 0 ELSE (
        (counts.up::float8 / (counts.up + counts.down) + 3.8416 / (2 * (counts.up + counts.down))
         - 1.96 * sqrt((counts.up::float8 / (counts.up + counts.down))
                       * (1 - counts.up::float8 / (counts.up + counts.down)) / (counts.up + counts.down)
                       + 3.8416 / (4.0 * (counts.up + counts.down) ^ 2)))
        / (1 + 3.8416 / (counts.up + counts.down))) END
FROM (
    SELECT r.comment_id,
           count(*) FILTER (WHERE v.value = 1 AND u.id IS NOT NULL)  AS up,
           count(*) FILTER (WHERE v.value = -1 AND u.id IS NOT NULL) AS down
    FROM revoted r
    LEFT JOIN comment_vote v ON v.comment_id = r.comment_id
    LEFT JOIN app_user u ON u.id = v.user_id AND NOT u.is_shadowbanned
    GROUP BY r.comment_id
) counts
WHERE c.id = counts.comment_id;

-- Ratings: one per work per account. Where both accounts rated, keep the most recent value.
CREATE TEMP TABLE rerated ON COMMIT DROP AS
SELECT DISTINCT a.work_id
FROM rating a
JOIN restore_pair p ON a.user_id = p.anon_id
JOIN rating n ON n.work_id = a.work_id AND n.user_id = p.named_id;

DELETE FROM rating n USING restore_pair p, rating a
WHERE n.user_id = p.named_id AND a.user_id = p.anon_id AND a.work_id = n.work_id
  AND a.updated_at > n.updated_at;
DELETE FROM rating a USING restore_pair p, rating n
WHERE a.user_id = p.anon_id AND n.user_id = p.named_id AND n.work_id = a.work_id;
UPDATE rating r SET user_id = p.named_id FROM restore_pair p WHERE r.user_id = p.anon_id;

-- Same rebuild as WorkRepository.rebuildRatingAggregate, for works that lost a duplicate rating.
DELETE FROM work_rating_agg WHERE work_id IN (SELECT work_id FROM rerated);
INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram, updated_at)
SELECT r.work_id, count(*)::integer, sum(r.value)::bigint, avg(r.value)::real,
       ((20.0 * COALESCE(
           (SELECT sum(value_sum)::float8 / NULLIF(sum(count), 0) FROM rating_global_shard), 0
       )) + sum(r.value)) / (20.0 + count(*)),
       ARRAY[
           count(*) FILTER (WHERE r.value BETWEEN 1 AND 2),
           count(*) FILTER (WHERE r.value BETWEEN 3 AND 4),
           count(*) FILTER (WHERE r.value BETWEEN 5 AND 6),
           count(*) FILTER (WHERE r.value BETWEEN 7 AND 8),
           count(*) FILTER (WHERE r.value BETWEEN 9 AND 10)
       ]::integer[], now()
FROM rating r
WHERE r.work_id IN (SELECT work_id FROM rerated)
GROUP BY r.work_id;

-- Everything else keyed by account. Rows that would collide are left on the anonymous account and
-- go with it when it is deleted: they duplicate what the named account already holds.
INSERT INTO user_active_day (user_id, day)
SELECT p.named_id, d.day FROM user_active_day d JOIN restore_pair p ON d.user_id = p.anon_id
ON CONFLICT DO NOTHING;

UPDATE work_alias_observation o SET user_id = p.named_id
FROM restore_pair p
WHERE o.user_id = p.anon_id
  AND NOT EXISTS (SELECT 1 FROM work_alias_observation x
                  WHERE x.source = o.source AND x.source_key = o.source_key AND x.user_id = p.named_id);

UPDATE filter_block f SET user_id = p.named_id FROM restore_pair p WHERE f.user_id = p.anon_id;
UPDATE work_link_dispute d SET reported_by = p.named_id FROM restore_pair p WHERE d.reported_by = p.anon_id;
UPDATE ban_evasion_flag f SET user_id = p.named_id FROM restore_pair p WHERE f.user_id = p.anon_id;

-- Both accounts were the same person on the same phone, so the account's age and activity are the
-- union of the two.
UPDATE app_user n
SET created_at   = LEAST(n.created_at, merged.created_at),
    last_seen_at = GREATEST(n.last_seen_at, merged.last_seen_at)
FROM (
    SELECT p.named_id, min(a.created_at) AS created_at, max(a.last_seen_at) AS last_seen_at
    FROM restore_pair p JOIN app_user a ON a.id = p.anon_id
    GROUP BY p.named_id
) merged
WHERE n.id = merged.named_id;

DELETE FROM app_user a USING restore_pair p WHERE a.id = p.anon_id;

\echo '== After'
SELECT count(*) AS anonymous_accounts_left_on_named_phones
FROM app_device d JOIN app_user u ON u.id = d.user_id
WHERE u.nickname IS NULL
  AND d.ssaid_hash IN (SELECT ssaid_hash FROM device_accounts WHERE nickname IS NOT NULL);

\if :apply
	COMMIT;
	\echo '== APPLIED'
\else
	ROLLBACK;
	\echo '== DRY RUN - nothing was changed. Re-run with -v apply=true to apply.'
\endif
