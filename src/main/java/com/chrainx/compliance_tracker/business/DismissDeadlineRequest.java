package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// The natural key identifying which live-computed deadline to dismiss - the same shape
// rules.Deadline itself already carries for identifying a specific occurrence. No id/business
// field (same #66/#46 IDOR-avoidance reasoning as every other request DTO in this app).
// customObligationId/customName are only meaningful (and only ever sent) for ObligationType.CUSTOM.
public record DismissDeadlineRequest(
        @NotNull ObligationType obligationType,
        @NotNull LocalDate dueDate,
        Long customObligationId,
        String customName
) {
}
