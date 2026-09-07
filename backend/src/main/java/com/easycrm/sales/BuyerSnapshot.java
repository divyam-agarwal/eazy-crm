package com.easycrm.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The buyer as they were when the quotation was sent — frozen, like the line items and
 * the totals beside it, so a SENT version renders the same document forever (F11).
 *
 * Flat columns rather than the JSONB the AWS design's D10 named: QuotationItem already
 * freezes its product as name/hsn/uom snapshot columns in this same table family, and
 * ddl-auto: validate checks columns but cannot check inside a blob. See the design spec
 * §3.1 for the full reversal.
 *
 * Null for a DRAFT and non-null for a SENT version; QuotationService.send() is what
 * establishes that, and the database deliberately does not (a NOT NULL default would make
 * "never frozen" look like a real value).
 */
@Embeddable
public class BuyerSnapshot {

    @Column(name = "buyer_business_name")
    private String businessName;

    @Column(name = "buyer_gstin", length = 15)
    private String gstin;

    @Column(name = "buyer_billing_address", length = 512)
    private String billingAddress;

    protected BuyerSnapshot() {}

    public BuyerSnapshot(String businessName, String gstin, String billingAddress) {
        this.businessName = businessName;
        this.gstin = gstin;
        this.billingAddress = billingAddress;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getGstin() {
        return gstin;
    }

    public String getBillingAddress() {
        return billingAddress;
    }
}
