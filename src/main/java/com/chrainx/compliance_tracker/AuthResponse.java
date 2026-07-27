package com.chrainx.compliance_tracker;

// `token` (the access token) keeps its original name deliberately, not renamed to `accessToken`
// - the existing frontend already reads response.token and stores it as-is; renaming would
// break that the moment this backend change merges, before any frontend follow-up lands.
// `refreshToken` is purely additive - an old client that's never heard of it just ignores the
// extra JSON field harmlessly.
public record AuthResponse(String token, String refreshToken) {
}
