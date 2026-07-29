package com.chrainx.compliance_tracker.auth;

// Issue #120 (expanded scope): register no longer returns tokens (an unverified account can't log
// in anyway now, so handing it real tokens immediately was misleading) - just a human-readable
// confirmation instead.
public record RegistrationResponse(String message) {
}
