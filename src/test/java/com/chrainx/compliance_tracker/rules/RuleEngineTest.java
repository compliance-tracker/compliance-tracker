package com.chrainx.compliance_tracker.rules;

import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.business.CustomObligation;
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

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 1, 1));

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

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 1, 1));

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

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 1, 1));

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

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 7, 31));

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
        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 2, 15));

        Deadline gst = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.GST_F5)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 4, 30), gst.getDueDate());
    }

    @Test
    void gstRegisteredBusiness_defaultsToQuarterlyFiling_whenNeverSetExplicitly() {
        // A freshly-constructed Business (no explicit setGstFilingFrequency call) must behave
        // exactly like every business that existed before issue #45 added this field at all.
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(true);

        assertEquals(GstFilingFrequency.QUARTERLY, business.getGstFilingFrequency());
    }

    @Test
    void monthlyGstFiler_getsGstDeadline_oneMonthAfterCalendarMonthEnd() {
        // Issue #45 - IRAS's actual rule: a return/payment is due exactly one month after the
        // end of the accounting period, whatever that period's own length is.
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(true);
        business.setGstFilingFrequency(GstFilingFrequency.MONTHLY);

        // Reference date is in February -> month end 2026-02-28 -> deadline 2026-03-28
        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 2, 15));

        Deadline gst = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.GST_F5)
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 3, 28), gst.getDueDate());
    }

    @Test
    void nonGstRegisteredBusiness_hasNoGstDeadline() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, Collections.emptyList(), Collections.emptyList(), LocalDate.of(2026, 2, 15));

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

        List<Deadline> deadlines = ruleEngine.computeDeadlines(business, List.of(pass1, pass2), Collections.emptyList(), LocalDate.of(2026, 2, 15));

        List<LocalDate> workPassDueDates = deadlines.stream()
                .filter(d -> d.getObligationType() == ObligationType.WORK_PASS_RENEWAL)
                .map(Deadline::getDueDate)
                .toList();

        assertEquals(2, workPassDueDates.size());
        assertTrue(workPassDueDates.contains(LocalDate.of(2026, 9, 1)));
        assertTrue(workPassDueDates.contains(LocalDate.of(2027, 3, 15)));
    }

    // firstFinancialYearExceedsAcraLimit (issue #31) - sourced from Companies Act 1967 s.198 +
    // ACRA's FYE guidance: a company's first financial year may run up to 18 months from
    // incorporation before needing ACRA's special approval.

    @Test
    void firstFinancialYear_withinEighteenMonths_doesNotExceedTheLimit() {
        // Incorporated 2026-01-15, first FYE 2026-12-31 - about 11.5 months, a completely
        // ordinary choice.
        assertFalse(ruleEngine.firstFinancialYearExceedsAcraLimit(
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void firstFinancialYear_exactlyEighteenMonths_doesNotExceedTheLimit() {
        // The boundary itself is allowed - only strictly *more* than 18 months needs approval.
        LocalDate incorporationDate = LocalDate.of(2026, 1, 15);
        assertFalse(ruleEngine.firstFinancialYearExceedsAcraLimit(
                incorporationDate, incorporationDate.plusMonths(18)));
    }

    @Test
    void firstFinancialYear_beyondEighteenMonths_exceedsTheLimit() {
        // Incorporated 2026-01-15, "first" FYE claimed as 2027-12-31 - almost 24 months, well
        // past what ACRA allows without special approval.
        assertTrue(ruleEngine.firstFinancialYearExceedsAcraLimit(
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 12, 31)));
    }

    @Test
    void firstFinancialYear_oneDayBeyondEighteenMonths_exceedsTheLimit() {
        LocalDate incorporationDate = LocalDate.of(2026, 1, 15);
        assertTrue(ruleEngine.firstFinancialYearExceedsAcraLimit(
                incorporationDate, incorporationDate.plusMonths(18).plusDays(1)));
    }

    // A "nearest occurrence of this month/day" version of this method was tried first, to avoid
    // a *different* false positive (a years-old business's stored financialYearEnd could hold
    // any past/future year, since only its month/day is ever actually used elsewhere in the
    // app). That version was itself wrong: the nearest occurrence of any month/day is always
    // within ~12 months of any starting date, so it could never register an 18-month violation
    // at all - caught by this exact test failing against that implementation, not spotted by
    // inspection. This method is only ever called from createBusiness (see that method's own
    // comment) - a brand new business, where financialYearEnd unambiguously *is* the literal
    // first FYE being declared, not some later cycle's date - so literal subtraction is correct
    // there, not a bug needing "nearest occurrence" protection against a scenario that can't
    // arise at the one call site that exists.
    @Test
    void financialYearEndFarInTheFuture_correctlyExceedsTheLimit() {
        assertTrue(ruleEngine.firstFinancialYearExceedsAcraLimit(
                LocalDate.of(2021, 1, 1), LocalDate.of(2029, 12, 31)));
    }

    // Custom obligations (issue #59).

    private CustomObligation customObligation(Long id, String name, LocalDate dueDate, Integer recurrenceMonths) {
        CustomObligation obligation = new CustomObligation();
        obligation.setId(id);
        obligation.setName(name);
        obligation.setDueDate(dueDate);
        obligation.setRecurrenceMonths(recurrenceMonths);
        return obligation;
    }

    @Test
    void oneOffCustomObligation_usesItsOwnDueDate_unchanged() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        CustomObligation obligation = customObligation(1L, "Renew business insurance", LocalDate.of(2026, 9, 1), null);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, Collections.emptyList(), List.of(obligation), LocalDate.of(2026, 2, 15));

        Deadline custom = deadlines.stream().filter(d -> d.getObligationType() == ObligationType.CUSTOM).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 1), custom.getDueDate());
        assertEquals("Renew business insurance", custom.getCustomName());
        assertEquals(1L, custom.getCustomObligationId());
    }

    @Test
    void oneOffCustomObligation_thatHasAlreadyPassed_keepsShowingItsOriginalDueDate() {
        // Same "an overdue deadline stays visible, not silently hidden" behavior as
        // WORK_PASS_RENEWAL already has - a one-off custom obligation has no recurrence to roll
        // forward to, so unlike ACRA it must NOT advance to some other date.
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        CustomObligation obligation = customObligation(1L, "Submit annual license renewal", LocalDate.of(2026, 1, 1), null);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, Collections.emptyList(), List.of(obligation), LocalDate.of(2026, 6, 1));

        Deadline custom = deadlines.stream().filter(d -> d.getObligationType() == ObligationType.CUSTOM).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 1), custom.getDueDate());
    }

    @Test
    void recurringCustomObligation_rollsForwardByItsOwnIntervalInMonths_notYears() {
        // Anchor 2025-01-15, every 3 months - by 2026-02-01, the next unpassed occurrence is
        // 2026-04-15 (2025-01-15 -> 04-15 -> 07-15 -> 10-15 -> 2026-01-15 -> 2026-04-15).
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        CustomObligation obligation = customObligation(1L, "Quarterly safety inspection", LocalDate.of(2025, 1, 15), 3);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, Collections.emptyList(), List.of(obligation), LocalDate.of(2026, 2, 1));

        Deadline custom = deadlines.stream().filter(d -> d.getObligationType() == ObligationType.CUSTOM).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 4, 15), custom.getDueDate());
    }

    @Test
    void recurringCustomObligation_dueInTheFuture_isNotRolledForward() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        CustomObligation obligation = customObligation(1L, "Annual policy review", LocalDate.of(2026, 11, 1), 12);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, Collections.emptyList(), List.of(obligation), LocalDate.of(2026, 2, 15));

        Deadline custom = deadlines.stream().filter(d -> d.getObligationType() == ObligationType.CUSTOM).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 1), custom.getDueDate());
    }

    @Test
    void multipleCustomObligations_eachProduceTheirOwnDeadline() {
        Business business = new Business();
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        CustomObligation insurance = customObligation(1L, "Renew business insurance", LocalDate.of(2026, 9, 1), null);
        CustomObligation license = customObligation(2L, "Submit annual license renewal", LocalDate.of(2026, 10, 1), null);

        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business, Collections.emptyList(), List.of(insurance, license), LocalDate.of(2026, 2, 15));

        List<Deadline> customDeadlines = deadlines.stream().filter(d -> d.getObligationType() == ObligationType.CUSTOM).toList();
        assertEquals(2, customDeadlines.size());
        assertTrue(customDeadlines.stream().anyMatch(d -> d.getCustomObligationId().equals(1L) && d.getCustomName().equals("Renew business insurance")));
        assertTrue(customDeadlines.stream().anyMatch(d -> d.getCustomObligationId().equals(2L) && d.getCustomName().equals("Submit annual license renewal")));
    }
}
