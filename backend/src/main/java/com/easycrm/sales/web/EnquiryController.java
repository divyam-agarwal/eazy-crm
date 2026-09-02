package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.EnquiryService;
import com.easycrm.sales.EnquirySource;
import com.easycrm.sales.EnquiryStage;
import com.easycrm.sales.web.dto.AdvanceRequest;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import com.easycrm.sales.web.dto.EnquiryUpdateRequest;
import com.easycrm.sales.web.dto.LoseRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enquiries")
public class EnquiryController {

    private final EnquiryService service;

    public EnquiryController(EnquiryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EnquiryResponse> create(@Valid @RequestBody EnquiryCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public EnquiryResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Full header replace (not a partial merge): every editable field is set from the
     * body, so an omitted nullable field (assignedTo, expectedValue, contactEmail,
     * requirementText, customerId) is cleared. Clients MUST send the complete header.
     * Mirrors QuotationController.patch's full-replace convention; revisit PUT-vs-PATCH
     * house-wide when the frontend lands. Active-stage only (terminal enquiry -> 422).
     */
    @PatchMapping("/{id}")
    public EnquiryResponse update(@PathVariable UUID id, @Valid @RequestBody EnquiryUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/advance")
    public EnquiryResponse advance(@PathVariable UUID id, @Valid @RequestBody AdvanceRequest req) {
        return service.advance(id, req.stage());
    }

    @PostMapping("/{id}/lose")
    public EnquiryResponse lose(@PathVariable UUID id, @Valid @RequestBody LoseRequest req) {
        return service.lose(id, req.lostReason());
    }

    @GetMapping
    public PageResponse<EnquiryResponse> list(
            @RequestParam(required = false) EnquiryStage stage,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) EnquirySource source,
            @ParameterObject Pageable pageable) {
        return service.list(stage, assignedTo, source, pageable);
    }
}
