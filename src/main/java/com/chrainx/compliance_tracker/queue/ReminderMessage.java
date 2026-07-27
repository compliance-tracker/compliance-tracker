package com.chrainx.compliance_tracker.queue;

import com.chrainx.compliance_tracker.rules.ObligationType;

import java.time.LocalDate;

// A Java "record": a concise way to define an immutable data-carrier class. Writing
// `record ReminderMessage(...)` auto-generates the constructor, getters (named after the
// fields, e.g. deadlineRecordId() not getDeadlineRecordId()), equals/hashCode, and toString -
// equivalent to what Deadline.java writes out by hand, just less boilerplate. Used here rather
// than for the JPA entities because this is a plain message payload, never persisted itself.
public record ReminderMessage(
        Long deadlineRecordId,
        Long businessId,
        ObligationType obligationType,
        LocalDate dueDate
) {
}
