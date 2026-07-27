package com.chrainx.compliance_tracker.rules;

import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.business.WorkPass;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private final RuleEngine ruleEngine = new RuleEngine();

    @Test
    void singaporeTimeZone_isTheRealAsiaSingaporeZone_atUtcPlusEight() {
        // Regression test for issue #28: guards the one constant every LocalDate.now(...) call
        // site in the app (BusinessController, DeadlineSyncService, SqsDispatchService) relies
        // on for "what is today" - a typo'd zone ID here would silently reintroduce the exact
        // server-default-timezone bug this constant exists to prevent, with nothing else in the
        // app able to catch it (it's just a String until resolved).
        assertEquals("Asia/Singapore", RuleEngine.SINGAPORE_TIME_ZONE.getId());
        assertEquals(ZoneOffset.ofHours(8), RuleEngine.SINGAPORE_TIME_ZONE.getRules().getOffset(java.time.Instant.now()));
    }

    @Test
    void computesAcraDeadline_sevenMonthsAfterFinancialYearEnd() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 1, 1));

        Deadline acra = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2027, 7, 31), acra.getDueDate());
    }

    @Test
    void acraDeadline_rollsForwardToNextYear_oncePassed() {
        // Regression test for issue #27: financialYearEnd + 7 months is a single fixed date -
        // once that date has passed, the deadline must roll forward to next year's occurrence
        // instead of staying stuck on the same date forever.
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2024, 12, 31)); // deadline: 2025-07-31
        business.setGstRegistered(false);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 1, 1));

        Deadline acra = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst()
                .orElseThrow();

        // 2025-07-31 already passed by 2026-01-01, so the next occurrence is 2026-07-31, not
        // the original 2025-07-31 and not a jump straight to 2027.
        assertEquals(LocalDate.of(2026, 7, 31), acra.getDueDate());
    }

    @Test
    void acraDeadline_rollsForwardMultipleYears_ifFinancialYearEndIsOld() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2020, 12, 31)); // deadline: 2021-07-31

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 1, 1));

        Deadline acra = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 7, 31), acra.getDueDate());
    }

    @Test
    void acraDeadline_dueToday_isNotRolledForward() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2025, 12, 31)); // deadline: 2026-07-31

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 7, 31));

        Deadline acra = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 7, 31), acra.getDueDate());
    }

    @Test
    void gstRegisteredBusiness_getsGstDeadline_oneMonthAfterCalendarQuarterEnd() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(true);

        // Reference date falls in Q1 (Jan-Mar) -> quarter end 2026-03-31 -> deadline 2026-04-30
        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 2, 15));

        Deadline gst = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.GST_F5)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 4, 30), gst.getDueDate());
    }

    @Test
    void nonGstRegisteredBusiness_hasNoGstDeadline() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), LocalDate.of(2026, 2, 15));

        assertFalse(deadlines.stream().anyMatch(d -> d.getObligationType() == ObligationType.GST_F5));
        assertTrue(deadlines.stream().anyMatch(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN));
    }

    @Test
    void eachWorkPass_producesItsOwnRenewalDeadline_equalToItsExpiryDate() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        WorkPass pass1 = new WorkPass();
        pass1.setExpiryDate(LocalDate.of(2026, 9, 1));

        WorkPass pass2 = new WorkPass();
        pass2.setExpiryDate(LocalDate.of(2027, 3, 15));

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, List.of(pass1, pass2), LocalDate.of(2026, 2, 15));

        List<LocalDate> workPassDueDates = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.WORK_PASS_RENEWAL)
                .map(Deadline::getDueDate)
                .toList();

        assertEquals(2, workPassDueDates.size());
        assertTrue(workPassDueDates.contains(LocalDate.of(2026, 9, 1)));
        assertTrue(workPassDueDates.contains(LocalDate.of(2027, 3, 15)));
    }
}
