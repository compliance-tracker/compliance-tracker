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

    // The starting point for the scheduler/dispatcher's "what needs a reminder right now" query.
    // Deliberately not filtered by due date here (issue #53) - since each business now has its
    // own leadTimeDays, there's no single cutoff this query could apply; DeadlineSyncService
    // filters per-record against its own business's leadTimeDays instead.
    List<DeadlineRecord> findByReminderSentFalse();

    // Cleans up a stale, not-yet-reminded deadline after a business's financialYearEnd changes
    // (issue #30) - DeadlineSyncService's own dedupe check (existsByBusinessIdAndObligationType-
    // AndDueDate) only ever prevents re-inserting a deadline that's already correct; it has no
    // way to remove one that's now wrong because the FYE it was computed from changed. Without
    // this, a business that changes its FYE would end up with the OLD (now-incorrect) unreminded
    // ACRA record still sitting in the queue alongside the newly-synced correct one, and could
    // get reminded off the wrong due date. Scoped to reminderSentFalse only - an already-sent
    // reminder is historical record of something that genuinely happened and is left alone.
    void deleteByBusinessIdAndObligationTypeAndReminderSentFalse(Long businessId, ObligationType obligationType);
}
