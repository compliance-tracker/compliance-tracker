package com.chrainx.compliance_tracker;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Simple in-memory fixed-window counter, keyed by client IP - no new dependency (no Redis,
// no Bucket4j) since this is a single instance, and the whole point is stopping repeated
// password guesses, not precise traffic shaping. Deliberately per-IP rather than per-email:
// AuthController already gives the same 401 for "wrong password" and "no such account" to
// avoid leaking which emails exist, and rate limiting by email would reveal exactly that
// (attempts against a real account get blocked sooner than a made-up one).
//
// Known limitation: a stale entry for an IP that never comes back is never actively evicted,
// only lazily reset the next time that same IP is seen after its window expires - unbounded
// memory growth is theoretically possible from many distinct IPs. Not a concern at this
// project's scale (single instance, no load balancer), but would need a scheduled sweep or a
// distributed store (Redis) before this held up in a real multi-instance deployment.
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private record Attempts(int count, Instant windowStart) {
        boolean windowExpired() {
            return Duration.between(windowStart, Instant.now()).compareTo(WINDOW) > 0;
        }
    }

    private final ConcurrentMap<String, Attempts> attemptsByIp = new ConcurrentHashMap<>();

    public boolean isBlocked(String ip) {
        Attempts attempts = attemptsByIp.get(ip);
        return attempts != null && !attempts.windowExpired() && attempts.count() >= MAX_FAILED_ATTEMPTS;
    }

    public void recordFailure(String ip) {
        attemptsByIp.compute(ip, (key, existing) -> {
            if (existing == null || existing.windowExpired()) {
                return new Attempts(1, Instant.now());
            }
            return new Attempts(existing.count() + 1, existing.windowStart());
        });
    }

    // A successful login clears the counter - a legitimate user who mistyped their password a
    // few times shouldn't stay throttled after they get it right.
    public void recordSuccess(String ip) {
        attemptsByIp.remove(ip);
    }
}
