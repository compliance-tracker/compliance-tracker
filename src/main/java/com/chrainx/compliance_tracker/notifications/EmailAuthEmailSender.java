package com.chrainx.compliance_tracker.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Real AuthEmailSender (issue #37, extended #36), same JavaMailSender/notifications.channel=email
// opt-in as EmailNotificationSender. Sends the raw token itself, not a link to a frontend page -
// neither a reset-password nor a verify-email page exists yet (frontend follow-up, not built as
// part of these backend-only issues), so a clickable link would point nowhere real.
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
        sendPlainTextEmail(toEmail, "Reset your Compliance Tracker password", """
                A password reset was requested for this account.

                Reset token: %s

                If you didn't request this, you can safely ignore this email - your \
                password won't change unless this token is actually used.
                """.formatted(resetToken));
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationToken) {
        sendPlainTextEmail(toEmail, "Verify your Compliance Tracker email address", """
                Thanks for registering. Use this token to verify you own this email address:

                Verification token: %s

                If you didn't create this account, you can safely ignore this email.
                """.formatted(verificationToken));
    }

    private void sendPlainTextEmail(String toEmail, String subject, String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message);
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body);
        } catch (MessagingException e) {
            // Same reasoning as EmailNotificationSender - every call above is a plain string
            // setter, this checked exception is essentially unreachable, but still needs
            // handling since the interface declares it.
            throw new RuntimeException("Failed to build email", e);
        }

        mailSender.send(message);
    }
}
