package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Issue #59: the dedupe key above can't distinguish two different custom obligations that
    // happen to share the same business and due date - customObligationId is the real
    // disambiguator for ObligationType.CUSTOM records specifically.
    boolean existsByCustomObligationIdAndDueDate(Long customObligationId, LocalDate dueDate);

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

    // Same reasoning as the method above, for a custom obligation's own dueDate/recurrenceMonths
    // changing via CustomObligationController.updateCustomObligation (issue #59) - without this,
    // an edited obligation would keep its old, now-stale unreminded DeadlineRecord sitting
    // around, and the next sync's dedupe check (existsByCustomObligationIdAndDueDate) would
    // insert the new one alongside it rather than replacing it.
    void deleteByCustomObligationIdAndReminderSentFalse(Long customObligationId);

    // Issue #57: every record this app has ever persisted for a business, past and future,
    // reminded and not - the actual historical audit trail. Unlike GET /businesses/{id}/deadlines
    // (which only ever shows a recurring obligation's *next* occurrence, since RuleEngine
    // computes forward from "today"), this surfaces every past occurrence a sync run ever
    // persisted a real row for. Newest-due-date-first, matching "what did we file, most
    // recently" being the natural first question to ask.
    Page<DeadlineRecord> findByBusinessIdOrderByDueDateDesc(Long businessId, Pageable pageable);
}
