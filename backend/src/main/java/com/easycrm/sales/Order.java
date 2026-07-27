package com.easycrm.sales;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_order", // "order" is a reserved SQL word
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_order_tenant_no", columnNames = {"tenant_id", "order_no"}),
           @UniqueConstraint(name = "uq_order_tenant_quotation", columnNames = {"tenant_id", "quotation_id"})
       })
public class Order extends TenantScopedEntity {

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "quotation_version_id", nullable = false)
    private UUID quotationVersionId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "po_reference")
    private String poReference;

    @Column(name = "po_date")
    private LocalDate poDate;

    @Column(name = "sub_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal subTotal;

    @Column(name = "total_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalTax;

    @Column(name = "grand_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    protected Order() {}

    public Order(UUID quotationId, UUID quotationVersionId, UUID customerId, String orderNo,
                 BigDecimal subTotal, BigDecimal totalTax, BigDecimal grandTotal,
                 String poReference, LocalDate poDate) {
        this.quotationId = quotationId;
        this.quotationVersionId = quotationVersionId;
        this.customerId = customerId;
        this.orderNo = orderNo;
        this.subTotal = subTotal;
        this.totalTax = totalTax;
        this.grandTotal = grandTotal;
        this.poReference = poReference;
        this.poDate = poDate;
        this.status = OrderStatus.CONFIRMED;
    }

    public String getOrderNo() { return orderNo; }
    public UUID getQuotationId() { return quotationId; }
    public UUID getQuotationVersionId() { return quotationVersionId; }
    public UUID getCustomerId() { return customerId; }
    public String getPoReference() { return poReference; }
    public LocalDate getPoDate() { return poDate; }
    public BigDecimal getSubTotal() { return subTotal; }
    public BigDecimal getTotalTax() { return totalTax; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public OrderStatus getStatus() { return status; }
}
