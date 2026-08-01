package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.ObligationType;

import java.time.LocalDate;

// A plain natural-key value object (issue #34) - used to check a live-computed rules.Deadline
// (BusinessController.getDeadlines) or a persisted DeadlineRecord (DeadlineSyncService's
// dispatch-gate query) against the set of deadlines a user has manually dismissed. A record gets
// free equals/hashCode, which is all a Set<DismissedDeadlineKey> membership check needs - no
// entity/DB knowledge here at all, same "pure value" spirit as rules.Deadline itself.
public record DismissedDeadlineKey(
        Long businessId, ObligationType obligationType, LocalDate dueDate, Long customObligationId) {

    public static DismissedDeadlineKey of(Long businessId, Deadline deadline) {
        return new DismissedDeadlineKey(
                businessId, deadline.getObligationType(), deadline.getDueDate(), deadline.getCustomObligationId());
    }

    public static DismissedDeadlineKey of(Long businessId, DeadlineRecord record) {
        return new DismissedDeadlineKey(
                businessId, record.getObligationType(), record.getDueDate(),
                record.getCustomObligation() != null ? record.getCustomObligation().getId() : null);
    }

    public static DismissedDeadlineKey of(DismissedDeadline dismissed) {
        return new DismissedDeadlineKey(
                dismissed.getBusiness().getId(), dismissed.getObligationType(), dismissed.getDueDate(),
                dismissed.getCustomObligation() != null ? dismissed.getCustomObligation().getId() : null);
    }
}
