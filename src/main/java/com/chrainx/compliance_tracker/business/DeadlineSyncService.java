package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.logging.CorrelationIdSupport;
import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

// @Service: same idea as @Component (Spring creates and manages one instance, injectable
// elsewhere) - the different annotation name is purely a convention marking "this holds
// business logic", it has no functional difference from @Component here.
@Service
public class DeadlineSyncService {

    private final BusinessRepository businessRepository;
    private final WorkPassRepository workPassRepository;
    private final CustomObligationRepository customObligationRepository;
    private final DeadlineRecordRepository deadlineRecordRepository;
    private final RuleEngine ruleEngine;

    @Autowired
    public DeadlineSyncService(BusinessRepository businessRepository,
                                WorkPassRepository workPassRepository,
                                CustomObligationRepository customObligationRepository,
                                DeadlineRecordRepository deadlineRecordRepository,
                                RuleEngine ruleEngine) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
        this.customObligationRepository = customObligationRepository;
        this.deadlineRecordRepository = deadlineRecordRepository;
        this.ruleEngine = ruleEngine;
    }

    // @Scheduled(cron = ...): Spring calls this method automatically on the given schedule -
    // "second minute hour day-of-month month day-of-week". Below runs daily at 01:00
    // Singapore time specifically (zone = ...) - without it, Spring's default is the server's
    // own timezone, so a deployment outside SGT would run this job at the wrong Singapore-local
    // hour (issue #28: this is a Singapore-specific product, "today" must always mean Singapore's
    // today, for the job's trigger time as much as for the date math inside it).
    // Requires @EnableScheduling on the main application class, otherwise @Scheduled is a
    // no-op and this would silently never run.
    //
    // Recomputes every business's deadlines from scratch via RuleEngine each run (rather than
    // computing once at business-creation time), so results always reflect the business's
    // current data - e.g. if financialYearEnd is edited later, the next sync picks it up
    // automatically with no separate "update" logic needed.
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Singapore")
    public void syncDeadlines() {
        // Issue #51: this job runs on its own scheduled thread, never inside an HTTP request -
        // CorrelationIdFilter has nothing to attach to. Gives every log line this one run
        // produces (across every business it processes) a shared, greppable ID, the same way a
        // web request gets one.
        CorrelationIdSupport.runWithNewCorrelationId(this::doSyncDeadlines);
    }

    private void doSyncDeadlines() {
        LocalDate today = LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE);

        for (Business business : businessRepository.findAll()) {
            List<WorkPass> workPasses = workPassRepository.findByBusinessId(business.getId());
            List<CustomObligation> customObligations = customObligationRepository.findByBusinessId(business.getId());
            List<Deadline> computed = ruleEngine.computeDeadlines(business, workPasses, customObligations, today);

            for (Deadline deadline : computed) {
                // Issue #59: a custom obligation's own id is the real dedupe key - the plain
                // (business, obligationType, dueDate) key can't tell two different custom
                // obligations that happen to share a due date apart.
                boolean alreadyPersisted = deadline.getCustomObligationId() != null
                        ? deadlineRecordRepository.existsByCustomObligationIdAndDueDate(
                                deadline.getCustomObligationId(), deadline.getDueDate())
                        : deadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate(
                                business.getId(), deadline.getObligationType(), deadline.getDueDate());

                // Skip if it already exists - inserting again would create a duplicate row,
                // and re-saving a fresh one would wipe out reminderSent=true if a reminder
                // was already sent for it.
                if (!alreadyPersisted) {
                    DeadlineRecord record = new DeadlineRecord();
                    record.setBusiness(business);
                    record.setObligationType(deadline.getObligationType());
                    record.setDueDate(deadline.getDueDate());
                    if (deadline.getCustomObligationId() != null) {
                        record.setCustomObligation(customObligationRepository.getReferenceById(deadline.getCustomObligationId()));
                        record.setCustomName(deadline.getCustomName());
                    }
                    deadlineRecordRepository.save(record);
                }
            }
        }
    }

    // "Due soon" is now per-business (issue #53) - each DeadlineRecord's own business.leadTimeDays
    // decides how far ahead it counts as due soon, replacing the single global window every
    // business used to share. Filtered in Java, not SQL - a per-row cutoff computed from a
    // joined column isn't expressible as a plain Spring Data derived query, and this project's
    // scale doesn't need anything fancier (see findByReminderSentFalse's own comment).
    //
    // @Transactional(readOnly = true): Business is a lazy (@ManyToOne(fetch = FetchType.LAZY))
    // relationship on DeadlineRecord - without an open Hibernate session, accessing
    // record.getBusiness().getLeadTimeDays() below throws LazyInitializationException the
    // moment the filter runs, because a plain (non-transactional) repository call's session
    // closes as soon as findByReminderSentFalse() itself returns, before the .stream() that
    // reads each record's business ever executes. Found live running the real integration tests
    // (SqsDispatchIntegrationTest/ReminderWorkerIntegrationTest), not assumed - readOnly since
    // this method never writes anything.
    @Transactional(readOnly = true)
    public List<DeadlineRecord> findDueSoonAndUnreminded(LocalDate referenceDate) {
        return deadlineRecordRepository.findByReminderSentFalse().stream()
                .filter(record -> !record.getDueDate().isAfter(referenceDate.plusDays(record.getBusiness().getLeadTimeDays())))
                .toList();
    }
}
