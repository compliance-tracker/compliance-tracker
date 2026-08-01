package com.chrainx.compliance_tracker.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// A real regression guard for a real bug found live (issue #62): setting
// notifications.channel=webhook made the whole app fail to start, because
// AuthEmailSender (a separate abstraction from NotificationSender, but keyed off the same
// property) had no implementation for anything other than "logging"/"email" - AuthController's
// constructor needs one unconditionally, so this wasn't a degraded-but-running state, it was a
// genuine boot failure. No unit test caught this, since a unit test constructs a bean directly
// and never exercises Spring's own @ConditionalOnProperty bean-selection machinery at all - only
// a real @SpringBootTest with this exact property actually proves the fix (LoggingAuthEmailSender
// switching to @ConditionalOnExpression) works, and would catch it again if it ever regressed.
@SpringBootTest(properties = {"notifications.channel=webhook", "notifications.webhook-url=http://localhost:1"})
@ActiveProfiles("test")
class NotificationsChannelWiringIntegrationTest {

    @Autowired
    private AuthEmailSender authEmailSender;

    @Autowired
    private NotificationSender notificationSender;

    @Test
    void contextLoads_withWebhookChannel_andWiresTheRightBeans() {
        // The real point: this test method running at all proves ApplicationContext startup
        // succeeded - a bean-resolution failure fails at context refresh, before any @Test
        // method would ever run.
        assertInstanceOf(LoggingAuthEmailSender.class, authEmailSender);
        assertInstanceOf(WebhookNotificationSender.class, notificationSender);
    }
}
