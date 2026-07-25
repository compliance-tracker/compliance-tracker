package com.chrainx.compliance_tracker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Without this, a browser calling this API from a different origin (the frontend runs on
// http://localhost:5173, this API on :8081 - different port = different origin) gets blocked
// by the browser's own same-origin policy before the request even reaches this server. This
// is a browser-side security rule, not something curl/Postman are subject to - which is why
// everything worked fine via curl all along despite CORS never being configured until now.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origin}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}
