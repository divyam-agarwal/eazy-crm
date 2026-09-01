package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationExpiryAndRaceTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    UserRepository users;

    @Autowired
    TokenHasher hasher;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static final String ACCEPT = "{\"password\":\"correct-horse\"}";

    /** Write an invitation row directly, with an arbitrary expiry the API cannot produce. */
    private String seed(UUID tenantId, String email, Instant expiresAt) {
        String raw = "seeded-" + UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s -> invitations.save(new Invitation(
                    tenantId, email, Role.SALES_EXEC, hasher.sha256Hex(raw), expiresAt, UUID.randomUUID())));
        } finally {
            TenantContext.clear();
        }
        return raw;
    }

    /** The 404 body an accept produces for the given token. */
    private String rejectedAcceptBody(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCEPT))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** The 404 body a preview produces for the given token. */
    private String rejectedPreviewBody(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/invitations/" + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Asserted as BYTES, not merely as a status. "Expired" is the state most likely to
     * attract a helpful message later ("this link has expired, ask for a new one"), and
     * that message is exactly the enumeration leak challenge #55 closes: it confirms the
     * token was real. A status-only assertion would not notice.
     */
    @Test
    void anExpiredInvitationCannotBeAccepted() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "expired@shop.in", Instant.now().minus(1, ChronoUnit.MINUTES));

        assertEquals(
                rejectedAcceptBody("never-existed"),
                rejectedAcceptBody(raw),
                "an expired token must be indistinguishable from one that never existed");
    }

    @Test
    void anExpiredInvitationCannotBePreviewed() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "expprev@shop.in", Instant.now().minus(1, ChronoUnit.MINUTES));

        assertEquals(
                rejectedPreviewBody("never-existed"),
                rejectedPreviewBody(raw),
                "preview must not reveal that a token merely expired");
    }

    /**
     * Expiry is lazy (D6), so an expired invitation stays PENDING forever — and the
     * partial unique index is PENDING-scoped, not expiry-aware. Left alone, that means the
     * owner's list shows "expired: true" while a re-invite to the same address is refused
     * with "that email already has a pending invitation", about the very row the API just
     * called expired. The invite path retires the dead row instead.
     */
    @Test
    void anExpiredInvitationDoesNotBlockReInvitingThatAddress() throws Exception {
        var owner = tokens.provisionOwner("27");
        seed(owner.tenantId(), "again@shop.in", Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"again@shop.in\",\"role\":\"SALES_EXEC\"}"))
                .andExpect(status().isCreated());

        // Exactly one PENDING row survives: the dead one was revoked, not left alongside.
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].expired").value(false));
    }

    /** A LIVE pending invitation still blocks a re-invite — only the expired one is retired. */
    @Test
    void aLivePendingInvitationStillBlocksReInviting() throws Exception {
        var owner = tokens.provisionOwner("27");
        seed(owner.tenantId(), "live@shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"live@shop.in\",\"role\":\"SALES_EXEC\"}"))
                .andExpect(status().isConflict());
    }

    // Expiry is lazy (D6): the row stays PENDING, and the list DERIVES expired=true.
    // Nothing sweeps it — that is the deliberate difference from quotation expiry.
    @Test
    void anExpiredInvitationStillListsAsPendingButFlaggedExpired() throws Exception {
        var owner = tokens.provisionOwner("27");
        seed(owner.tenantId(), "flagged@shop.in", Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("flagged@shop.in"))
                .andExpect(jsonPath("$[0].expired").value(true));
    }

    /**
     * Two concurrent accepts of ONE token. Exactly one must win; the loser must not create
     * a second user. @Version on the invitation claim is what enforces it — the loser gets
     * a 409 from the OptimisticLockingFailureException handler, or a 404 if it lost the
     * read race instead. Either is acceptable; TWO users is not.
     *
     * <p>The CyclicBarrier is load-bearing. Without it the two tasks merely tend to
     * overlap, and on a run where the first finishes before the second starts the test
     * passes while exercising no concurrency at all — a green light for a @Version that
     * had been removed. The barrier makes the overlap a precondition rather than luck. It
     * cannot deadlock: the pool has exactly two threads and invokeAll submits exactly two
     * tasks, so both parties always arrive.
     */
    @Test
    void twoConcurrentAcceptsCreateExactlyOneUser() throws Exception {
        var owner = tokens.provisionOwner("27");
        String raw = seed(owner.tenantId(), "race@shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        CyclicBarrier startLine = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            startLine.await();
            return mvc.perform(post("/api/v1/auth/invitations/" + raw + "/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ACCEPT))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

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
            tx.executeWithoutResult(s -> assertTrue(
                    users.findByEmail("race@shop.in").isPresent(), "the winning accept must have created the user"));
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
        String first = seed(owner.tenantId(), "twice@shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        // Accept the first so its row leaves PENDING, freeing the partial index, then seed
        // a second invitation to the same address — leaving one used token and one live
        // token for one address, which is the state under test.
        mvc.perform(post("/api/v1/auth/invitations/" + first + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCEPT))
                .andExpect(status().isCreated());

        String second = seed(owner.tenantId(), "twice@shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/auth/invitations/" + second + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCEPT))
                .andExpect(status().isConflict());
    }

    /**
     * The same shape as the test above, but with the two addresses differing ONLY in case.
     * uq_user_tenant_email (V6) compares the raw column, so it sees two different strings
     * and lets both through — leaving one tenant with two ACTIVE users, possibly with
     * different roles, for one human. uq_user_tenant_email_lower (V32) is what actually
     * stops it; the service's findByEmailIgnoreCase pre-check cannot, because these two
     * accepts arrive on different valid tokens and neither is an invite.
     */
    @Test
    void twoCaseVariantsOfOneAddressCannotBothBecomeUsers() throws Exception {
        var owner = tokens.provisionOwner("27");
        String first = seed(owner.tenantId(), "casey@shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/auth/invitations/" + first + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCEPT))
                .andExpect(status().isCreated());

        // Accepting the first freed the PENDING partial index, so a differently-spelled
        // second invitation to the same human can exist alongside it.
        String second = seed(owner.tenantId(), "Casey@Shop.in", Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/auth/invitations/" + second + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACCEPT))
                .andExpect(status().isConflict());
    }
}
