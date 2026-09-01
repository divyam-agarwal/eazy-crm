package com.easycrm.catalog.web;

import com.easycrm.catalog.PriceListItemService;
import com.easycrm.catalog.web.dto.PriceListItemRequest;
import com.easycrm.catalog.web.dto.PriceListItemResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/price-lists/{priceListId}/items")
public class PriceListItemController {

    private final PriceListItemService service;

    public PriceListItemController(PriceListItemService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PriceListItemResponse> add(
            @PathVariable UUID priceListId, @Valid @RequestBody PriceListItemRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(priceListId, req));
    }

    @GetMapping
    public List<PriceListItemResponse> list(@PathVariable UUID priceListId) {
        return service.list(priceListId);
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> delete(@PathVariable UUID priceListId, @PathVariable UUID itemId) {
        service.delete(priceListId, itemId);
        return ResponseEntity.noContent().build();
    }
}
