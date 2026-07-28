package com.chrainx.compliance_tracker.business;

import java.time.LocalDate;

// Same reasoning as BusinessResponse (issue #46) - the API's own contract, not the JPA entity.
public record WorkPassResponse(Long id, String employeeName, LocalDate expiryDate) {

    public static WorkPassResponse from(WorkPass workPass) {
        return new WorkPassResponse(workPass.getId(), workPass.getEmployeeName(), workPass.getExpiryDate());
    }
}
