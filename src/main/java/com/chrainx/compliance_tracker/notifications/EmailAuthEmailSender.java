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
// Both emails send a real clickable button/link now. sendVerificationEmail used to send the raw
// token as plain text - the comment justifying that ("frontend issue #56 isn't built yet") went
// stale the moment frontend #69 actually built the Verify Email page (reads its token from the
// same ?token= query param VerifyEmailPage.tsx uses), the same class of staleness already found
// and fixed once for sendPasswordResetEmail - worth checking any "not built yet" comment against
// current frontend status before trusting it, not just at the time it was written.
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
        String html = EmailTemplate.render(
                "Reset your password",
                "A password reset was requested for this account. This link is valid for 1 hour "
                        + "and can only be used once.",
                "Reset password",
                resetLink,
                "If you didn't request this, you can safely ignore this email - your password "
                        + "won't change unless the link above is actually used.");
        sendHtmlEmail(toEmail, "Reset your Compliance Tracker password", html);
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationToken) {
        String verifyLink = frontendUrl + "/verify-email?token=" + verificationToken;
        String html = EmailTemplate.render(
                "Verify your email address",
                "Thanks for registering with Compliance Tracker. Confirm this is really your "
                        + "email address, then log in to get started. This link is valid for 7 days.",
                "Verify email address",
                verifyLink,
                "If you didn't create this account, you can safely ignore this email.");
        sendHtmlEmail(toEmail, "Verify your Compliance Tracker email address", html);
    }

    private void sendHtmlEmail(String toEmail, String subject, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message);
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
        } catch (MessagingException e) {
            // Same reasoning as EmailNotificationSender - every call above is a plain string
            // setter, this checked exception is essentially unreachable, but still needs
            // handling since the interface declares it.
            throw new RuntimeException("Failed to build email", e);
        }

        mailSender.send(message);
    }
}
