package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    /** A freshly accepted order: CONFIRMED, no cancel reason. */
    private Order newOrder() {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "ORD/2526/0001", new BigDecimal("100.00"), new BigDecimal("18.00"),
            new BigDecimal("118.00"), null, null);
    }

    private Order dispatched() {
        Order o = newOrder();
        o.dispatch();
        return o;
    }

    private Order closed() {
        Order o = dispatched();
        o.close();
        return o;
    }

    private Order cancelled() {
        Order o = newOrder();
        o.cancel("customer withdrew PO");
        return o;
    }

    @Test
    void startsConfirmedWithNoCancelReason() {
        Order o = newOrder();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getCancelReason()).isNull();
    }

    @Test
    void terminalPredicatesMatchTheGraph() {
        assertThat(OrderStatus.CONFIRMED.isActive()).isTrue();
        assertThat(OrderStatus.DISPATCHED.isActive()).isTrue();
        assertThat(OrderStatus.CLOSED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void dispatchThenCloseIsTheHappyPath() {
        Order o = newOrder();
        o.dispatch();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        o.close();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void dispatchRejectedUnlessConfirmed() {
        assertThatThrownBy(() -> dispatched().dispatch()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> closed().dispatch()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().dispatch()).isInstanceOf(ValidationException.class);
    }

    @Test
    void closeRejectedUnlessDispatched() {
        // no skipping dispatch: CONFIRMED -> CLOSED is not a legal edge
        assertThatThrownBy(() -> newOrder().close()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> closed().close()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().close()).isInstanceOf(ValidationException.class);
    }

    @Test
    void cancelAllowedFromBothActiveStatesAndStoresTheReason() {
        Order fromConfirmed = newOrder();
        fromConfirmed.cancel("customer withdrew PO");
        assertThat(fromConfirmed.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fromConfirmed.getCancelReason()).isEqualTo("customer withdrew PO");

        Order fromDispatched = dispatched();
        fromDispatched.cancel("goods returned");
        assertThat(fromDispatched.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fromDispatched.getCancelReason()).isEqualTo("goods returned");
    }

    @Test
    void cancelRejectedOnTerminalStates() {
        assertThatThrownBy(() -> closed().cancel("too late"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cancelled().cancel("again"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void cancelRequiresANonBlankReasonAndLeavesTheOrderUntouched() {
        assertThatThrownBy(() -> newOrder().cancel(null)).isInstanceOf(ValidationException.class);
        Order o = newOrder();
        assertThatThrownBy(() -> o.cancel("   ")).isInstanceOf(ValidationException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getCancelReason()).isNull();
    }
}
