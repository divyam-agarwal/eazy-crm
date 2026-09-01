package com.easycrm.catalog.web;

import com.easycrm.catalog.PriceListService;
import com.easycrm.catalog.web.dto.PriceListRequest;
import com.easycrm.catalog.web.dto.PriceListResponse;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/price-lists")
public class PriceListController {

    private final PriceListService service;

    public PriceListController(PriceListService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PriceListResponse> create(@Valid @RequestBody PriceListRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public PriceListResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public PageResponse<PriceListResponse> list(@RequestParam(required = false) Boolean active, Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public PriceListResponse rename(@PathVariable UUID id, @Valid @RequestBody PriceListRequest req) {
        return service.rename(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public PriceListResponse deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public PriceListResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}
