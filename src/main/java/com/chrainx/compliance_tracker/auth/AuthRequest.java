package com.chrainx.compliance_tracker.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Shared by register and login (see AuthController.isTooWeak's own comment for why the password
// *strength* rule can't live here as an annotation - login must never reject an existing,
// pre-strength-check account's correct password). Format validation is different: an
// empty/malformed/absurdly long email is never valid input for either endpoint, so @NotBlank/
// @Email/@Size are safe here. Found live, not hypothetically - registering with an empty or
// malformed email previously reached AuthController.register unrejected and blew up as an
// unhandled 500 trying to actually send a verification email to that address (in the malformed
// case, only after a real SMTP round-trip failed). @Size(max = 254) is RFC 5321's own max total
// address length, not an arbitrary round number.
public record AuthRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String password
) {
}
