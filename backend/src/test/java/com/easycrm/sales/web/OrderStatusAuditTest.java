package com.easycrm.sales.web;

import com.easycrm.iam.AuditLogRepository;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderStatusAuditTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired AuditLogRepository audits;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    /** Customer + product + quotation -> send -> accept. Returns the new order's id. */
    private String createOrder(String auth) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
            .andExpect(status().isOk());
        return JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void dispatchAndCloseEachWriteAnAuditRow() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/" + id + "/close").header("Authorization", auth))
            .andExpect(status().isOk());

        // RLS-scoped read: set the tenant context, then count inside a transaction.
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long dispatched = tx.execute(s -> audits.countByAction("ORDER_DISPATCHED"));
        long closed = tx.execute(s -> audits.countByAction("ORDER_CLOSED"));
        long cancelled = tx.execute(s -> audits.countByAction("ORDER_CANCELLED"));
        assertThat(dispatched).isEqualTo(1);
        assertThat(closed).isEqualTo(1);
        assertThat(cancelled).isEqualTo(0);
    }

    @Test
    void cancelWritesAnAuditRowAndARejectedTransitionWritesNone() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        String id = createOrder(auth);

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk());
        // rejected transition on a terminal order: no second row
        mvc.perform(post("/api/v1/orders/" + id + "/dispatch").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());

        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long cancelled = tx.execute(s -> audits.countByAction("ORDER_CANCELLED"));
        long dispatched = tx.execute(s -> audits.countByAction("ORDER_DISPATCHED"));
        assertThat(cancelled).isEqualTo(1);
        assertThat(dispatched).isEqualTo(0);
    }

    /**
     * Regression guard: the existing tests above only count rows, so OrderService could
     * regress to reading status *after* the mutation (making from == to) and every count
     * assertion would still pass. This asserts the detail map itself: `from` must carry the
     * pre-transition status, and `cancelReason` must be present only on the cancel row.
     */
    @Test
    void auditDetailCarriesPreTransitionStatusAndCancelReasonOnlyWhereExpected() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();

        String dispatchedOrderId = createOrder(auth);
        mvc.perform(post("/api/v1/orders/" + dispatchedOrderId + "/dispatch").header("Authorization", auth))
            .andExpect(status().isOk());

        String cancelledOrderId = createOrder(auth);
        mvc.perform(post("/api/v1/orders/" + cancelledOrderId + "/cancel").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"cancelReason":"customer withdrew PO"}"""))
            .andExpect(status().isOk());

        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        var dispatchedRow = tx.execute(s -> audits.findFirstByAction("ORDER_DISPATCHED")).orElseThrow();
        assertThat(dispatchedRow.getDetail()).containsEntry("from", "CONFIRMED");
        assertThat(dispatchedRow.getDetail()).doesNotContainKey("cancelReason");

        var cancelledRow = tx.execute(s -> audits.findFirstByAction("ORDER_CANCELLED")).orElseThrow();
        assertThat(cancelledRow.getDetail()).containsEntry("from", "CONFIRMED");
        assertThat(cancelledRow.getDetail()).containsEntry("cancelReason", "customer withdrew PO");
    }
}
