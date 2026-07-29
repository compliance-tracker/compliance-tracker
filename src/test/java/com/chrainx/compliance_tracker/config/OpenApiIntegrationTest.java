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
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as ActuatorHealthIntegrationTest: only a real
// HTTP call through the actual security filter chain can prove SecurityConfig's permitAll rule
// for the generated OpenAPI spec/Swagger UI actually works (issue #21), not just that
// springdoc-openapi's auto-configuration compiles.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class OpenApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocs_isReachableWithNoAuthorizationHeader_andListsRealEndpoints() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // A couple of concrete, real paths - proves the spec was actually generated from this
        // app's own controllers, not just that some empty/default OpenAPI document came back.
        assertTrue(response.getBody().contains("/api/businesses"));
        assertTrue(response.getBody().contains("/api/auth/login"));
    }

    @Test
    void apiDocs_declaresTheBearerAuthSecurityScheme() {
        // Regression test for OpenApiConfig - without this, Swagger UI's "Authorize" button has
        // nothing to attach a token to when trying a protected endpoint from the docs UI itself.
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertTrue(response.getBody().contains("\"bearerAuth\""));
        assertTrue(response.getBody().contains("\"bearerFormat\":\"JWT\""));
    }

    @Test
    void swaggerUi_isReachableWithNoAuthorizationHeader() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void protectedEndpoints_stillRequireRealAuth_docsExposureDoesNotWeakenThat() {
        // The actual endpoints themselves must not have quietly become public just because
        // their documentation is - this is the one regression that would actually matter.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/businesses", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
