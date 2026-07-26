package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.QuotationService;
import com.easycrm.sales.QuotationStatus;
import com.easycrm.sales.web.dto.ItemsRequest;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationHeaderRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import com.easycrm.sales.web.dto.QuotationVersionResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private final QuotationService service;

    public QuotationController(QuotationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<QuotationResponse> create(@Valid @RequestBody QuotationCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public QuotationResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<QuotationResponse> list(
            @RequestParam(required = false) QuotationStatus status,
            @RequestParam(required = false) UUID customerId,
            Pageable pageable) {
        return service.list(status, customerId, pageable);
    }

    @GetMapping("/{id}/versions")
    public List<QuotationVersionResponse> versions(@PathVariable UUID id) {
        return service.getVersions(id);
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public QuotationVersionResponse version(@PathVariable UUID id, @PathVariable int versionNo) {
        return service.getVersion(id, versionNo);
    }

    @PatchMapping("/{id}")
    public QuotationResponse patch(@PathVariable UUID id,
                                   @Valid @RequestBody QuotationHeaderRequest req) {
        return service.patchHeader(id, req);
    }

    @PutMapping("/{id}/items")
    public QuotationResponse replaceItems(@PathVariable UUID id,
                                          @Valid @RequestBody ItemsRequest req) {
        return service.replaceItems(id, req);
    }
}
