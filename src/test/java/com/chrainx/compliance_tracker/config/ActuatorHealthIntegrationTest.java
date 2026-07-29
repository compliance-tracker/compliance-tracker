package com.chrainx.compliance_tracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as AuthIntegrationTest: only a real HTTP call
// through the actual security filter chain can prove SecurityConfig's permitAll rule for
// /actuator/health actually works, not just that the property/bean config compiles (issue #44).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ActuatorHealthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void health_isReachableWithNoAuthorizationHeaderAtAll_andReportsUp() {
        // No Authorization header set anywhere here - a load balancer/orchestrator health check
        // is infrastructure, not a logged-in user, and can't attach a JWT. Real Postgres is up
        // in this test environment, so a genuine DB connectivity check (auto-configured from
        // the DataSource on the classpath) should report UP, not just that the JVM is alive.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"status\":\"UP\""));
    }

    @Test
    void health_doesNotExposeComponentDetails_toAnUnauthenticatedCaller() {
        // show-details=never (application.properties) - a public, unauthenticated endpoint
        // shouldn't leak which DB it's checking, connection pool internals, etc. Regression
        // test for that specific config value, not just "the endpoint returns something".
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertFalse(response.getBody().contains("components"));
    }

    @Test
    void livenessAndReadinessProbeGroups_areBothReachableAndUp() {
        // management.endpoint.health.probes.enabled=true (application.properties) - the
        // Kubernetes-style sub-groups a real container orchestrator would actually point its
        // liveness/readiness probes at, not just the top-level /actuator/health.
        ResponseEntity<String> liveness = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        ResponseEntity<String> readiness = restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertEquals(HttpStatus.OK, liveness.getStatusCode());
        assertTrue(liveness.getBody().contains("\"status\":\"UP\""));

        assertEquals(HttpStatus.OK, readiness.getStatusCode());
        assertTrue(readiness.getBody().contains("\"status\":\"UP\""));
    }

    @Test
    void onlyHealthIsExposed_notTheFullActuatorEndpointSet() {
        // management.endpoints.web.exposure.include=health (application.properties) - a bare
        // "expose everything" default would leak env vars, bean definitions, etc. over HTTP.
        // /actuator/env is a good canary: never permitAll'd, and not itself exposed either, so
        // it should fail closed the same way any other unmapped/unauthenticated path does.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);

        assertTrue(response.getStatusCode().is4xxClientError());
    }
}
