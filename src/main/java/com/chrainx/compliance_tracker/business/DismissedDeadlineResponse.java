package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;

import java.time.Instant;
import java.time.LocalDate;

public record DismissedDeadlineResponse(
        Long id, ObligationType obligationType, LocalDate dueDate,
        Long customObligationId, String customName, Instant dismissedAt
) {

    public static DismissedDeadlineResponse from(DismissedDeadline dismissed) {
        return new DismissedDeadlineResponse(
                dismissed.getId(),
                dismissed.getObligationType(),
                dismissed.getDueDate(),
                dismissed.getCustomObligation() != null ? dismissed.getCustomObligation().getId() : null,
                dismissed.getCustomName(),
                dismissed.getDismissedAt());
    }
}
