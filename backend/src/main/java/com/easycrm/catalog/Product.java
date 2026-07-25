package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "product",
       uniqueConstraints = @UniqueConstraint(name = "uq_product_tenant_sku",
                                             columnNames = {"tenant_id", "sku"}))
public class Product extends TenantScopedEntity {

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Uom uom;

    @Column(name = "gst_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "base_rate", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseRate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Product() {}

    public Product(String sku, String name, String hsnCode, Uom uom,
                   BigDecimal gstRate, BigDecimal baseRate) {
        this.sku = sku;
        this.name = name;
        this.hsnCode = hsnCode;
        this.uom = uom;
        this.gstRate = gstRate;
        this.baseRate = baseRate;
        this.active = true;
    }

    public void update(String name, String hsnCode, Uom uom, BigDecimal gstRate, BigDecimal baseRate) {
        this.name = name;
        this.hsnCode = hsnCode;
        this.uom = uom;
        this.gstRate = gstRate;
        this.baseRate = baseRate;
    }

    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getHsnCode() { return hsnCode; }
    public Uom getUom() { return uom; }
    public BigDecimal getGstRate() { return gstRate; }
    public BigDecimal getBaseRate() { return baseRate; }
    public boolean isActive() { return active; }
}
