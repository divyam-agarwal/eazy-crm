package com.easycrm.platform.openapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Layer 2 of the dev-only exposure. SecurityConfig ends in {@code .anyRequest().denyAll()},
 * which is the right production answer for the springdoc paths; rather than punching a
 * conditional hole in it, this contributes a separate, higher-precedence chain that exists only
 * under the dev profile.
 *
 * <p>Two consequences worth keeping: SecurityConfig itself is untouched, so production
 * behaviour is exactly what it was; and deleting this file restores today's behaviour
 * completely, with no leftover conditional to reason about.
 *
 * <p>The securityMatcher is what keeps this chain narrow, and it must never widen — a chain at
 * {@code @Order(0)} matching more than these paths would take precedence over the real one for
 * every request it matched.
 */
@Configuration
@Profile("dev")
public class DevApiDocsSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain apiDocsFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
