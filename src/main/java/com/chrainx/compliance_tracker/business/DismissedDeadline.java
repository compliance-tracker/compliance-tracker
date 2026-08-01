package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

// Issue #34: lets a user manually mark a specific deadline as handled, independent of
// DeadlineRecord.reminderSent (which only ever means "the automated reminder pipeline actually
// sent this one", not "a human already dealt with it"). Matched by natural key rather than a
// DeadlineRecord id, since GET /businesses/{id}/deadlines is entirely live-computed by
// RuleEngine with no persisted row/id of its own to reference - a DeadlineRecord for what's on
// screen may not even exist yet, since the sync job that creates them only runs once a day (see
// DeadlineRecord's own comment on why rules.Deadline stays a pure computation).
@Entity
public class DismissedDeadline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    @Enumerated(EnumType.STRING)
    private ObligationType obligationType;

    private LocalDate dueDate;

    // Both null except for ObligationType.CUSTOM - same disambiguator DeadlineRecord's own
    // customObligation/customName pair already uses, since two different custom obligations on
    // the same business can share a due date.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_obligation_id")
    private CustomObligation customObligation;

    private String customName;

    private Instant dismissedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }

    public ObligationType getObligationType() { return obligationType; }
    public void setObligationType(ObligationType obligationType) { this.obligationType = obligationType; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public CustomObligation getCustomObligation() { return customObligation; }
    public void setCustomObligation(CustomObligation customObligation) { this.customObligation = customObligation; }

    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }

    public Instant getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(Instant dismissedAt) { this.dismissedAt = dismissedAt; }
}
