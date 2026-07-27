package com.chrainx.compliance_tracker.notifications;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;

// Abstraction over "how a reminder actually reaches a business" - deliberately separate from
// ReminderWorkerService, so a real channel (email via AWS SES, SMS, etc.) can be swapped in
// later without touching the queue-consuming/idempotency logic at all.
public interface NotificationSender {
    void send(Business business, DeadlineRecord deadlineRecord);
}
