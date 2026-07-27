package com.chrainx.compliance_tracker.queue;
import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.auth.User;
import com.chrainx.compliance_tracker.business.DeadlineSyncService;
import com.chrainx.compliance_tracker.auth.UserRepository;
import com.chrainx.compliance_tracker.business.BusinessRepository;

import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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
//
// @ActiveProfiles("test") disables real @Scheduled jobs (see SchedulingConfig) - without it,
// ReminderWorkerService's real background poller (every 30s) could consume/delete this test's
// message before the assertion below runs, since @SpringBootTest boots the entire real app.
@SpringBootTest
@ActiveProfiles("test")
class SqsDispatchIntegrationTest {

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

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
        User owner = new User();
        owner.setEmail("sqs-dispatch-test-" + System.nanoTime() + "@example.com");
        owner.setPasswordHash("unused-in-this-test");
        userRepository.save(owner);

        Business business = new Business();
        business.setName("Integration Test Co");
        // ACRA rule is financialYearEnd + 7 months, so this makes today the ACRA due date.
        // Uses the app's own "today" (Singapore time, issue #28), not the test runner's default
        // zone - see ReminderWorkerIntegrationTest for why this must match.
        business.setFinancialYearEnd(LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE).minusMonths(7));
        business.setGstRegistered(false);
        business.setOwner(owner);
        businessRepository.save(business);

        deadlineSyncService.syncDeadlines();
        int dispatched = sqsDispatchService.dispatchDueSoonDeadlines(1);
        assertTrue(dispatched >= 1);

        String queueUrl = sqsClient.getQueueUrl(
                GetQueueUrlRequest.builder().queueName(queueName).build()
        ).queueUrl();

        // waitTimeSeconds(5) - long polling, matching ReminderWorkerService's real poll - not
        // short polling's default of 0. Regression fix for issue #75: SQS's documented
        // short-polling behavior only samples a subset of servers and doesn't guarantee
        // returning every currently-available message on one call, so a message dispatched
        // moments earlier could occasionally come back empty on the very next receive - flaky
        // in the genuine sense (failed for real, twice in a row, then passed 3/3 immediately
        // after with zero code changes), not a bug in the dispatch logic being tested.
        ReceiveMessageResponse response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder().queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(5).build()
        );

        assertFalse(response.messages().isEmpty());
    }
}
