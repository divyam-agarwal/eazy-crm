package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.EnquiryService;
import com.easycrm.sales.EnquirySource;
import com.easycrm.sales.EnquiryStage;
import com.easycrm.sales.web.dto.EnquiryCreateRequest;
import com.easycrm.sales.web.dto.EnquiryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/enquiries")
public class EnquiryController {

    private final EnquiryService service;

    public EnquiryController(EnquiryService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<EnquiryResponse> create(@Valid @RequestBody EnquiryCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public EnquiryResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<EnquiryResponse> list(
            @RequestParam(required = false) EnquiryStage stage,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) EnquirySource source,
            Pageable pageable) {
        return service.list(stage, assignedTo, source, pageable);
    }
}
