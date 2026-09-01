# User Invitations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `OWNER` invites an email address with a role; the invitee follows a tokened link, sets a password, and becomes an `ACTIVE` user of that tenant.

**Architecture:** `invitation` is the third **global** (non-tenant-scoped) table after `refresh_token` and `share_link` — accept is pre-auth, so the tenant has to be resolved from the token itself. The token is 256 random bits, SHA-256-hashed at rest, single-use. Accept binds `TenantContext` **before** opening its transaction (challenge #9), because `User` is `@TenantId` + RLS and a Hibernate session resolves its tenant at session-open. A `RoleGuard` extracted into `platform/security` carries the `OWNER` check that `TenantService` currently hand-rolls.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA / Hibernate (`@TenantId`), PostgreSQL + Flyway + RLS, JUnit 5, MockMvc, Testcontainers, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-01-user-invitations-design.md` — read it alongside this plan; every task below argues from a numbered decision (D1–D10) or section in it.

## Global Constraints

- **Baseline:** `main` at `830f4bd`, branch `user-invitations`. The suite is **464 tests, 0 failures, 0 errors** before Task 1. Every task must leave it green.
- **Counting tests spans two Gradle projects.** Use `find . -path '*/build/test-results/test/*.xml'` — a root-only variant reports 441 and the 23-test gap is a phantom.
- **A filtered test run MUST be project-qualified:** `./gradlew :test --tests '…'` for root-project tests. Unqualified `./gradlew test --tests '…'` applies the filter to *every* project and fails on whichever has no match.
- **Docker must be running** before any test run (`open -a Docker`, wait for `docker info`). Testcontainers starts one shared Postgres 16 container per JVM.
- **Money is never a `double`.** Not touched by this slice, but the rule stands.
- **Tenant isolation is structural.** Never hand-write `WHERE tenant_id = ?`. `invitation` is a deliberate, allowlisted exception and is the only new table permitted to carry a plain `tenant_id` column — Task 2 registers it in both guards.
- **Commits:** author as `divyam`, plain `git commit`. Never mention Claude/AI, never add a `Co-Authored-By` trailer.
- **The plaintext invite token is a bearer credential.** Never log it. `Invitation` gets no `toString()`.

---

### Task 1: `RoleGuard` — extract the owner check

Pure refactor, no behaviour change. It exists first because Tasks 3 and 4 depend on it, and doing it alone makes it reviewable as a refactor rather than hidden inside a feature.

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/security/RoleGuard.java`
- Modify: `backend/src/main/java/com/easycrm/tenant/TenantService.java` (drop the private `requireOwner()`, inject and call `RoleGuard`)
- Test: `backend/src/test/java/com/easycrm/platform/security/RoleGuardTest.java`

**Interfaces:**
- Consumes: `TenantContext.get()`, `ForbiddenException` — both existing.
- Produces: `RoleGuard.requireOwner(String message)` — throws `ForbiddenException(message)` unless the bound principal's role is `"OWNER"`. Tasks 3 and 4 call it.

It lives in `platform/security`, **not** `iam`: `iam` already depends on `tenant` (`AuthService` imports `Tenant`, `TenantRepository`), so a guard in `iam` called from `TenantService` would make those two packages mutually dependent. See spec §5.1.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/security/RoleGuardTest.java`:

```java
package com.easycrm.platform.security;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoleGuardTest {

    private final RoleGuard guard = new RoleGuard();

    @AfterEach void clear() { TenantContext.clear(); }

    private void bind(String role) {
        TenantContext.set(new TenantContext.TenantPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), role));
    }

    @Test
    void ownerPasses() {
        bind("OWNER");
        assertDoesNotThrow(() -> guard.requireOwner("nope"));
    }

    @Test
    void salesManagerIsRejected() {
        bind("SALES_MANAGER");
        ForbiddenException ex =
            assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
        assertEquals("nope", ex.getMessage(), "the caller's message must reach the 403 body");
    }

    @Test
    void salesExecIsRejected() {
        bind("SALES_EXEC");
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }

    // The unauthenticated case must be a 403 from the guard, not a NullPointerException.
    // TenantContext.get() returns Optional.empty() when nothing is bound.
    @Test
    void noPrincipalIsRejected() {
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }

    // A principal whose role is null (SYSTEM contexts built by AuthService pass a role
    // string, but nothing structurally prevents null) must not blow up the comparison.
    @Test
    void nullRoleIsRejected() {
        TenantContext.set(new TenantContext.TenantPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), null));
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.security.RoleGuardTest'
```

Expected: FAIL — compilation error, `RoleGuard` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/easycrm/platform/security/RoleGuard.java`:

```java
package com.easycrm.platform.security;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Component;

/**
 * The one place an "is the caller an owner?" check lives. Extracted from
 * {@code TenantService}'s private copy when {@code InvitationService} became the second
 * caller — one copy earlier than {@code AssignableUsers} was extracted, deliberately.
 *
 * <p>Lives in {@code platform.security} rather than {@code iam} to avoid a package cycle:
 * {@code iam} already depends on {@code tenant}, so a guard in {@code iam} called from
 * {@code TenantService} would make the two mutually dependent. It therefore compares the
 * literal {@code "OWNER"} rather than {@code Role.OWNER.name()} — {@code Role} lives in
 * {@code iam}, and {@code TenantPrincipal.role} is a String regardless.
 *
 * <p>The message is caller-supplied so each 403 body stays as specific as the hand-rolled
 * checks were.
 */
@Component
public class RoleGuard {

    private static final String OWNER = "OWNER";

    public void requireOwner(String message) {
        String role = TenantContext.get()
            .map(TenantContext.TenantPrincipal::role)
            .orElse(null);
        if (!OWNER.equals(role)) {
            throw new ForbiddenException(message);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.platform.security.RoleGuardTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Switch `TenantService` to the guard**

In `backend/src/main/java/com/easycrm/tenant/TenantService.java`:

Delete the private method entirely:

```java
    private void requireOwner() {
        String role = TenantContext.get().map(TenantContext.TenantPrincipal::role).orElse(null);
        if (!"OWNER".equals(role)) {
            throw new ForbiddenException("only an owner may change the business profile");
        }
    }
```

Add the field and constructor parameter:

```java
    private final TenantRepository tenants;
    private final RoleGuard roleGuard;

    public TenantService(TenantRepository tenants, RoleGuard roleGuard) {
        this.tenants = tenants;
        this.roleGuard = roleGuard;
    }
```

Change the call site in `updateProfile` from `requireOwner();` to:

```java
        roleGuard.requireOwner("only an owner may change the business profile");
```

Fix imports: add `import com.easycrm.platform.security.RoleGuard;`. Remove `import com.easycrm.platform.error.ForbiddenException;` **only if** nothing else in the file still throws it — check first; `NotFoundException` and `TenantContext` are still used by `current()`, so leave those.

- [ ] **Step 6: Run the whole suite — the refactor's regression proof**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **469 tests, 0 failures** (464 + 5 new). Any existing tenant-profile test that asserts a 403 must still pass **unchanged** — that is what proves this is a refactor and not a behaviour change. If one needed editing, stop: the extraction changed behaviour.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/easycrm/platform/security/RoleGuard.java \
        backend/src/main/java/com/easycrm/tenant/TenantService.java \
        backend/src/test/java/com/easycrm/platform/security/RoleGuardTest.java
git commit -m "refactor: extract the owner check into a shared RoleGuard

TenantService hand-rolled the codebase's only role check. InvitationService
needs the same one, so extract it now rather than plant a second copy — one
copy earlier than AssignableUsers was extracted, deliberately.

It lives in platform/security, not iam: iam already depends on tenant, so a
guard in iam called from TenantService would make the two packages mutually
dependent. The message stays caller-supplied so each 403 body keeps the
specificity the hand-rolled checks had.

TenantService's existing tests are unchanged, which is the proof this is a
refactor."
```

---

### Task 2: The `invitation` table, entity, and both isolation guards

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__invitation.sql`
- Create: `backend/src/main/java/com/easycrm/iam/Invitation.java`
- Create: `backend/src/main/java/com/easycrm/iam/InvitationStatus.java`
- Create: `backend/src/main/java/com/easycrm/iam/InvitationRepository.java`
- Modify: `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java:19-23` (`GLOBAL_TABLES`)
- Modify: `backend/src/test/java/com/easycrm/platform/tenancy/RlsCoverageIntegrationTest.java:49-52` (`GLOBAL_TABLES`)
- Test: `backend/src/test/java/com/easycrm/iam/InvitationTest.java`

**Interfaces:**
- Consumes: `BaseEntity` (id, createdAt, updatedAt, `@Version version`), `Role`, `ConflictException`.
- Produces:
  - `enum InvitationStatus { PENDING, ACCEPTED, REVOKED }`
  - `Invitation(UUID tenantId, String email, Role role, String tokenHash, Instant expiresAt, UUID invitedBy)` — starts `PENDING`
  - `void accept(UUID userId, Instant when)`, `void revoke()`, `boolean isExpired(Instant now)`
  - getters: `getTenantId()`, `getEmail()`, `getRole()`, `getTokenHash()`, `getStatus()`, `getExpiresAt()`, `getInvitedBy()`, `getAcceptedAt()`, `getAcceptedUserId()`
  - `InvitationRepository.findByTokenHash(String)`, `.findByTenantIdAndStatus(UUID, InvitationStatus)`

- [ ] **Step 1: Write the failing entity test**

Create `backend/src/test/java/com/easycrm/iam/InvitationTest.java`:

```java
package com.easycrm.iam;

import com.easycrm.platform.error.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    private Invitation pending() {
        return new Invitation(UUID.randomUUID(), "ravi@shop.in", Role.SALES_EXEC,
            "a".repeat(64), NOW.plus(7, ChronoUnit.DAYS), UUID.randomUUID());
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
        Invitation inv = pending();                        // expires NOW + 7d
        assertFalse(inv.isExpired(NOW));
        assertFalse(inv.isExpired(NOW.plus(7, ChronoUnit.DAYS)));       // exactly at expiry
        assertTrue(inv.isExpired(NOW.plus(7, ChronoUnit.DAYS).plusSeconds(1)));
    }
}
```

Note the boundary: `isExpired` uses `expiresAt.isBefore(now)`, so an invitation is **not** expired at the exact instant it expires. The test pins that.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.InvitationTest'
```

Expected: FAIL — `Invitation` and `InvitationStatus` do not exist.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V31__invitation.sql`:

```sql
-- GLOBAL table: deliberately NO row-level security and NO @TenantId, for the same reason
-- as refresh_token and share_link. Accepting an invitation happens with no JWT, so there
-- is no tenant to filter by — this row is what resolves one. The user it creates is then
-- written through @TenantId + RLS as normal.
--
-- Registered in BOTH TenantScopingArchTest.GLOBAL_TABLES (layer 2) and
-- RlsCoverageIntegrationTest.GLOBAL_TABLES (layer 3). Omitting either fails the build.
CREATE TABLE invitation (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    email            VARCHAR(255) NOT NULL,
    role             VARCHAR(16) NOT NULL,
    -- VARCHAR, not CHAR: Hibernate maps String to varchar and ddl-auto: validate would
    -- reject a bpchar column. refresh_token.token_hash is VARCHAR(64) for the same reason.
    token_hash       VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    invited_by       UUID NOT NULL,
    accepted_at      TIMESTAMPTZ,
    accepted_user_id UUID,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

-- The accept lookup, and the uniqueness the token's security relies on.
CREATE UNIQUE INDEX uq_invitation_token_hash ON invitation (token_hash);

-- At most one LIVE invitation per address per tenant. PARTIAL, so accepted and revoked
-- rows accumulate freely as history. lower(email) so a second invite to a case variant
-- ("Ravi@shop.in" vs "ravi@shop.in") collides too. This makes a double-invite a
-- database-level conflict rather than a check-then-act race in the service.
CREATE UNIQUE INDEX uq_invitation_pending_email
    ON invitation (tenant_id, lower(email))
    WHERE status = 'PENDING';

-- The owner's pending list — the only list query this slice adds. Shipped in the creating
-- migration per the standing agreement (HANDOFF §8): one line now, a migration on a live
-- table later.
CREATE INDEX idx_invitation_tenant_status ON invitation (tenant_id, status, expires_at);
```

- [ ] **Step 4: Write the enum and entity**

Create `backend/src/main/java/com/easycrm/iam/InvitationStatus.java`:

```java
package com.easycrm.iam;

public enum InvitationStatus { PENDING, ACCEPTED, REVOKED }
```

Create `backend/src/main/java/com/easycrm/iam/Invitation.java`:

```java
package com.easycrm.iam;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * GLOBAL table (intentionally NOT tenant-scoped), like {@code refresh_token} and
 * {@code share_link}: accepting an invitation is pre-auth and must resolve a tenant from
 * the opaque token alone, so this cannot be tenant-filtered.
 *
 * <p>The token is stored HASHED, unlike {@code share_link}'s plaintext one. That token
 * only reads a frozen document and is deliberately idempotent; this one CREATES an
 * authenticated principal with a role, which is refresh-token-grade capability and gets
 * refresh-token-grade handling. It is also single-use — "the same link keeps working" is
 * the failure mode here, not the feature. See spec 2026-09-01 §3.
 *
 * <p>The plaintext token is a bearer credential and exists only in the mint response; it
 * is never stored, never logged, and this class has no toString().
 */
@Entity
@Table(name = "invitation")
public class Invitation extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    protected Invitation() {}

    public Invitation(UUID tenantId, String email, Role role, String tokenHash,
                      Instant expiresAt, UUID invitedBy) {
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
        this.status = InvitationStatus.PENDING;
    }

    public void accept(UUID userId, Instant when) {
        requirePending();
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedUserId = userId;
        this.acceptedAt = when;
    }

    public void revoke() {
        requirePending();
        this.status = InvitationStatus.REVOKED;
    }

    /** Not expired AT the boundary instant — only strictly after it. */
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    /**
     * The entity carries its own precondition rather than trusting the caller to have
     * checked — the same reason {@code Quotation.expire()} re-asserts SENT.
     */
    private void requirePending() {
        if (status != InvitationStatus.PENDING) {
            throw new ConflictException("invitation is no longer pending");
        }
    }

    public UUID getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public String getTokenHash() { return tokenHash; }
    public InvitationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public UUID getAcceptedUserId() { return acceptedUserId; }
}
```

Create `backend/src/main/java/com/easycrm/iam/InvitationRepository.java`:

```java
package com.easycrm.iam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    // Derived queries are not transactional by default, which would leave the RLS GUC
    // unset. invitation has no RLS, but the annotation keeps these consistent with the
    // rest of the codebase and correct if a policy is ever added — the same reasoning
    // ShareLinkRepository documents.
    @Transactional(readOnly = true)
    Optional<Invitation> findByTokenHash(String tokenHash);

    @Transactional(readOnly = true)
    List<Invitation> findByTenantIdAndStatus(UUID tenantId, InvitationStatus status);
}
```

- [ ] **Step 5: Run the entity test**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.InvitationTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Watch both isolation guards fail, then register the table**

Run them **before** editing the allowlists — seeing them fail is what proves they are load-bearing:

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.arch.TenantScopingArchTest' \
                             --tests 'com.easycrm.platform.tenancy.RlsCoverageIntegrationTest'
```

Expected: BOTH FAIL. `TenantScopingArchTest` reports `Invitation` is not assignable to `TenantScopedEntity`; `RlsCoverageIntegrationTest` reports `invitation` has RLS neither enabled, forced, nor policied.

Now add the entry to `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java`:

```java
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "com.easycrm.tenant.Tenant",
        "com.easycrm.iam.RefreshToken",   // pre-auth session table, looked up by hash
        "com.easycrm.sales.ShareLink",    // pre-auth share table: resolves the tenant itself
        "com.easycrm.iam.Invitation"      // pre-auth invite table: resolves the tenant itself
    );
```

And to `backend/src/test/java/com/easycrm/platform/tenancy/RlsCoverageIntegrationTest.java`:

```java
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "refresh_token",   // pre-auth session table, looked up by hash
        "share_link",      // pre-auth share table: resolves the tenant itself
        "invitation"       // pre-auth invite table: resolves the tenant itself
    );
```

- [ ] **Step 7: Run both guards again**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.arch.TenantScopingArchTest' \
                             --tests 'com.easycrm.platform.tenancy.RlsCoverageIntegrationTest'
```

Expected: BOTH PASS. `RlsCoverageIntegrationTest` also asserts no **stale** exemptions, so a typo in either table name fails here rather than silently exempting nothing.

- [ ] **Step 8: Run the whole suite (Flyway + Hibernate validate)**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **476 tests, 0 failures** (469 + 7). Every `@SpringBootTest` boots the schema, so a mismatch between `V31` and the entity mapping fails here — `ddl-auto: validate` is what catches a wrong column type or a missing column.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V31__invitation.sql \
        backend/src/main/java/com/easycrm/iam/Invitation.java \
        backend/src/main/java/com/easycrm/iam/InvitationStatus.java \
        backend/src/main/java/com/easycrm/iam/InvitationRepository.java \
        backend/src/test/java/com/easycrm/iam/InvitationTest.java \
        backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java \
        backend/src/test/java/com/easycrm/platform/tenancy/RlsCoverageIntegrationTest.java
git commit -m "feat: add the invitation table, entity and repository

The third global (non-tenant-scoped) table, after refresh_token and
share_link, for the same reason: accepting an invitation is pre-auth, so
the tenant has to come from the token row itself.

The token is hashed at rest and single-use, unlike share_link's plaintext
idempotent token — presenting this one creates an authenticated principal
rather than reading a frozen document, so it follows refresh_token's rules.

Ships three indexes at creation: unique token_hash, a PARTIAL unique
(tenant_id, lower(email)) WHERE PENDING so a double-invite is a database
conflict rather than a service-level race, and (tenant_id, status,
expires_at) for the owner's pending list.

Registered in both isolation guards. Both were watched failing first."
```

---

### Task 3: Invite — `POST /api/v1/invitations`

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/InvitationService.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/InvitationController.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/InviteRequest.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/InvitationResponse.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/InvitationControllerTest.java`

**Interfaces:**
- Consumes: `RoleGuard.requireOwner(String)` (Task 1); `Invitation`, `InvitationRepository`, `InvitationStatus` (Task 2); `TokenHasher.sha256Hex(String)`, `UserRepository.findByEmail(String)`, `AuditService.record(String, UUID, Map)`, `EmailSender.send(String,String,String)`, `TenantContext`.
- Produces:
  - `InvitationService.invite(InviteRequest req)` → `InvitationResponse`
  - `record InviteRequest(String email, String role)`
  - `record InvitationResponse(UUID id, String email, String role, Instant expiresAt, String acceptUrl)` — `acceptUrl` is null on the list endpoint in Task 4, populated only here.
  - Constant `InvitationService.TTL_DAYS = 7`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/web/InvitationControllerTest.java`:

```java
package com.easycrm.iam.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationControllerTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String invite(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    @Test
    void ownerCanInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("ravi@shop.in", "SALES_EXEC")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("ravi@shop.in"))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"))
            .andExpect(jsonPath("$.expiresAt").exists())
            // The plaintext token is returned exactly once, embedded in the accept URL.
            .andExpect(jsonPath("$.acceptUrl").exists());
    }

    @Test
    void salesExecCannotInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + exec)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("ravi@shop.in", "SALES_EXEC")))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesManagerCannotInvite() throws Exception {
        var owner = tokens.provisionOwner("27");
        String mgr = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_MANAGER");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + mgr)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("ravi@shop.in", "SALES_EXEC")))
            .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/api/v1/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("ravi@shop.in", "SALES_EXEC")))
            .andExpect(status().isUnauthorized());
    }

    // The partial unique index is the backstop; the service pre-check produces the clean 409.
    @Test
    void aSecondPendingInviteToTheSameAddressIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("dup@shop.in", "SALES_EXEC")))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("dup@shop.in", "SALES_EXEC")))
            .andExpect(status().isConflict());
    }

    // lower(email) in the index means a case variant is the same address.
    @Test
    void aCaseVariantOfAPendingAddressIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("case@shop.in", "SALES_EXEC")))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("CASE@shop.in", "SALES_EXEC")))
            .andExpect(status().isConflict());
    }

    @Test
    void anOwnerMayInviteAnotherOwner() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("co@shop.in", "OWNER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void aMalformedEmailIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("not-an-email", "SALES_EXEC")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownRoleIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invite("who@shop.in", "ADMIN")))
            .andExpect(status().isBadRequest());
    }
}
```

`@Pattern` on the role field (not a `Role` enum parameter) is what makes an unknown role a `400` from bean validation rather than a Jackson deserialisation `500`.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationControllerTest'
```

