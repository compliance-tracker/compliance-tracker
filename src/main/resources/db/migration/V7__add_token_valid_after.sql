-- A password reset should invalidate any JWT issued before the reset, not just stop the old
-- password from working (issue #96, found as a known gap while building #37). NULL means "no
-- restriction" - every existing account, and every account that never resets its password, has
-- no floor on which tokens are accepted.

ALTER TABLE app_user ADD COLUMN token_valid_after TIMESTAMP;
