package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ConflictException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    private Invitation pending() {
        return new Invitation(
                UUID.randomUUID(),
                "ravi@shop.in",
                Role.SALES_EXEC,
                "a".repeat(64),
                NOW.plus(7, ChronoUnit.DAYS),
                UUID.randomUUID());
    }

    @Test
    void startsPending() {
        assertEquals(InvitationStatus.PENDING, pending().getStatus());
    }

    @Test
    void acceptRecordsTheUserAndTime() {
        Invitation inv = pending();
        UUID userId = UUID.randomUUID();
        inv.accept(userId, NOW);
        assertEquals(InvitationStatus.ACCEPTED, inv.getStatus());
        assertEquals(userId, inv.getAcceptedUserId());
        assertEquals(NOW, inv.getAcceptedAt());
    }

    // The entity carries its own precondition rather than trusting the service to have
    // checked — the same reason Quotation.expire() re-asserts SENT.
    @Test
    void acceptRejectsAnAlreadyAcceptedInvitation() {
        Invitation inv = pending();
        inv.accept(UUID.randomUUID(), NOW);
        assertThrows(ConflictException.class, () -> inv.accept(UUID.randomUUID(), NOW));
    }

    @Test
    void acceptRejectsARevokedInvitation() {
        Invitation inv = pending();
        inv.revoke();
        assertThrows(ConflictException.class, () -> inv.accept(UUID.randomUUID(), NOW));
    }

    @Test
    void revokeRejectsAnAcceptedInvitation() {
        Invitation inv = pending();
        inv.accept(UUID.randomUUID(), NOW);
        assertThrows(ConflictException.class, inv::revoke);
    }

    @Test
    void revokeIsNotIdempotent() {
        Invitation inv = pending();
        inv.revoke();
        assertThrows(ConflictException.class, inv::revoke);
    }

    @Test
    void isExpiredIsFalseBeforeTheBoundaryAndTrueAfter() {
        Invitation inv = pending(); // expires NOW + 7d
        assertFalse(inv.isExpired(NOW));
        assertFalse(inv.isExpired(NOW.plus(7, ChronoUnit.DAYS))); // exactly at expiry
        assertTrue(inv.isExpired(NOW.plus(7, ChronoUnit.DAYS).plusSeconds(1)));
    }
}
