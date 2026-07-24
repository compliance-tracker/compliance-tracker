package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real end-to-end check, not mocked: boots the actual Spring context (real Postgres, real
// LocalStack SQS via application.properties), creates a business whose ACRA deadline falls
// due today, runs the real sync + dispatch, then reads the real queue back to confirm a
// message actually landed - proving the wiring works, not just the mocked logic.
@SpringBootTest
class SqsDispatchIntegrationTest {

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private DeadlineSyncService deadlineSyncService;

    @Autowired
    private SqsDispatchService sqsDispatchService;

    @Autowired
    private SqsClient sqsClient;

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    @Test
    void businessWithDeadlineDueToday_producesRealSqsMessage() {
        Business business = new Business();
        business.setName("Integration Test Co");
        // ACRA rule is financialYearEnd + 7 months, so this makes today the ACRA due date.
        business.setFinancialYearEnd(LocalDate.now().minusMonths(7));
        business.setGstRegistered(false);
        businessRepository.save(business);

        deadlineSyncService.syncDeadlines();
        int dispatched = sqsDispatchService.dispatchDueSoonDeadlines(1);
        assertTrue(dispatched >= 1);

        String queueUrl = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(queueName).build()
        ).queueUrl();

        ReceiveMessageResponse response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder().queueUrl(queueUrl).maxNumberOfMessages(10).build()
        );

        assertFalse(response.messages().isEmpty());
    }
}
