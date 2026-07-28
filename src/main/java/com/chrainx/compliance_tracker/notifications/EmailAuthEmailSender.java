package com.chrainx.compliance_tracker.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Real AuthEmailSender (issue #37), same JavaMailSender/notifications.channel=email opt-in as
// EmailNotificationSender. Sends the raw reset token itself, not a link to a frontend
// reset-password page - that page doesn't exist yet (frontend follow-up, not built as part of
// this backend-only issue), so a clickable link would point nowhere real.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "email")
public class EmailAuthEmailSender implements AuthEmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    @Autowired
    public EmailAuthEmailSender(JavaMailSender mailSender, @Value("${notifications.email-from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message);
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Reset your Compliance Tracker password");
            helper.setText("""
                    A password reset was requested for this account.

                    Reset token: %s

                    If you didn't request this, you can safely ignore this email - your \
                    password won't change unless this token is actually used.
                    """.formatted(resetToken));
        } catch (MessagingException e) {
            // Same reasoning as EmailNotificationSender - every call above is a plain string
            // setter, this checked exception is essentially unreachable, but still needs
            // handling since the interface declares it.
            throw new RuntimeException("Failed to build password reset email", e);
        }

        mailSender.send(message);
    }
}
