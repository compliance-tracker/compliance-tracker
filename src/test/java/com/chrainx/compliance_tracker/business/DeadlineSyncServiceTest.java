package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void findDueSoonAndUnreminded_delegatesToRepositoryWithCorrectCutoff() {
        List<DeadlineRecord> expected = List.of(new DeadlineRecord());
        when(deadlineRecordRepository.findByReminderSentFalseAndDueDateLessThanEqual(LocalDate.of(2026, 8, 7)))
                .thenReturn(expected);

        List<DeadlineRecord> result = service.findDueSoonAndUnreminded(LocalDate.of(2026, 7, 24), 14);

        assertEquals(expected, result);
    }
}
