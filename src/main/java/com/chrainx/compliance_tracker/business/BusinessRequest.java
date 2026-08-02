package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.GstFilingFrequency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
// gstFilingFrequency (issue #45) is optional, same reasoning as leadTimeDays/incorporationDate -
// BusinessController defaults it to QUARTERLY (the pre-existing behavior) on create when omitted,
// and preserves the existing value on update when omitted, rather than forcing every client to
// always supply it.
// @Size(max = 255) on name: found live that nothing bounded this at all before - a 10,000
// character name was accepted with a 200. 255 is a generous, deliberately round bound for a
// business name, not tied to any DB column width (name is AES-GCM-encrypted, stored as
// unbounded TEXT - Postgres itself would never reject a long value the way a VARCHAR(255) would).
public record BusinessRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull LocalDate financialYearEnd,
        boolean gstRegistered,
        @Min(1) @Max(90) Integer leadTimeDays,
        LocalDate incorporationDate,
        GstFilingFrequency gstFilingFrequency
) {
}
