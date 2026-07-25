package com.easycrm.catalog.web;

import com.easycrm.catalog.ProductService;
import com.easycrm.catalog.web.dto.ProductCreateRequest;
import com.easycrm.catalog.web.dto.ProductResponse;
import com.easycrm.catalog.web.dto.ProductUpdateRequest;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<ProductResponse> list(@RequestParam(required = false) Boolean active,
                                              Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody ProductUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public ProductResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }

    @PostMapping("/{id}/activate")
    public ProductResponse activate(@PathVariable UUID id) { return service.activate(id); }
}
