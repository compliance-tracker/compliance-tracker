package com.chrainx.compliance_tracker.rules;

import com.chrainx.compliance_tracker.Business;
import com.chrainx.compliance_tracker.WorkPass;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

// @Component: tells Spring "create one instance of this and make it injectable elsewhere"
// (e.g. into BusinessController's constructor) - same mechanism as @RestController, just
// without any HTTP handling attached to it.
//
// Deliberately pure: no DB or HTTP dependency here, and referenceDate is passed in rather
// than read from LocalDate.now() internally, so every rule is a plain function of its inputs
// and fully deterministic to unit-test.
@Component
public class RuleEngine {

    public List<Deadline> computeDeadlines(Business business, List<WorkPass> workPasses, LocalDate referenceDate) {
        List<Deadline> deadlines = new ArrayList<>();

        // ACRA rule: due 7 months after Financial Year End (standard, non-listed company case
        // only - see README for the listed-company variant we're not modeling). FYE recurs on
        // the same month/day every year, so the deadline does too - nextAcraDeadline finds the
        // next upcoming occurrence rather than the single one-time date this year's FYE produces.
        deadlines.add(new Deadline(
                ObligationType.ACRA_ANNUAL_RETURN,
                nextAcraDeadline(business.getFinancialYearEnd(), referenceDate)
        ));

        // GST F5 rule only applies if the business is actually GST-registered.
        if (business.isGstRegistered()) {
            deadlines.add(new Deadline(
                    ObligationType.GST_F5,
                    calendarQuarterEnd(referenceDate).plusMonths(1)
            ));
        }

        // Work pass rule: one deadline per pass, since a business can have multiple employees
        // each holding their own pass with its own expiry date - unlike ACRA/GST, this isn't
        // a single deadline per business.
        for (WorkPass pass : workPasses) {
            deadlines.add(new Deadline(
                    ObligationType.WORK_PASS_RENEWAL,
                    pass.getExpiryDate()
            ));
        }

        return deadlines;
    }

    // financialYearEnd is stored as a single date, but it stands for "this business's FYE
    // falls on this month/day, every year" - so the ACRA deadline (FYE + 7 months) recurs
    // annually too, not just once. Starting from that one stored date's +7-months deadline,
    // keep advancing a year at a time until we land on the next one that hasn't passed yet
    // relative to referenceDate. Once a business's most recent deadline has come and gone,
    // this naturally rolls forward to next year's instead of forever returning the same date.
    private LocalDate nextAcraDeadline(LocalDate financialYearEnd, LocalDate referenceDate) {
        LocalDate deadline = financialYearEnd.plusMonths(7);
        while (deadline.isBefore(referenceDate)) {
            deadline = deadline.plusYears(1);
        }
        return deadline;
    }

    // Finds the end date of the calendar quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec) that
    // referenceDate falls in. E.g. Feb 15 -> Q1 -> March 31.
    private LocalDate calendarQuarterEnd(LocalDate referenceDate) {
        int quarterEndMonth = ((referenceDate.getMonthValue() - 1) / 3) * 3 + 3;
        return YearMonth.of(referenceDate.getYear(), quarterEndMonth).atEndOfMonth();
    }
}
