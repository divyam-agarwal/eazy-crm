# User invitations — design

**Date:** 2026-09-01
**Status:** Implemented on branch `user-invitations`; amended after the whole-branch review
(§4.1 `V32`, §7's re-invite consequence, §8's suspended-tenant state and its `400` correction)
**Backlog item:** §8 #3 in `docs/superpowers/HANDOFF.md` — the sole surviving piece of the
P0-auth follow-up
**Baseline:** `main` at `830f4bd` (464 tests, 0 failures, 0 errors)

---

## 1. What this builds, and why it is not just a second signup

Today a tenant has exactly one user: the `OWNER` created by `AuthService.signup`. There is no
path to a second. A distributor whose office has three salespeople cannot put them in the
system at all, which makes `assigned_to`, the whole record-visibility slice, and the
`SALES_EXEC` role notional — every one of them describes a multi-user tenant that cannot
currently exist.

The behaviour is ordinary: an owner names an email and a role, the invitee follows a link,
sets a password, and becomes an active user of that tenant. Three things make it worth a
design spec rather than a straight CRUD task.

**It is pre-auth work against tenant-scoped data.** Accepting an invitation creates a `User`,
and `User` is `@TenantId` + RLS. But the invitee has no JWT — that is the entire point of the
flow — so the tenant has to come from the token itself. That is the same problem
`refresh_token` and `share_link` already solve, and `invitation` becomes the third table of
that shape. It also inherits the ordering trap from challenge #9: a Hibernate session resolves
its tenant *when it opens*, so the context must be bound before the transaction starts, not
inside it.

**It is the app's first real authorization surface.** Exactly one role check exists in the
codebase — `TenantService.requireOwner()`, a private method reading `TenantContext`. Invite
and revoke need the same check, which would make it two hand-rolled copies. The
`AssignableUsers` story (three copies before extraction) says what happens next; this slice
extracts one copy earlier.

**It is the first token this codebase mints that grants authenticated capability to someone
who is not yet a user.** `share_link` mints a token too, and deliberately stores it in
plaintext. The reasoning that justified plaintext there does not survive here — see §3.

---

## 2. Decisions taken

- **D1 — `invitation` is a global table**, not tenant-scoped: accept is pre-auth and must
  resolve a tenant from the token alone. Third of its kind after `refresh_token` and
  `share_link`.
- **D2 — the token is hashed at rest and single-use**, unlike `share_link`'s plaintext
  idempotent token. §3 gives the reasoning.
- **D3 — no `app_user` row exists until accept.** `invitation` carries the email and invited
  role; `UserStatus` gains no `PENDING` member and no existing `ACTIVE` filter changes.
- **D4 — the invite link is returned in the 201 body** *and* pushed at `EmailSender`. The
  owner can paste it into WhatsApp exactly as `ShareLinkService` intends its share URL to be
  pasted, so the feature works on day one against the `LoggingEmailSender` stub; a real
  sender later needs no API change.
- **D5 — `requireOwner` is extracted to a shared `RoleGuard`** in `platform/security`, and
  `TenantService` switches to it in the same change. §5.1 says why not `iam`.
- **D6 — expiry is evaluated lazily on read, with no scheduled job.** §7.
- **D7 — accept returns a full `AuthResponse`** (access + refresh + ids). Not a convenience:
  `LoginRequest` requires the tenant **slug**, which an invitee has never seen, so without
  tokens on accept a freshly-created user could not log in at all.
- **D8 — an owner may invite any of the three roles, `OWNER` included.** Co-owners are
  ordinary in this market and nothing downstream distinguishes a founding owner from an
  invited one.
- **D9 — invitations expire 7 days after issue.** Long enough for a WhatsApp message to be
  seen over a weekend, short enough that a stale link in a chat history stops working.
- **D10 — `acceptUrl` points at a reserved frontend route, not at an API path.** §6.3.

---

## 3. Why this token is hashed when `share_link`'s is not

