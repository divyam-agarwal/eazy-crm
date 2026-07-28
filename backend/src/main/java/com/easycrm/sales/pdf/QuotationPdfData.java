package com.easycrm.sales.pdf;

import java.time.Instant;
import java.util.List;

/**
 * Everything the template needs, already formatted. Deliberately free of entities and
 * of BigDecimal: the document presents the frozen snapshot and recomputes nothing, so
 * formatting decisions all happen before rendering, where they are easy to test.
 *
 * A tax field that does not apply is null, not zero — the template omits the whole
 * column rather than printing "Rs. 0.00" for a tax that was never charged.
 */
public record QuotationPdfData(Seller seller, Buyer buyer, Doc doc, List<Line> lines,
                               Totals totals, boolean interState, Instant renderTimestamp) {

    public record Seller(String businessName, String gstin, String address,
                         String phone, String email) {}

    public record Buyer(String businessName, String gstin, String address) {}

    public record Doc(String quoteNo, int versionNo, String date, String validUntil,
                      String placeOfSupply, String paymentTerms, String deliveryTerms,
                      String notes) {}

    public record Line(int serial, String name, String hsn, String uom, String qty,
                       String rate, String discountPct, String gstRate,
                       String taxableValue, String lineTotal) {}

    public record Totals(String subTotal, String cgst, String sgst, String igst,
                         String totalTax, String grandTotal) {}
}
