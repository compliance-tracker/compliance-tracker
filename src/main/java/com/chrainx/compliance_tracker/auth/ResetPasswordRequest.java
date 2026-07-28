package com.chrainx.compliance_tracker.auth;

public record ResetPasswordRequest(String token, String newPassword) {
}
