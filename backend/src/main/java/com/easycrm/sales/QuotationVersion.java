package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quotation_version",
       uniqueConstraints = @UniqueConstraint(name = "uq_qv_tenant_quotation_no",
                                             columnNames = {"tenant_id", "quotation_id", "version_no"}))
public class QuotationVersion extends TenantScopedEntity {

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionStatus status;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "payment_terms")
    private String paymentTerms;

    @Column(name = "delivery_terms")
    private String deliveryTerms;

    @Column
    private String notes;

    @Column(name = "place_of_supply", nullable = false, length = 2)
    private String placeOfSupply;

    @Column(name = "sub_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "total_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalTax = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected QuotationVersion() {}

    public QuotationVersion(UUID quotationId, int versionNo, String placeOfSupply) {
        this.quotationId = quotationId;
        this.versionNo = versionNo;
        this.placeOfSupply = placeOfSupply;
        this.status = VersionStatus.DRAFT;
    }

    public void setHeader(LocalDate validUntil, String paymentTerms, String deliveryTerms, String notes) {
        this.validUntil = validUntil;
        this.paymentTerms = paymentTerms;
        this.deliveryTerms = deliveryTerms;
        this.notes = notes;
    }

    public void setTotals(BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal) {
        this.subTotal = subTotal;
        this.totalTax = totalTax;
        this.grandTotal = grandTotal;
    }

    public void markSent(Instant sentAt) {
        this.status = VersionStatus.SENT;
        this.sentAt = sentAt;
    }

    public UUID getQuotationId() { return quotationId; }
    public int getVersionNo() { return versionNo; }
    public VersionStatus getStatus() { return status; }
    public LocalDate getValidUntil() { return validUntil; }
    public String getPaymentTerms() { return paymentTerms; }
    public String getDeliveryTerms() { return deliveryTerms; }
    public String getNotes() { return notes; }
    public String getPlaceOfSupply() { return placeOfSupply; }
    public BigDecimal getSubTotal() { return subTotal; }
    public BigDecimal getTotalTax() { return totalTax; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public Instant getSentAt() { return sentAt; }
}