`ShareLink` stores its token in plaintext and its javadoc defends the choice: the token only
reads a frozen quotation that is rendered from rows in this same database, and plaintext is
what makes resharing idempotent — one stable link per version, so a link already sent to a
customer keeps working.

Neither half of that argument transfers.

An invitation token does not read anything. Presenting it **creates an authenticated principal
with a role inside a tenant** — the same class of capability a refresh token carries, which is
why `refresh_token` is hashed. And idempotency is not wanted here: an invitation should be
consumable exactly once, so "the same link keeps working" is the failure mode, not the
feature.

So `invitation` follows `refresh_token`, not `share_link`: 256 bits from `SecureRandom`,
base64url-encoded, stored as `TokenHasher.sha256Hex`, returned **once** in the 201 response and
never retrievable afterwards. A "resend" is therefore revoke + re-invite, which mints a new
token and invalidates the old one. That is the correct semantic anyway — a resend should not
leave two live links.

The plaintext token is a bearer credential: it must never be logged, and the entity gets no
`toString()`, matching `ShareLink`.

---

## 4. Data model

### 4.1 `invitation` (new, migration `V31__invitation.sql`)

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK, UUIDv7, from `BaseEntity` |
| `tenant_id` | `uuid not null` | **plain column** — no `@TenantId`, no RLS |
| `email` | `text not null` | the invited address |
| `role` | `varchar(16) not null` | `Role` enum, `@Enumerated(STRING)` |
| `token_hash` | `varchar(64) not null` | SHA-256 hex, unique |
| `status` | `varchar(16) not null` | `PENDING` / `ACCEPTED` / `REVOKED` |
| `expires_at` | `timestamptz not null` | issue + 7 days (D9) |
| `invited_by` | `uuid not null` | the owner's `app_user` id |
| `accepted_at` | `timestamptz` | null until accepted |
| `accepted_user_id` | `uuid` | the `app_user` created on accept |
| `created_at`, `updated_at`, `version` | | from `BaseEntity` |

`token_hash` is `varchar`, not `char`: Hibernate maps `String` to `varchar` and
`ddl-auto: validate` would reject a `bpchar` column. `refresh_token.token_hash` is
`VARCHAR(64)` for the same reason. `BaseEntity` carries no `created_by` — `invited_by` is
this table's actor column.

Three indexes, all in the creating migration — the standing agreement from §8 of the handoff
(adding an index at creation costs one line; retrofitting it costs a migration on a live
table):

1. `UNIQUE (token_hash)` — the accept lookup, and the uniqueness the token relies on.
2. `CREATE UNIQUE INDEX ... ON invitation (tenant_id, lower(email)) WHERE status = 'PENDING'`
   — at most one live invitation per address per tenant. A **partial** index, so accepted and
   revoked rows accumulate freely as history. This makes a double-invite a database-level
   conflict rather than a check-then-act race in the service.
3. `(tenant_id, status, expires_at)` — the owner's pending list, which is the only list query
   this slice adds.

`lower(email)` in index 2 and a `lower()`-normalising write path together: a second invite to
`Ravi@shop.in` when `ravi@shop.in` is pending must collide. Store the address as entered
(it is shown back to the owner) but index and compare it folded.

**That rule has to reach `app_user` too, and originally did not.** `uq_user_tenant_email` (V6) is
on the raw column, so it read `ravi@shop.in` and `Ravi@shop.in` as two different users and a case
variant could be invited over an existing member and accepted, giving one tenant two `ACTIVE`
users — possibly with different roles — for one human. `V32__app_user_case_insensitive_email.sql`
adds a unique index on `app_user (tenant_id, lower(email))` **alongside** the V6 constraint, and
the membership pre-check uses `findByEmailIgnoreCase`. Challenge #57 has the full story.

### 4.2 Registering a third global table

`invitation` carries `tenant_id` but is not tenant-scoped, so it must be added to **both**
allowlists, with a comment in the same shape as the two entries already there:

