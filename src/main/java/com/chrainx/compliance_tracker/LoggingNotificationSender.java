package com.chrainx.compliance_tracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Stand-in NotificationSender: logs instead of really sending an email/SMS. No email provider
// is wired up yet (not in scope) - this exists so the rest of the dispatch/worker pipeline is
// genuinely end-to-end testable now, with the real channel swappable in later behind the same
// NotificationSender interface.
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Business business, DeadlineRecord deadlineRecord) {
        log.info("[REMINDER] Business '{}' (id={}): {} due {}",
                business.getName(), business.getId(),
                deadlineRecord.getObligationType(), deadlineRecord.getDueDate());
    }
}
