package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;
import com.chrainx.compliance_tracker.rules.GstFilingFrequency;
import com.chrainx.compliance_tracker.security.EncryptedStringConverter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDate;

// This entity is never bound directly from a request or serialized directly into a response
// (issue #46) - BusinessRequest/BusinessResponse are the API's actual contract, this is purely
// the persistence shape. Bean Validation (@NotBlank/@NotNull, issue #20) lives on BusinessRequest
// now, not here - a client-supplied value is validated before it ever reaches this class.
@Entity
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Issue #63: encrypted at rest (AES-256-GCM, see EncryptedStringConverter). Never looked up
    // by exact match anywhere in this app (only ever fetched by id/ownerId), so unlike
    // User.email there's no need for a separate deterministic lookup hash here.
    @Convert(converter = EncryptedStringConverter.class)
    private String name;

    private LocalDate financialYearEnd;

    private boolean gstRegistered;

    // Issue #45 - IRAS actually supports monthly/quarterly/six-monthly GST accounting periods,
    // not just the quarterly default this app originally assumed unconditionally. Only means
    // anything when gstRegistered is true (same as the GST_F5 rule itself); defaults to
    // QUARTERLY for both a freshly-constructed entity in Java and, via the V15 migration's
    // column default, every row that existed before this field did - preserves every existing
    // business's real behavior exactly, nothing silently changes for anyone not using this.
    @Enumerated(EnumType.STRING)
    private GstFilingFrequency gstFilingFrequency = GstFilingFrequency.QUARTERLY;

    // How many days before a deadline SqsDispatchService should send a reminder for it (issue
    // #53) - used to be a single hardcoded 14 for every business. Defaults to 14 (matching the
    // old hardcoded behavior) for both a freshly-constructed entity in Java and, via the V8
    // migration's column default, every row that existed before this field did.
    private int leadTimeDays = 14;

    // When this business was incorporated (issue #31) - nullable, unlike every other field here,
    // since there's no honest default and plenty of existing businesses will never set it. Its
    // only current use is validating financialYearEnd against the Companies Act's first-year
    // 18-month cap (see BusinessController) - a business that never sets it just skips that
    // check, same as before this field existed.
    private LocalDate incorporationDate;

    // FetchType.LAZY + @JsonIgnore: the owning User (including their password hash) must never
    // be serialized into an API response - the frontend has no need to see it, and leaking a
    // password hash through a JSON response would be a real security bug, not just untidy.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @JsonIgnore
    private User owner;

    // Getters and setters — Spring/Hibernate needs these to read/write fields
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getFinancialYearEnd() { return financialYearEnd; }
    public void setFinancialYearEnd(LocalDate financialYearEnd) { this.financialYearEnd = financialYearEnd; }

    public boolean isGstRegistered() { return gstRegistered; }
    public void setGstRegistered(boolean gstRegistered) { this.gstRegistered = gstRegistered; }

    public GstFilingFrequency getGstFilingFrequency() { return gstFilingFrequency; }
    public void setGstFilingFrequency(GstFilingFrequency gstFilingFrequency) { this.gstFilingFrequency = gstFilingFrequency; }

    public int getLeadTimeDays() { return leadTimeDays; }
    public void setLeadTimeDays(int leadTimeDays) { this.leadTimeDays = leadTimeDays; }

    public LocalDate getIncorporationDate() { return incorporationDate; }
    public void setIncorporationDate(LocalDate incorporationDate) { this.incorporationDate = incorporationDate; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
}
