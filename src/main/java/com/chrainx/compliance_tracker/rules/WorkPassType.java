package com.chrainx.compliance_tracker.rules;

// Issue #32: previously every WorkPass was implicitly treated as an Employment Pass. Researched
// the real MOM renewal rules for all three pass types before adding this (see README's
// Compliance rules table) - the renewal *deadline* formula turns out to be identical across all
// three (`= passExpiryDate`, no grace period after expiry), only the recommended/allowed
// application window differs (EP/S Pass: up to 6 months before; Work Permit: 7-12 weeks
// recommended) - which this app doesn't model at all (it only tracks the hard deadline, not the
// advisory earliest-application window). So RuleEngine needs no branching by type; this field
// exists purely so the app knows and can correctly label which real pass a WorkPass actually is.
public enum WorkPassType {
    EMPLOYMENT_PASS,
    S_PASS,
    WORK_PERMIT
}
