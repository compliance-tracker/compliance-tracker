package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Real end-to-end pipeline check: sync -> dispatch -> worker, all against the real Spring
// context (real Postgres, real LocalStack SQS). Proves a business with a due-today deadline
// ends up with reminderSent=true after the worker actually processes the real queue message -
// not just that each piece works in mocked isolation.
//
// @ActiveProfiles("test") disables the real background poller (see SchedulingConfig) so this
// test's own explicit pollAndProcess() call below is the only thing consuming the queue -
// otherwise the real scheduled poller could race it and consume the message first.
@SpringBootTest
@ActiveProfiles("test")
class ReminderWorkerIntegrationTest {

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeadlineSyncService deadlineSyncService;

    @Autowired
    private SqsDispatchService sqsDispatchService;

    @Autowired
    private DeadlineRecordRepository deadlineRecordRepository;

    @Autowired
    private ReminderWorkerService reminderWorkerService;

    @Test
    void fullPipeline_syncThenDispatchThenWorker_marksReminderSent() {
        User owner = new User();
        owner.setEmail("reminder-worker-test-" + System.nanoTime() + "@example.com");
        owner.setPasswordHash("unused-in-this-test");
        userRepository.save(owner);

        Business business = new Business();
        business.setName("Worker Integration Test Co");
        // Uses the app's own "today" (Singapore time, issue #28), not the test runner's default
        // zone - the app now computes deadlines relative to RuleEngine.SINGAPORE_TIME_ZONE, so
        // this must match or the two can disagree about whether today is actually the due date,
        // particularly for the ~8 hours/day where the UTC calendar date and the SGT one differ.
        business.setFinancialYearEnd(LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE).minusMonths(7));
        business.setGstRegistered(false);
        business.setOwner(owner);
        businessRepository.save(business);

        deadlineSyncService.syncDeadlines();
        sqsDispatchService.dispatchDueSoonDeadlines(1);
        reminderWorkerService.pollAndProcess();

        Optional<DeadlineRecord> record = deadlineRecordRepository
                .findAll()
                .stream()
                .filter(r -> r.getBusiness().getId().equals(business.getId()))
                .findFirst();

        assertTrue(record.isPresent());
        assertTrue(record.get().isReminderSent());
    }
}
