package com.easycrm.tenant;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(length = 15)
    private String gstin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TenantStatus status = TenantStatus.TRIAL;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    protected Tenant() {} // JPA

    public Tenant(String slug, String businessName, String stateCode) {
        this(slug, businessName, stateCode, null, TenantStatus.TRIAL, null);
    }

    public Tenant(String slug, String businessName, String stateCode,
                  String gstin, TenantStatus status, Instant trialEndsAt) {
        this.slug = slug;
        this.businessName = businessName;
        this.stateCode = stateCode;
        this.gstin = gstin;
        this.status = status;
        this.trialEndsAt = trialEndsAt;
    }

    public String getSlug() { return slug; }
    public String getBusinessName() { return businessName; }
    public String getStateCode() { return stateCode; }
    public String getGstin() { return gstin; }
    public TenantStatus getStatus() { return status; }
    public Instant getTrialEndsAt() { return trialEndsAt; }
    public void setStatus(TenantStatus status) { this.status = status; }
}
