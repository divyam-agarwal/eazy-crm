package com.easycrm.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                // Public share links: no JWT. The tenant is resolved from the share_link
                // row itself, and every read behind it still goes through @TenantId + RLS.
                // HEAD is permitted alongside GET: link unfurlers and some WhatsApp/proxy
                // paths issue HEAD before GET, and Spring MVC answers HEAD on a @GetMapping
                // automatically, so without this the link would preview as broken (401).
                .requestMatchers(HttpMethod.GET, "/public/q/*").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/public/q/*").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/signup", "/api/v1/auth/login",
                    "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                // Accepting an invitation is pre-auth by definition: the invitee has no
                // JWT and no tenant until this call creates them. The tenant is resolved
                // from the invitation row, and the User insert behind it still goes
                // through @TenantId + RLS. Under /api/v1/auth/** so it inherits that
                // prefix's rate-limit policy — an unmatched path would be unlimited.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/invitations/*/accept").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            // Unauthenticated request -> 401 (not authenticated), not Spring's default 403.
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
