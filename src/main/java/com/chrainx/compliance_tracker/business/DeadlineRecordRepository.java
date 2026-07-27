package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DeadlineRecordRepository extends JpaRepository<DeadlineRecord, Long> {

    // Used by the sync job to check "have I already stored this exact deadline?" before
    // inserting, so reminderSent isn't reset back to false on every re-run.
    // NOTE: doesn't distinguish between multiple WorkPasses that happen to share the same
    // due date on the same business - same pragmatic simplification pattern as elsewhere in
    // this project (see README known limitations).
    boolean existsByBusinessIdAndObligationTypeAndDueDate(Long businessId, ObligationType obligationType, LocalDate dueDate);

    // The actual "what needs a reminder right now" query the scheduler/dispatcher reads from.
    List<DeadlineRecord> findByReminderSentFalseAndDueDateLessThanEqual(LocalDate cutoff);
}
