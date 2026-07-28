package com.chrainx.compliance_tracker.error;

// Consistent JSON error body across the whole API (issue #47) - `error` is a short,
// machine-readable code the frontend can branch on directly (e.g. `body.error === "UNAUTHORIZED"`)
// instead of string-matching a generic thrown Error's message for a status code, which is what
// the frontend was reduced to before this existed. `message` stays human-readable, for logging
// or direct display, but callers should never parse it to make a decision.
public record ApiError(String error, String message) {
}