Expected: FAIL — 404 on every request; no controller exists.

- [ ] **Step 3: Write the DTOs**

Create `backend/src/main/java/com/easycrm/iam/web/dto/InviteRequest.java`:

```java
package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code role} is a validated String rather than a {@code Role} parameter on purpose: an
 * unknown value must be a 400 from bean validation, not a Jackson deserialisation failure.
 */
public record InviteRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "OWNER|SALES_MANAGER|SALES_EXEC",
                       message = "role must be OWNER, SALES_MANAGER or SALES_EXEC")
    String role
) {}
```

Create `backend/src/main/java/com/easycrm/iam/web/dto/InvitationResponse.java`:

```java
package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code acceptUrl} carries the plaintext token and is populated ONLY by the mint
 * response — the token is hashed at rest and cannot be recovered afterwards, so the
 * pending-list variant leaves it null.
 */
public record InvitationResponse(UUID id, String email, String role,
                                 Instant expiresAt, String acceptUrl) {}
```

- [ ] **Step 4: Write the service**

Create `backend/src/main/java/com/easycrm/iam/InvitationService.java`:

```java
package com.easycrm.iam;

import com.easycrm.iam.email.EmailSender;
import com.easycrm.iam.web.dto.InvitationResponse;
import com.easycrm.iam.web.dto.InviteRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class InvitationService {

    static final long TTL_DAYS = 7;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final TokenHasher hasher;
    private final RoleGuard roleGuard;
    private final AuditService audit;
    private final EmailSender emailSender;
    private final String publicBaseUrl;

    public InvitationService(InvitationRepository invitations, UserRepository users,
                             TokenHasher hasher, RoleGuard roleGuard, AuditService audit,
                             EmailSender emailSender,
                             @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.hasher = hasher;
        this.roleGuard = roleGuard;
        this.audit = audit;
        this.emailSender = emailSender;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Runs under the owner's JWT, so the user lookup below is RLS-scoped as normal.
     *
     * <p>The write runs through TransactionTemplate rather than @Transactional on this
     * method: a self-invoked @Transactional method is not proxied and its annotation is
     * silently ignored. AuthService uses the same form for the same reason.
     *
     * <p>The email is sent AFTER commit — no email for a rollback, matching
     * AuthService.signup.
     */
    public InvitationResponse invite(InviteRequest req) {
        roleGuard.requireOwner("only an owner may invite users");

        String rawToken = randomToken();
        Minted minted = tx.execute(status -> mintInTransaction(req, rawToken));

        String acceptUrl = publicBaseUrl + "/invite/" + rawToken;
        emailSender.send(req.email(), "You have been invited to EasyCRM",
            "Open this link to join: " + acceptUrl);

        return new InvitationResponse(minted.id(), minted.email(), req.role(),
            minted.expiresAt(), acceptUrl);
    }

    private record Minted(UUID id, String email, Instant expiresAt) {}

    /** Carries no annotation on purpose — the caller supplies the transaction. */
    private Minted mintInTransaction(InviteRequest req, String rawToken) {
        UUID tenantId = TenantContext.tenantId();
        UUID invitedBy = TenantContext.get()
            .map(TenantContext.TenantPrincipal::userId).orElse(null);

        // Already a member? The read is RLS-scoped to this tenant.
        users.findByEmail(req.email()).ifPresent(u -> {
            throw new ConflictException("that email is already a user of this workspace");
        });

        // Already invited? The partial unique index is the real guard against a race; this
        // pre-check exists so the ordinary case gets a clear message rather than the
        // DataIntegrityViolation backstop's generic one.
        boolean alreadyPending = invitations
            .findByTenantIdAndStatus(tenantId, InvitationStatus.PENDING).stream()
            .anyMatch(i -> i.getEmail().toLowerCase(Locale.ROOT)
                            .equals(req.email().toLowerCase(Locale.ROOT)));
        if (alreadyPending) {
            throw new ConflictException("that email already has a pending invitation");
        }

        Invitation inv = invitations.save(new Invitation(
            tenantId, req.email(), Role.valueOf(req.role()), hasher.sha256Hex(rawToken),
            Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS), invitedBy));

        audit.record("INVITE_SENT", invitedBy,
            Map.of("email", req.email(), "role", req.role()));

        return new Minted(inv.getId(), inv.getEmail(), inv.getExpiresAt());
    }

    private String randomToken() {
        byte[] bytes = new byte[32]; // 256 bits, matching RefreshTokenService
        random.nextBytes(bytes);
        return base64Url.encodeToString(bytes);
    }
}
```

