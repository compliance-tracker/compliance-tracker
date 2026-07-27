package com.chrainx.compliance_tracker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                .cors(cors -> {})
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
                        .requestMatchers("/hello", "/api/auth/**", "/error", "/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                // Runs our filters before Spring Security's own default
                // username/password filter, since we're never using that default form-login
                // mechanism at all - only the JWT filter.
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
