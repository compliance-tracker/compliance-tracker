package com.chrainx.compliance_tracker.queue;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.auth.User;
import com.chrainx.compliance_tracker.business.DeadlineRecordRepository;
import com.chrainx.compliance_tracker.business.DeadlineSyncService;
import com.chrainx.compliance_tracker.auth.UserRepository;
import com.chrainx.compliance_tracker.business.BusinessRepository;
import com.chrainx.compliance_tracker.security.EmailHasher;

import com.chrainx.compliance_tracker.rules.ObligationType;
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

    @Autowired
    private EmailHasher emailHasher;

    @Test
    void fullPipeline_syncThenDispatchThenWorker_marksReminderSent() {
        String ownerEmail = "reminder-worker-test-" + System.nanoTime() + "@example.com";
        User owner = new User();
        owner.setEmail(ownerEmail);
        // Issue #63: emailHash is now NOT NULL/UNIQUE at the DB level - a fixture User built
        // directly (bypassing AuthController.register, which is the only place this is normally
        // computed) needs to set it explicitly too.
        owner.setEmailHash(emailHasher.hash(ownerEmail));
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
        sqsDispatchService.dispatchDueSoonDeadlines();
        reminderWorkerService.pollAndProcess();

        // Issue #33 gave this business a second, unrelated deadline record (corporate income
        // tax, due nowhere near "today") alongside the ACRA one this test actually drives
        // through the pipeline - findAll() has no guaranteed row order (same gotcha already hit
        // elsewhere in this codebase, see CLAUDE.md), so filtering on obligationType too is what
        // actually picks the record this test means to assert on, not just "the business's
        // first record, whichever that happens to be."
        Optional<DeadlineRecord> record = deadlineRecordRepository
                .findAll()
                .stream()
                .filter(r -> r.getBusiness().getId().equals(business.getId())
                        && r.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst();

        assertTrue(record.isPresent());
        assertTrue(record.get().isReminderSent());
    }
}
