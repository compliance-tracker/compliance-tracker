package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

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

    private String name;

    private LocalDate financialYearEnd;

    private boolean gstRegistered;

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

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
}
