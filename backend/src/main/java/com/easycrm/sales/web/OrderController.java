package com.easycrm.sales.web;

import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.OrderService;
import com.easycrm.sales.OrderStatus;
import com.easycrm.sales.web.dto.CancelRequest;
import com.easycrm.sales.web.dto.OrderResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/dispatch")
    public OrderResponse dispatch(@PathVariable UUID id) {
        return service.dispatch(id);
    }

    @PostMapping("/{id}/close")
    public OrderResponse close(@PathVariable UUID id) {
        return service.close(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest req) {
        return service.cancel(id, req.cancelReason());
    }

    @GetMapping
    public PageResponse<OrderResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID customerId,
            Pageable pageable) {
        return service.list(status, customerId, pageable);
    }
}
