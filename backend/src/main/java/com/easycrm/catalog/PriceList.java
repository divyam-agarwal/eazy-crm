package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "price_list",
       uniqueConstraints = @UniqueConstraint(name = "uq_price_list_tenant_name",
                                             columnNames = {"tenant_id", "name"}))
public class PriceList extends TenantScopedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected PriceList() {}

    public PriceList(String name) {
        this.name = name;
        this.active = true;
    }

    public void rename(String name) { this.name = name; }
    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getName() { return name; }
    public boolean isActive() { return active; }
}
