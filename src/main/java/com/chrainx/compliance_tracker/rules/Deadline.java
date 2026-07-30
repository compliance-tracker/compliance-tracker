package com.chrainx.compliance_tracker.rules;

import java.time.LocalDate;

public class Deadline {

    private final ObligationType obligationType;
    private final LocalDate dueDate;
    // Both null for the 3 built-in types. Set together, only for ObligationType.CUSTOM:
    // customName is the display text (there's no fixed label to fall back to, unlike the
    // built-in types); customObligationId is what lets DeadlineSyncService dedupe multiple
    // custom obligations that happen to share the same business and due date, which the
    // built-in types' (business, obligationType, dueDate) key can't distinguish.
    private final String customName;
    private final Long customObligationId;

    public Deadline(ObligationType obligationType, LocalDate dueDate) {
        this(obligationType, dueDate, null, null);
    }

    public Deadline(ObligationType obligationType, LocalDate dueDate, String customName, Long customObligationId) {
        this.obligationType = obligationType;
        this.dueDate = dueDate;
        this.customName = customName;
        this.customObligationId = customObligationId;
    }

    public ObligationType getObligationType() {
        return obligationType;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getCustomName() {
        return customName;
    }

    public Long getCustomObligationId() {
        return customObligationId;
    }
}
