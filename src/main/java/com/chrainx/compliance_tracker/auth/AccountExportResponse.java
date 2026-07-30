package com.chrainx.compliance_tracker.auth;

import com.chrainx.compliance_tracker.business.BusinessResponse;
import com.chrainx.compliance_tracker.business.CustomObligationResponse;
import com.chrainx.compliance_tracker.business.WorkPassResponse;

import java.util.List;

// Issue #48 (PDPA compliance review, Access & Correction Obligation): "everything this account
// has given us" as one real, downloadable JSON document - the whole account owner's own data,
// not paginated (a real export needs to actually be complete, not one page of it).
public record AccountExportResponse(String email, boolean emailVerified, List<BusinessExport> businesses) {

    public record BusinessExport(
            BusinessResponse business,
            List<WorkPassResponse> workPasses,
            List<CustomObligationResponse> customObligations
    ) {
    }
}
