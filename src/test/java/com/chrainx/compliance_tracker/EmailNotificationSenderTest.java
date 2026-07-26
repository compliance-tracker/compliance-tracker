package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.ObligationType;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailNotificationSenderTest {

    @Test
    void send_buildsAMessage_addressedToTheBusinessOwner() throws Exception {
        User owner = new User();
        owner.setEmail("owner@example.com");

        Business business = new Business();
        business.setName("Test Cafe Pte Ltd");
        business.setOwner(owner);

        DeadlineRecord deadlineRecord = new DeadlineRecord();
        deadlineRecord.setObligationType(ObligationType.GST_F5);
        deadlineRecord.setDueDate(LocalDate.of(2026, 10, 30));

        // A real JavaMailSenderImpl (not mocked) so createMimeMessage() builds a genuine
        // MimeMessage - only send() is overridden here (to capture instead of opening a real
        // SMTP connection), so this test inspects the actual message content
        // EmailNotificationSender built, not just that some mock method got called.
        MimeMessage[] captured = new MimeMessage[1];
        JavaMailSenderImpl capturingSender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
                captured[0] = mimeMessage;
            }
        };
        EmailNotificationSender capturingNotificationSender =
                new EmailNotificationSender(capturingSender, "reminders@example.com");

        capturingNotificationSender.send(business, deadlineRecord);

        MimeMessage message = captured[0];
        assertEquals("reminders@example.com", message.getFrom()[0].toString());
        assertEquals("owner@example.com", message.getAllRecipients()[0].toString());
        assertTrue(message.getSubject().contains("GST F5 Filing"));
        assertTrue(message.getSubject().contains("2026-10-30"));

        String body = (String) message.getContent();
        assertTrue(body.contains("Test Cafe Pte Ltd"));
        assertTrue(body.contains("GST F5 Filing"));
        assertTrue(body.contains("2026-10-30"));
        assertTrue(body.contains("not compliance advice"));
    }
}
