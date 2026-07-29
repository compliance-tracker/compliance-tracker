package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.logging.CorrelationIdFilter;
import com.chrainx.compliance_tracker.rules.ObligationType;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DeadlineSyncServiceTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final WorkPassRepository workPassRepository = mock(WorkPassRepository.class);
    private final DeadlineRecordRepository deadlineRecordRepository = mock(DeadlineRecordRepository.class);
    private final RuleEngine ruleEngine = new RuleEngine();

    private final DeadlineSyncService service =
            new DeadlineSyncService(businessRepository, workPassRepository, deadlineRecordRepository, ruleEngine);

    @Test
    void syncDeadlines_insertsNewRecord_whenNotAlreadyPersisted() {
        Business business = new Business();
        business.setId(1L);
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        when(businessRepository.findAll()).thenReturn(List.of(business));
        when(workPassRepository.findByBusinessId(1L)).thenReturn(Collections.emptyList());
        when(deadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate(anyLong(), any(), any()))
                .thenReturn(false);

        service.syncDeadlines();

        // Only ACRA applies here (not GST-registered, no work passes) -> exactly one insert.
        verify(deadlineRecordRepository, times(1)).save(any(DeadlineRecord.class));
    }

    @Test
    void syncDeadlines_skipsInsert_whenRecordAlreadyExists() {
        Business business = new Business();
        business.setId(1L);
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        when(businessRepository.findAll()).thenReturn(List.of(business));
        when(workPassRepository.findByBusinessId(1L)).thenReturn(Collections.emptyList());
        when(deadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate(anyLong(), any(), any()))
                .thenReturn(true);

        service.syncDeadlines();

        verify(deadlineRecordRepository, never()).save(any(DeadlineRecord.class));
    }

    // Issue #51: syncDeadlines runs on its own @Scheduled thread, never inside an HTTP request,
    // so CorrelationIdFilter never runs for it - CorrelationIdSupport is what gives this run's
    // own log lines a shared, greppable ID instead.
    @Test
    void syncDeadlines_setsACorrelationId_forTheDurationOfTheRun_andClearsItAfterwards() {
        when(businessRepository.findAll()).thenAnswer(invocation -> {
            assertNotNull(MDC.get(CorrelationIdFilter.MDC_KEY));
            return Collections.emptyList();
        });

        service.syncDeadlines();

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    // findDueSoonAndUnreminded now filters per-record against its own business's leadTimeDays
    // (issue #53), not one global cutoff applied to every record - these three tests replace the
    // old single "delegates with correct cutoff" test, which stopped making sense once the
    // cutoff became per-business rather than a single value the repository could apply in SQL.

    @Test
    void findDueSoonAndUnreminded_includesARecord_dueWithinItsOwnBusinesssLeadTime() {
        Business shortLeadBusiness = new Business();
        shortLeadBusiness.setLeadTimeDays(7);
        DeadlineRecord dueInSixDays = new DeadlineRecord();
        dueInSixDays.setBusiness(shortLeadBusiness);
        dueInSixDays.setDueDate(LocalDate.of(2026, 7, 30));

        when(deadlineRecordRepository.findByReminderSentFalse()).thenReturn(List.of(dueInSixDays));

        List<DeadlineRecord> result = service.findDueSoonAndUnreminded(LocalDate.of(2026, 7, 24));

        assertEquals(List.of(dueInSixDays), result);
    }

    @Test
    void findDueSoonAndUnreminded_excludesARecord_dueBeyondItsOwnBusinesssLeadTime() {
        Business shortLeadBusiness = new Business();
        shortLeadBusiness.setLeadTimeDays(7);
        DeadlineRecord dueInThirtyDays = new DeadlineRecord();
        dueInThirtyDays.setBusiness(shortLeadBusiness);
        dueInThirtyDays.setDueDate(LocalDate.of(2026, 8, 23));

        when(deadlineRecordRepository.findByReminderSentFalse()).thenReturn(List.of(dueInThirtyDays));

        List<DeadlineRecord> result = service.findDueSoonAndUnreminded(LocalDate.of(2026, 7, 24));

        assertEquals(List.of(), result);
    }

    @Test
    void findDueSoonAndUnreminded_usesEachRecordsOwnBusinessLeadTime_notASharedOne() {
        // The actual point of issue #53: two businesses with different leadTimeDays get
        // different "due soon" windows, evaluated independently within the same call.
        Business shortLeadBusiness = new Business();
        shortLeadBusiness.setLeadTimeDays(7);
        Business longLeadBusiness = new Business();
        longLeadBusiness.setLeadTimeDays(30);

        DeadlineRecord dueInThirtyDaysShortLead = new DeadlineRecord();
        dueInThirtyDaysShortLead.setBusiness(shortLeadBusiness);
        dueInThirtyDaysShortLead.setDueDate(LocalDate.of(2026, 8, 23));

        DeadlineRecord dueInThirtyDaysLongLead = new DeadlineRecord();
        dueInThirtyDaysLongLead.setBusiness(longLeadBusiness);
        dueInThirtyDaysLongLead.setDueDate(LocalDate.of(2026, 8, 23));

        when(deadlineRecordRepository.findByReminderSentFalse())
                .thenReturn(List.of(dueInThirtyDaysShortLead, dueInThirtyDaysLongLead));

        List<DeadlineRecord> result = service.findDueSoonAndUnreminded(LocalDate.of(2026, 7, 24));

        assertEquals(List.of(dueInThirtyDaysLongLead), result);
    }
}
