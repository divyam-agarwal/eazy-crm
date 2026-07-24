package com.easycrm.tenant;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    protected Tenant() {} // JPA

    public Tenant(String slug, String businessName, String stateCode) {
        this.slug = slug;
        this.businessName = businessName;
        this.stateCode = stateCode;
    }

    public String getSlug() { return slug; }
    public String getBusinessName() { return businessName; }
    public String getStateCode() { return stateCode; }
}
