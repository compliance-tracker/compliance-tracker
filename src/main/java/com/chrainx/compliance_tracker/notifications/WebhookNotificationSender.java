package com.chrainx.compliance_tracker.notifications;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

// Issue #62: a cheap additional NotificationSender for anyone who'd rather get reminders in a
// channel they already monitor than an inbox. Posts a plain {"text": "..."} JSON body - Slack's
// own incoming-webhook format, and one enough other tools (Mattermost, several generic "Slack-
// compatible" webhook receivers) also accept that this doesn't need to be Slack-specific code,
// just a Slack-*shaped* request.
//
// RestClient.create() (not an injected RestClient.Builder bean) deliberately - this project has
// hit real Spring Boot 4 module-split surprises before (Jackson's package rename, Flyway's
// starter requirement, TestRestTemplate's own module), and RestClientAutoConfiguration wasn't
// found anywhere on this project's actual dependency tree when checked directly. RestClient
// itself is a plain spring-web class with no autoconfiguration required to construct one
// directly, sidestepping the question entirely rather than adding a dependency to answer it.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "webhook")
public class WebhookNotificationSender implements NotificationSender {

    private final RestClient restClient;
    private final String webhookUrl;

    @Autowired
    public WebhookNotificationSender(@Value("${notifications.webhook-url:}") String webhookUrl) {
        // Fail fast at startup, not on the first real reminder - a blank URL here would
        // otherwise surface as a confusing connection error deep inside ReminderWorkerService's
        // own try/catch (which would just quietly leave the message for SQS to retry forever,
        // never actually alerting anyone that the real problem is a missing config value).
        if (!StringUtils.hasText(webhookUrl)) {
            throw new IllegalStateException(
                    "notifications.webhook-url must be set when notifications.channel=webhook");
        }
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(Business business, DeadlineRecord deadlineRecord) {
        String text = "*Compliance reminder*: %s — %s due %s".formatted(
                business.getName(), ObligationLabel.of(deadlineRecord), deadlineRecord.getDueDate());

        // Deliberately no try/catch here - a failed webhook post throws (RestClientException on
        // a non-2xx or unreachable host), which ReminderWorkerService's own per-message try/catch
        // already handles the same way any other send failure is handled: log it and leave the
        // message in the queue for SQS to retry, same as EmailNotificationSender's own unchecked-
        // exception-on-failure shape.
        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .toBodilessEntity();
    }
}
