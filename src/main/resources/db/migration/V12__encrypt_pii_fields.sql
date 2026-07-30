-- Issue #63: encrypt email/business name/employee name at rest (AES-256-GCM, application-level,
-- see EncryptedStringConverter). Existing rows are local dev/CI test data only - re-encrypting
-- already-stored plaintext isn't expressible in plain SQL, so as with V2 when auth was first
-- added ("existing business rows were test/portfolio data only, cleared before this migration"),
-- clear and let the app repopulate rather than writing a one-off Java-backed data migration for
-- a portfolio project with no real deployment yet.
DELETE FROM idempotency_key;
DELETE FROM deadline_record;
DELETE FROM work_pass;
DELETE FROM custom_obligation;
DELETE FROM business;
DELETE FROM password_reset_token;
DELETE FROM email_verification_token;
DELETE FROM app_user;

-- TEXT, not VARCHAR(255) - encrypted output (IV + ciphertext + auth tag, Base64-encoded) is
-- always longer than the original plaintext, and VARCHAR(255) was already tight for a real
-- business/employee name before adding that overhead.
ALTER TABLE app_user ALTER COLUMN email TYPE TEXT;
ALTER TABLE business ALTER COLUMN name TYPE TEXT;
ALTER TABLE work_pass ALTER COLUMN employee_name TYPE TEXT;

-- The UNIQUE constraint that used to sit directly on email is meaningless now - encryption is
-- deliberately non-deterministic, so the same email produces different ciphertext every time,
-- and two different rows' ciphertext is never equal even for the same plaintext. email_hash (a
-- deterministic HMAC-SHA256 of the raw email, see EmailHasher) is what actually carries
-- uniqueness now, and what every real lookup queries against instead of email itself.
ALTER TABLE app_user DROP CONSTRAINT app_user_email_key;
ALTER TABLE app_user ADD COLUMN email_hash VARCHAR(64) NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT app_user_email_hash_key UNIQUE (email_hash);
