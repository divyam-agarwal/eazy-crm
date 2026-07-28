package com.easycrm.sales;

import java.util.UUID;

/**
 * One event for all three transitions, so the number of event types stays fixed as
 * statuses are added. {@code cancelReason} is null except on a cancellation.
 */
public record OrderStatusChangedEvent(UUID orderId, String orderNo, OrderStatus from,
                                      OrderStatus to, String cancelReason, UUID actorUserId) {}
