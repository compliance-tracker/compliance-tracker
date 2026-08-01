package com.chrainx.compliance_tracker.notifications;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// Real NotificationSender (issue #17), replacing the LoggingNotificationSender stand-in for
// anyone who sets notifications.channel=email (see application.properties). Sends to the
// business owner's own registered email (Business.owner.email) - the account holder is the one
// who should hear about their own business's deadlines, there's no separate "business contact
// email" concept in this app.
@Component
@ConditionalOnProperty(prefix = "notifications", name = "channel", havingValue = "email")
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    @Autowired
    public EmailNotificationSender(JavaMailSender mailSender, @Value("${notifications.email-from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(Business business, DeadlineRecord deadlineRecord) {
        String obligationLabel = ObligationLabel.of(deadlineRecord);

        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message);
            helper.setFrom(fromAddress);
            helper.setTo(business.getOwner().getEmail());
            helper.setSubject("Compliance reminder: %s due %s".formatted(obligationLabel, deadlineRecord.getDueDate()));
            helper.setText("""
                    %s has a compliance deadline coming up:

                    %s
                    Due: %s

                    This is a reminder/tracking notice, not compliance advice - always verify \
                    against the official government source before relying on this date.
                    """.formatted(business.getName(), obligationLabel, deadlineRecord.getDueDate()));
        } catch (MessagingException e) {
            // MimeMessageHelper's checked exception is essentially unreachable here - every
            // call above is a plain string setter (no attachments, no multipart) - but the
            // interface itself declares it, so it still needs handling. Wrapping in an
            // unchecked exception lets ReminderWorkerService's existing per-message try/catch
            // (see pollAndProcess) do the same job it already does for any other send failure:
            // leave the message in the queue for SQS to retry.
            throw new RuntimeException("Failed to build reminder email", e);
        }

        mailSender.send(message);
    }
}
