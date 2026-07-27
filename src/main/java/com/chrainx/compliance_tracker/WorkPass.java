package com.chrainx.compliance_tracker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

// Separate entity (not a field on Business) because one business can have many employees,
// each holding their own work pass with its own expiry date.
@Entity
public class WorkPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Same reasoning as Business's fields (issue #20) - checked on @Valid @RequestBody binding.
    @NotBlank
    private String employeeName;

    @NotNull
    private LocalDate expiryDate;

    // Many WorkPass rows can point to the same Business (many-to-one).
    // FetchType.LAZY: don't load the related Business from the DB until .getBusiness() is
    // actually called - avoids pulling in data we don't need every time a WorkPass loads.
    // @JsonIgnore: the caller already knows the business id from the URL path
    // (/api/businesses/{businessId}/work-passes) - nesting the full parent Business object in
    // every WorkPass response would just be redundant noise, not new information.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    @JsonIgnore
    private Business business;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmployeeName() { return employeeName; }
    public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
}
