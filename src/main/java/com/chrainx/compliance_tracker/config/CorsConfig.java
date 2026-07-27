package com.chrainx.compliance_tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Without this, a browser calling this API from a different origin (the frontend runs on
// http://localhost:5173, this API on :8081 - different port = different origin) gets blocked
// by the browser's own same-origin policy before the request even reaches this server. This
// is a browser-side security rule, not something curl/Postman are subject to - which is why
// everything worked fine via curl all along despite CORS never being configured until now.
//
// This used to be a plain WebMvcConfigurer.addCorsMappings - which only applies to requests
// that actually reach a controller through the normal MVC dispatch path. When Spring Security's
// HttpStatusEntryPoint rejects a request (missing/invalid/expired token) and commits a 401
// directly inside the security filter chain, that MVC-level CORS handling never runs, so the
// error response had no Access-Control-Allow-Origin header at all - invisible to browser JS as
// anything more specific than an opaque network failure. Found live while building frontend
// issue #17 (silent refresh on 401): a real browser couldn't distinguish "token expired" from
// "backend unreachable", even though curl showed the 401 was there all along (issue #83).
// A CorsConfigurationSource bean, wired into SecurityConfig via `.cors(...)`, makes Spring
// Security register its own CorsFilter at the very front of the chain - before the entry point
// can reject anything - so every response gets CORS headers, success or failure alike.
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origin}")
    private String allowedOrigin;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
