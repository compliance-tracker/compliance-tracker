package com.chrainx.compliance_tracker.queue;

import com.chrainx.compliance_tracker.logging.CorrelationIdSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

// Issue #18: a message that lands in the dead-letter queue (after ReminderWorkerService fails
// to process it 3 times, see the redrive policy in docs/architecture.md's "Dead-letter
// handling") was previously silent - nothing surfaced it. This is the "at minimum, a way to
// inspect DLQ depth" half of that issue.
//
// Deliberately does NOT add a new HTTP endpoint to expose this. This app has no admin/role
// concept at all yet - every endpoint just means "authenticated as *some* user" - and DLQ
// contents span every business/user in the system, not just the caller's own. Building a real
// admin-auth model just to expose one read-only number would be a much bigger, separate scope
// decision than this issue asks for (see issue #65, admin action audit log, and #39, admin rule
// editing - both still open, both would need the same real admin-role groundwork this
// deliberately doesn't invent here). Log-based alerting is the honest, low-risk MVP instead: a
// WARN log line an operator (or, on real AWS, a CloudWatch Logs metric filter/alarm - not set up
// here, this project isn't deployed anywhere real yet, see root CLAUDE.md's AWS deployment
// decision) can act on, plus `aws sqs get-queue-attributes` for manual inspection (already the
// documented technique - see docs/architecture.md's "Dead-letter handling" and NOTES.md's issue
// #75 investigation, which used this exact command for the same reason).
@Service
public class DlqMonitorService {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitorService.class);

    private final SqsClient sqsClient;
    private final String dlqName;

    @Autowired
    public DlqMonitorService(SqsClient sqsClient, @Value("${aws.sqs.dlq-name}") String dlqName) {
        this.sqsClient = sqsClient;
        this.dlqName = dlqName;
    }

    // Every 5 minutes - DLQ depth only ever changes after a message has already failed
    // processing 3 times (the redrive policy's maxReceiveCount), so it moves far slower than
    // ReminderWorkerService's own 30s poll; no need to check this anywhere near that often.
    @Scheduled(fixedDelay = 300_000)
    public void checkDlqDepth() {
        CorrelationIdSupport.runWithNewCorrelationId(this::doCheckDlqDepth);
    }

    private void doCheckDlqDepth() {
        String queueUrl = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(dlqName).build()
        ).queueUrl();

        String approximateCount = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                .build()
        ).attributesAsStrings().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES.toString());

        int depth = approximateCount != null ? Integer.parseInt(approximateCount) : 0;

        // Deliberately silent when empty (the overwhelmingly common case) - a WARN every 5
        // minutes for "still nothing to report" would just be noise a real operator learns to
        // ignore, defeating the point of using WARN as a signal at all.
        if (depth > 0) {
            log.warn("Dead-letter queue '{}' has {} message(s) - one or more reminders failed "
                    + "processing repeatedly and need manual investigation", dlqName, depth);
        }
    }
}