- `TenantScopingArchTest.GLOBAL_TABLES` (layer 2 — the `@TenantId` guard)
- `RlsCoverageIntegrationTest.GLOBAL_TABLES` (layer 3 — the RLS guard)

The RLS guard fails on a **stale** exemption as well as a missing one, so the two lists cannot
quietly drift apart. Omitting either one fails the build rather than leaking, which is the
point of both.

### 4.3 What does not change

`UserStatus` keeps its two members. `User` gains no column. Every existing
`status == ACTIVE` filter — `AssignableUsers`, `AuthService.login` — is untouched, which is
the whole reason D3 chose create-on-accept over a `PENDING` user row.

---

## 5. Components

### 5.1 `platform/security/RoleGuard` (new)

```java
@Component
public class RoleGuard {
    public void requireOwner(String message) { ... }   // ForbiddenException on failure
}
```

Reads the role from `TenantContext.get()` exactly as `TenantService.requireOwner()` does
today. `TenantService` drops its private copy and calls this instead, passing its existing
message (`"only an owner may change the business profile"`) so its behaviour and its tests are
unchanged. `InvitationService` is the second caller.

The `message` parameter is deliberate: a caller-supplied reason keeps the 403 bodies as
specific as the hand-rolled ones were, so extraction costs no message quality.

**It lives in `platform/security`, not `iam`, to avoid a package cycle.** `iam` already
depends on `tenant` (`AuthService` imports `Tenant` and `TenantRepository`), so putting the
guard in `iam` and calling it from `TenantService` would make the two packages mutually
dependent. `RoleGuard` needs neither: it reads `TenantContext` and throws
`ForbiddenException`, both in `platform`, which `iam` and `tenant` already depend on. It
therefore compares against the literal `"OWNER"` rather than `Role.OWNER.name()` — `Role`
lives in `iam` and `platform` must not depend on it. That is exactly what
`TenantService.requireOwner()` does today, and `TenantPrincipal.role` is a `String` anyway,
so nothing is lost.

### 5.2 `iam/Invitation`, `InvitationStatus`, `InvitationRepository` (new)

`Invitation extends BaseEntity` (not `TenantScopedEntity`), mirroring `ShareLink`. Behaviour
lives on the entity, matching the `Quotation.expire()` precedent:

- `accept(UUID userId, Instant when)` — asserts `PENDING`, sets `ACCEPTED`, stamps
  `accepted_at` / `accepted_user_id`.
- `revoke()` — asserts `PENDING`, sets `REVOKED`.
- `isExpired(Instant now)` — `expires_at.isBefore(now)`.

Both mutators carry their own precondition rather than trusting the service to have checked,
the same reason `Quotation.expire()` re-asserts `SENT`.

`InvitationRepository extends JpaRepository<Invitation, UUID>` with
`findByTokenHash(String)` and `findByTenantIdAndStatus(UUID, InvitationStatus)`, both
`@Transactional(readOnly = true)` for the reason `ShareLinkRepository` documents.

### 5.3 `iam/InvitationService` (new)

Four operations. `invite`, `listPending` and `revoke` run under the owner's JWT and are
ordinary `@Transactional` methods.

`accept` is pre-auth and is deliberately **not** `@Transactional` — it manages its own
transaction after binding the tenant, for the reason §6.2 gives.

`preview` is pre-auth and needs **no tenant context at all**: it reads `invitation` and
`tenant`, and both are global tables (`TenantService` documents `tenant` as the one place a
tenant id must be passed explicitly rather than left to `@TenantId` + RLS). So it is an
ordinary `@Transactional(readOnly = true)` read that happens to run with nothing bound. Worth
stating, because "pre-auth" and "must bind a tenant first" are separate properties and only
`accept` has both.

### 5.4 `iam/web/InvitationController` + `iam/web/PublicInvitationController` (new)

