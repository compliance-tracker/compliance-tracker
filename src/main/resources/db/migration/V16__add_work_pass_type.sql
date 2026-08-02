-- Issue #32: every WorkPass was previously implicitly assumed to be an Employment Pass.
-- DEFAULT 'EMPLOYMENT_PASS' applies to every existing row, preserving current behavior exactly -
-- nothing changes for anyone not opting into recording a different pass type.

ALTER TABLE work_pass ADD COLUMN pass_type VARCHAR(255) NOT NULL DEFAULT 'EMPLOYMENT_PASS'
    CHECK (pass_type IN ('EMPLOYMENT_PASS', 'S_PASS', 'WORK_PERMIT'));
