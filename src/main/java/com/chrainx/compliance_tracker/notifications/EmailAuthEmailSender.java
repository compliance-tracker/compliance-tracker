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
// opt-in as EmailNotificationSender.
//
// sendPasswordResetEmail sends a real clickable link now (frontend issue #55, the reset-password
// UI, is done - it reads its token straight from the URL's ?token= query param, see
// ResetPasswordPage.tsx). sendVerificationEmail still sends the raw token as plain text, not a
// link - frontend issue #56 (the verify-email UI) is filed but not built yet, so a link would
// point nowhere real. Update sendVerificationEmail the same way once #56 lands.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "email")
public class EmailAuthEmailSender implements AuthEmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String frontendUrl;

    @Autowired
    public EmailAuthEmailSender(JavaMailSender mailSender, @Value("${notifications.email-from}") String fromAddress,
                                 @Value("${app.frontend-url}") String frontendUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
        sendPlainTextEmail(toEmail, "Reset your Compliance Tracker password", """
                A password reset was requested for this account.

                Reset your password: %s

                If you didn't request this, you can safely ignore this email - your \
                password won't change unless this link is actually used.
                """.formatted(resetLink));
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
