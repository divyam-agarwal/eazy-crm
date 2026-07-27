package com.easycrm.sales;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EnquiryService {

    private final EnquiryRepository enquiries;

    public EnquiryService(EnquiryRepository enquiries) { this.enquiries = enquiries; }

    @Transactional
    public EnquiryResponse create(EnquiryCreateRequest req) {
        String normalized = PhoneNormalizer.normalize(req.contactPhone());
        requireNoActiveDuplicate(normalized);
        Enquiry saved = enquiries.save(new Enquiry(
            req.customerId(), req.contactName(), req.contactPhone(), normalized,
            req.contactEmail(), req.source(), req.requirementText(),
            req.assignedTo(), req.expectedValue()));
        return EnquiryResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public EnquiryResponse get(UUID id) {
        return EnquiryResponse.of(enquiries.findById(id)
            .orElseThrow(() -> new NotFoundException("enquiry not found")));
    }

    @Transactional(readOnly = true)
    public PageResponse<EnquiryResponse> list(
            EnquiryStage stage, UUID assignedTo, EnquirySource source, Pageable pageable) {
        return PageResponse.of(
            enquiries.findAll(EnquirySpecifications.filter(stage, assignedTo, source), pageable)
                .map(EnquiryResponse::of));
    }

    /**
     * App-level pre-check for the "one active enquiry per phone" invariant. This is
     * check-then-act; the partial unique index + the global DataIntegrityViolation->409
     * handler (challenge #15) is the concurrency backstop.
     */
    private void requireNoActiveDuplicate(String normalizedPhone) {
        enquiries.findByNormalizedPhone(normalizedPhone).stream()
            .filter(e -> e.getStage().isActive())
            .findAny()
            .ifPresent(e -> {
                throw new ConflictException(
                    "an active enquiry already exists for this phone (id " + e.getId() + ")");
            });
    }
}
