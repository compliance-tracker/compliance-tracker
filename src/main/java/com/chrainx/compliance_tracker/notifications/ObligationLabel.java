package com.chrainx.compliance_tracker.notifications;

import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.rules.ObligationType;

import java.util.Map;

// Shared human-readable label for what a DeadlineRecord's reminder is actually about - extracted
// here once a second NotificationSender implementation (WebhookNotificationSender, issue #62)
// needed the exact same (obligationType -> display text) mapping EmailNotificationSender already
// had, rather than risking two independently-maintained copies quietly drifting apart the next
// time a label needs to change.
public final class ObligationLabel {

    private static final Map<ObligationType, String> LABELS = Map.of(
            ObligationType.ACRA_ANNUAL_RETURN, "ACRA Annual Return",
            ObligationType.GST_F5, "GST F5 Filing",
            ObligationType.WORK_PASS_RENEWAL, "Work Pass Renewal"
    );

    private ObligationLabel() {
    }

    // Issue #59: a CUSTOM obligation has no fixed label to look up - its own customName is the
    // real display text, set on the record at sync time from the obligation's own name.
    public static String of(DeadlineRecord record) {
        return record.getObligationType() == ObligationType.CUSTOM
                ? record.getCustomName()
                : LABELS.getOrDefault(record.getObligationType(), record.getObligationType().toString());
    }
}
