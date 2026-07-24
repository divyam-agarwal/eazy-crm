package com.easycrm.support;

import com.easycrm.platform.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class TestTokens {
    @Autowired JwtService jwt;
    public String owner(UUID tenantId) {
        return jwt.mint(tenantId, UUID.randomUUID(), "OWNER");
    }
}