`TransactionTemplate tx` joins the constructor alongside the fields already listed above.

- [ ] **Step 5: Write the controller**

Create `backend/src/main/java/com/easycrm/iam/web/InvitationController.java`:

```java
package com.easycrm.iam.web;

import com.easycrm.iam.InvitationService;
import com.easycrm.iam.web.dto.InvitationResponse;
import com.easycrm.iam.web.dto.InviteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, owner-only. The pre-auth half lives in PublicInvitationController. */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody InviteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.invite(req));
    }
}
```

`/api/**` is already `authenticated()` in `SecurityConfig`, so the unauthenticated 401 needs no new configuration.

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationControllerTest'
```

Expected: PASS, 9 tests.

- [ ] **Step 7: Run the whole suite**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **485 tests, 0 failures** (476 + 9).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/InvitationService.java \
        backend/src/main/java/com/easycrm/iam/web/InvitationController.java \
        backend/src/main/java/com/easycrm/iam/web/dto/InviteRequest.java \
        backend/src/main/java/com/easycrm/iam/web/dto/InvitationResponse.java \
        backend/src/test/java/com/easycrm/iam/web/InvitationControllerTest.java
git commit -m "feat: an owner can invite a user by email and role

POST /api/v1/invitations mints a 256-bit token, stores its SHA-256 hash,
and returns the plaintext exactly once inside acceptUrl. RoleGuard makes it
owner-only — the first endpoint outside the tenant profile to carry a role
check.

The link is both returned and pushed at EmailSender: LoggingEmailSender only
logs, so returning it is what makes the feature usable on day one, the same
way ShareLinkService returns a URL for the owner to paste into WhatsApp. A
real sender later needs no API change.

The email is sent after commit, so a rollback sends nothing.

Inviting an address that is already a member, or that already has a pending
invitation (case-insensitively), is a 409."
```

