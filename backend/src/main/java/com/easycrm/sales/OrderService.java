package com.easycrm.sales;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) { this.orders = orders; }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.of(orders.findById(id)
            .orElseThrow(() -> new NotFoundException("order not found")));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, UUID customerId, Pageable pageable) {
        Page<Order> page;
        if (status != null) page = orders.findByStatus(status, pageable);
        else if (customerId != null) page = orders.findByCustomerId(customerId, pageable);
        else page = orders.findAll(pageable);
        return PageResponse.of(page.map(OrderResponse::of));
    }
}
