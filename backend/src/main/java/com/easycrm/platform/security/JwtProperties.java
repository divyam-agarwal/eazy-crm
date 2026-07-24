package com.easycrm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easycrm.jwt")
public record JwtProperties(String secret, long accessTtlSeconds) {}
