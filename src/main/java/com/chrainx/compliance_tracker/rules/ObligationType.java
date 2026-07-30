package com.chrainx.compliance_tracker.rules;

public enum ObligationType {
    ACRA_ANNUAL_RETURN,
    GST_F5,
    WORK_PASS_RENEWAL,
    // Issue #59: a business's own user-defined obligation - Deadline/DeadlineRecord's
    // customName field carries the actual display text, since there's no fixed label for this
    // one the way EmailNotificationSender.OBLIGATION_LABELS has for the other three.
    CUSTOM
}
