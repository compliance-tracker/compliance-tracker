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
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;

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
                        // A password reset (issue #96) sets tokenValidAfter to the moment of
                        // reset - a token minted before that is rejected here even though its
                        // own signature/expiry still check out, since TokenBlocklist alone can't
                        // catch a session this filter never saw get issued (e.g. one from before
                        // this deployment, or simply one the reset flow has no reference to).
                        if (isValidForUser(token, user)) {
                            var authentication = new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList());
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    });
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    // Package-private, not private, so JwtAuthenticationFilterTest can exercise it directly
    // without needing a full mocked request/response/filter chain just to prove this one rule.
    boolean isValidForUser(String token, User user) {
        if (user.getTokenValidAfter() == null) {
            return true;
        }
        Date issuedAt = jwtService.extractIssuedAt(token);
        if (issuedAt == null) {
            return false;
        }
        // A JWT's "iat" claim only has *second* precision (the numeric-date format the JWT spec
        // uses), but tokenValidAfter is an Instant.now() with sub-second precision - comparing
        // them directly would wrongly reject a token legitimately minted in the same second as
        // the reset (e.g. logging back in immediately after). Truncating tokenValidAfter down to
        // the second it falls in fixes that false rejection; the trade-off is a token minted a
        // fraction of a second *before* the reset, in that same second, is accepted too - an
        // unavoidable consequence of the JWT format's own precision limit, not something this
        // comparison can resolve, and a narrow (sub-one-second) window worth documenting rather
        // than pretending away.
        return !issuedAt.toInstant().isBefore(user.getTokenValidAfter().truncatedTo(ChronoUnit.SECONDS));
    }
}
