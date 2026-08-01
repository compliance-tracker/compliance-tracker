package com.chrainx.compliance_tracker.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

// Default AuthEmailSender: logs instead of really sending an email. Same
// notifications.channel property NotificationSender's implementations key off, so one setting
// controls both - active whenever it isn't explicitly set to "email", keeping CI and local dev
// working with zero mail credentials configured at all.
//
// @ConditionalOnExpression, not @ConditionalOnProperty(havingValue = "logging",
// matchIfMissing = true) - that literal condition only matched "logging" or unset, so issue #62
// adding a third real notifications.channel value ("webhook") broke this for real: no
// AuthEmailSender bean existed at all for channel=webhook (WebhookNotificationSender has no
// equivalent, since there's no sane way to deliver a password-reset link via a generic webhook),
// and the whole app failed to start (AuthController's constructor needs one unconditionally).
// Found live, not caught by any unit test, by actually starting the app with
// notifications.channel=webhook before shipping it. This expression form is the fix that
// actually matches the comment's own stated intent - "active whenever it isn't email" - for any
// current or future non-email channel value, not just the one that happened to exist when this
// class was first written.
@Component
@ConditionalOnExpression("!'${notifications.channel:logging}'.equals('email')")
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
