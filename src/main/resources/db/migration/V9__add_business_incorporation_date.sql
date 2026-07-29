-- Lets the app validate a first-year financial year end against the Companies Act's 18-month
-- cap (issue #31, sourced from Companies Act 1967 s.198 + ACRA's FYE guidance). Nullable and no
-- default, unlike leadTimeDays (V8) - there's no honest default for "when was this business
-- incorporated", and every existing business simply skips the new validation until this is set.

ALTER TABLE business ADD COLUMN incorporation_date DATE;
