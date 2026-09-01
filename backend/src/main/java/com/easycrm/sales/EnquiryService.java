package com.easycrm.sales;

import com.easycrm.iam.AssignableUsers;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import com.easycrm.sales.web.dto.EnquiryUpdateRequest;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnquiryService {

    private final EnquiryRepository enquiries;
    private final VisibleFinder finder;
    private final AssignableUsers assignableUsers;

    public EnquiryService(EnquiryRepository enquiries, VisibleFinder finder, AssignableUsers assignableUsers) {
        this.enquiries = enquiries;
        this.finder = finder;
        this.assignableUsers = assignableUsers;
    }

    @Transactional
    public EnquiryResponse create(EnquiryCreateRequest req) {
        String normalized = PhoneNormalizer.normalize(req.contactPhone());
        requireNoActiveDuplicateExcept(normalized, null);
        assignableUsers.require(req.assignedTo());
        Enquiry saved = enquiries.save(new Enquiry(
                req.customerId(),
                req.contactName(),
                req.contactPhone(),
                normalized,
                req.contactEmail(),
                req.source(),
                req.requirementText(),
                req.assignedTo(),
                req.expectedValue()));
        return EnquiryResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public EnquiryResponse get(UUID id) {
        return EnquiryResponse.of(find(id));
    }

    @Transactional
    public EnquiryResponse update(UUID id, EnquiryUpdateRequest req) {
        Enquiry e = find(id);
        String normalized = PhoneNormalizer.normalize(req.contactPhone());
        requireNoActiveDuplicateExcept(normalized, id);
        assignableUsers.require(req.assignedTo());
        e.updateHeader(
                req.customerId(),
                req.contactName(),
                req.contactPhone(),
                normalized,
                req.contactEmail(),
                req.source(),
                req.requirementText(),
                req.assignedTo(),
                req.expectedValue());
        return EnquiryResponse.of(e);
    }

    @Transactional
    public EnquiryResponse advance(UUID id, EnquiryStage target) {
        Enquiry e = find(id);
        e.advanceTo(target);
        return EnquiryResponse.of(e);
    }

    @Transactional
    public EnquiryResponse lose(UUID id, String reason) {
        Enquiry e = find(id);
        e.lose(reason);
        return EnquiryResponse.of(e);
    }

    @Transactional(readOnly = true)
    public PageResponse<EnquiryResponse> list(
            EnquiryStage stage, UUID assignedTo, EnquirySource source, Pageable pageable) {
        return PageResponse.of(finder.pageEnquiries(EnquirySpecifications.filter(stage, assignedTo, source), pageable)
                .map(EnquiryResponse::of));
    }

    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404 — the
     * caller must not be able to tell them apart.
     */
    private Enquiry find(UUID id) {
        return finder.findEnquiry(id).orElseThrow(() -> new NotFoundException("enquiry not found"));
    }

    /**
     * App-level pre-check for the "one active enquiry per phone" invariant. This is
     * check-then-act; the partial unique index + the global DataIntegrityViolation->409
     * handler (challenge #15) is the concurrency backstop.
     *
     * <p>{@code selfId} excludes the enquiry currently being edited from the match, so an
     * edit that doesn't change the phone (or that changes it while remaining unique) isn't
     * blocked by itself. On create, {@code selfId} is {@code null}, which never equals any
     * UUID, so every active match still blocks.
     */
    private void requireNoActiveDuplicateExcept(String normalizedPhone, UUID selfId) {
        // Deliberately UNFILTERED: this pre-check must see every active enquiry in the
        // tenant, not just the caller's. Filtering it would let two reps each create an
        // active enquiry for the same phone. Spec 2026-08-29-record-visibility-design.md §6.
        enquiries.findByNormalizedPhone(normalizedPhone).stream()
                .filter(e -> e.getStage().isActive())
                .filter(e -> !e.getId().equals(selfId))
                .findAny()
                .ifPresent(e -> {
                    throw new ConflictException(
                            "an active enquiry already exists for this phone (id " + e.getId() + ")");
                });
    }
}
