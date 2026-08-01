package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DismissedDeadlineRepository extends JpaRepository<DismissedDeadline, Long> {

    // Used both to render "what have I dismissed" (DeadlineDismissalController.getDismissed)
    // and to filter the live deadlines view (BusinessController.getDeadlines).
    List<DismissedDeadline> findByBusinessId(Long businessId);

    Optional<DismissedDeadline> findByIdAndBusinessId(Long id, Long businessId);

    // Split into a CUSTOM/non-CUSTOM pair rather than one null-safe method - mirrors
    // DeadlineRecordRepository's own existsByBusinessIdAndObligationTypeAndDueDate /
    // existsByCustomObligationIdAndDueDate split: a Spring Data derived query can't express
    // "customObligationId equals this value, OR is null" in a single method.
    Optional<DismissedDeadline> findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationIdIsNull(
            Long businessId, ObligationType obligationType, LocalDate dueDate);

    Optional<DismissedDeadline> findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationId(
            Long businessId, ObligationType obligationType, LocalDate dueDate, Long customObligationId);
}
