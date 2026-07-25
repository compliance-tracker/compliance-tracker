package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // Real JwtService (not mocked) - it's cheap, deterministic, and testing against a mock
    // would just be testing that the mock returns what we told it to.
    private final JwtService jwtService = new JwtService(
            "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 86_400_000L);

    private final AuthController controller = new AuthController(userRepository, passwordEncoder, jwtService);

    @Test
    void register_savesNewUser_andReturnsAToken() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("new@example.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().token());
        assertEquals("new@example.com", jwtService.extractEmail(response.getBody().token()));
    }

    @Test
    void register_returns409_whenEmailAlreadyTaken() {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(new User()));

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("taken@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void login_returnsAToken_whenPasswordMatches() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<AuthResponse> response = controller.login(new AuthRequest("owner@example.com", "correct-password"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", jwtService.extractEmail(response.getBody().token()));
    }

    @Test
    void login_returns401_whenPasswordIsWrong() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<AuthResponse> response = controller.login(new AuthRequest("owner@example.com", "wrong-password"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_returns401_whenEmailDoesNotExist() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        ResponseEntity<AuthResponse> response = controller.login(new AuthRequest("nobody@example.com", "anything"));

        assertEquals(401, response.getStatusCode().value());
    }
}