---

### Task 4: Pending list and revoke

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/InvitationService.java` (add `listPending`, `revoke`)
- Modify: `backend/src/main/java/com/easycrm/iam/web/InvitationController.java` (add `GET`, `DELETE`)
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/PendingInvitationResponse.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/InvitationListRevokeTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces:
  - `InvitationService.listPending()` → `List<PendingInvitationResponse>`
  - `InvitationService.revoke(UUID id)` → `void`
  - `record PendingInvitationResponse(UUID id, String email, String role, Instant expiresAt, boolean expired)`

`expired` is **derived on read**, never written (D6 — expiry is lazy, no sweep job). See spec §7 for why this differs from quotation expiry.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/web/InvitationListRevokeTest.java`:

```java
package com.easycrm.iam.web;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationListRevokeTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String inviteAs(String bearer, String email) throws Exception {
        return mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void pendingListShowsTheInviteAndNeverTheToken() throws Exception {
        var owner = tokens.provisionOwner("27");
        inviteAs(owner.token(), "list1@shop.in");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("list1@shop.in"))
            .andExpect(jsonPath("$[0].role").value("SALES_EXEC"))
            .andExpect(jsonPath("$[0].expired").value(false))
            // The token is hashed at rest and must never come back out.
            .andExpect(jsonPath("$[0].acceptUrl").doesNotExist())
            .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void revokeRemovesItFromThePendingList() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "revoke1@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // Revoking frees the address: the partial unique index only covers PENDING rows, so a
    // re-invite after a revoke must succeed. This is the "resend" path (spec §3).
    @Test
    void revokeThenReinviteSucceeds() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "resend@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        inviteAs(owner.token(), "resend@shop.in");   // 201 asserted inside the helper
    }

    @Test
    void revokingTwiceIs409() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "twice@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isConflict());
    }

    @Test
    void revokingAnUnknownIdIs404() throws Exception {
        var owner = tokens.provisionOwner("27");
        mvc.perform(delete("/api/v1/invitations/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNotFound());
    }

    @Test
    void salesExecCanNeitherListNorRevoke() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = inviteAs(owner.token(), "guard@shop.in");
        String id = JsonPath.read(body, "$.id");
        String exec = tokens.as(owner.tenantId(), UUID.randomUUID(), "SALES_EXEC");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + exec))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + exec))
            .andExpect(status().isForbidden());
    }

    // invitation is a GLOBAL table with no RLS, so tenant scoping here is the service's
    // job and has to be proven rather than assumed.
    @Test
    void anotherTenantsOwnerSeesNothingAndCannotRevoke() throws Exception {
        var a = tokens.provisionOwner("27");
        var b = tokens.provisionOwner("29");
        String body = inviteAs(a.token(), "tenanta@shop.in");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + b.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + b.token()))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationListRevokeTest'
```

