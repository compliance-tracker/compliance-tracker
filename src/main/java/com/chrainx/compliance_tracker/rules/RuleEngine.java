package com.chrainx.compliance_tracker.rules;

import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.business.CustomObligation;
import com.chrainx.compliance_tracker.business.WorkPass;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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

    // Every deadline this app computes only ever means anything relative to Singapore's own
    // calendar - a business's FYE, GST quarter, and work pass expiry are all Singapore-local
    // concepts (issue #28). Every LocalDate.now(...) call anywhere in the app must use this,
    // not the JVM's default zone - otherwise a deployment on a server outside SGT (e.g. AWS's
    // us-east-1, running UTC) would silently roll deadlines over a day early or late right
    // around midnight SGT, exactly when it matters most. Defined once here, the domain-logic
    // home for "what is `today` for compliance purposes", rather than repeated as a literal
    // ZoneId.of("Asia/Singapore") at each call site where a typo could silently drift.
    public static final ZoneId SINGAPORE_TIME_ZONE = ZoneId.of("Asia/Singapore");

    public List<Deadline> computeDeadlines(Business business, List<WorkPass> workPasses,
                                            List<CustomObligation> customObligations, LocalDate referenceDate) {
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

        // Custom obligations (issue #59) - a one-off (recurrenceMonths null) just uses dueDate
        // as-is, even if it's already in the past (same as WORK_PASS_RENEWAL above: an overdue
        // deadline should keep showing, not silently disappear). A recurring one recomputes the
        // actual next occurrence from its fixed anchor date every time, the same
        // never-mutate-the-stored-date pattern nextAcraDeadline already uses for ACRA.
        for (CustomObligation obligation : customObligations) {
            LocalDate dueDate = obligation.getRecurrenceMonths() == null
                    ? obligation.getDueDate()
                    : nextRecurringDeadline(obligation.getDueDate(), obligation.getRecurrenceMonths(), referenceDate);
            deadlines.add(new Deadline(ObligationType.CUSTOM, dueDate, obligation.getName(), obligation.getId()));
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

    // Same shape as nextAcraDeadline above, generalized to a configurable month step instead of
    // a fixed 12 - a custom obligation's own recurrenceMonths (issue #59). anchorDate is never
    // mutated (same reasoning as financialYearEnd never being mutated for ACRA); this just keeps
    // adding one interval at a time until the result isn't before referenceDate.
    private LocalDate nextRecurringDeadline(LocalDate anchorDate, int recurrenceMonths, LocalDate referenceDate) {
        LocalDate deadline = anchorDate;
        while (deadline.isBefore(referenceDate)) {
            deadline = deadline.plusMonths(recurrenceMonths);
        }
        return deadline;
    }

    // Companies Act 1967 s.198 (+ ACRA's own FYE guidance, which cites the same 18-month
    // threshold for changing an FYE): a company's very first financial year - the one starting
    // at incorporation - may run up to 18 months before it needs ACRA's special approval; every
    // subsequent financial year is capped at 12 months instead (enforced implicitly here by
    // nextAcraDeadline's own year-at-a-time recurrence, not by this method).
    //
    // Deliberately a literal date subtraction, not a "nearest occurrence of this month/day"
    // search - an earlier version tried that (to avoid a *different* false-positive, see below)
    // and it was actually wrong: the nearest occurrence of any month/day is, by definition,
    // always within about 12 months of any starting date, so a check built that way could never
    // detect a genuine violation at all - caught by this method's own test suite failing, not
    // assumed. financialYearEnd here has to be read as the literal first FYE date being
    // declared (which is exactly what it is at the point this is actually called -
    // BusinessController.createBusiness only, a brand new business with no prior cycles yet, not
    // updateBusiness - see that method's own comment for why re-checking on every future edit
    // would be the wrong call).
    public boolean firstFinancialYearExceedsAcraLimit(LocalDate incorporationDate, LocalDate financialYearEnd) {
        return financialYearEnd.isAfter(incorporationDate.plusMonths(18));
    }

    // Finds the end date of the calendar quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec) that
    // referenceDate falls in. E.g. Feb 15 -> Q1 -> March 31.
    private LocalDate calendarQuarterEnd(LocalDate referenceDate) {
        int quarterEndMonth = ((referenceDate.getMonthValue() - 1) / 3) * 3 + 3;
        return YearMonth.of(referenceDate.getYear(), quarterEndMonth).atEndOfMonth();
    }
}
