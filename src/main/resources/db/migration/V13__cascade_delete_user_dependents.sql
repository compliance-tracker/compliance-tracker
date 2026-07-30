-- Issue #48 (PDPA compliance review, Retention Limitation Obligation): DELETE /api/auth/account
-- is being added, letting a user delete their own account and everything tied to it. None of
-- the FKs referencing app_user had ON DELETE CASCADE (all default to the implicit RESTRICT-like
-- "no action") - deleting a User row directly would fail outright with a foreign key violation.
-- Same reasoning as V3 (which did this for business's own dependents): handled at the DB level,
-- not via a specific ordered sequence of DELETE statements in application code, so it stays
-- correct and atomic regardless of how a row ever gets deleted, not just through this one
-- endpoint - business's own dependents (work_pass/deadline_record/custom_obligation) already
-- cascade from business itself (V3/V11), so cascading business_id -> app_user here is enough to
-- remove everything transitively.

ALTER TABLE business DROP CONSTRAINT business_owner_id_fkey;
ALTER TABLE business ADD CONSTRAINT business_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE idempotency_key DROP CONSTRAINT idempotency_key_owner_id_fkey;
ALTER TABLE idempotency_key ADD CONSTRAINT idempotency_key_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE password_reset_token DROP CONSTRAINT password_reset_token_user_id_fkey;
ALTER TABLE password_reset_token ADD CONSTRAINT password_reset_token_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE email_verification_token DROP CONSTRAINT email_verification_token_user_id_fkey;
ALTER TABLE email_verification_token ADD CONSTRAINT email_verification_token_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
