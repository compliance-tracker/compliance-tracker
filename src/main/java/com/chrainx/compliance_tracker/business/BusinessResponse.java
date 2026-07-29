package com.chrainx.compliance_tracker.business;

import java.time.LocalDate;

// What the API actually returns for a Business - separate from the JPA entity (issue #46), so
// "what does this endpoint expose" is an explicit, reviewable decision at this record's
// definition, not something that has to be remembered every time a field is added to the
// entity. This already caused one near-miss: Business.owner needed @JsonIgnore or it would have
// serialized a whole User - including their password hash - straight into every response.
public record BusinessResponse(
        Long id, String name, LocalDate financialYearEnd, boolean gstRegistered, int leadTimeDays,
        LocalDate incorporationDate) {

    public static BusinessResponse from(Business business) {
        return new BusinessResponse(business.getId(), business.getName(), business.getFinancialYearEnd(),
                business.isGstRegistered(), business.getLeadTimeDays(), business.getIncorporationDate());
    }
}
