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
    // Only set for ObligationType.WORK_PASS_RENEWAL (issue #32) - which real MOM pass this
    // deadline is for, so a caller can show "S Pass renewal" instead of a generic label. Not
    // threaded any further than this live view (DeadlineRecord/notifications stay untouched,
    // deliberately - see WorkPass.passType's own comment for why the renewal deadline formula
    // itself doesn't need to branch by type at all).
    private final WorkPassType workPassType;

    public Deadline(ObligationType obligationType, LocalDate dueDate) {
        this(obligationType, dueDate, null, null, null);
    }

    public Deadline(ObligationType obligationType, LocalDate dueDate, WorkPassType workPassType) {
        this(obligationType, dueDate, null, null, workPassType);
    }

    public Deadline(ObligationType obligationType, LocalDate dueDate, String customName, Long customObligationId) {
        this(obligationType, dueDate, customName, customObligationId, null);
    }

    private Deadline(ObligationType obligationType, LocalDate dueDate, String customName, Long customObligationId,
                      WorkPassType workPassType) {
        this.obligationType = obligationType;
        this.dueDate = dueDate;
        this.customName = customName;
        this.customObligationId = customObligationId;
        this.workPassType = workPassType;
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

    public WorkPassType getWorkPassType() {
        return workPassType;
    }
}
