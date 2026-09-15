-- MFA is a property of a login session, not merely of the moderator account. Without this bit, a
-- password-only session opened before TOTP enrolment becomes privileged as soon as another session
-- confirms the account-wide secret.
ALTER TABLE mod_session
    ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN totp_enrolment_owner BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing sessions predate per-session MFA proof and pending seeds have no session owner. Force a
-- fresh login/enrolment rather than blessing a password-only session or disclosing an orphaned seed.
UPDATE moderator SET totp_secret = NULL WHERE NOT totp_confirmed;
DELETE FROM mod_session;
