-- Configurable per-business reminder lead time (issue #53) - the dispatcher used to hardcode a
-- single 14-day lookahead for every business. Existing rows get the same 14-day default they
-- were already effectively getting, so this is a behavior-preserving migration for anyone who
-- never changes it.

ALTER TABLE business ADD COLUMN lead_time_days INTEGER NOT NULL DEFAULT 14;
