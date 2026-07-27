package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

// @Service: same idea as @Component (Spring creates and manages one instance, injectable
// elsewhere) - the different annotation name is purely a convention marking "this holds
// business logic", it has no functional difference from @Component here.
@Service
public class DeadlineSyncService {

    private final BusinessRepository businessRepository;
    private final WorkPassRepository workPassRepository;
    private final DeadlineRecordRepository deadlineRecordRepository;
    private final RuleEngine ruleEngine;

    @Autowired
    public DeadlineSyncService(BusinessRepository businessRepository,
                                WorkPassRepository workPassRepository,
                                DeadlineRecordRepository deadlineRecordRepository,
                                RuleEngine ruleEngine) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
        this.deadlineRecordRepository = deadlineRecordRepository;
        this.ruleEngine = ruleEngine;
    }

    // @Scheduled(cron = ...): Spring calls this method automatically on the given schedule -
    // "second minute hour day-of-month month day-of-week". Below runs daily at 01:00.
    // Requires @EnableScheduling on the main application class, otherwise @Scheduled is a
    // no-op and this would silently never run.
    //
    // Recomputes every business's deadlines from scratch via RuleEngine each run (rather than
    // computing once at business-creation time), so results always reflect the business's
    // current data - e.g. if financialYearEnd is edited later, the next sync picks it up
    // automatically with no separate "update" logic needed.
    @Scheduled(cron = "0 0 1 * * *")
    public void syncDeadlines() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Singapore"));

        for (Business business : businessRepository.findAll()) {
            List<WorkPass> workPasses = workPassRepository.findByBusinessId(business.getId());
            List<Deadline> computed = ruleEngine.computeDeadlines(business, workPasses, today);

            for (Deadline deadline : computed) {
                boolean alreadyPersisted = deadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate(
                        business.getId(), deadline.getObligationType(), deadline.getDueDate());

                // Skip if it already exists - inserting again would create a duplicate row,
                // and re-saving a fresh one would wipe out reminderSent=true if a reminder
                // was already sent for it.
                if (!alreadyPersisted) {
                    DeadlineRecord record = new DeadlineRecord();
                    record.setBusiness(business);
                    record.setObligationType(deadline.getObligationType());
                    record.setDueDate(deadline.getDueDate());
                    deadlineRecordRepository.save(record);
                }
            }
        }
    }

    public List<DeadlineRecord> findDueSoonAndUnreminded(LocalDate referenceDate, int daysAhead) {
        LocalDate cutoff = referenceDate.plusDays(daysAhead);
        return deadlineRecordRepository.findByReminderSentFalseAndDueDateLessThanEqual(cutoff);
    }
}
