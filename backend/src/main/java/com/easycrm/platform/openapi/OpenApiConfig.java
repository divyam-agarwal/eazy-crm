package com.easycrm.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
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

    static {
        // Money on the wire is a JSON string, never a number (see CLAUDE.md and
        // BigDecimalStringModule, which serializes every BigDecimal with writeString). springdoc
        // infers schemas from the *Java* type and knows nothing about a runtime Jackson module,
        // so left alone it publishes `type: number` for grandTotal, rate, qty and every other
        // BigDecimal — a contract that contradicts both the server and this document's own
        // description. One global replacement fixes every occurrence at once; per-field
        // annotations would be ~31 places to forget one.
        //
        // Request bodies turn into `type: string` too, and that is intended: Jackson parses a
        // JSON string into a BigDecimal without complaint, and string is the canonical form
        // everywhere else in this system.
        //
        // A static block, not a @Bean body: SpringDocUtils writes into a static registry
        // (AdditionalModelsConverter) that the model-converter chain consults at generation
        // time, so it only has to have happened before the first document is rendered. Class
        // loading of this @Configuration during context refresh is comfortably earlier.
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new StringSchema().format("decimal"));
    }

    private final String version;

    private final String publicBaseUrl;

    /**
     * The version is the Gradle project version, reaching us through the build-info file that
     * {@code springBoot { buildInfo() }} generates. One source of truth: a literal in
     * application.yml would be a second copy of the same number to keep in sync by hand.
     *
     * <p>The server URL comes from {@code easycrm.public-base-url}, the one property this app
     * already treats as its canonical external origin (share links and invitation links are both
     * built from it). Without an explicit {@code servers} entry springdoc synthesizes one from
     * whatever request happened to fetch the document — which, for the committed snapshot, is a
     * MockMvc request, and it published {@code http://localhost}: not merely vague but wrong, in
     * the artefact a frontend reads.
     */
    public OpenApiConfig(BuildProperties buildProperties, @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.version = buildProperties.getVersion();
        this.publicBaseUrl = publicBaseUrl;
    }

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info().title("EasyCRM API").version(version).description("""
                                Multi-tenant CRM for Indian distributors, traders and small manufacturers. \
                                Scope stops at the Order: no invoicing, stock or ledger.

                                Routes under /api/** require a bearer JWT, including GET /api/v1/auth/me. \
                                The exceptions are the four unauthenticated auth calls \
                                (signup, login, refresh, logout) and the invitation preview/accept pair; \
                                each is marked with an empty security requirement below. \
                                GET /public/q/{token} is a separate, unauthenticated route that is not \
                                under /api/** at all — the share token itself is the credential. \
                                Money is carried as a JSON string, never a number. Errors share one \
                                envelope: {"error":{"code","message","fields"}}.\
                                """))
                .servers(List.of(new Server()
                        .url(publicBaseUrl)
                        .description("Configured deployment origin (easycrm.public-base-url)")))
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
