package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.WorkPassType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Same reasoning as BusinessRequest (issue #46) - no `id`/`business` field at all, so the same
// #66-style IDOR shape can't happen here either.
//
// passType (issue #32) is nullable on the request - WorkPassController defaults a missing value
// to EMPLOYMENT_PASS, the same default the entity itself falls back to, rather than forcing every
// existing caller to start naming a type it never had to think about before.
public record WorkPassRequest(
        @NotBlank String employeeName,
        @NotNull LocalDate expiryDate,
        WorkPassType passType
) {
}
