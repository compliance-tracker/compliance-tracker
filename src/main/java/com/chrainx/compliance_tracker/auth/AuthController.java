package com.chrainx.compliance_tracker.auth;

import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.notifications.AuthEmailSender;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final TokenBlocklist tokenBlocklist;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final AuthEmailSender authEmailSender;
    private final long passwordResetExpirationMs;
    private final long emailVerificationExpirationMs;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                           LoginRateLimiter loginRateLimiter, TokenBlocklist tokenBlocklist,
                           PasswordResetTokenRepository passwordResetTokenRepository,
                           EmailVerificationTokenRepository emailVerificationTokenRepository,
                           AuthEmailSender authEmailSender,
                           @Value("${auth.password-reset-expiration-ms}") long passwordResetExpirationMs,
                           @Value("${auth.email-verification-expiration-ms}") long emailVerificationExpirationMs) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.tokenBlocklist = tokenBlocklist;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.authEmailSender = authEmailSender;
        this.passwordResetExpirationMs = passwordResetExpirationMs;
        this.emailVerificationExpirationMs = emailVerificationExpirationMs;
    }

    // Deliberately NOT @Transactional, unlike forgotPassword/resetPassword/verifyEmail below -
    // tried it, and it broke issue #42's registration-race handling. With @Transactional here,
    // userRepository.save(user) no longer runs in its own transaction (Spring Data JPA's
    // SimpleJpaRepository methods are @Transactional themselves, but nested calls join the
    // caller's existing transaction rather than opening a new one) - so Hibernate can defer the
    // actual INSERT (and its unique-constraint check) until the transaction commits, which
    // happens in the @Transactional proxy *after* this method body already returned, past the
    // try/catch entirely. The result: the #42 concurrency test started failing both requests
    // instead of splitting cleanly into one 200 + one 409. Found live re-running that exact
    // test after adding @Transactional here, not assumed - removed it once the failure pointed
    // straight at the change. register doesn't need it anyway (no derived delete query here).
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        if (isTooWeak(request.password())) {
            return ResponseEntity.status(400).body(new ApiError("BAD_REQUEST",
                    "Password must be at least 8 characters and include a letter and a digit."));
        }

        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(409).body(new ApiError("CONFLICT", "An account with this email already exists."));
        }

        User user = new User();
        user.setEmail(request.email());
        // Only the hash is ever stored - passwordEncoder.matches() at login time compares a
        // freshly-hashed attempt against this, the raw password itself is never persisted.
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // The findByEmail check above isn't atomic with this save - two concurrent
            // registration requests for the same email can both pass that check before either
            // commits, and the DB's unique constraint on email (the actual enforcement point)
            // rejects the second insert. Without this catch, that surfaces as an unhandled
            // exception (a 500) instead of the same clean 409 the sequential-request case
            // already returns above.
            return ResponseEntity.status(409).body(new ApiError("CONFLICT", "An account with this email already exists."));
        }

        // Fires and forgets a verification email (issue #36) - deliberately doesn't block or
        // gate the response on this. The account is real and immediately usable either way;
        // email ownership is proven separately, on its own time, not a precondition for using
        // the app at all right now (see User.emailVerified's own comment).
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.setToken(UUID.randomUUID().toString());
        verificationToken.setUserId(user.getId());
        verificationToken.setExpiresAt(Instant.now().plusMillis(emailVerificationExpirationMs));
        emailVerificationTokenRepository.save(verificationToken);
        authEmailSender.sendVerificationEmail(user.getEmail(), verificationToken.getToken());

        return ResponseEntity.ok(new AuthResponse(
                jwtService.generateAccessToken(user.getEmail()), jwtService.generateRefreshToken(user.getEmail())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();

        // Checked before touching the DB at all - once an IP has hit the limit, every further
        // attempt short-circuits here regardless of whether the credentials would've been right.
        if (loginRateLimiter.isBlocked(clientIp)) {
            return ResponseEntity.status(429).body(new ApiError("TOO_MANY_REQUESTS",
                    "Too many failed login attempts. Try again in a minute."));
        }

        var user = userRepository.findByEmail(request.email()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Deliberately the same error for "no such user" and "wrong password" - revealing
            // which one it was would let an attacker enumerate which emails have accounts.
            loginRateLimiter.recordFailure(clientIp);
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Incorrect email or password."));
        }

        loginRateLimiter.recordSuccess(clientIp);
        return ResponseEntity.ok(new AuthResponse(
                jwtService.generateAccessToken(user.getEmail()), jwtService.generateRefreshToken(user.getEmail())));
    }

    // Exchanges a valid, not-yet-used refresh token for a brand new access + refresh token pair
    // (issue #26) - lets a session keep going past the access token's own expiry without the
    // user logging in again. The refresh token is single-use: rotated (revoked, then replaced)
    // on every successful call, so a leaked refresh token can be ridden at most once before
    // reuse fails outright, rather than remaining valid indefinitely until its own expiry.
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest httpRequest) {
        String refreshToken = extractBearerToken(httpRequest);
        if (refreshToken == null) {
            return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Missing or malformed Authorization header."));
        }

        if (tokenBlocklist.isRevoked(refreshToken) || !jwtService.isRefreshToken(refreshToken)) {
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired refresh token."));
        }

        String email = jwtService.extractEmail(refreshToken);
        if (email == null) {
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired refresh token."));
        }

        var user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // The account this refresh token was issued for no longer exists - nothing to
            // refresh into.
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired refresh token."));
        }

        // Same tokenValidAfter floor JwtAuthenticationFilter.isValidForUser enforces on access
        // tokens (issue #96) - without this, a refresh token minted before a password reset
        // could still be exchanged for a brand new access token indefinitely, defeating the
        // point of the reset invalidating existing sessions. Same truncatedTo(SECONDS) reasoning
        // as that method - see its comment for why.
        if (user.getTokenValidAfter() != null) {
            Date issuedAt = jwtService.extractIssuedAt(refreshToken);
            if (issuedAt == null || issuedAt.toInstant().isBefore(user.getTokenValidAfter().truncatedTo(ChronoUnit.SECONDS))) {
                return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired refresh token."));
            }
        }

        tokenBlocklist.revoke(refreshToken);

        return ResponseEntity.ok(new AuthResponse(
                jwtService.generateAccessToken(email), jwtService.generateRefreshToken(email)));
    }

    // No @RequestBody - unlike register/login, logout needs no credentials, only the token
    // that's already on every request via the Authorization header (same header
    // JwtAuthenticationFilter already reads). Sits under /api/auth/** in SecurityConfig's
    // permitAll() list along with register/login, so a missing/malformed header is handled here
    // as a plain 400 rather than SecurityConfig rejecting it with a 401 first.
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest) {
        String token = extractBearerToken(httpRequest);
        if (token == null) {
            return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "Missing or malformed Authorization header."));
        }

        tokenBlocklist.revoke(token);

        return ResponseEntity.ok().build();
    }

    // Always returns 200, regardless of whether the email actually belongs to an account -
    // same enumeration-avoidance reasoning as login's identical 401 for "no such user" and
    // "wrong password" (issue #37). If it does exist, generates a fresh single-use token
    // (replacing any previous one for that user, so only the most recent reset request is ever
    // valid) and emails it via AuthEmailSender - logged, not really sent, unless
    // notifications.channel=email is configured, same as reminder emails (issue #17).
    //
    // @Transactional: PasswordResetTokenRepository.deleteByUserId is a derived delete query,
    // which (unlike the inherited save()/delete() JpaRepository already wraps its own
    // transaction around) needs an actual transaction already open on the calling thread or it
    // throws InvalidDataAccessApiUsageException - found live running this method for real, not
    // assumed. Also makes the delete-then-insert here properly atomic as a bonus.
    @Transactional
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(user -> {
            passwordResetTokenRepository.deleteByUserId(user.getId());

            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setToken(UUID.randomUUID().toString());
            resetToken.setUserId(user.getId());
            resetToken.setExpiresAt(Instant.now().plusMillis(passwordResetExpirationMs));
            passwordResetTokenRepository.save(resetToken);

            authEmailSender.sendPasswordResetEmail(user.getEmail(), resetToken.getToken());
        });

        return ResponseEntity.ok().build();
    }

    // Consumes a token from forgotPassword above. 401 if it's missing, already used, or expired
    // - deliberately the same code as other auth failures (not a distinct "token invalid" vs
    // "token expired" distinction), so this can't be used to probe which tokens once existed.
    // Same password strength check as register (issue #43) - a reset is still setting a real
    // password, the bar shouldn't be any lower just because it arrived via a different path.
    // @Transactional: same reasoning as forgotPassword above - deleteByUserId needs an open
    // transaction.
    @Transactional
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        Optional<PasswordResetToken> resetToken = passwordResetTokenRepository.findByToken(request.token());
        if (resetToken.isEmpty() || resetToken.get().getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired reset token."));
        }

        if (isTooWeak(request.newPassword())) {
            return ResponseEntity.status(400).body(new ApiError("BAD_REQUEST",
                    "Password must be at least 8 characters and include a letter and a digit."));
        }

        User user = userRepository.findById(resetToken.get().getUserId()).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Closes issue #96: stops the old password from working (above) but a reset used to
        // leave any JWT issued before it - e.g. one an attacker had already obtained - valid
        // until its own natural expiry. Every access/refresh token checks this floor now (see
        // JwtAuthenticationFilter.isValidForUser / this class's own refresh()).
        user.setTokenValidAfter(Instant.now());
        userRepository.save(user);

        // Single-use: delete every token for this user, not just the one that was used - covers
        // the (should-be-rare, given forgotPassword also clears old ones) case of more than one
        // row existing for the same user at once.
        passwordResetTokenRepository.deleteByUserId(user.getId());

        return ResponseEntity.ok().build();
    }

    // Consumes a token from register's verification email (issue #36). 401 if it's missing,
    // already used, or expired - same enumeration-resistant single code as reset-password above,
    // not a distinct "invalid" vs "expired" response. @Transactional for the same
    // derived-delete-query reason as forgotPassword/resetPassword.
    @Transactional
    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody VerifyEmailRequest request) {
        Optional<EmailVerificationToken> verificationToken = emailVerificationTokenRepository.findByToken(request.token());
        if (verificationToken.isEmpty() || verificationToken.get().getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid or expired verification token."));
        }

        User user = userRepository.findById(verificationToken.get().getUserId()).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);

        emailVerificationTokenRepository.deleteByUserId(user.getId());

        return ResponseEntity.ok().build();
    }

    private String extractBearerToken(HttpServletRequest httpRequest) {
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring("Bearer ".length());
    }

    // Checked only in register - AuthRequest is shared with login, and this must never reject a
    // login attempt for an existing account whose password predates this check (issue #43).
    // Deliberately minimal (length + one letter + one digit, not a full complexity ruleset) -
    // enough to stop trivially weak passwords ("a", "12345") without frustrating real users with
    // demands for special characters/mixed case that don't meaningfully improve security here.
    private static final int MIN_PASSWORD_LENGTH = 8;

    private boolean isTooWeak(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return true;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return !hasLetter || !hasDigit;
    }
}
