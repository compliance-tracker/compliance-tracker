package com.chrainx.compliance_tracker.queue;
import com.chrainx.compliance_tracker.notifications.NotificationSender;
import com.chrainx.compliance_tracker.business.DeadlineRecordRepository;
import com.chrainx.compliance_tracker.business.DeadlineRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

// Consumes the SQS queue SqsDispatchService produces into. This is the piece that actually
// gets to say "reminder sent" - SqsDispatchService only ever enqueues, never marks anything as
// done, precisely so this worker is the single source of truth for "did this actually happen."
@Service
public class ReminderWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderWorkerService.class);

    private final SqsClient sqsClient;
    private final DeadlineRecordRepository deadlineRecordRepository;
    private final NotificationSender notificationSender;
    private final ObjectMapper objectMapper;
    private final String queueName;

    @Autowired
    public ReminderWorkerService(SqsClient sqsClient,
                                  DeadlineRecordRepository deadlineRecordRepository,
                                  NotificationSender notificationSender,
                                  ObjectMapper objectMapper,
                                  @Value("${aws.sqs.queue-name}") String queueName) {
        this.sqsClient = sqsClient;
        this.deadlineRecordRepository = deadlineRecordRepository;
        this.notificationSender = notificationSender;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    // Polls every 30s. SQS itself has no "push" mode without extra infra (e.g. SNS + Lambda) -
    // polling (with maxNumberOfMessages + waitTimeSeconds for long polling, reducing empty
    // round-trips) is the normal way to consume a queue directly like this.
    //
    // @Transactional lives here, not on processMessage(): Spring's @Transactional is
    // proxy-based and does NOT intercept self-invocation (this method calling
    // processMessage() on `this` directly) - putting it on processMessage would silently do
    // nothing. It has to sit on the method Spring's scheduler actually calls through the
    // proxy. One open Hibernate session for the whole poll+process batch is what lets
    // deadlineRecord.getBusiness() (a FetchType.LAZY field) be read below without throwing
    // LazyInitializationException - @Scheduled methods get no session from Spring's
    // open-in-view (that only applies to web requests).
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void pollAndProcess() {
        String queueUrl = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(queueName).build()
        ).queueUrl();

        List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()
        ).messages();

        for (Message message : messages) {
            try {
                processMessage(queueUrl, message);
            } catch (Exception e) {
                // Isolate this message's failure so it doesn't stop the rest of the batch from
                // being processed. Deliberately don't delete the message here - leaving it in
                // the queue is what lets SQS's own visibility timeout + redrive policy
                // (maxReceiveCount, set on the queue itself, not in this code) retry it and
                // eventually route it to the dead-letter queue after repeated failures.
                log.error("Failed to process reminder message, leaving in queue for retry: {}", message.body(), e);
            }
        }
    }

    private void processMessage(String queueUrl, Message message) {
        ReminderMessage reminderMessage = objectMapper.readValue(message.body(), ReminderMessage.class);
        Optional<DeadlineRecord> record = deadlineRecordRepository.findById(reminderMessage.deadlineRecordId());

        if (record.isEmpty()) {
            // The DeadlineRecord this message refers to no longer exists (e.g. deleted since
            // enqueue). Nothing to act on - delete the message so it doesn't get retried forever.
            log.warn("No DeadlineRecord found for id={}, discarding message", reminderMessage.deadlineRecordId());
            deleteMessage(queueUrl, message);
            return;
        }

        DeadlineRecord deadlineRecord = record.get();

        // The actual idempotency check: if this deadline was already reminded about (e.g. this
        // message is a redelivered duplicate after a successful prior send), skip sending again,
        // but still delete the message - it's now genuinely handled.
        if (!deadlineRecord.isReminderSent()) {
            notificationSender.send(deadlineRecord.getBusiness(), deadlineRecord);
            deadlineRecord.setReminderSent(true);
            deadlineRecordRepository.save(deadlineRecord);
        }

        // Only delete after the DB write succeeds - if this worker crashes before reaching this
        // line, the message stays in the queue and SQS redelivers it once the visibility timeout
        // expires, so a crash mid-processing is retried rather than silently losing the reminder.
        deleteMessage(queueUrl, message);
    }

    private void deleteMessage(String queueUrl, Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
