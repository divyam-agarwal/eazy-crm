package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for Quotation's own transition preconditions. No Spring, no database. */
class QuotationTest {

    private Quotation sentQuotation() {
        Quotation q = new Quotation(UUID.randomUUID(), null);
        q.markSent();
        return q;
    }

    @Test
    void expiresASentQuotation() {
        Quotation q = sentQuotation();
        q.expire();
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void refusesToExpireADraftAndLeavesTheStatusUnmutated() {
        Quotation q = new Quotation(UUID.randomUUID(), null); // starts DRAFT
        assertThatThrownBy(q::expire)
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("only a sent quotation can be expired");
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void refusesToExpireAnAcceptedQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.markAccepted();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void refusesToExpireARejectedQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.reject();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void refusesToExpireAnAlreadyExpiredQuotationAndLeavesTheStatusUnmutated() {
        Quotation q = sentQuotation();
        q.expire();
        assertThatThrownBy(q::expire).isInstanceOf(ValidationException.class);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }
}
