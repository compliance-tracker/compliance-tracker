-- Issue #45: IRAS actually supports monthly, quarterly, and six-monthly GST accounting periods -
-- this app previously assumed quarterly unconditionally. DEFAULT 'QUARTERLY' applies to every
-- existing row too, preserving current behavior exactly for every business that isn't a monthly
-- filer - nothing changes for anyone not opting into this.

ALTER TABLE business ADD COLUMN gst_filing_frequency VARCHAR(255) NOT NULL DEFAULT 'QUARTERLY'
    CHECK (gst_filing_frequency IN ('MONTHLY', 'QUARTERLY'));
