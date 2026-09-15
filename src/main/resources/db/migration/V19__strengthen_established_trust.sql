-- Tier 2 is used for actions that affect every user. Age alone is cheap to manufacture in bulk, so
-- require sustained activity as well as the existing 90-day clean-account requirement.
CREATE OR REPLACE VIEW user_trust AS
SELECT u.id AS user_id,
       CASE
           WHEN u.created_at > now() - INTERVAL '24 hours'
                OR (SELECT count(*) FROM user_active_day d WHERE d.user_id = u.id) < 3 THEN 0
           WHEN u.created_at < now() - INTERVAL '90 days'
                AND (SELECT count(*) FROM user_active_day d WHERE d.user_id = u.id) >= 30
                AND NOT u.is_banned AND NOT u.is_shadowbanned THEN 2
           ELSE 1
       END AS tier
FROM app_user u;
