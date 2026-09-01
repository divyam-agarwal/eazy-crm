package com.easycrm.catalog;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
        name = "price_list_item",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_pli_tenant_list_product",
                        columnNames = {"tenant_id", "price_list_id", "product_id"}))
public class PriceListItem extends TenantScopedEntity {

    @Column(name = "price_list_id", nullable = false)
    private UUID priceListId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "override_rate", precision = 18, scale = 2)
    private BigDecimal overrideRate;

    @Column(name = "discount_pct", precision = 18, scale = 4)
    private BigDecimal discountPct;

    protected PriceListItem() {}

    public PriceListItem(UUID priceListId, UUID productId, BigDecimal overrideRate, BigDecimal discountPct) {
        this.priceListId = priceListId;
        this.productId = productId;
        this.overrideRate = overrideRate;
        this.discountPct = discountPct;
    }

    public UUID getPriceListId() {
        return priceListId;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getOverrideRate() {
        return overrideRate;
    }

    public BigDecimal getDiscountPct() {
        return discountPct;
    }
}