Split by authentication posture, mirroring `AuthController` / `PublicShareController`. Keeping
them in separate classes is what keeps the `permitAll` matchers in `SecurityConfig` a
whole-controller statement rather than a per-method one.

### 5.5 Extensions

- `SecurityConfig` — `permitAll` for `GET /api/v1/auth/invitations/*` and
  `POST /api/v1/auth/invitations/*/accept`.
- `AuditService` — three new action strings, no code change: `INVITE_SENT`,
  `INVITE_ACCEPTED`, `INVITE_REVOKED`.
- `application.yml` — nothing. The invite TTL is a constant on the service, matching
  `RefreshTokenService.TTL_DAYS`; the accept URL reuses the existing
  `easycrm.public-base-url` property (`@Value`-injected, exactly as `ShareLinkService` takes
  it) rather than introducing a second base-URL setting that could drift from it.

---

## 6. Endpoints and flows

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/invitations` | OWNER | `201` `{id, email, role, expiresAt, acceptUrl}` |
| `GET` | `/api/v1/invitations` | OWNER | `200` pending list, **no tokens** |
| `DELETE` | `/api/v1/invitations/{id}` | OWNER | `204` |
| `GET` | `/api/v1/auth/invitations/{token}` | public | `200` `{businessName, email, role}` |
| `POST` | `/api/v1/auth/invitations/{token}/accept` | public | `201` `AuthResponse` |

The two public routes live under `/api/v1/auth/**` on purpose. That prefix already carries a
rate-limit policy (capacity 30), so they inherit per-IP capping for free rather than needing a
fourth policy — and the alternative, an unmatched path, is *unlimited* by
`RateLimitProperties.policyFor`. They are also genuinely auth operations, so the prefix is
honest, not a trick to acquire a limit.

The preview endpoint exists so the accept page can render "Shri Ram Traders invited you as
Sales Executive" instead of asking for a password against an unnamed workspace. It reveals the
tenant's business name and the invited email to a token holder, which is exactly what the
invitation email itself would have said.

### 6.1 Invite

1. `roleGuard.requireOwner(...)`.
2. Reject an email that is already a user of this tenant → `409`. The read runs under the
   owner's tenant context, so `UserRepository.findByEmail` is RLS-scoped as normal.
3. Mint the token, save the `Invitation` (`PENDING`, +7 days, `invited_by` = caller).
   A pending duplicate hits the partial unique index → `409`.
4. `emailSender.send(...)` **after** the transaction commits, matching `AuthService.signup`'s
   comment: no email for a rollback.
5. Return the plaintext token exactly once, embedded in `acceptUrl`.

### 6.2 Accept — the ordering that matters

`User` is `@TenantId` + RLS, and a Hibernate session resolves its tenant when it *opens*. So
this follows `AuthService.signup` (challenge #9) rather than an ordinary `@Transactional`
method:

1. `invitations.findByTokenHash(sha256Hex(token))` — a global table, so no tenant context is
   needed and none exists.
2. Validate `PENDING` and not expired.
3. `TenantContext.set(new TenantPrincipal(inv.getTenantId(), null, "SYSTEM"))`.
4. `tx.execute(...)`:
   - re-read and claim the invitation: `inv.accept(...)`, saved inside this transaction, so
     `@Version` fails a concurrent second accept;
   - insert the `User` — `ACTIVE`, the invited role, bcrypt password;
   - `audit.record("INVITE_ACCEPTED", user.getId(), ...)`;
   - mint access + refresh tokens, build the `AuthResponse`.
5. `finally { TenantContext.clear(); }`.

Getting step 3 and step 4 in the other order does not throw — it silently writes a `User` with
no tenant bound, which RLS then rejects or, worse, `@TenantId` stamps as null. That is the
same silent-ordering trap challenge #52 records for `TenantJobRunner`, arriving from a
different direction.

### 6.3 What `acceptUrl` points at (D10)

`acceptUrl` is `{easycrm.public-base-url}/invite/{token}` — a **frontend** route, which does
not exist yet. It is deliberately not an API path.

These links are pasted into WhatsApp and live in chat history indefinitely. Whatever they
point at on the day they are minted, they must still point at on the day the frontend lands,
so the durable form is reserved now. Baking `/api/v1/auth/invitations/{token}` into a customer's
chat would either strand those links or force a permanent redirect from an API namespace.

The honest consequence, which qualifies D4: **until the frontend ships, that URL is not
browsable.** The token is still delivered — it is the last path segment — and the two public
endpoints consume it directly, which is how the integration tests drive the flow. So the
*token* is usable on day one; the *page* is not. `share_link` had no such gap because
`/public/q/{token}` renders a server-side PDF, and there is no server-rendered equivalent of a
password form worth building here for a frontend that is weeks away.

### 6.4 Duplicate and race defence, three layers

| Layer | Blocks |
|---|---|
| Partial unique index on `(tenant_id, lower(email)) WHERE PENDING` | a second live invitation to one address |
| `@Version` on the accept claim | the same token accepted twice concurrently |
| `UNIQUE(tenant_id, email)` on `app_user` | two *different* invitations to one address racing to accept |

The third is the one a service-level check cannot cover, because the two accepts arrive on
different tokens and each sees a valid `PENDING` row.

---

## 7. Expiry is lazy, and that is a decision

`expires_at` is checked when a token is presented; the pending list derives `EXPIRED` for
display without writing it. There is deliberately **no** `TenantJobRunner` job, even though
the previous slice built exactly such a job for a superficially identical problem.

The difference is who observes the state. A quotation's `EXPIRED` is business-visible: it
appears in list views, drives the pipeline total, is audited, and changes what the customer
sees on a shared link — so it has to be materialised, and a stale `SENT` row overstates live
business. An invitation's expiry is observable only by someone presenting the token, and the
lazy check is authoritative at exactly that moment. Materialising it would buy nothing and add
a second cron, a second sweep, and a second set of failure modes.

Record this as a decision, not an oversight, if it is ever asked why quotations have an expiry
job and invitations do not.

**Laziness charges one price, on the invite path.** An expired invitation stays `PENDING`
forever, and the partial unique index in §4.1 is `PENDING`-scoped rather than expiry-aware — so
without care, an unopened link would block its own address from ever being re-invited, while the
owner's list cheerfully reported `expired: true` about the row doing the blocking. `invite`
therefore revokes a colliding *expired* row and continues, which frees the partial index in the
same transaction. It uses `saveAndFlush`, not `save`: Hibernate's action queue runs every insert
before any update, so a plain `save` lets the new `PENDING` row hit the index while the old one is
still `PENDING` on disk. A live pending invitation still yields the `409`.

---

## 8. Error handling

Every failed accept — unknown token, revoked, already accepted, expired — returns the **same**
response shape. The `/public/q/*` and login paths already take this line (`AuthService.login`
throws one generic 401 for slug, email and password failures alike, explicitly to avoid
enumeration), and the reasoning is stronger here: an invitation token is a bearer credential,
and distinguishing "expired" from "unknown" tells a probing caller that a token existed.

`404` is the chosen status for all four, over `410 Gone`: `410` is more descriptive and that
descriptiveness is the leak.

The `GET` preview behaves identically — the same `404` for all four states — so it cannot be
used as an oracle against the `POST`.

**A fifth state joined the four after the whole-branch review:** an invitation that is itself
perfectly live, but whose **tenant is `SUSPENDED`**. `AuthService.login` refuses a suspended
tenant explicitly, and `accept` is the only other entry point in the app that resolves a tenant
from something other than an existing JWT — so a tenant suspended for non-payment could otherwise
keep onboarding staff on links issued before suspension. Both public routes refuse it, through
the *same* `NotFoundException` as every other rejection, for the reason above. The whole chain
(`findByTokenHash` → `PENDING` → not expired → tenant not suspended → one `orElseThrow`) lives in
a single `InvitationService.requireLive(rawToken)` that both public methods call, so the property
is enforced structurally rather than by keeping two copies in step.

(Scope note: `AuthService.refresh` has the same suspended-tenant gap. It predates this slice and
is deliberately out of its scope.)

Everything else is the existing contract: `403` from `RoleGuard`, `409` on duplicate email or
duplicate pending invite, `400` from bean validation on a malformed email or a password shorter
than 8 characters — the accept request reuses `SignupRequest`'s exact constraints
(`@NotBlank @Size(min = 8)`), so an invited user's password rules cannot drift from a
self-serve owner's.

`400`, not `422`: `ApiExceptionHandler` maps `MethodArgumentNotValidException` to `400` and
has done since before this slice, so that is the codebase's established contract for a body
that fails bean validation. (`422` is what a hand-thrown `ValidationException` returns —
a semantic rejection after the body parsed, such as a GSTIN whose checksum is wrong. This
paragraph originally said `422` and was simply wrong about the code.)

---

## 9. Testing

**Unit**
- `Invitation.accept` / `revoke` reject a non-`PENDING` invitation.
- `isExpired` boundary.
- `RoleGuard.requireOwner` throws for `SALES_MANAGER` and `SALES_EXEC`, passes for `OWNER`,
  and throws when no principal is bound.

**Integration**
- Each of the three managed endpoints returns `403` for a non-owner.
- Invite → accept creates an `ACTIVE` user with the **invited** role, in the **inviting
  tenant**, able to authenticate with the returned tokens.
- Cross-tenant: a token minted by tenant A creates a user in A and nothing in B; B's owner's
  pending list never shows A's invitation.
- Second accept of one token fails; exactly one `app_user` row exists afterwards.
- Expired, revoked and unknown tokens are indistinguishable in status and body.
- A second pending invite to the same address (and to a case variant of it) is `409`.
- Inviting an address that is already a user is `409`.
- The accept route is rate-limited — that is, it is genuinely inside `/api/v1/auth/**`.
- `TenantScopingArchTest` and `RlsCoverageIntegrationTest` both pass with `invitation`
  exempted, and the latter's stale-exemption check still passes.
- `TenantServiceTest` is unchanged by the `RoleGuard` extraction — the regression proof that
  D5 is a refactor.

---

## 10. Deliberately not in scope

- **Members management** — listing existing users, changing a member's role, disabling or
  re-enabling one. Chosen scope is invite + accept + revoke + pending list.
- **The `SALES_MANAGER` visibility tier.** It remains collapsed into the unrestricted tier as
  the record-visibility slice left it; this slice makes the role *invitable*, not
  differently-scoped. Do not read a `SALES_MANAGER` invitation as evidence the three-tier rule
  in parent spec §6 is built.
- **A real `EmailSender`.** The stub logs; the link is delivered by the owner (D4). Building
  SMTP/SES is a slice of its own and would gate this one.
- **Resend as its own endpoint.** Revoke + invite achieves it with correct semantics (§3).
- **Password reset.** Adjacent, token-shaped, and genuinely reusable from this design — but a
  separate flow with its own decisions.

---

## 11. Challenge-log entries this slice owes

1. **Two pre-auth tokens in one codebase with opposite storage rules.** `share_link` plaintext,
   `invitation` hashed — and the discipline that the difference is derived from what the token
   *grants* (a read of a frozen document vs. the creation of an authenticated principal),
   not from habit. The interesting part is that the plaintext choice was correct in its own
   context and copying it here would have been a real vulnerability.
2. **Binding the tenant before the transaction, arriving from the pre-auth direction.**
   Challenge #9 covers this for signup and #52 for the job runner; the invitation accept path
   is the third arrival at the same trap, which is itself the point worth logging — a
   recurring ordering hazard with no structural guard behind it.

Log them only if they still read as non-obvious once implemented.
