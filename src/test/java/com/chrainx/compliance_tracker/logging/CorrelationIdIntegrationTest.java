package com.chrainx.compliance_tracker.logging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Real, unmocked, full-stack test - only a real HTTP call through the actual filter chain (not
// a mocked-level test calling CorrelationIdFilter.doFilter directly) can prove
// CorrelationIdFilter is actually wired in and actually runs ahead of Spring Security, not just
// that the filter class's own logic is correct in isolation (issue #51).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class CorrelationIdIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void response_carriesAFreshCorrelationId_whenNoneWasSent() {
        ResponseEntity<String> response = restTemplate.getForEntity("/hello", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void response_echoesBackTheSameCorrelationId_whenOneWasSent() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "integration-test-correlation-id");

        ResponseEntity<String> response = restTemplate.exchange(
                "/hello", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals("integration-test-correlation-id", response.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void aRequestRejectedBySecurity_stillGetsACorrelationId() {
        // The whole point of registering this filter at HIGHEST_PRECEDENCE rather than letting
        // Spring Boot auto-register it at its default (last) order - a request Security rejects
        // outright, before it ever reaches a controller, is exactly the kind of request most
        // worth being able to trace.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/businesses", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }
}
