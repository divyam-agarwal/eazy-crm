package com.easycrm.iam;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationExpiryAndRaceTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired InvitationRepository invitations;
    @Autowired UserRepository users;
    @Autowired TokenHasher hasher;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    private static final String ACCEPT = "{\"password\":\"correct-horse\"}";

    /** Write an invitation row directly, with an arbitrary expiry the API cannot produce. */
    private String seed(UUID tenantId, String email, Instant expiresAt) {
        String raw = "seeded-" + UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s -> invitations.save(new Invitation(
                tenantId, email, Role.SALES_EXEC, hasher.sha256Hex(raw),
                expiresAt, UUID.randomUUID())));
        } finally {
            TenantContext.clear();
        }
        return raw;
    }

    @Test
    void anExpiredInvitationCannotBeAccepted() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "expired@shop.in",
            Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(post("/api/v1/auth/invitations/" + raw + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    @Test
    void anExpiredInvitationCannotBePreviewed() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "expprev@shop.in",
            Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(get("/api/v1/auth/invitations/" + raw))
            .andExpect(status().isNotFound());
    }

    // Expiry is lazy (D6): the row stays PENDING, and the list DERIVES expired=true.
    // Nothing sweeps it — that is the deliberate difference from quotation expiry.
    @Test
    void anExpiredInvitationStillListsAsPendingButFlaggedExpired() throws Exception {
        var owner = tokens.provisionOwner("27");
        seed(owner.tenantId(), "flagged@shop.in", Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("flagged@shop.in"))
            .andExpect(jsonPath("$[0].expired").value(true));
    }

    /**
     * Two concurrent accepts of ONE token. Exactly one must win; the loser must not create
     * a second user. @Version on the invitation claim is what enforces it — the loser gets
     * a 409 from the OptimisticLockingFailureException handler, or a 404 if it lost the
     * read race instead. Either is acceptable; TWO users is not.
     */
    @Test
    void twoConcurrentAcceptsCreateExactlyOneUser() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "race@shop.in",
            Instant.now().plus(1, ChronoUnit.DAYS));

        Callable<Integer> attempt = () -> mvc.perform(
                post("/api/v1/auth/invitations/" + raw + "/accept")
                    .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andReturn().getResponse().getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(attempt, attempt));
            int created = 0;
            for (Future<Integer> f : results) {
                if (f.get() == 201) created++;
            }
            assertEquals(1, created, "exactly one accept may succeed");
        } finally {
            pool.shutdownNow();
        }

        // And exactly one user row exists for that address.
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s ->
                assertTrue(users.findByEmail("race@shop.in").isPresent(),
                    "the winning accept must have created the user"));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Two DIFFERENT invitations to one address, both accepted. The partial unique index
     * does not cover this (it only stops a second PENDING row existing at once — here the
     * first is ACCEPTED, which leaves PENDING and so frees the index for a second invite
     * to the same address). UNIQUE(tenant_id, email) on app_user is the only thing
     * standing between this and two users. Spec §6.4.
     */
    @Test
    void twoInvitationsToOneAddressCannotBothBecomeUsers() throws Exception {
        var owner = tokens.provisionOwner("27");
        String first = seed(owner.tenantId(), "twice@shop.in",
            Instant.now().plus(1, ChronoUnit.DAYS));

        // Accept the first so its row leaves PENDING, freeing the partial index, then seed
        // a second invitation to the same address — leaving one used token and one live
        // token for one address, which is the state under test.
        mvc.perform(post("/api/v1/auth/invitations/" + first + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        String second = seed(owner.tenantId(), "twice@shop.in",
            Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/auth/invitations/" + second + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isConflict());
    }
}
