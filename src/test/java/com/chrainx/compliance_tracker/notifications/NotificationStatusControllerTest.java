package com.chrainx.compliance_tracker.notifications;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationStatusControllerTest {

    @Test
    void getStatus_reportsLogging_whenChannelIsTheDefault() {
        NotificationStatusController controller = new NotificationStatusController("logging", "");

        NotificationStatusResponse status = controller.getStatus();

        assertEquals("logging", status.channel());
        assertNull(status.fromAddress());
    }

    @Test
    void getStatus_reportsEmail_withTheConfiguredFromAddress() {
        NotificationStatusController controller =
                new NotificationStatusController("email", "reminders@example.com");

        NotificationStatusResponse status = controller.getStatus();

        assertEquals("email", status.channel());
        assertEquals("reminders@example.com", status.fromAddress());
    }

    @Test
    void getStatus_reportsWebhook_withNoUrlExposed() {
        // Issue #62 - the real webhook URL is itself a bearer credential, deliberately never
        // echoed back, same reasoning as never echoing spring.mail.* credentials for email.
        NotificationStatusController controller =
                new NotificationStatusController("webhook", "");

        NotificationStatusResponse status = controller.getStatus();

        assertEquals("webhook", status.channel());
        assertNull(status.fromAddress());
    }

    @Test
    void getStatus_treatsAnyNonEmailNonWebhookValue_asLogging() {
        // Same matchIfMissing-style reasoning as the @ConditionalOnProperty beans this mirrors
        // (LoggingNotificationSender/EmailNotificationSender) - anything other than exactly
        // "email" is the safe logging default, not just an unset property.
        NotificationStatusController controller = new NotificationStatusController("bogus", "");

        NotificationStatusResponse status = controller.getStatus();

        assertEquals("logging", status.channel());
        assertNull(status.fromAddress());
    }
}
