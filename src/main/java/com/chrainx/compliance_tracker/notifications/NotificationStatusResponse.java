package com.chrainx.compliance_tracker.notifications;

import com.fasterxml.jackson.annotation.JsonInclude;

// fromAddress is only meaningful when channel is "email" - omitted entirely (not null) for the
// "logging" default, matching issue #114's requested shape.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationStatusResponse(String channel, String fromAddress) {

    public static NotificationStatusResponse logging() {
        return new NotificationStatusResponse("logging", null);
    }

    public static NotificationStatusResponse email(String fromAddress) {
        return new NotificationStatusResponse("email", fromAddress);
    }

    // Issue #62 - deliberately no webhook-url field: a real webhook URL is itself a bearer
    // credential (Slack's own URLs embed the auth token directly in the path), the same reason
    // this endpoint never echoed spring.mail.* credentials for the email channel either.
    public static NotificationStatusResponse webhook() {
        return new NotificationStatusResponse("webhook", null);
    }
}
