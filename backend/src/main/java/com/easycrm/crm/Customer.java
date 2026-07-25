package com.easycrm.crm;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "customer",
       uniqueConstraints = @UniqueConstraint(name = "uq_customer_tenant_gstin",
                                             columnNames = {"tenant_id", "gstin"}))
public class Customer extends TenantScopedEntity {

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(length = 15)
    private String gstin;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(name = "billing_address", length = 512)
    private String billingAddress;

    @Column(name = "shipping_address", length = 512)
    private String shippingAddress;

    @Column(name = "credit_days", nullable = false)
    private int creditDays;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "price_list_id")
    private UUID priceListId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomerSource source;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Customer() {}

    public Customer(String businessName, String gstin, String stateCode,
                    String billingAddress, String shippingAddress, int creditDays,
                    UUID assignedTo, UUID priceListId, CustomerSource source) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.billingAddress = billingAddress;
        this.shippingAddress = shippingAddress;
        this.creditDays = creditDays;
        this.assignedTo = assignedTo;
        this.priceListId = priceListId;
        this.source = source;
        this.active = true;
    }

    public void update(String businessName, String gstin, String stateCode,
                       String billingAddress, String shippingAddress, int creditDays,
                       UUID assignedTo, UUID priceListId, CustomerSource source) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.stateCode = stateCode;
        this.billingAddress = billingAddress;
        this.shippingAddress = shippingAddress;
        this.creditDays = creditDays;
        this.assignedTo = assignedTo;
        this.priceListId = priceListId;
        this.source = source;
    }

    public void activate() { this.active = true; }
    public void deactivate() { this.active = false; }

    public String getBusinessName() { return businessName; }
    public String getGstin() { return gstin; }
    public String getStateCode() { return stateCode; }
    public String getBillingAddress() { return billingAddress; }
    public String getShippingAddress() { return shippingAddress; }
    public int getCreditDays() { return creditDays; }
    public UUID getAssignedTo() { return assignedTo; }
    public UUID getPriceListId() { return priceListId; }
    public CustomerSource getSource() { return source; }
    public boolean isActive() { return active; }
}
