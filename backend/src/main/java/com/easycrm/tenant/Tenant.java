package com.easycrm.tenant;

import com.easycrm.platform.persistence.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * GLOBAL tenant registry (no @TenantId / no RLS — it IS the tenant list). Unlike every
 * other entity, its id is an APPLICATION-ASSIGNED UUIDv7 rather than Hibernate-generated:
 * signup must know the tenant id BEFORE the transaction opens, so it can set the tenant
 * context (and the app.current_tenant GUC) up front and let Hibernate @TenantId resolve to
 * this tenant when inserting the first tenant-scoped rows in the SAME transaction. Hibernate
 * resolves a session's tenant only at session-open, so binding after the fact does not work.
 * Implements {@link Persistable} so Spring Data issues an INSERT (persist) despite the
 * pre-set id — otherwise a non-null id routes save() through merge/UPDATE. See
 * engineering-challenges #9.
 */
@Entity
@Table(name = "tenant")
@EntityListeners(AuditingEntityListener.class)
public class Tenant implements Persistable<UUID> {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(length = 15)
    private String gstin;

    @Column(length = 512)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TenantStatus status = TenantStatus.TRIAL;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @Transient
    private boolean isNew = true;

    protected Tenant() {} // JPA

    public Tenant(String slug, String businessName, String stateCode) {
        this(slug, businessName, stateCode, null, TenantStatus.TRIAL, null);
    }

    public Tenant(
            String slug,
            String businessName,
            String stateCode,
            String gstin,
            TenantStatus status,
            Instant trialEndsAt) {
        this.id = UuidV7.generate();
        this.slug = slug;
        this.businessName = businessName;
        this.stateCode = stateCode;
        this.gstin = gstin;
        this.status = status;
        this.trialEndsAt = trialEndsAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public String getSlug() {
        return slug;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getStateCode() {
        return stateCode;
    }

    public String getGstin() {
        return gstin;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    /** Full replace: an omitted field clears the stored value (house-wide PATCH semantics). */
    public void updateProfile(String address, String phone, String email) {
        this.address = address;
        this.phone = phone;
        this.email = email;
    }
}
