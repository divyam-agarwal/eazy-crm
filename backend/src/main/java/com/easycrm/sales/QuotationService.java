package com.easycrm.sales;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.web.dto.ItemRequest;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import com.easycrm.sales.web.dto.QuotationVersionResponse;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuotationService {

    private final QuotationRepository quotations;
    private final QuotationVersionRepository versions;
    private final QuotationItemRepository items;
    private final CustomerRepository customers;
    private final TenantRepository tenants;
    private final PriceResolver priceResolver;

    public QuotationService(QuotationRepository quotations, QuotationVersionRepository versions,
                            QuotationItemRepository items, CustomerRepository customers,
                            TenantRepository tenants, PriceResolver priceResolver) {
        this.quotations = quotations;
        this.versions = versions;
        this.items = items;
        this.customers = customers;
        this.tenants = tenants;
        this.priceResolver = priceResolver;
    }

    @Transactional
    public QuotationResponse create(QuotationCreateRequest req) {
        Customer customer = customers.findById(req.customerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        boolean interState = isInterState(customer.getStateCode());

        Quotation quotation = quotations.save(new Quotation(req.customerId(), req.enquiryId()));
        QuotationVersion version = versions.save(
            new QuotationVersion(quotation.getId(), 1, customer.getStateCode()));
        version.setHeader(req.validUntil(), req.paymentTerms(), req.deliveryTerms(), req.notes());
        buildItems(version, req.customerId(), req.items(), interState);
        quotation.setCurrentVersionId(version.getId());
        return toResponse(quotation);
    }

    @Transactional(readOnly = true)
    public QuotationResponse get(UUID id) {
        return toResponse(findQuotation(id));
    }

    // --- shared helpers used by later tasks (edit/send/revise) ---

    /** Recomputes item lines + version totals from the given item requests. Assumes DRAFT. */
    void buildItems(QuotationVersion version, UUID customerId, List<ItemRequest> itemReqs, boolean interState) {
        List<GstCalculator.LineResult> lineResults = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        int idx = 0;
        for (ItemRequest ir : itemReqs) {
            if (ir.qty() == null || ir.qty().compareTo(BigDecimal.ZERO) <= 0) {
                errors.put("items[" + idx + "].qty", "quantity must be greater than zero");
            }
            if (ir.discountPct() != null
                    && (ir.discountPct().compareTo(BigDecimal.ZERO) < 0
                        || ir.discountPct().compareTo(new BigDecimal("100")) > 0)) {
                errors.put("items[" + idx + "].discountPct", "discount must be between 0 and 100");
            }
            idx++;
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);

        for (ItemRequest ir : itemReqs) {
            PriceResolver.Resolved r = priceResolver.resolve(customerId, ir.productId());
            BigDecimal rate = ir.rate() != null ? ir.rate() : r.rate();
            BigDecimal discount = ir.discountPct() != null ? ir.discountPct() : BigDecimal.ZERO;
            GstCalculator.LineResult lr = GstCalculator.computeLine(
                new GstCalculator.LineInput(ir.qty(), rate, discount, r.gstRate()), interState);
            lineResults.add(lr);
            items.save(new QuotationItem(version.getId(), ir.productId(), r.name(), r.hsn(), r.uom(),
                ir.qty(), rate, discount, r.gstRate(), lr.taxableValue(), lr.cgst(), lr.sgst(),
                lr.igst(), lr.lineTotal()));
        }
        GstCalculator.Totals t = GstCalculator.totals(lineResults);
        version.setTotals(t.subTotal(), t.totalTax(), t.grandTotal());
    }

    boolean isInterState(String customerStateCode) {
        Tenant tenant = tenants.findById(TenantContext.tenantId())
            .orElseThrow(() -> new NotFoundException("tenant not found"));
        return !tenant.getStateCode().equals(customerStateCode);
    }

    Quotation findQuotation(UUID id) {
        return quotations.findById(id).orElseThrow(() -> new NotFoundException("quotation not found"));
    }

    QuotationResponse toResponse(Quotation q) {
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        var itemList = items.findByVersionId(v.getId());
        return QuotationResponse.of(q, QuotationVersionResponse.of(v, itemList));
    }
}
