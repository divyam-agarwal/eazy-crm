package com.easycrm.sales;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * GLOBAL table (intentionally NOT tenant-scoped), like {@code refresh_token}: the public
 * share endpoint is pre-auth and must resolve a tenant from the opaque token alone, so
 * this cannot be tenant-filtered. Everything it points at is then loaded through
 * {@code @TenantId} + RLS as normal.
 *
 * The token is stored in plaintext — unlike refresh_token, which is hashed. A refresh
 * token grants authenticated capability; this one only reads a frozen quotation that is
 * rendered from rows in this same database. Plaintext is what makes sharing idempotent:
 * one stable link per version, so a link already sent to a customer keeps working.
 * It is a bearer credential, so it must never be logged — hence no toString().
 */
@Entity
@Table(name = "share_link")
public class ShareLink extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "quotation_version_id", nullable = false)
    private UUID quotationVersionId;

    protected ShareLink() {}

    public ShareLink(String token, UUID tenantId, UUID quotationVersionId) {
        this.token = token;
        this.tenantId = tenantId;
        this.quotationVersionId = quotationVersionId;
    }

    public String getToken() { return token; }
    public UUID getTenantId() { return tenantId; }
    public UUID getQuotationVersionId() { return quotationVersionId; }
}
