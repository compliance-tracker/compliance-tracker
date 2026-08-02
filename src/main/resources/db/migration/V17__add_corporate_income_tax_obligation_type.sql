-- Issue #33: deadline_record.obligation_type has a DB-level CHECK constraint enumerating every
-- allowed ObligationType value (added V1, widened once already for CUSTOM in V11) - a new Java
-- enum constant alone doesn't widen it, found live via a genuine constraint-violation error from
-- SqsDispatchIntegrationTest, not assumed. Same drop-and-recreate pattern V11 used.

ALTER TABLE deadline_record DROP CONSTRAINT deadline_record_obligation_type_check;
ALTER TABLE deadline_record ADD CONSTRAINT deadline_record_obligation_type_check
    CHECK (obligation_type IN ('ACRA_ANNUAL_RETURN', 'GST_F5', 'WORK_PASS_RENEWAL', 'CUSTOM', 'CORPORATE_INCOME_TAX'));
