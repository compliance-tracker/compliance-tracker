package com.chrainx.compliance_tracker.notifications;

// Abstraction over "how a generic auth-related email (password reset, issue #37) actually
// reaches a user" - deliberately separate from NotificationSender, which is shaped specifically
// around compliance reminders (Business + DeadlineRecord). Same channel-swap reasoning: a real
// implementation can be opted into without touching AuthController's logic at all, and the
// zero-config logging default keeps CI/local dev free of any real mail credential requirement.
public interface AuthEmailSender {
    void sendPasswordResetEmail(String toEmail, String resetToken);
}
