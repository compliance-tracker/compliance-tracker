package com.chrainx.compliance_tracker.notifications;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Same reasoning as EmailNotificationSenderIntegrationTest: a real, local, in-memory SMTP server
// (GreenMail), actually sent to over real SMTP - proves the whole pipeline, not just that the
// message-building code compiles.
class EmailAuthEmailSenderIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    private EmailAuthEmailSender newSender(String frontendUrl) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());

        return new EmailAuthEmailSender(mailSender, "auth@example.com", frontendUrl);
    }

    // Regression test for a real bug found live while manually verifying this exact flow
    // against Mailpit: a blank "from" address (notifications.email-from falling back to an
    // unset MAIL_FROM/MAIL_USERNAME) throws jakarta.mail.internet.AddressException at send
    // time, not at startup - worth a real test now that it's been hit for real once.
    @Test
    void sendPasswordResetEmail_containsARealClickableLink_toTheFrontendsResetPasswordPage() throws Exception {
        EmailAuthEmailSender sender = newSender("http://localhost:5173");

        sender.sendPasswordResetEmail("owner@example.com", "abc-123-token");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);

        MimeMessage delivered = received[0];
        assertEquals("owner@example.com", delivered.getAllRecipients()[0].toString());
        assertTrue(delivered.getSubject().contains("Reset"));
        assertTrue(delivered.getContentType().contains("text/html"));

        String body = GreenMailUtil.getBody(delivered);
        // The actual point of issue #55's frontend follow-up: a real link the user can click,
        // matching ResetPasswordPage.tsx's own ?token= query param exactly - not just the raw
        // token as bare text, which is what this used to send before the frontend page existed.
        assertTrue(body.contains("http://localhost:5173/reset-password?token=abc-123-token"));
        // The link appears three times by design (the button's href, the fallback link's own
        // href, and that same fallback link's displayed text) - all three need to actually be
        // present, not just one of them.
        assertEquals(3, countOccurrences(body, "http://localhost:5173/reset-password?token=abc-123-token"));
    }

    @Test
    void sendPasswordResetEmail_usesWhicheverFrontendUrlIsConfigured_notAHardcodedOne() throws Exception {
        EmailAuthEmailSender sender = newSender("https://app.compliance-tracker.example");

        sender.sendPasswordResetEmail("owner@example.com", "xyz-789-token");

        String body = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
        assertTrue(body.contains("https://app.compliance-tracker.example/reset-password?token=xyz-789-token"));
    }

    // Regression test for a real, previously-stale comment: this used to deliberately send the
    // raw token as plain text because "frontend issue #56 isn't built yet" - that stopped being
    // true once frontend #69 actually built the Verify Email page (VerifyEmailPage.tsx, reads its
    // token from the same ?token= query param convention as the reset-password page), the same
    // class of staleness already found and fixed once for sendPasswordResetEmail.
    @Test
    void sendVerificationEmail_containsARealClickableLink_toTheFrontendsVerifyEmailPage() throws Exception {
        EmailAuthEmailSender sender = newSender("http://localhost:5173");

        sender.sendVerificationEmail("owner@example.com", "verify-token-456");

        MimeMessage delivered = greenMail.getReceivedMessages()[0];
        assertTrue(delivered.getSubject().contains("Verify"));
        assertTrue(delivered.getContentType().contains("text/html"));

        String body = GreenMailUtil.getBody(delivered);
        assertTrue(body.contains("http://localhost:5173/verify-email?token=verify-token-456"));
    }

    @Test
    void sendVerificationEmail_usesWhicheverFrontendUrlIsConfigured_notAHardcodedOne() throws Exception {
        EmailAuthEmailSender sender = newSender("https://app.compliance-tracker.example");

        sender.sendVerificationEmail("owner@example.com", "verify-token-789");

        String body = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
        assertTrue(body.contains("https://app.compliance-tracker.example/verify-email?token=verify-token-789"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