Expected: FAIL — no `GET`/`DELETE` mapping; 405 or 404.

- [ ] **Step 3: Write the DTO**

Create `backend/src/main/java/com/easycrm/iam/web/dto/PendingInvitationResponse.java`:

```java
package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * No token field, deliberately: the token is hashed at rest and cannot be recovered. A
 * "resend" is revoke + re-invite, which mints a new one.
 *
 * <p>{@code expired} is DERIVED at read time and never stored — invitation expiry is lazy
 * (spec §7), unlike quotation expiry, which is materialised by a nightly job.
 */
public record PendingInvitationResponse(UUID id, String email, String role,
                                        Instant expiresAt, boolean expired) {}
```

- [ ] **Step 4: Add the service methods**

Add to `InvitationService`:

```java
    @Transactional(readOnly = true)
    public List<PendingInvitationResponse> listPending() {
        roleGuard.requireOwner("only an owner may view invitations");
        Instant now = Instant.now();
        return invitations
            .findByTenantIdAndStatus(TenantContext.tenantId(), InvitationStatus.PENDING)
            .stream()
            .map(i -> new PendingInvitationResponse(i.getId(), i.getEmail(),
                i.getRole().name(), i.getExpiresAt(), i.isExpired(now)))
            .toList();
    }

    @Transactional
    public void revoke(UUID id) {
        roleGuard.requireOwner("only an owner may revoke an invitation");
        Invitation inv = invitations.findById(id)
            .filter(i -> i.getTenantId().equals(TenantContext.tenantId()))
            .orElseThrow(() -> new NotFoundException("invitation not found"));
        inv.revoke();                 // throws ConflictException unless PENDING
        invitations.save(inv);
        audit.record("INVITE_REVOKED",
            TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null),
            Map.of("email", inv.getEmail()));
    }
```

New imports on `InvitationService`: `java.util.List`, `com.easycrm.platform.error.NotFoundException`, `com.easycrm.iam.web.dto.PendingInvitationResponse`.

**The `.filter(...)` on `findById` is load-bearing and is the one place this codebase hand-writes a tenant comparison.** `invitation` is global, so neither `@TenantId` nor RLS filters it — without that line, one tenant's owner could revoke another tenant's invitation by id. Returning `NotFoundException` rather than `ForbiddenException` for a foreign id is deliberate: a 403 would confirm the id exists.

- [ ] **Step 5: Add the controller mappings**

Add to `InvitationController`:

```java
    @GetMapping
    public List<PendingInvitationResponse> listPending() {
        return invitations.listPending();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        invitations.revoke(id);
        return ResponseEntity.noContent().build();
    }
```

New imports: `java.util.List`, `java.util.UUID`, `com.easycrm.iam.web.dto.PendingInvitationResponse`, and `GetMapping`, `DeleteMapping`, `PathVariable`.

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationListRevokeTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Run the whole suite**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **492 tests, 0 failures** (485 + 7).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/InvitationService.java \
        backend/src/main/java/com/easycrm/iam/web/InvitationController.java \
        backend/src/main/java/com/easycrm/iam/web/dto/PendingInvitationResponse.java \
        backend/src/test/java/com/easycrm/iam/web/InvitationListRevokeTest.java
git commit -m "feat: an owner can list pending invitations and revoke one

Revoke is what makes a mis-sent invite recoverable; the share-link slice
shipped without it and that regret is still open on the backlog.

The partial unique index only covers PENDING rows, so revoke frees the
address and revoke + re-invite is the resend path — which correctly leaves
only one live link rather than two.

Neither response carries a token: it is hashed at rest and unrecoverable.
The expired flag is derived at read time, never stored — invitation expiry
is lazy, unlike quotation expiry, which a nightly job materialises.

invitation is a global table, so revoke's tenant check is hand-written and
load-bearing: without it one tenant's owner could revoke another's by id. A
foreign id returns 404, not 403, so it cannot be used to prove an id exists."
```

---

### Task 5: Accept — the pre-auth path

The hard task. Read spec §6.2 before starting.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/InvitationService.java` (add `accept`)
- Create: `backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/AcceptInvitationRequest.java`
- Modify: `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java` (two `permitAll` matchers)
- Test: `backend/src/test/java/com/easycrm/iam/web/InvitationAcceptTest.java`

**Interfaces:**
- Consumes: Tasks 1–4; plus `PasswordEncoder`, `JwtService.mint(UUID,UUID,String)`, `RefreshTokenService.issue(UUID,UUID)`, `TransactionTemplate`, `User`, `UserStatus`, `AuthResponse`.
- Produces:
  - `InvitationService.accept(String rawToken, AcceptInvitationRequest req)` → `AuthResponse`
  - `record AcceptInvitationRequest(String password, String phone)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/web/InvitationAcceptTest.java`:

