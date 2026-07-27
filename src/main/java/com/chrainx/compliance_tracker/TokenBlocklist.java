package com.chrainx.compliance_tracker;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// A JWT is stateless by design - the server never remembers which tokens exist, it just checks
// a signature and an expiry timestamp. That's exactly what makes "log out" hard: there's
// nothing to delete server-side, because nothing was ever stored server-side. This class is the
// deliberate, minimal exception - a small in-memory set of token strings that have been
// explicitly revoked (via POST /api/auth/logout, see AuthController), checked by
// JwtAuthenticationFilter on every request alongside the normal signature/expiry check.
//
// Revoking by exact token string (not by user/email) means logout is naturally per-session:
// if the same user is logged in on two devices, each login produced a different token (every
// token embeds a fresh issued-at timestamp before being signed), so logging out on one device
// only blocklists that device's token - the other keeps working. That's a deliberate choice,
// not an oversight; "log out everywhere" would be a different feature.
//
// Known limitation, same honesty as LoginRateLimiter: a revoked token is never actively
// evicted from this set - it just sits here until the app restarts. Since a token is only ever
// worth revoking while it would otherwise still be valid, this set can grow by at most "one
// entry per logout within the token's lifetime window" (currently 24h, see
// jwt.expiration-ms) - bounded, not unbounded, but a scheduled sweep (removing entries past
// their token's own expiry) would be the natural next step for a longer-lived deployment.
@Component
public class TokenBlocklist {

    private final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();

    public void revoke(String token) {
        revokedTokens.add(token);
    }

    public boolean isRevoked(String token) {
        return revokedTokens.contains(token);
    }
}
