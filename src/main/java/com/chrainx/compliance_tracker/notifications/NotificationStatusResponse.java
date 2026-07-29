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
}
