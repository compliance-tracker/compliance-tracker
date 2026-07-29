package com.chrainx.compliance_tracker.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// springdoc-openapi (issue #21) auto-generates a working spec from the controllers/DTOs alone -
// this bean only adds what it can't infer: human-readable title/description (matching the
// disclaimer this app already surfaces elsewhere - see root CLAUDE.md, it must never read as
// compliance advice), and the Bearer JWT security scheme, without which Swagger UI's own
// "Authorize" button would have nothing to attach a token to when trying a protected endpoint.
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI complianceTrackerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Compliance Tracker API")
                        .description("Tracks Singapore SME compliance deadlines (ACRA Annual Return, GST F5, "
                                + "work pass renewals) and sends reminders ahead of each one. "
                                + "This is a reminder/tracking tool, not compliance advice - always verify "
                                + "against the official ACRA/IRAS/MOM source before relying on a date.")
                        .version("v1"))
                // Registered globally (every operation requires it by default) since almost
                // every endpoint does - register/login/refresh/etc. under /api/auth/** are the
                // only real exceptions, and Spring Security already treats them as public
                // regardless of what this spec claims; a wrong "requires auth" label here is
                // cosmetic, not a real gap the way an actually-missing SecurityConfig rule would
                // be.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
