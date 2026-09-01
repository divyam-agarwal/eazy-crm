package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.QuotationService;
import com.easycrm.sales.QuotationStatus;
import com.easycrm.sales.ShareLinkService;
import com.easycrm.sales.pdf.QuotationPdfService;
import com.easycrm.sales.web.dto.AcceptRequest;
import com.easycrm.sales.web.dto.ItemsRequest;
import com.easycrm.sales.web.dto.OrderResponse;
import com.easycrm.sales.web.dto.QuotationCreateRequest;
import com.easycrm.sales.web.dto.QuotationHeaderRequest;
import com.easycrm.sales.web.dto.QuotationResponse;
import com.easycrm.sales.web.dto.QuotationVersionResponse;
import com.easycrm.sales.web.dto.ShareResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private final QuotationService service;
    private final QuotationPdfService pdfService;
    private final ShareLinkService shareLinks;

    public QuotationController(QuotationService service, QuotationPdfService pdfService, ShareLinkService shareLinks) {
        this.service = service;
        this.pdfService = pdfService;
        this.shareLinks = shareLinks;
    }

    @PostMapping
    public ResponseEntity<QuotationResponse> create(@Valid @RequestBody QuotationCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public QuotationResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

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

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        byte[] bytes = pdfService.renderByQuotation(id, version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }

    @PatchMapping("/{id}")
    public QuotationResponse patch(@PathVariable UUID id, @Valid @RequestBody QuotationHeaderRequest req) {
        return service.patchHeader(id, req);
    }

    @PutMapping("/{id}/items")
    public QuotationResponse replaceItems(@PathVariable UUID id, @Valid @RequestBody ItemsRequest req) {
        return service.replaceItems(id, req);
    }

    @PostMapping("/{id}/send")
    public QuotationResponse send(@PathVariable UUID id) {
        return service.send(id);
    }

    @PostMapping("/{id}/accept")
    public OrderResponse accept(@PathVariable UUID id, @RequestBody(required = false) AcceptRequest req) {
        return service.accept(id, req == null ? new AcceptRequest(null, null) : req);
    }

    @PostMapping("/{id}/revise")
    public QuotationResponse revise(@PathVariable UUID id) {
        return service.revise(id);
    }

    @PostMapping("/{id}/reject")
    public QuotationResponse reject(@PathVariable UUID id) {
        return service.reject(id);
    }

    @PostMapping("/{id}/expire")
    public QuotationResponse expire(@PathVariable UUID id) {
        return service.expire(id);
    }

    @PostMapping("/{id}/share")
    public ShareResponse share(@PathVariable UUID id) {
        return shareLinks.share(id);
    }
}
