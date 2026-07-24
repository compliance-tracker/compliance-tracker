package com.chrainx.compliance_tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

// Pushes due-soon, unreminded deadlines onto SQS. Deliberately does NOT mark reminderSent
// here - that happens in the worker (issue #13) only after a reminder is actually sent
// successfully. If this producer marked it sent at enqueue time and the message were then
// lost or the worker crashed, the deadline would be silently skipped forever with no
// retry - marking success has to happen at the point where the work is actually done.
@Service
public class SqsDispatchService {

    private final DeadlineSyncService deadlineSyncService;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueName;

    @Autowired
    public SqsDispatchService(DeadlineSyncService deadlineSyncService,
                               SqsClient sqsClient,
                               ObjectMapper objectMapper,
                               @Value("${aws.sqs.queue-name}") String queueName) {
        this.deadlineSyncService = deadlineSyncService;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    // @Scheduled methods must be no-arg - Spring has no way to supply a parameter on a timer
    // trigger - so this thin wrapper is what's actually scheduled, delegating to the real
    // (parameterized, directly testable) method below with a fixed 14-day lookahead.
    //
    // Runs 15 minutes after DeadlineSyncService's daily sync (01:00), giving it time to finish
    // persisting any newly-computed deadlines first. Not a hard guarantee of ordering - a
    // proper fix would explicitly chain sync -> dispatch in one job - but acceptable for this
    // project's scope; flagged as a known limitation.
    @Scheduled(cron = "0 15 1 * * *")
    public void scheduledDispatch() {
        dispatchDueSoonDeadlines(14);
    }

    public int dispatchDueSoonDeadlines(int daysAhead) {
        String queueUrl = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(queueName).build()
        ).queueUrl();

        List<DeadlineRecord> dueSoon = deadlineSyncService.findDueSoonAndUnreminded(LocalDate.now(), daysAhead);

        for (DeadlineRecord record : dueSoon) {
            // Jackson 3.x (bundled by Spring Boot 4.1) made writeValueAsString throw an
            // unchecked tools.jackson.core.JacksonException instead of Jackson 2's checked
            // JsonProcessingException - no try/catch needed; a serialization failure here
            // means a programming error and should propagate and fail loudly.
            String body = objectMapper.writeValueAsString(new ReminderMessage(
                    record.getId(),
                    record.getBusiness().getId(),
                    record.getObligationType(),
                    record.getDueDate()
            ));

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
        }

        return dueSoon.size();
    }
}
