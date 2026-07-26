package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "quotation",
       uniqueConstraints = @UniqueConstraint(name = "uq_quotation_tenant_no",
                                             columnNames = {"tenant_id", "quote_no"}))
public class Quotation extends TenantScopedEntity {

    @Column(name = "quote_no", length = 32)
    private String quoteNo;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "enquiry_id")
    private UUID enquiryId;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuotationStatus status;

    protected Quotation() {}

    public Quotation(UUID customerId, UUID enquiryId) {
        this.customerId = customerId;
        this.enquiryId = enquiryId;
        this.status = QuotationStatus.DRAFT;
    }

    public void setCurrentVersionId(UUID id) { this.currentVersionId = id; }
    public void assignQuoteNo(String no) { this.quoteNo = no; }
    public void markSent() { this.status = QuotationStatus.SENT; }
    public void reviseToDraft() { this.status = QuotationStatus.DRAFT; }
    public void reject() { this.status = QuotationStatus.REJECTED; }
    public void expire() { this.status = QuotationStatus.EXPIRED; }

    public String getQuoteNo() { return quoteNo; }
    public UUID getCustomerId() { return customerId; }
    public UUID getEnquiryId() { return enquiryId; }
    public UUID getCurrentVersionId() { return currentVersionId; }
    public QuotationStatus getStatus() { return status; }
}
