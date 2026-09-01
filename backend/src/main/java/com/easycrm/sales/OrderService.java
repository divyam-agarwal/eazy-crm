package com.easycrm.sales;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.web.dto.OrderResponse;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final ApplicationEventPublisher events;
    private final VisibleFinder finder;

    public OrderService(ApplicationEventPublisher events, VisibleFinder finder) {
        this.events = events;
        this.finder = finder;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, UUID customerId, Pageable pageable) {
        return PageResponse.of(finder.pageOrders(OrderSpecifications.filter(status, customerId), pageable)
                .map(OrderResponse::of));
    }

    @Transactional
    public OrderResponse dispatch(UUID id) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.dispatch();
        publish(o, from);
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse close(UUID id) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.close();
        publish(o, from);
        return OrderResponse.of(o);
    }

    @Transactional
    public OrderResponse cancel(UUID id, String reason) {
        Order o = find(id);
        OrderStatus from = o.getStatus();
        o.cancel(reason);
        publish(o, from);
        return OrderResponse.of(o);
    }

    private void publish(Order o, OrderStatus from) {
        UUID actorUserId =
                TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
        events.publishEvent(new OrderStatusChangedEvent(
                o.getId(), o.getOrderNo(), from, o.getStatus(), o.getCancelReason(), actorUserId));
    }

    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404 — the
     * caller must not be able to tell them apart.
     */
    private Order find(UUID id) {
        return finder.findOrder(id).orElseThrow(() -> new NotFoundException("order not found"));
    }
}
