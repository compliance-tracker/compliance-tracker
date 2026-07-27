package com.chrainx.compliance_tracker.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

// Runs once per request, before the actual controller. Looks for "Authorization: Bearer <token>",
// and if it's present, not revoked, not a refresh token, and valid, tells Spring Security "this
// request is authenticated as this user" by populating the SecurityContext - which is what
// @AuthenticationPrincipal in controllers reads from. If the header is missing, the token's
// revoked, the token's actually a refresh token (issue #26 - those are only ever valid against
// POST /api/auth/refresh, never as a substitute access token), or the token's invalid, this
// filter simply does nothing and lets the request continue unauthenticated - SecurityConfig's
// authorizeHttpRequests rules are what actually reject it with a 401 further down the chain.
// Deliberately not special-cased: all of these are treated exactly the same passive way, rather
// than this filter setting its own response status and short-circuiting the chain itself (which
// would risk skipping other filters further down, e.g. CORS handling).
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TokenBlocklist tokenBlocklist;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository, TokenBlocklist tokenBlocklist) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.tokenBlocklist = tokenBlocklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length());

            if (!tokenBlocklist.isRevoked(token) && !jwtService.isRefreshToken(token)) {
                String email = jwtService.extractEmail(token);

                if (email != null) {
                    userRepository.findByEmail(email).ifPresent(user -> {
                        var authentication = new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
