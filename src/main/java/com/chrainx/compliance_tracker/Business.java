package com.chrainx.compliance_tracker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Entity
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Bean Validation (issue #20) - checked whenever a Business is bound from a @Valid
    // @RequestBody (create/update), not on every save - so id/owner above and gstRegistered
    // below stay unannotated, they're never client-supplied for a create (id is cleared
    // server-side, see #66) or don't need a "missing" case (a primitive boolean already can't
    // be null).
    @NotBlank
    private String name;

    @NotNull
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
