package com.easycrm.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() { return tenantId; }
}
