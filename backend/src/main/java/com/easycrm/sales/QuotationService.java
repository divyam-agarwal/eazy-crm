package com.easycrm.sales;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.web.dto.AcceptRequest;
import com.easycrm.sales.web.dto.ItemRequest;
import com.easycrm.sales.web.dto.ItemsRequest;
import com.easycrm.sales.web.dto.OrderResponse;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationHeaderRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import com.easycrm.sales.web.dto.QuotationVersionResponse;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    private final DocumentNumberService documentNumbers;
    private final OrderRepository orders;
    private final EnquiryRepository enquiries;
    private final ApplicationEventPublisher events;

    public QuotationService(QuotationRepository quotations, QuotationVersionRepository versions,
                            QuotationItemRepository items, CustomerRepository customers,
                            TenantRepository tenants, PriceResolver priceResolver,
                            DocumentNumberService documentNumbers, OrderRepository orders,
                            EnquiryRepository enquiries, ApplicationEventPublisher events) {
        this.quotations = quotations;
        this.versions = versions;
        this.items = items;
        this.customers = customers;
        this.tenants = tenants;
        this.priceResolver = priceResolver;
        this.documentNumbers = documentNumbers;
        this.orders = orders;
        this.enquiries = enquiries;
        this.events = events;
    }

    @Transactional
    public QuotationResponse create(QuotationCreateRequest req) {
        Customer customer = customers.findById(req.customerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        boolean interState = isInterState(customer.getStateCode());

        if (req.enquiryId() != null) {
            Enquiry enquiry = enquiries.findById(req.enquiryId())
                .orElseThrow(() -> new NotFoundException("enquiry not found"));
            enquiry.markConverted(); // 422 if the enquiry is already terminal
        }

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

    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> list(QuotationStatus status, UUID customerId, Pageable pageable) {
        Page<Quotation> page = quotations.findAll(
            QuotationSpecifications.filter(status, customerId), pageable);
        return PageResponse.of(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<QuotationVersionResponse> getVersions(UUID quotationId) {
        findQuotation(quotationId); // 404 if not visible
        return versions.findByQuotationIdOrderByVersionNoAsc(quotationId).stream()
            .map(v -> QuotationVersionResponse.of(v, items.findByVersionId(v.getId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public QuotationVersionResponse getVersion(UUID quotationId, int versionNo) {
        findQuotation(quotationId);
        QuotationVersion v = versions.findByQuotationIdAndVersionNo(quotationId, versionNo)
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        return QuotationVersionResponse.of(v, items.findByVersionId(v.getId()));
    }

    @Transactional
    public QuotationResponse patchHeader(UUID id, QuotationHeaderRequest req) {
        Quotation q = findQuotation(id);
        QuotationVersion v = requireDraft(q);
        v.setHeader(req.validUntil(), req.paymentTerms(), req.deliveryTerms(), req.notes());
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse replaceItems(UUID id, ItemsRequest req) {
        Quotation q = findQuotation(id);
        QuotationVersion v = requireDraft(q);
        items.deleteByVersionId(v.getId());
        Customer customer = customers.findById(q.getCustomerId())
            .orElseThrow(() -> new NotFoundException("customer not found"));
        buildItems(v, q.getCustomerId(), req.items(), isInterState(customer.getStateCode()));
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse send(UUID id) {
        Quotation q = findQuotation(id);
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new ValidationException("status", "only a draft quotation can be sent");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        if (q.getQuoteNo() == null) {
            q.assignQuoteNo(documentNumbers.nextQuoteNo(LocalDate.now()));
        }
        q.markSent();
        v.markSent(Instant.now());
        return toResponse(q);
    }

    @Transactional
    public OrderResponse accept(UUID id, AcceptRequest req) {
        Quotation q = findQuotation(id);
        if (q.getStatus() == QuotationStatus.ACCEPTED) {
            // Idempotent: return the order already created for this quotation — unless it
            // was cancelled, in which case there is no live order to hand back. Reopening
            // means a new quotation: UNIQUE(tenant_id, quotation_id) on sales_order makes
            // one-order-per-quotation structural, so a second order here is impossible.
            Order existing = orders.findByQuotationId(q.getId())
                .orElseThrow(() -> new NotFoundException("order not found"));
            if (existing.getStatus() == OrderStatus.CANCELLED) {
                throw new ValidationException("status",
                    "the order for this quotation was cancelled; raise a new quotation");
            }
            return OrderResponse.of(existing);
        }
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be accepted");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        Order order = orders.save(new Order(q.getId(), v.getId(), q.getCustomerId(),
            documentNumbers.nextOrderNo(LocalDate.now()),
            v.getSubTotal(), v.getTotalTax(), v.getGrandTotal(),
            req.poReference(), req.poDate()));
        q.markAccepted();
        UUID actorUserId = TenantContext.get()
            .map(TenantContext.TenantPrincipal::userId).orElse(null);
        events.publishEvent(new QuotationAcceptedEvent(q.getId(), order.getId(), v.getId(),
            order.getGrandTotal(), order.getOrderNo(), actorUserId));
        return OrderResponse.of(order);
    }

    @Transactional
    public QuotationResponse revise(UUID id) {
        Quotation q = findQuotation(id);
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be revised");
        }
        QuotationVersion prev = versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
        QuotationVersion next = versions.save(
            new QuotationVersion(q.getId(), prev.getVersionNo() + 1, prev.getPlaceOfSupply()));
        next.setHeader(prev.getValidUntil(), prev.getPaymentTerms(),
                       prev.getDeliveryTerms(), prev.getNotes());
        // Copy the previous version's frozen items verbatim (already-computed values).
        for (QuotationItem s : items.findByVersionId(prev.getId())) {
            items.save(new QuotationItem(next.getId(), s.getProductId(), s.getNameSnapshot(),
                s.getHsnSnapshot(), s.getUomSnapshot(), s.getQty(), s.getRate(), s.getDiscountPct(),
                s.getGstRate(), s.getTaxableValue(), s.getCgst(), s.getSgst(), s.getIgst(),
                s.getLineTotal()));
        }
        next.setTotals(prev.getSubTotal(), prev.getTotalTax(), prev.getGrandTotal());
        q.setCurrentVersionId(next.getId());
        q.reviseToDraft();
        return toResponse(q);
    }

    /** The current version must be an editable DRAFT; a SENT (frozen) version is immutable. */
    private QuotationVersion requireDraft(Quotation q) {
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new ValidationException("status", "only a draft quotation can be edited");
        }
        return versions.findById(q.getCurrentVersionId())
            .orElseThrow(() -> new NotFoundException("quotation version not found"));
    }

    @Transactional
    public QuotationResponse reject(UUID id) {
        Quotation q = findQuotation(id);
        requireSent(q, "rejected");
        q.reject();
        return toResponse(q);
    }

    @Transactional
    public QuotationResponse expire(UUID id) {
        Quotation q = findQuotation(id);
        requireSent(q, "expired");
        q.expire();
        return toResponse(q);
    }

    private void requireSent(Quotation q, String verb) {
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new ValidationException("status", "only a sent quotation can be " + verb);
        }
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
            if (ir.rate() != null && ir.rate().compareTo(BigDecimal.ZERO) < 0) {
                errors.put("items[" + idx + "].rate", "rate must not be negative");
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
