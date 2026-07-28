package com.chrainx.compliance_tracker.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// What a client sends to create or update a Business (issue #46) - deliberately has no `id` or
// `owner` field at all, not just fields a controller has to remember to clear. The entire class
// of IDOR risk from issue #66 (a client supplying their own `id`, JPA's save() silently doing an
// UPDATE instead of an INSERT) is now structurally impossible here - there's no field for a
// client to even supply one in. Shared by both create and update, since both take the exact
// same shape.
public record BusinessRequest(
        @NotBlank String name,
        @NotNull LocalDate financialYearEnd,
        boolean gstRegistered
) {
}
