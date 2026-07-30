package com.chrainx.compliance_tracker.notifications;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.rules.ObligationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Default NotificationSender: logs instead of really sending an email. Active whenever
// notifications.channel isn't explicitly set to "email" (matchIfMissing=true) - this is what
// keeps CI and local dev working with zero mail credentials configured at all, and is what
// EmailNotificationSender (issue #17) sits alongside rather than replaces outright.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "logging", matchIfMissing = true)
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Business business, DeadlineRecord deadlineRecord) {
        // Issue #59: CUSTOM's own customName is the actual useful info here - the bare enum
        // value alone would just print "CUSTOM" for every one of a business's custom obligations,
        // indistinguishable from each other in the log.
        Object obligationLabel = deadlineRecord.getObligationType() == ObligationType.CUSTOM
                ? deadlineRecord.getCustomName()
                : deadlineRecord.getObligationType();
        log.info("[REMINDER] Business '{}' (id={}): {} due {}",
                business.getName(), business.getId(),
                obligationLabel, deadlineRecord.getDueDate());
    }
}
