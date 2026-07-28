package com.chrainx.compliance_tracker.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Same reasoning as BusinessRequest (issue #46) - no `id`/`business` field at all, so the same
// #66-style IDOR shape can't happen here either.
public record WorkPassRequest(
        @NotBlank String employeeName,
        @NotNull LocalDate expiryDate
) {
}