```java
package com.easycrm.iam.web;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationAcceptTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired com.easycrm.platform.ratelimit.RateLimitProperties rateLimits;

    @AfterEach void clear() { TenantContext.clear(); }

    private static final String ACCEPT = "{\"password\":\"correct-horse\"}";

    /** Invite, and return the raw token pulled out of acceptUrl's last path segment. */
    private String inviteAndExtractToken(String bearer, String email, String role)
            throws Exception {
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        return acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
    }

    @Test
    void acceptCreatesAnActiveUserWithTheInvitedRoleAndReturnsTokens() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "new@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.userId").exists())
            // The user lands in the INVITING tenant, with the INVITED role.
            .andExpect(jsonPath("$.tenantId").value(owner.tenantId().toString()))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    /**
     * D7's justification, made executable: login requires a tenant SLUG the invitee has
     * never seen, so the tokens returned by accept are the only way in. This asserts the
     * returned access token actually authenticates.
     */
    @Test
    void theReturnedAccessTokenWorksImmediately() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "works@shop.in", "SALES_EXEC");

        String body = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("works@shop.in"))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    @Test
    void theInvitationIsSingleUse() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "once@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    @Test
    void anAcceptedInviteLeavesThePendingList() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "gone@shop.in", "SALES_EXEC");

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aRevokedInvitationCannotBeAccepted() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rev@shop.in\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownTokenIs404() throws Exception {
        mvc.perform(post("/api/v1/auth/invitations/not-a-real-token/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound());
    }

    // Enumeration safety: an unknown token and a consumed one must be indistinguishable —
    // same status AND same body. A different message would confirm a token had existed.
    @Test
    void aConsumedTokenIsIndistinguishableFromAnUnknownOne() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "enum@shop.in", "SALES_EXEC");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        String consumed = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/api/v1/auth/invitations/nonexistent-token/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(unknown, consumed,
            "a consumed token must be indistinguishable from one that never existed");
    }

    @Test
    void aShortPasswordIs400() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "short@shop.in", "SALES_EXEC");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * The route's rate-limit protection comes ENTIRELY from living under /api/v1/auth/**:
     * RateLimitProperties.policyFor leaves an unmatched path UNLIMITED, so moving this
     * endpoint elsewhere would silently uncap it. The limiter itself is disabled for the
     * suite (see IntegrationTest), so assert the policy resolution rather than the 429.
     */
    @Test
    void theAcceptRouteResolvesToTheAuthRateLimitPolicy() {
        var policy = rateLimits.policyFor("/api/v1/auth/invitations/some-token/accept");
        org.junit.jupiter.api.Assertions.assertTrue(policy.isPresent(),
            "the accept route must match a rate-limit policy — an unmatched path is unlimited");
        assertEquals("auth", policy.get().name());
    }

    @Test
    void acceptingAnOwnerInvitationYieldsAnOwner() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "co@shop.in", "OWNER");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("OWNER"));
    }

    // The accepted user must be a real member of the tenant, not a phantom: they can
    // exercise a tenant-scoped read that RLS governs.
    @Test
    void theAcceptedUserCanReadTenantScopedData() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "reads@shop.in", "SALES_EXEC");
        String body = mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationAcceptTest'
```

Expected: FAIL — 401 (the route is not permitted) or 404 (no mapping).

- [ ] **Step 3: Write the request DTO**

Create `backend/src/main/java/com/easycrm/iam/web/dto/AcceptInvitationRequest.java`:

```java
package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The password constraints are copied from SignupRequest verbatim so an invited user's
 * password rules cannot drift from a self-serve owner's.
 */
public record AcceptInvitationRequest(
    @NotBlank @Size(min = 8, message = "password must be at least 8 characters")
    String password,
    String phone
) {}
```

- [ ] **Step 4: Write `accept` — mind the ordering**

Add to `InvitationService` (new fields `PasswordEncoder encoder`, `JwtService jwt`, `RefreshTokenService refreshTokens`, `TransactionTemplate tx`, all constructor-injected):

```java
    /**
     * Pre-auth. NOT @Transactional: the tenant context must be set BEFORE the transaction
     * (and its Hibernate session) opens, because a session resolves its tenant only at
     * open and TenantAwareTransactionManager reads it in doBegin to set the RLS GUC. The
     * User insert below is @TenantId + RLS, so getting this order wrong does not throw —
     * it silently writes an unbound row. Same trap as challenge #9 and #52.
     */
    public AuthResponse accept(String rawToken, AcceptInvitationRequest req) {
        // Global table: no tenant context needed, and none exists yet.
        Invitation inv = invitations.findByTokenHash(hasher.sha256Hex(rawToken))
            .filter(i -> i.getStatus() == InvitationStatus.PENDING)
            .filter(i -> !i.isExpired(Instant.now()))
            .orElseThrow(() -> new NotFoundException("invitation not found"));

        TenantContext.set(new TenantContext.TenantPrincipal(inv.getTenantId(), null, "SYSTEM"));
        try {
            return tx.execute(status -> {
                // Re-read inside the transaction and claim it. @Version means a concurrent
                // second accept of this same token loses here and gets a 409.
                Invitation claimed = invitations.findById(inv.getId())
                    .orElseThrow(() -> new NotFoundException("invitation not found"));

                User user = users.save(new User(
                    claimed.getEmail(), req.phone(), encoder.encode(req.password()),
                    claimed.getRole(), UserStatus.ACTIVE));

                claimed.accept(user.getId(), Instant.now());
                invitations.save(claimed);

                audit.record("INVITE_ACCEPTED", user.getId(),
                    Map.of("email", claimed.getEmail(), "role", claimed.getRole().name()));

                String access = jwt.mint(claimed.getTenantId(), user.getId(),
                    claimed.getRole().name());
                String refresh = refreshTokens.issue(user.getId(), claimed.getTenantId());
                return new AuthResponse(access, refresh, claimed.getTenantId(),
                    user.getId(), claimed.getRole().name());
            });
        } finally {
            TenantContext.clear();
        }
    }
```

Every rejected state — unknown, revoked, accepted, expired — funnels into the **same** `NotFoundException("invitation not found")` from the one `orElseThrow`. That is what makes the states indistinguishable; do not split it into per-state messages.

New imports: `com.easycrm.iam.web.dto.AcceptInvitationRequest`, `com.easycrm.iam.web.dto.AuthResponse`, `com.easycrm.platform.security.JwtService`, `org.springframework.security.crypto.password.PasswordEncoder`, `org.springframework.transaction.support.TransactionTemplate`.

- [ ] **Step 5: Write the public controller**

Create `backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java`:

```java
package com.easycrm.iam.web;

import com.easycrm.iam.InvitationService;
import com.easycrm.iam.web.dto.AcceptInvitationRequest;
import com.easycrm.iam.web.dto.AuthResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-auth half of invitations. Split from InvitationController by authentication
 * posture, mirroring AuthController / PublicShareController — that split is what lets
 * SecurityConfig permit whole paths rather than individual methods.
 *
 * <p>Under /api/v1/auth/** on purpose: that prefix already carries a rate-limit policy, so
 * these inherit per-IP capping. An unmatched path is UNLIMITED
 * (RateLimitProperties.policyFor), which is what makes the prefix load-bearing rather than
 * cosmetic.
 */
@RestController
@RequestMapping("/api/v1/auth/invitations")
public class PublicInvitationController {

    private final InvitationService invitations;

    public PublicInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<AuthResponse> accept(@PathVariable String token,
                                               @Valid @RequestBody AcceptInvitationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.accept(token, req));
    }
}
```

- [ ] **Step 6: Permit the route in `SecurityConfig`**

In `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`, add inside `authorizeHttpRequests`, next to the existing auth matchers:

```java
                // Accepting an invitation is pre-auth by definition: the invitee has no
                // JWT and no tenant until this call creates them. The tenant is resolved
                // from the invitation row, and the User insert behind it still goes
                // through @TenantId + RLS. Under /api/v1/auth/** so it inherits that
                // prefix's rate-limit policy — an unmatched path would be unlimited.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/invitations/*/accept").permitAll()
```

Place it **before** the `.requestMatchers("/api/**").authenticated()` line — matchers are evaluated in order and the first match wins.

