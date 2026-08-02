package com.chrainx.compliance_tracker.business;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// Same reasoning as WorkPassRequest (issue #46) - no id/business field, so the same #66-style
// IDOR shape can't happen here either. recurrenceMonths is optional - omitted (or explicitly
// null) means a one-off obligation, matching CustomObligation's own field. @Size(max = 255) on
// name - same unbounded-length gap found and fixed on BusinessRequest.name/WorkPassRequest.employeeName.
public record CustomObligationRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull LocalDate dueDate,
        @Min(1) Integer recurrenceMonths
) {
}
