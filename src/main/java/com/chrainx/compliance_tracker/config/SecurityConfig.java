package com.chrainx.compliance_tracker.config;
import com.chrainx.compliance_tracker.auth.JwtAuthenticationFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // BCrypt: a hashing algorithm designed specifically for passwords - deliberately slow (to
    // resist brute-forcing) and includes a random "salt" automatically, so two users with the
    // same password get different stored hashes. Never store or compare raw passwords directly.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // Registers Spring Security's own CorsFilter, backed by the CorsConfigurationSource
                // bean in CorsConfig, at the very front of this filter chain - before the entry
                // point below can reject anything. Needed because CORS used to be MVC-level only
                // (WebMvcConfigurer), which never ran on a request the security chain rejected
                // early (e.g. a 401 for an expired token) - so that response had no CORS headers
                // at all, invisible to browser JS as anything more specific than a generic network
                // failure. Found live, not hypothetically, while building frontend issue #17
                // (issue #83).
                .cors(Customizer.withDefaults())
                // CSRF protection exists to stop a malicious site from tricking a logged-in
                // user's browser into submitting requests using their session cookie. It's
                // irrelevant here: this API is stateless (no cookies at all, see below) and
                // authenticated purely via a bearer token the frontend attaches explicitly.
                .csrf(csrf -> csrf.disable())
                // STATELESS: Spring Security must not create or rely on an HttpSession - every
                // request re-proves who it is via the JWT, nothing is remembered server-side
                // between requests. This is what makes the API horizontally scalable later
                // (any server instance can handle any request, no shared session store needed).
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without an explicit entry point, Spring Security's default for a request with
                // no authentication at all is 403 Forbidden - technically "authenticated but
                // not permitted," which is misleading here since there's no authentication to
                // speak of. 401 Unauthorized is the semantically correct response for "you
                // haven't proven who you are."
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Browser CORS preflight requests (OPTIONS) carry no Authorization
                        // header - must be let through here, or every cross-origin request
                        // from the frontend would be rejected before CorsConfig's headers
                        // even get a chance to apply.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // A load balancer/container orchestrator's health check (issue #44)
                        // can't attach a JWT - it's not a logged-in user, just infrastructure
                        // asking "is this instance alive/ready." Only health is actually
                        // exposed at all (management.endpoints.web.exposure.include=health in
                        // application.properties), so this can't accidentally open up anything
                        // more sensitive even if that property ever widened.
                        // OpenAPI/Swagger UI (issue #21) - documentation, not an endpoint that
                        // does anything on a caller's behalf, so it's public the same way the
                        // GitHub repo/README already are. /v3/api-docs is the generated spec
                        // itself (what Swagger UI fetches to render); /swagger-ui/** is the UI's
                        // own static assets and the page at /swagger-ui/index.html.
                        .requestMatchers("/hello", "/api/auth/**", "/error", "/actuator/health/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )
                // Runs our JwtAuthenticationFilter before Spring Security's own default
                // username/password filter, since we're never using that default form-login
                // mechanism at all - only the JWT filter.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
