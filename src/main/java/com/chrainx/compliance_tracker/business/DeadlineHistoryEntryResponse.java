package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;

import java.time.LocalDate;

// Issue #57 - one persisted DeadlineRecord, as history. `reminderSent` is the automated-pipeline
// outcome (see DeadlineRecord's own field); `dismissed` is computed by the caller by
// cross-referencing DismissedDeadlineKey (issue #34), not a column on DeadlineRecord itself -
// both can meaningfully be shown side by side, since a record can be manually dismissed either
// before or after an automated reminder already went out for it.
public record DeadlineHistoryEntryResponse(
        Long id, ObligationType obligationType, LocalDate dueDate, String customName,
        boolean reminderSent, boolean dismissed
) {

    public static DeadlineHistoryEntryResponse from(DeadlineRecord record, boolean dismissed) {
        return new DeadlineHistoryEntryResponse(
                record.getId(), record.getObligationType(), record.getDueDate(), record.getCustomName(),
                record.isReminderSent(), dismissed);
    }
}