- [ ] **Step 7: Run the test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationAcceptTest'
```

Expected: PASS, 11 tests.

- [ ] **Step 8: Run the whole suite**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **503 tests, 0 failures** (492 + 11).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/InvitationService.java \
        backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java \
        backend/src/main/java/com/easycrm/iam/web/dto/AcceptInvitationRequest.java \
        backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java \
        backend/src/test/java/com/easycrm/iam/web/InvitationAcceptTest.java
git commit -m "feat: accept an invitation and become a user of that tenant

The pre-auth half. The invitee has no JWT, so the tenant comes from the
invitation row; the User insert behind it still goes through @TenantId and
RLS as normal.

The context is bound BEFORE the transaction opens, not inside it: a
Hibernate session resolves its tenant at session-open and
TenantAwareTransactionManager reads it in doBegin. The wrong order does not
throw, it silently writes an unbound row — the same trap as challenge #9
and #52, arriving from a third direction.

Accept returns a full AuthResponse because login requires a tenant slug the
invitee has never seen; without tokens here the new user could not get in.

Every rejected state — unknown, revoked, already accepted, expired — returns
one identical 404. A distinct status or message for 'expired' would confirm
to a prober that a token had existed, so the states are deliberately
indistinguishable and a test asserts the bodies are byte-identical.

The route sits under /api/v1/auth/** so it inherits that prefix's rate-limit
policy; an unmatched path would be unlimited."
```

---

### Task 6: The preview endpoint

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/InvitationService.java` (add `preview`)
- Modify: `backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java` (add `GET`)
- Modify: `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java` (one matcher)
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/InvitationPreviewResponse.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/InvitationPreviewTest.java`

**Interfaces:**
- Consumes: Tasks 1–5; `TenantRepository.findById(UUID)`.
- Produces:
  - `InvitationService.preview(String rawToken)` → `InvitationPreviewResponse`
  - `record InvitationPreviewResponse(String businessName, String email, String role)`

`preview` needs **no tenant context**: it reads `invitation` and `tenant`, and both are global tables. An ordinary `@Transactional(readOnly = true)` is correct here — "pre-auth" and "must bind a tenant" are separate properties, and only `accept` has both (spec §5.3).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/iam/web/InvitationPreviewTest.java`:

```java
package com.easycrm.iam.web;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationPreviewTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String inviteAndExtractToken(String bearer, String email) throws Exception {
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        return acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
    }

    @Test
    void previewNamesTheWorkspaceAndTheInvitedRole() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "prev@shop.in");

        mvc.perform(get("/api/v1/auth/invitations/" + token))
            .andExpect(status().isOk())
            // TestTokens.provisionOwner names every tenant "Test Biz".
            .andExpect(jsonPath("$.businessName").value("Test Biz"))
            .andExpect(jsonPath("$.email").value("prev@shop.in"))
            .andExpect(jsonPath("$.role").value("SALES_EXEC"));
    }

    @Test
    void previewNeedsNoAuthentication() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "noauth@shop.in");
        mvc.perform(get("/api/v1/auth/invitations/" + token))
            .andExpect(status().isOk());
    }

    @Test
    void previewOfAnUnknownTokenIs404() throws Exception {
        mvc.perform(get("/api/v1/auth/invitations/nope-not-real"))
            .andExpect(status().isNotFound());
    }

    /**
     * The preview must not be usable as an oracle against the POST: a consumed token and a
     * token that never existed have to look identical here too, or a prober could learn
     * from GET what the POST refuses to tell them.
     */
    @Test
    void previewCannotDistinguishAConsumedTokenFromAnUnknownOne() throws Exception {
        var owner = tokens.provisionOwner("27");
        String token = inviteAndExtractToken(owner.token(), "oracle@shop.in");
        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct-horse\"}"))
            .andExpect(status().isCreated());

        String consumed = mvc.perform(get("/api/v1/auth/invitations/" + token))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(get("/api/v1/auth/invitations/never-existed"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        assertEquals(unknown, consumed,
            "preview must not reveal that a token once existed");
    }

    @Test
    void previewOfARevokedInvitationIs404() throws Exception {
        var owner = tokens.provisionOwner("27");
        String body = mvc.perform(post("/api/v1/invitations")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"prevrev@shop.in\",\"role\":\"SALES_EXEC\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String acceptUrl = JsonPath.read(body, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);
        String id = JsonPath.read(body, "$.id");

        mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.token()))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/invitations/" + token))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationPreviewTest'
```

Expected: FAIL — 401 or 404; no GET mapping.

- [ ] **Step 3: Write the DTO**

Create `backend/src/main/java/com/easycrm/iam/web/dto/InvitationPreviewResponse.java`:

```java
package com.easycrm.iam.web.dto;

/**
 * What the accept page needs to render: which workspace, which address, which role.
 * Reveals those to a token holder, which is exactly what the invitation message itself
 * would have said. No id and no token.
 */
public record InvitationPreviewResponse(String businessName, String email, String role) {}
```

- [ ] **Step 4: Add `preview` to the service**

Add `TenantRepository tenants` to the constructor, then:

```java
    /**
     * Pre-auth, but — unlike accept — it binds no tenant: invitation and tenant are BOTH
     * global tables, so an ordinary read is correct. "Pre-auth" and "must bind a tenant"
     * are separate properties and only accept has both.
     *
     * <p>Rejects with the SAME NotFoundException as accept, for every state, so the GET
     * cannot be used as an oracle against the POST.
     */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String rawToken) {
        Invitation inv = invitations.findByTokenHash(hasher.sha256Hex(rawToken))
            .filter(i -> i.getStatus() == InvitationStatus.PENDING)
            .filter(i -> !i.isExpired(Instant.now()))
            .orElseThrow(() -> new NotFoundException("invitation not found"));

        Tenant tenant = tenants.findById(inv.getTenantId())
            .orElseThrow(() -> new NotFoundException("invitation not found"));

        return new InvitationPreviewResponse(
            tenant.getBusinessName(), inv.getEmail(), inv.getRole().name());
    }
```

New imports: `com.easycrm.tenant.Tenant`, `com.easycrm.tenant.TenantRepository`, `com.easycrm.iam.web.dto.InvitationPreviewResponse`.

- [ ] **Step 5: Add the mapping and permit the route**

Add to `PublicInvitationController`:

```java
    @GetMapping("/{token}")
    public InvitationPreviewResponse preview(@PathVariable String token) {
        return invitations.preview(token);
    }
