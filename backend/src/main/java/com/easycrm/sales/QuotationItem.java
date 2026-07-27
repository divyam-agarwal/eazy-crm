package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quotation_item")
public class QuotationItem extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "name_snapshot", nullable = false)
    private String nameSnapshot;

    @Column(name = "hsn_snapshot", length = 8)
    private String hsnSnapshot;

    @Column(name = "uom_snapshot", nullable = false, length = 16)
    private String uomSnapshot;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal qty;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal rate;

    @Column(name = "discount_pct", precision = 18, scale = 4)
    private BigDecimal discountPct;

    @Column(name = "gst_rate", nullable = false, precision = 18, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "taxable_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxableValue;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal cgst;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal sgst;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal igst;

    @Column(name = "line_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal lineTotal;

    protected QuotationItem() {}

    public QuotationItem(UUID versionId, UUID productId, String nameSnapshot, String hsnSnapshot,
                         String uomSnapshot, BigDecimal qty, BigDecimal rate, BigDecimal discountPct,
                         BigDecimal gstRate, BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst,
                         BigDecimal igst, BigDecimal lineTotal) {
        this.versionId = versionId;
        this.productId = productId;
        this.nameSnapshot = nameSnapshot;
        this.hsnSnapshot = hsnSnapshot;
        this.uomSnapshot = uomSnapshot;
        this.qty = qty;
        this.rate = rate;
        this.discountPct = discountPct;
        this.gstRate = gstRate;
        this.taxableValue = taxableValue;
        this.cgst = cgst;
        this.sgst = sgst;
        this.igst = igst;
        this.lineTotal = lineTotal;
    }

    public UUID getVersionId() { return versionId; }
    public UUID getProductId() { return productId; }
    public String getNameSnapshot() { return nameSnapshot; }
    public String getHsnSnapshot() { return hsnSnapshot; }
    public String getUomSnapshot() { return uomSnapshot; }
    public BigDecimal getQty() { return qty; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getDiscountPct() { return discountPct; }
    public BigDecimal getGstRate() { return gstRate; }
    public BigDecimal getTaxableValue() { return taxableValue; }
    public BigDecimal getCgst() { return cgst; }
    public BigDecimal getSgst() { return sgst; }
    public BigDecimal getIgst() { return igst; }
    public BigDecimal getLineTotal() { return lineTotal; }
}
