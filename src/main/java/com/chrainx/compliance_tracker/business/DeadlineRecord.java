package com.chrainx.compliance_tracker.business;

import com.chrainx.compliance_tracker.rules.ObligationType;
import jakarta.persistence.*;

import java.time.LocalDate;

// Persisted counterpart to rules.Deadline. rules.Deadline stays a pure, in-memory computation
// result (no DB knowledge); this entity exists because the scheduler needs actual state -
// specifically reminderSent - which a value freshly recomputed from RuleEngine every time
// can never carry.
@Entity
public class DeadlineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    // @Enumerated(STRING): store the enum's name ("ACRA_ANNUAL_RETURN") in the DB column,
    // not its ordinal position (0, 1, 2). Ordinal storage silently breaks if enum values are
    // ever reordered/inserted later - STRING is the safe default.
    @Enumerated(EnumType.STRING)
    private ObligationType obligationType;

    private LocalDate dueDate;

    private boolean reminderSent = false;

    // Both null except for ObligationType.CUSTOM (issue #59) - see rules.Deadline's own comment
    // for why both are needed. customObligation is LAZY same as business - a plain @ManyToOne
    // FK, not owned/cascaded from this side (custom_obligation is what cascades onto this table
    // via the DB's own ON DELETE CASCADE, not the other way around).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_obligation_id")
    private CustomObligation customObligation;

    private String customName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }

    public ObligationType getObligationType() { return obligationType; }
    public void setObligationType(ObligationType obligationType) { this.obligationType = obligationType; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public boolean isReminderSent() { return reminderSent; }
    public void setReminderSent(boolean reminderSent) { this.reminderSent = reminderSent; }

    public CustomObligation getCustomObligation() { return customObligation; }
    public void setCustomObligation(CustomObligation customObligation) { this.customObligation = customObligation; }

    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }
}
