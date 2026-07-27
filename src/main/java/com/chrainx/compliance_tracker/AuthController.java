package com.chrainx.compliance_tracker;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final TokenBlocklist tokenBlocklist;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                           LoginRateLimiter loginRateLimiter, TokenBlocklist tokenBlocklist) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.tokenBlocklist = tokenBlocklist;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(409).build();
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
            return ResponseEntity.status(409).build();
        }

        return ResponseEntity.ok(new AuthResponse(jwtService.generateToken(user.getEmail())));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();

        // Checked before touching the DB at all - once an IP has hit the limit, every further
        // attempt short-circuits here regardless of whether the credentials would've been right.
        if (loginRateLimiter.isBlocked(clientIp)) {
            return ResponseEntity.status(429).build();
        }

        var user = userRepository.findByEmail(request.email()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Deliberately the same error for "no such user" and "wrong password" - revealing
            // which one it was would let an attacker enumerate which emails have accounts.
            loginRateLimiter.recordFailure(clientIp);
            return ResponseEntity.status(401).build();
        }

        loginRateLimiter.recordSuccess(clientIp);
        return ResponseEntity.ok(new AuthResponse(jwtService.generateToken(user.getEmail())));
    }

    // No @RequestBody - unlike register/login, logout needs no credentials, only the token
    // that's already on every request via the Authorization header (same header
    // JwtAuthenticationFilter already reads). Sits under /api/auth/** in SecurityConfig's
    // permitAll() list along with register/login, so a missing/malformed header is handled here
    // as a plain 400 rather than SecurityConfig rejecting it with a 401 first.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().build();
        }

        String token = authHeader.substring("Bearer ".length());
        tokenBlocklist.revoke(token);

        return ResponseEntity.ok().build();
    }
}
