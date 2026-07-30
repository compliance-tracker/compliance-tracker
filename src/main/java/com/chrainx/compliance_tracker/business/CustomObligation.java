package com.chrainx.compliance_tracker.business;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDate;

// Issue #59: a business's own compliance items beyond the 3 built-in ones (ACRA/GST/work pass).
// dueDate is the anchor date, same role as Business.financialYearEnd for the ACRA rule - never
// mutated automatically. recurrenceMonths is null for a one-off obligation (the user re-edits
// dueDate themselves once it's handled, same as WorkPass.expiryDate); when set, RuleEngine
// recomputes the actual next-due date live from this anchor + the interval, the same pattern
// RuleEngine.nextAcraDeadline already uses for FYE + 7 months recurring annually - never
// persisted back onto dueDate itself.
//
// Never bound directly from a request or serialized directly into a response (issue #46) -
// CustomObligationRequest/CustomObligationResponse are the API's actual contract.
@Entity
public class CustomObligation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private LocalDate dueDate;

    private Integer recurrenceMonths;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    @JsonIgnore
    private Business business;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public Integer getRecurrenceMonths() { return recurrenceMonths; }
    public void setRecurrenceMonths(Integer recurrenceMonths) { this.recurrenceMonths = recurrenceMonths; }

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
}
