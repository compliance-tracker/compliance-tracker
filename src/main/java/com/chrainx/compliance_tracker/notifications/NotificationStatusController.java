package com.chrainx.compliance_tracker.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Issue #114: the frontend's Harbour Ledger redesign (frontend #39/#63) needs a Notifications
// status page - "here's how reminders currently reach you" - and nothing exposed
// notifications.channel/notifications.email-from to any client before this. Deliberately just
// current config, not a "recently sent" history endpoint (that's a bigger feature needing a
// persisted send log, out of scope here per the issue itself). No new SecurityConfig entry
// needed - this falls under the existing default .anyRequest().authenticated(), same as every
// other real API endpoint; only auth/health/docs are public.
@RestController
@RequestMapping("/api/notifications")
public class NotificationStatusController {

    private final String channel;
    private final String fromAddress;

    public NotificationStatusController(@Value("${notifications.channel}") String channel,
                                         @Value("${notifications.email-from}") String fromAddress) {
        this.channel = channel;
        this.fromAddress = fromAddress;
    }

    @GetMapping("/status")
    public NotificationStatusResponse getStatus() {
        if (!"email".equals(channel)) {
            return NotificationStatusResponse.logging();
        }
        return NotificationStatusResponse.email(fromAddress);
    }
}
