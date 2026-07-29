package com.chrainx.compliance_tracker.queue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - a real message sent directly to the real LocalStack DLQ
// (bypassing the redrive policy entirely, which would need 3 genuine ReminderWorkerService
// failures to trigger for real - this test only needs a message to already be sitting in the
// DLQ, not to prove the redrive path itself, which is already covered separately - see
// docs/architecture.md's "Dead-letter handling").
@SpringBootTest
@ActiveProfiles("test")
class DlqMonitorIntegrationTest {

    @Autowired
    private DlqMonitorService dlqMonitorService;

    @Autowired
    private SqsClient sqsClient;

    @Value("${aws.sqs.dlq-name}")
    private String dlqName;

    private String dlqUrl;

    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void cleanUp() {
        if (logAppender != null) {
            ((Logger) LoggerFactory.getLogger(DlqMonitorService.class)).detachAppender(logAppender);
        }
        // Drain whatever this test itself put in the real DLQ - leaving it behind would pollute
        // the shared LocalStack queue for later test runs, the exact same class of problem
        // issue #75 hit and documented (44 leftover messages breaking a later, otherwise-correct
        // test).
        if (dlqUrl != null) {
            List<Message> leftover = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(dlqUrl).maxNumberOfMessages(10).waitTimeSeconds(2).build()).messages();
            for (Message message : leftover) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(dlqUrl).receiptHandle(message.receiptHandle()).build());
            }
        }
    }

    @Test
    void checkDlqDepth_findsARealMessage_andLogsAWarning() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(DlqMonitorService.class)).addAppender(logAppender);

        dlqUrl = sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build()).queueUrl();
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(dlqUrl)
                .messageBody("{\"deadlineRecordId\":1,\"businessId\":1,"
                        + "\"obligationType\":\"ACRA_ANNUAL_RETURN\",\"dueDate\":\"2026-08-01\"}")
                .build());

        dlqMonitorService.checkDlqDepth();

        assertTrue(logAppender.list.stream().anyMatch(event ->
                event.getLevel() == Level.WARN && event.getFormattedMessage().contains(dlqName)));
    }
}
