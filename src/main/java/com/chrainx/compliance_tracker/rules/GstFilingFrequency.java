package com.chrainx.compliance_tracker.rules;

// Issue #45. IRAS actually supports monthly, quarterly, and six-monthly GST accounting periods
// (https://www.iras.gov.sg/taxes/goods-services-tax-(gst)/filing-gst/due-dates-and-requests-for-extension)
// - a return/payment is always due exactly one month after the end of the accounting period,
// regardless of which frequency it is. Scoped to the two frequencies the issue actually names
// (quarterly, the pre-existing behavior, and monthly) - six-monthly stays a known, documented
// limitation rather than invented/guessed at, same standing rule as every other rule in this app.
public enum GstFilingFrequency {
    MONTHLY,
    QUARTERLY
}