```

New imports: `org.springframework.web.bind.annotation.GetMapping`, `com.easycrm.iam.web.dto.InvitationPreviewResponse`.

And in `SecurityConfig`, beside the accept matcher:

```java
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/invitations/*").permitAll()
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.web.InvitationPreviewTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Run the whole suite**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **508 tests, 0 failures** (503 + 5).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/easycrm/iam/InvitationService.java \
        backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java \
        backend/src/main/java/com/easycrm/iam/web/dto/InvitationPreviewResponse.java \
        backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java \
        backend/src/test/java/com/easycrm/iam/web/InvitationPreviewTest.java
git commit -m "feat: preview an invitation before accepting it

Lets the accept page say which workspace invited you and as what, instead of
asking for a password against an unnamed tenant. It reveals to a token holder
exactly what the invitation message already told them.

Unlike accept, preview binds no tenant: invitation and tenant are both global
tables, so an ordinary read is correct. Pre-auth and must-bind-a-tenant are
separate properties and only accept has both.

It rejects with the same 404 as accept for every state, so the GET cannot be
used as an oracle against the POST — a test asserts both bodies are identical."
```

---

### Task 7: Expiry and concurrency

The cases the endpoint tests cannot reach through HTTP alone: a genuinely expired invitation, and two accepts racing on one token.

**Files:**
- Test: `backend/src/test/java/com/easycrm/iam/InvitationExpiryAndRaceTest.java`
- Modify (only if a defect is found): `backend/src/main/java/com/easycrm/iam/InvitationService.java`

**Interfaces:**
- Consumes: everything from Tasks 1–6. Adds no production interface.

An invitation cannot be aged through the API — the TTL is 7 days — so these tests write the row directly through `InvitationRepository` with a past `expiresAt`, then drive the public endpoints over HTTP.

- [ ] **Step 1: Write the test**

Create `backend/src/test/java/com/easycrm/iam/InvitationExpiryAndRaceTest.java`:

```java
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
     * first was revoked, freeing the address, then re-invited). UNIQUE(tenant_id, email)
     * on app_user is the only thing standing between this and two users. Spec §6.4.
     */
    @Test
    void twoInvitationsToOneAddressCannotBothBecomeUsers() throws Exception {
        var owner = tokens.provisionOwner("27");
        String first = seed(owner.tenantId(), "twice@shop.in",
            Instant.now().plus(1, ChronoUnit.DAYS));

        // Revoke the first so the partial index lets a second PENDING row exist, then seed
        // it — leaving two live tokens for one address, which is the state under test.
        String second = null;
        mvc.perform(post("/api/v1/auth/invitations/" + first + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isCreated());

        second = seed(owner.tenantId(), "twice@shop.in",
            Instant.now().plus(1, ChronoUnit.DAYS));

        mvc.perform(post("/api/v1/auth/invitations/" + second + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content(ACCEPT))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run it**

```bash
cd backend && ./gradlew :test --tests 'com.easycrm.iam.InvitationExpiryAndRaceTest'
```

Expected: PASS, 5 tests — the behaviour is already built by Tasks 5 and 6; these prove the paths HTTP alone cannot reach.

**If `twoInvitationsToOneAddressCannotBothBecomeUsers` returns 500 instead of 409**, the `DataIntegrityViolationException` is escaping wrapped in a Hibernate exception. Check `ApiExceptionHandler` catches what is actually thrown; add a handler only if genuinely missing, and say so in the commit.

**If `twoConcurrentAcceptsCreateExactlyOneUser` is flaky**, do not paper over it with a retry — that means the claim is not actually serialised, which is a real defect. Re-read spec §6.2 and fix the ordering.

- [ ] **Step 3: Run the whole suite**

```bash
cd backend && ./gradlew test
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: **513 tests, 0 failures** (508 + 5).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/easycrm/iam/InvitationExpiryAndRaceTest.java
git commit -m "test: pin invitation expiry and the two accept races

The cases HTTP alone cannot reach. The TTL is 7 days, so an expired
invitation has to be seeded directly with a past expires_at.

Expiry is lazy by design: an expired row stays PENDING and the list derives
expired=true rather than a job writing it. That is pinned here so the
absence of a sweep reads as a decision, not an omission.

Two races, defended by different layers. Concurrent accepts of ONE token are
stopped by @Version on the claim. Two DIFFERENT invitations to one address
are not — the partial unique index only prevents two PENDING rows coexisting
— so UNIQUE(tenant_id, email) on app_user is the only thing between that and
a duplicate user."
```

---

### Task 8: Documentation wrap-up

Required by the working agreements in `CLAUDE.md`, and the last thing before the branch review.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`

- [ ] **Step 1: Judge whether the challenge entries are still worth logging**

Spec §11 nominates two. Apply `CLAUDE.md`'s bar — "quality over volume", non-obvious, would-explain-in-an-interview — now that the code exists:

1. **Two pre-auth tokens with opposite storage rules.** `share_link` plaintext, `invitation` hashed, and the difference derived from what the token *grants*. The point worth keeping: copying the plaintext precedent would have been a real vulnerability, and the precedent was correct in its own context.
2. **Binding the tenant before the transaction, from the pre-auth direction.** Challenge #9 (signup) and #52 (job runner) already cover this hazard. A third arrival at the same trap with no structural guard is either worth a short cross-reference or not worth a new entry at all — decide honestly rather than padding the log.

Append whichever survive, using the template at the bottom of that file (Problem → why it's hard → Solution → Lesson). Number them from the highest existing entry.

- [ ] **Step 2: Update the annotations reference**

Check `docs/superpowers/annotations-reference.md` for every annotation this slice used, and add a row for any that is missing (origin, purpose, meta-annotation composition). Candidates — verify each against the file rather than assuming:

- `@Email`, `@Pattern`, `@Size`, `@NotBlank` (jakarta.validation) — probably already present from `SignupRequest`
- `@GetMapping`, `@DeleteMapping`, `@PathVariable` — check
- `@Enumerated(EnumType.STRING)` — probably present from other entities

Add only what is genuinely absent.

- [ ] **Step 3: Update the handoff**

In `docs/superpowers/HANDOFF.md`:

- §8 item 3: mark **user invitations DONE**, note the merge commit once known. That closes the P0-auth follow-up **entirely** — say so explicitly, because §8's ranking leans on it being the last open piece.
- §0: replace the "nothing is in flight" block with this slice's summary and the new test total.
- §3: add an inventory entry for the slice — the `invitation` table, the five endpoints, `RoleGuard`, and the two guard allowlists now holding three entries each.
- §8's ranking: with #1, #2 and #3 all closed, the remaining board is **#4 cursor pagination**, **PF19's entitlement-metering half** (still blocked on the billing design), and **`platform-web`**. Re-rank honestly rather than leaving stale prose.
- Add to the standing caveats: **the invite link's `acceptUrl` points at a frontend route that does not exist yet** (D10) — the token works, the page does not. That is the first thing to wire when the frontend lands.
- Note that `SALES_MANAGER` is now *invitable* but still collapsed into the unrestricted visibility tier — the three-tier rule in parent spec §6 remains unbuilt, and inviting one must not be read as evidence otherwise.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: record the user-invitations slice

Marks HANDOFF §8 item 3 DONE, which closes the P0-auth follow-up entirely,
and re-ranks the remaining board now that items 1-3 are all closed.

Adds the standing caveat that acceptUrl points at a frontend route that does
not exist yet: the token works, the page does not, and wiring it is the first
thing to do when the frontend lands.

Notes that SALES_MANAGER is now invitable but still collapsed into the
unrestricted visibility tier, so an invitation to that role is not evidence
the three-tier rule is built."
```

---

## Final verification

- [ ] Full suite green: **513 tests, 0 failures, 0 errors**
- [ ] `git log --oneline main..user-invitations` shows 8 commits, none mentioning Claude or AI
- [ ] Both isolation guards list three global tables and pass, including the stale-exemption check
- [ ] No plaintext invite token is written to any log
- [ ] Then: `superpowers:requesting-code-review` for a whole-branch review, followed by `superpowers:finishing-a-development-branch`
