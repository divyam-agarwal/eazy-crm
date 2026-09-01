package com.easycrm.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of the generated document's non-derived metadata. Everything else in the
 * spec — paths, schemas, parameters — is read off the controllers and DTOs by springdoc; this
 * bean supplies only what cannot be inferred.
 */
@Configuration
public class OpenApiConfig {

    private final String version;

    /**
     * The version is the Gradle project version, reaching us through the build-info file that
     * {@code springBoot { buildInfo() }} generates. One source of truth: a literal in
     * application.yml would be a second copy of the same number to keep in sync by hand.
     */
    public OpenApiConfig(BuildProperties buildProperties) {
        this.version = buildProperties.getVersion();
    }

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info().title("EasyCRM API").version(version).description("""
                                Multi-tenant CRM for Indian distributors, traders and small manufacturers. \
                                Scope stops at the Order: no invoicing, stock or ledger.

                                Every route under /api/** requires a bearer JWT except the auth routes, \
                                the invitation accept/preview pair, and GET /public/q/{token}. \
                                Money is carried as a JSON string, never a number. Errors share one \
                                envelope: {"error":{"code","message","fields"}}.\
                                """))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
