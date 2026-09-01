package com.easycrm.sales.pdf;

import com.easycrm.crm.Customer;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationItem;
import com.easycrm.sales.QuotationItemRepository;
import com.easycrm.sales.QuotationVersion;
import com.easycrm.sales.QuotationVersionRepository;
import com.easycrm.sales.VersionStatus;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotationPdfService {

    private final QuotationVersionRepository versions;
    private final QuotationItemRepository items;
    private final TenantRepository tenants;
    private final VisibleFinder finder;
    private final QuotationPdfRenderer renderer;

    public QuotationPdfService(
            QuotationVersionRepository versions,
            QuotationItemRepository items,
            TenantRepository tenants,
            VisibleFinder finder,
            QuotationPdfRenderer renderer) {
        this.versions = versions;
        this.items = items;
        this.tenants = tenants;
        this.finder = finder;
        this.renderer = renderer;
    }

    /** Latest SENT version when versionNo is null, otherwise that specific frozen version. */
    @Transactional(readOnly = true)
    public byte[] renderByQuotation(UUID quotationId, Integer versionNo) {
        Quotation q = finder.findQuotation(quotationId).orElseThrow(() -> new NotFoundException("quotation not found"));
        QuotationVersion v = versionNo == null
                ? versions.findById(requireCurrentVersion(q))
                        .orElseThrow(() -> new NotFoundException("quotation version not found"))
                // 422, not 404: the quotation exists and is visible — it is the version
                // parameter that is wrong, which is invalid input rather than a missing record.
                : versions.findByQuotationIdAndVersionNo(quotationId, versionNo)
                        .orElseThrow(() -> new ValidationException("version", "no such version"));
        return render(q, v);
    }

    /** Entry point for the public share link, which knows a version id and nothing else. */
    @Transactional(readOnly = true)
    public byte[] renderByVersionId(UUID versionId) {
        QuotationVersion v =
                versions.findById(versionId).orElseThrow(() -> new NotFoundException("quotation version not found"));
        Quotation q = finder.findQuotation(v.getQuotationId())
                .orElseThrow(() -> new NotFoundException("quotation not found"));
        return render(q, v);
    }

    private UUID requireCurrentVersion(Quotation q) {
        if (q.getQuoteNo() == null || q.getCurrentVersionId() == null) {
            throw new ValidationException("status", "send the quotation before rendering it");
        }
        return q.getCurrentVersionId();
    }

    private byte[] render(Quotation q, QuotationVersion v) {
        if (v.getStatus() != VersionStatus.SENT) {
            throw new ValidationException("status", "send the quotation before rendering it");
        }
        Tenant tenant =
                tenants.findById(TenantContext.tenantId()).orElseThrow(() -> new NotFoundException("tenant not found"));
        Customer customer =
                finder.findCustomer(q.getCustomerId()).orElseThrow(() -> new NotFoundException("customer not found"));
        List<QuotationItem> lines = items.findByVersionId(v.getId());

        // The version's place of supply is the buyer's state, frozen when it was created.
        // Comparing it to the seller's state is exact; inferring from whether any line
        // has non-zero IGST would misread a wholly zero-rated inter-state quote as
        // intra-state and print CGST/SGST rows for tax that was never charged.
        boolean interState = !v.getPlaceOfSupply().equals(tenant.getStateCode());

        return renderer.render(new QuotationPdfData(
                new QuotationPdfData.Seller(
                        tenant.getBusinessName(),
                        tenant.getGstin(),
                        tenant.getAddress(),
                        tenant.getPhone(),
                        tenant.getEmail()),
                new QuotationPdfData.Buyer(
                        customer.getBusinessName(), customer.getGstin(), customer.getBillingAddress()),
                new QuotationPdfData.Doc(
                        q.getQuoteNo(),
                        v.getVersionNo(),
                        IndianFormats.date(v.getSentAt()),
                        IndianFormats.date(v.getValidUntil()),
                        v.getPlaceOfSupply(),
                        v.getPaymentTerms(),
                        v.getDeliveryTerms(),
                        v.getNotes()),
                toLines(lines),
                totals(v, lines, interState),
                interState,
                v.getSentAt())); // pins deterministic PDF metadata to when the version froze
    }

    private List<QuotationPdfData.Line> toLines(List<QuotationItem> source) {
        List<QuotationPdfData.Line> out = new ArrayList<>();
        int serial = 1;
        for (QuotationItem i : source) {
            out.add(new QuotationPdfData.Line(
                    serial++,
                    i.getNameSnapshot(),
                    i.getHsnSnapshot(),
                    i.getUomSnapshot(),
                    IndianFormats.qty(i.getQty()),
                    IndianFormats.rupees(i.getRate()),
                    IndianFormats.percent(i.getDiscountPct()),
                    IndianFormats.percent(i.getGstRate()),
                    IndianFormats.rupees(i.getTaxableValue()),
                    IndianFormats.rupees(i.getLineTotal())));
        }
        return out;
    }

    private QuotationPdfData.Totals totals(QuotationVersion v, List<QuotationItem> lines, boolean interState) {
        BigDecimal cgst = sum(lines, QuotationItem::getCgst);
        BigDecimal sgst = sum(lines, QuotationItem::getSgst);
        BigDecimal igst = sum(lines, QuotationItem::getIgst);
        return new QuotationPdfData.Totals(
                IndianFormats.rupees(v.getSubTotal()),
                interState ? null : IndianFormats.rupees(cgst),
                interState ? null : IndianFormats.rupees(sgst),
                interState ? IndianFormats.rupees(igst) : null,
                IndianFormats.rupees(v.getTotalTax()),
                IndianFormats.rupees(v.getGrandTotal()));
    }

    private BigDecimal sum(List<QuotationItem> lines, java.util.function.Function<QuotationItem, BigDecimal> field) {
        // Summing already-rounded per-line tax, which is how the version's totals were
        // built (round per line, then sum — challenge #2). Nothing is re-rounded here.
        return lines.stream().map(field).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
