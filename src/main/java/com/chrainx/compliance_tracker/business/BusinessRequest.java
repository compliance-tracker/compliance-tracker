package com.chrainx.compliance_tracker.business;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// What a client sends to create or update a Business (issue #46) - deliberately has no `id` or
// `owner` field at all, not just fields a controller has to remember to clear. The entire class
// of IDOR risk from issue #66 (a client supplying their own `id`, JPA's save() silently doing an
// UPDATE instead of an INSERT) is now structurally impossible here - there's no field for a
// client to even supply one in. Shared by both create and update, since both take the exact
// same shape.
//
// leadTimeDays (issue #53) is deliberately a boxed Integer, not a primitive int, and has no
// @NotNull - it's optional, unlike the other fields. BusinessController fills in a sensible
// default when it's omitted (14 on create, matching the old hardcoded behavior; the existing
// value is left untouched on update) rather than forcing every client - including the current
// frontend, which doesn't send this field yet - to always supply it. @Min/@Max still apply when
// a value IS present, since Bean Validation treats a null as "not provided" and skips them.
//
// incorporationDate (issue #31) is optional too, for the same reason - no client sends it yet.
// Unlike leadTimeDays it has no Bean Validation annotation at all: the rule it enables (a
// first-year financialYearEnd can't be more than 18 months after it, per the Companies Act) is
// a cross-field check, which Bean Validation annotations on a single field can't express -
// BusinessController checks it explicitly instead, same pattern as the password-strength check
// on AuthController.register.
public record BusinessRequest(
        @NotBlank String name,
        @NotNull LocalDate financialYearEnd,
        boolean gstRegistered,
        @Min(1) @Max(90) Integer leadTimeDays,
        LocalDate incorporationDate
) {
}
