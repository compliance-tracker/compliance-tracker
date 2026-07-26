package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.ObligationType;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Unlike EmailNotificationSenderTest (which overrides send() to capture the MimeMessage
// in-process), this spins up GreenMail - a real, local, in-memory SMTP server - and actually
// sends over real SMTP to it. Proves the whole real pipeline works (JavaMailSender's SMTP
// handshake, message serialization, everything EmailNotificationSenderTest can't see because it
// never opens a real connection at all) without depending on or contacting any real mail
// provider.
class EmailNotificationSenderIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void send_actuallyDeliversAnEmail_overRealSmtp() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(greenMail.getSmtp().getPort());

        EmailNotificationSender sender = new EmailNotificationSender(mailSender, "reminders@example.com");

        User owner = new User();
        owner.setEmail("owner@example.com");

        Business business = new Business();
        business.setName("Test Cafe Pte Ltd");
        business.setOwner(owner);

        DeadlineRecord deadlineRecord = new DeadlineRecord();
        deadlineRecord.setObligationType(ObligationType.ACRA_ANNUAL_RETURN);
        deadlineRecord.setDueDate(LocalDate.of(2026, 7, 31));

        sender.send(business, deadlineRecord);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);

        MimeMessage delivered = received[0];
        assertEquals("owner@example.com", delivered.getAllRecipients()[0].toString());
        assertTrue(delivered.getSubject().contains("ACRA Annual Return"));
        assertTrue(GreenMailUtil.getBody(delivered).contains("Test Cafe Pte Ltd"));
    }
}
