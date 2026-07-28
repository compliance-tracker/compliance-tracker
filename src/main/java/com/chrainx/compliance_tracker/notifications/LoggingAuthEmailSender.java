package com.chrainx.compliance_tracker.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Default AuthEmailSender: logs instead of really sending an email. Same
// notifications.channel property NotificationSender's implementations key off, so one setting
// controls both - active whenever it isn't explicitly set to "email" (matchIfMissing=true),
// keeping CI and local dev working with zero mail credentials configured at all.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "logging", matchIfMissing = true)
public class LoggingAuthEmailSender implements AuthEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuthEmailSender.class);

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("[PASSWORD RESET] {} - token={}", toEmail, resetToken);
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationToken) {
        log.info("[EMAIL VERIFICATION] {} - token={}", toEmail, verificationToken);
    }
}
