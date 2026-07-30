package com.chrainx.compliance_tracker.business;

import java.time.LocalDate;

// Same reasoning as WorkPassResponse (issue #46). dueDate here is the stored anchor, not the
// live-recomputed next occurrence for a recurring obligation - the same relationship
// Business.financialYearEnd has to the real ACRA deadline, which is only ever visible via
// GET /businesses/{id}/deadlines, not the business's own record.
public record CustomObligationResponse(Long id, String name, LocalDate dueDate, Integer recurrenceMonths) {

    public static CustomObligationResponse from(CustomObligation obligation) {
        return new CustomObligationResponse(
                obligation.getId(), obligation.getName(), obligation.getDueDate(), obligation.getRecurrenceMonths());
    }
}
