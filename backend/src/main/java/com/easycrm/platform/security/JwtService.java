package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = props.accessTtlSeconds();
    }

    public String mint(UUID tenantId, UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    public TenantContext.TenantPrincipal parse(String token) {
        Claims c =
                Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new TenantContext.TenantPrincipal(
                UUID.fromString(c.get("tenant_id", String.class)),
                UUID.fromString(c.getSubject()),
                c.get("role", String.class));
    }
}
