package com.chrainx.compliance_tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(jwtService.generateToken(user.getEmail())));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        var user = userRepository.findByEmail(request.email()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Deliberately the same error for "no such user" and "wrong password" - revealing
            // which one it was would let an attacker enumerate which emails have accounts.
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok(new AuthResponse(jwtService.generateToken(user.getEmail())));
    }
}
