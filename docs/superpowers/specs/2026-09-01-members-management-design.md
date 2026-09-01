# Members management — design spec

**Date:** 2026-09-01
**Status:** designed, not implemented
**Baseline:** `main` at `e9d694e` (519 tests)
**Follows:** `2026-09-01-user-invitations-design.md`

---

## 1. What this is, and why now

The `user-invitations` slice (merged `f265cfe`) made a tenant able to have more than one
user. It shipped invite + accept + revoke + pending-list and stopped there, deliberately:
there is **no way to see who is in a workspace, change what they can do, or remove them.**
A multi-tenant workspace today is creatable but not administrable.

This slice closes that. Four operations, owner-only:

- **list** every member of the tenant, active and disabled
- **change** a member's role
- **disable** a member — revoke their access without destroying their history
- **enable** a member again

**Disable, not delete (D1).** A `User` row is referenced by `audit_log.actor_user_id`,
`invitation.invited_by`, and the `assigned_to` column of `customer`, `enquiry` and
`follow_up`. Deleting one would orphan every historical reference and make the audit trail
lie about who did what. `UserStatus.DISABLED` already exists in the enum and is already
honoured by `AuthService.login` and `AssignableUsers.require`; this slice makes it
*reachable* and makes it *bite*.

**Non-goal:** this slice does not narrow `SALES_MANAGER`. That role stays collapsed into
the unrestricted visibility tier exactly as `record-visibility` left it. Being able to
*assign* the role is not evidence the parent spec §6 three-tier rule is built — it is not.

---

## 2. Surface

One new controller, `com.easycrm.iam.web.MemberController`, at `/api/v1/members`, mirroring
`InvitationController`: authenticated, owner-only via `RoleGuard`, in the authenticated half
of the `iam.web` package (there is no pre-auth half here — every route requires a JWT, so
no `SecurityConfig` change is needed; `/api/**` is already `.authenticated()`).

| Method | Path | Body | Success |
|---|---|---|---|
| `GET` | `/api/v1/members` | — | `200` `List<MemberResponse>` |
| `POST` | `/api/v1/members/{id}/role` | `{"role":"SALES_EXEC"}` | `200 MemberResponse` |
| `POST` | `/api/v1/members/{id}/disable` | — | `200 MemberResponse` |
| `POST` | `/api/v1/members/{id}/enable` | — | `200 MemberResponse` |

`POST /{id}/<verb>` is the house transition idiom, already used by `OrderController`
(`/dispatch`, `/close`, `/cancel`). Role change is modelled as a transition rather than a
`PATCH` on the member because **PATCH house-wide is full-header-replace** (deferred-minor
#8): a `PATCH /members/{id}` carrying only `role` would, under the house semantic, read as
"clear phone and email too". A verb sub-resource has no such ambiguity.

`MemberResponse` = `id`, `email`, `phone`, `role`, `status`, `createdAt`. Never
`passwordHash`.

**Unpaged (D2).** `listPending` on the invitations surface is likewise unpaged. A tenant
has a handful of users — this product's customers are tier-2/3 distributors, not
enterprises — and an unpaged list keeps the response shape simple for the frontend that
will consume it. If a tenant ever has enough users for this to matter, that is a happy
problem and a later slice.

---

## 3. Where the logic lives

New `com.easycrm.iam.MemberService`. Not folded into `AuthService` (auth flows) and not
into `InvitationService`, which the previous slice's whole-branch review already flagged as
wanting a *split* by authentication posture, not more surface.

**`User` gains its first mutators:** `changeRole(Role)`, `disable()`, `enable()`. The
already-in-that-state guards live on the entity, throwing `ConflictException`, matching
`Quotation.expire()` and `Invitation.revoke()`. `changeRole` carries no guard — assigning
the role a member already holds is harmless and idempotent, and rejecting it would make a
retried request fail for no reason.

**The last-active-owner invariant does NOT live on the entity.** It needs a tenant-wide
count, which an entity cannot do. It lives in `MemberService` — see §5.

**Target resolution needs no hand-written tenant filter (D3).** `app_user` is `@TenantId` +
RLS, so `users.findById(id)` for another tenant's member returns empty and falls through to
404 structurally. This is the deliberate contrast with `InvitationService.revoke`, whose
hand-written `tenant_id` comparison is load-bearing *only* because `invitation` is a global,
RLS-exempt table (challenge #54). Do not copy that filter here; copying it would suggest the
structural mechanism is not trusted, and it is.

---

## 4. The `AssignedWorkload` port — the central structural decision

### 4.1 The requirement

Disabling a member who still holds open work strands that work: they cannot log in, so
nobody actions it. So **disable is refused while the member holds open work (D4)**, and the
409 tells the owner what to reassign first.

"Open work" is precisely definable from state that already exists:

| Aggregate | Open means | Why it counts |
|---|---|---|
| `Customer` | `is_active = true` and `assigned_to = target` | carries its own owner |
| `Enquiry` | `EnquiryStage.isActive()` and `assigned_to = target` | carries its own owner; `customer_id` is **nullable** — an enquiry precedes the customer in this wedge |
| `FollowUp` | `status = PENDING` and `assigned_to = target` | `assigned_to` is NOT NULL and intrinsic |

**`Quotation` and `Order` are deliberately absent.** They have no `assigned_to` of their
own; `VisibilityPolicy` derives their visibility from their customer via `viaCustomer(...)`.
Reassigning the customer carries them. Adding a check for them would be unreachable code.

**`Enquiry` and `FollowUp` are deliberately present**, and this is worth stating because the
intuition "everything follows the customer" is half true. It holds for quotations and orders
and fails for these two. A `PENDING` follow-up pointing at a disabled user is invisible to
every other `SALES_EXEC` and will never be actioned — precisely the failure the
activity/follow-up feature exists to prevent.

### 4.2 The problem it creates

Counting that work means reading `CustomerRepository`, `EnquiryRepository` and
`FollowUpRepository`. Two independent constraints bite:

1. **`VisibilityScopingArchTest`** — those three are *guarded repositories*. Only classes
   inside `com.easycrm.platform.visibility..` may call a read method on them; everything
   else must go through `VisibleFinder` or be on the `ALLOWED_METHODS` allowlist.
2. **Package direction** — `crm` and `sales` already depend on `iam`
   (`EnquiryService`, `CustomerService`, `ActivityService` and `FollowUpService` all import
   `iam.AssignableUsers`). `iam` currently imports **nothing** from either. A naive
   `MemberService` calling those repositories directly would create the codebase's first
   `iam` ↔ `sales` package cycle.

`VisibleFinder` is not the answer. For an owner its filter is a provable no-op today
(`VisibilityPolicy.unrestricted()` is true for every role but `SALES_EXEC`), so it would
return the right number — but **an invariant check must never filter.** Routing it through
`VisibleFinder` would start silently hiding rows the day a non-owner reaches this path, and
a gate that under-counts lets a disable through while work remains assigned. The
"no filtering" property must be structural, not incidental.

### 4.3 The decision

**Invert the dependency (D5).** `iam` declares a port whose signature mentions nothing from
`crm` or `sales`:

```java
package com.easycrm.iam;

/** One kind of work a member can still hold. Implementations live in the package that
 *  owns that work, so iam never imports crm or sales. */
public interface AssignedWorkload {
    String label();                 // "customers", "enquiries", "follow-ups"
    long countOpenFor(UUID userId);
}
```

Implemented by `crm.CustomerWorkload`, `sales.EnquiryWorkload` and `sales.FollowUpWorkload`.
`MemberService` injects `List<AssignedWorkload>` and aggregates.

The dependency arrow is **`crm`/`sales` → `iam`, which already exists**. `iam` gains zero
new imports and the package graph stays **acyclic**.

Consequences, both accepted:

- **Three entries on `VisibilityScopingArchTest.ALLOWED_METHODS`** (the count methods), each
  carrying the same justification the allowlist already records for `findByGstin` and
  `findByNormalizedPhone`: *must see the whole tenant or the invariant breaks*. That is
  exactly what the allowlist exists for. Adding an entry is a visibility decision and gets
  the same review as adding a table to `TenantScopingArchTest.GLOBAL_TABLES`.
- **The blocker set is discovered at runtime** via `List<AssignedWorkload>` rather than
  enumerated in one place. A future assigned-to aggregate joins the gate by existing, which
  is the desirable direction for this particular invariant — the failure mode of forgetting
  is silent orphaned work.

Two alternatives were considered and rejected. Calling the repositories directly from
`MemberService` drags five types into `iam` (three repositories plus `EnquiryStage` and
`FollowUpStatus`) *and* creates the cycle. Declaring the port in `platform` and implementing
it there would make `platform` depend on `iam` — breaking the stated rule that keeps
`RoleGuard` comparing the literal `"OWNER"` rather than `Role.OWNER.name()`.

### 4.4 The error the owner sees

One aggregated 409 naming every blocker at once, not one at a time — an owner who clears
customers, retries, and only then discovers follow-ups has been made to do the job twice.
The counts also travel as structured data keyed by `label()`, so a frontend can route to the
right reassign screen instead of parsing prose.

**This needs a change to the error envelope (D11).** On this baseline `ConflictException`
carries only a message, and `ApiExceptionHandler.conflict(...)` passes `null` where the
envelope's `fields` would go — only `ValidationException` (422) has ever carried field
detail. So `ConflictException` gains a second constructor taking a
`Map<String, Object>`, and the 409 handler passes it through. The existing single-argument
constructor stays and keeps returning no `fields` key, so **every other 409 in the codebase
is byte-identical to today's**.

Two execution notes for whoever implements it:

- `ConflictException` lives in the **`platform-primitives` Gradle module**, not the root
  project, and that module's JaCoCo floors are LINE `0.83` / BRANCH `0.99` against roughly
  22 branches. One new untested branch drops it to ~0.956 and reddens the build. The new
  constructor ships with its own test, in that module, or `check` fails.
- `ApiExceptionHandler` is being refactored concurrently by the `openapi-contract` slice,
  which replaces the inline `Map` envelope with typed `ApiError`/`ApiErrorResponse` records.
  A merge conflict in `conflict(...)` is expected and is mechanical: whichever slice lands
  second re-applies "pass the exception's fields through" to the other's shape.

---

## 5. The last-active-owner invariant

**A workspace must never reach zero `ACTIVE` `OWNER`s (D6).** Every member-admin route, plus
invite and revoke, calls `RoleGuard.requireOwner`. A tenant with no active owner can never
again invite, promote, or re-enable anyone, and **this product has no support or admin
surface** — recovery would be a manual `UPDATE` against production.

So both reducing operations check it: changing an `OWNER` to another role, and disabling an
`OWNER`. Refused with 409.

**Self-targeting is otherwise allowed (D7).** An owner may demote or disable themselves
provided another active owner remains. A blanket self-target ban was rejected: it would
force a departing sole-remaining-plus-one owner to ask a colleague to do it for them, for no
safety gain the invariant does not already provide.

### 5.1 The race, and why the obvious defences miss it

A plain count is check-then-act. Two active owners, two simultaneous requests:

| | T1 (Asha demotes Bilal) | T2 (Bilal demotes Asha) |
|---|---|---|
| t1 | count active OWNERs → **2** → passes | |
| t2 | | count active OWNERs → **2** → passes |
| t3 | `UPDATE app_user SET role=… WHERE id=bilal` | |
| t4 | | `UPDATE app_user SET role=… WHERE id=asha` |
| t5 | `COMMIT` | `COMMIT` |

Zero active owners. Both checks were true when they ran.

Nothing in the current stack catches this:

- **`@Version` does not.** Optimistic locking catches two writers of the *same* row. These
  are two different rows.
- **No constraint shape helps.** A unique index enforces *at most one* of something; there
  is no declarative constraint for *at least one*.
- **READ COMMITTED** — each count sees the pre-commit state, so both read 2.
- **REPEATABLE READ would not help either.** Postgres's RR is snapshot isolation, which
  aborts write-write conflicts on the same row. This is **write skew**: two transactions
  read an overlapping set, then write *disjoint* rows within it. Only `SERIALIZABLE` detects
  it. This is the textbook on-call-doctors anomaly.

### 5.2 The fix

**Materialise the conflict onto a row both transactions must touch (D8):** take a
`PESSIMISTIC_WRITE` lock on the **tenant row** at the top of every member-admin write.
T2 then blocks until T1 commits, re-counts, sees 1, and returns the 409 it should have.

`TenantRepository` gains `findForUpdate(UUID)` — `@Lock(LockModeType.PESSIMISTIC_WRITE)`,
the idiom `DocumentCounterRepository.findForUpdate` already established for gapless document
numbering. `tenant` is a global table with no RLS policy to fight, member-admin writes are
rare, and the lock is scoped to one tenant's row, so it serialises nothing else — not CRM
traffic, not other tenants.

The lock is taken on **all three** writes (role, disable, enable), not only the two that can
reduce the owner count. Uniform is cheaper to reason about than per-path, and `enable`
taking it costs nothing at these volumes.

`SERIALIZABLE` was rejected: it would mean handling serialization-failure retries across the
codebase to protect one invariant.

---

## 6. What makes `disable` actually bite

Flipping the column is not enough, and two of the four layers **do not exist today**:

| # | Layer | Status |
|---|---|---|
| 1 | `AuthService.login` refuses a non-`ACTIVE` user | **exists** |
| 2 | All of the member's refresh tokens are revoked | **new** |
| 3 | `AuthService.refresh` refuses a non-`ACTIVE` user | **new — the load-bearing fix** |
| 4 | `AssignableUsers.require` refuses a non-`ACTIVE` assignee for new work | **exists** |

**Layer 3 is the one without which this feature is decorative (D9).** `AuthService.refresh`
today rotates the token, loads the user, and mints a fresh access token **with no status
check at all**. Without this fix a disabled user refreshes indefinitely and never notices
they were disabled. The rejection reuses the existing generic
`UnauthorizedException("invalid refresh token")` — no new message, so the endpoint gains no
enumeration signal.

**Layer 2** needs `RefreshTokenRepository.findByUserIdAndTenantIdAndRevokedAtIsNull`. The
`tenantId` term is belt-and-braces on a global, RLS-exempt table — the same reasoning as
challenge #54 — even though a `userId` UUID is already globally unique.
`RefreshTokenService.revokeAllForUser(userId, tenantId)` returns the count, which goes into
the audit detail.

### 6.1 The ≤15-minute window is accepted, not overlooked

`easycrm.jwt.access-ttl-seconds` is 900 and `JwtAuthenticationFilter` does no database read,
so **a disabled member's already-minted access token keeps working until it expires** — at
most 15 minutes. The same window applies to a **demotion**: a demoted owner carries `OWNER`
in their token, and therefore passes `RoleGuard`, until it expires.

Closing it properly means a per-request user lookup in the JWT filter — a database read on
every authenticated call, in a filter that runs before any transaction or tenant binding is
established. The alternative, an in-process denylist, is the same multi-instance hazard the
rate limiter already carries. Neither is worth it for a 15-minute window on a rare operation
in a product with no hostile-insider threat model yet. **If this is ever revisited, shorten
the access TTL rather than adding the read.**

Role changes otherwise propagate for free: `AuthService.refresh` already re-reads
`user.getRole()` when minting, so the next refresh carries the new role with no extra work.

### 6.2 The reassign-first gate has the same shape, and is left open on purpose

`requireNoOpenWork` (§4.1) is check-then-act, exactly like the last-active-owner invariant
§5.1 defends against — but the tenant lock only serialises **member-admin** writes. The work
that gets assigned lives on the other side of the package boundary §4.3 just inverted, and
nothing there takes that lock:

| | T1 (disable M) | T2 (create customer, assigned to M) |
|---|---|---|
| t1 | lock tenant row | |
| t2 | count open work for M → **0** → passes | |
| t3 | | `AssignableUsers.require(M)` — plain `findById`, reads `ACTIVE` |
| t4 | `M.status = DISABLED`; `COMMIT` | |
| t5 | | save customer assigned to M; `COMMIT` |

Two transactions read an overlapping set (M's status and open-work count) and write disjoint
rows — the same anomaly class as §5.1, materialised through `POST /api/v1/customers` instead
of a second member-admin call. The result is an active customer assigned to a member who
cannot log in: exactly the stranded work §4.1 exists to prevent.

**This is deliberately not fixed.** Closing it the way §5.2 closes the owner race would mean
taking the tenant lock on every path that assigns work — `AssignableUsers.require`, reached
from `CustomerService`, `EnquiryService`, `FollowUpService`, and whatever calls it next —
which serialises ordinary CRM traffic (every customer, enquiry, and follow-up write, from
every caller, all day) to protect an admin operation that one owner runs rarely against one
member at a time. That trade is not close.

It is also not the same *kind* of gap as §5.1's. A workspace with zero active owners is
**unrecoverable in-product** (§5, D6) — nobody left who can fix it without a manual `UPDATE`.
This is not: the owner opens the members list, sees M still listed (disabled members are
listed on purpose, §2), and either re-enables them or reassigns the stray customer — no
database intervention, no support ticket. That asymmetry, not an oversight, is why one race
gets a `PESSIMISTIC_WRITE` lock and the other gets a paragraph. A future reader should not
"fix" the inconsistency by adding a lock nobody wants.

---

## 7. Error contract

| Condition | Status | Notes |
|---|---|---|
| caller is not `OWNER` | 403 | `RoleGuard`, message specific per operation |
| unknown id, or another tenant's member | 404 | structural via RLS (§3) |
| would leave zero active owners | 409 | |
| member still holds open work | 409 | counts carried as structured `fields` (§4.4) |
| already in that state | 409 | entity guard |
| unknown role string | 400 | `@Pattern` bean validation on the request DTO |

**400, not 422, for the unknown role** — and that is the house rule, not an accident.
`InviteRequest` already validates `role` as a `@Pattern`-constrained `String` precisely so
an unknown value is a bean-validation failure rather than a Jackson deserialisation error,
and `MethodArgumentNotValidException` maps to `400`. 422 (`VALIDATION_FAILED` via
`ValidationException`) is for *domain* validation thrown from a service. `ChangeRoleRequest`
copies `InviteRequest`'s pattern verbatim.

Unlike the invitations slice, **there is no consistent-404 enumeration contract here** and
none is needed: every route is owner-only and behind a JWT, so there is no anonymous caller
to act as an oracle. Distinguishing 404 from 409 is a usability gain with no information
leak — the caller is already inside the tenant.

Audit actions, matching the `INVITE_SENT`/`INVITE_REVOKED` naming:

- `MEMBER_ROLE_CHANGED` — `{email, from, to}`
- `MEMBER_DISABLED` — `{email, sessionsRevoked}`
- `MEMBER_ENABLED` — `{email}`

---

## 8. Migration

`V33__assigned_to_indexes.sql` — two indexes, no schema change:

```sql
CREATE INDEX idx_customer_assigned ON customer (tenant_id, assigned_to);
CREATE INDEX idx_enquiry_assigned  ON enquiry  (tenant_id, assigned_to);
```

**No table, column or constraint is added by this slice** — `app_user` already carries
`role`, `status` and `@Version`.

These two indexes are pre-existing debt, flagged in the handoff as a "before the first large
tenant" item: the visibility predicate `assigned_to = :me OR assigned_to IS NULL` has run
unindexed on both tables since the `record-visibility` slice. `follow_up` already ships its
equivalent, `idx_follow_up_owner_due (tenant_id, assigned_to, status, due_at)`.

**They are included here (D10) because this slice adds three more queries on exactly that
column**, and because the house pattern — set by `activity`/`follow_up` — is that the slice
adding the query adds the index. Adding it now costs one line; retrofitting it later costs a
migration against a live table.

---

## 9. Testing

- `MemberServiceTest` — the three entity guards, the last-owner invariant on both reducing
  paths, self-targeting allowed when another owner remains, the open-work gate with each
  `AssignedWorkload` blocking independently and the aggregated message.
- `MemberControllerTest` — the four routes, the 403/404/409/422 bodies, and that
  `passwordHash` never appears in a response.
- `AuthServiceRefreshTest` — **extended**: a disabled user's refresh is rejected. This is the
  regression test for §6's layer 3 and should be written to fail against today's code first.
- `MemberOwnerRaceTest` — two concurrent demotions against a two-owner tenant, using the
  `CyclicBarrier(2)` pattern the invitations slice established. **Must be seen to fail with
  the tenant lock removed**, or it proves nothing (the prove-it-can-fail discipline that
  caught challenge #33).
- `VisibilityScopingArchTest` — three allowlist additions.

Baseline is 519 tests on `e9d694e`; run `./gradlew clean check`, not `clean test`.

---

## 10. Out of scope

- **Delete a member.** §1, D1.
- **Narrowing `SALES_MANAGER`** into its own visibility tier. Still unbuilt.
- **Bulk reassignment.** The gate *refuses* a disable and reports what blocks it; moving the
  work is done through the existing per-record endpoints. A reassign-in-one-call endpoint is
  a reasonable follow-up once the frontend knows what it wants.
- **Self-service profile editing** (a member changing their own email/phone/password).
  Adjacent and token-shaped, and it shares a surface with password reset, which has its own
  decisions.
- **The suspended-tenant hole in `AuthService.refresh`.** `refresh` does not check
  `TenantStatus` either, so a suspended tenant's users keep refreshing. It predates this
  slice, the invitations slice left it alone deliberately, and it is a *tenant* lifecycle
  concern rather than a *member* one. Noted here because this slice touches the very same
  method for a different reason — the fix is one condition away, and whoever takes tenant
  suspension should take it.

---

## 11. Decisions

| | Decision | Rationale |
|---|---|---|
| D1 | Disable, never delete | orphaned audit/invitation/assignment references (§1) |
| D2 | List is unpaged | handful of users per tenant; matches `listPending` (§2) |
| D3 | No hand-written tenant filter on target lookup | `app_user` is `@TenantId` + RLS (§3) |
| D4 | Disable refused while member holds open work | stranded work nobody can action (§4.1) |
| D5 | `AssignedWorkload` port declared in `iam` | keeps the package graph acyclic (§4.3) |
| D6 | Never zero active owners | unrecoverable in-product (§5) |
| D7 | Self-targeting allowed if another owner remains | no gain from a blanket ban (§5) |
| D8 | `PESSIMISTIC_WRITE` on the tenant row | closes write skew; `SERIALIZABLE` too costly (§5.2) |
| D9 | `AuthService.refresh` gains a status check | without it disable is decorative (§6) |
| D10 | Ship the two `assigned_to` indexes here | the slice adding the queries adds the indexes (§8) |
| D11 | `ConflictException` gains an optional fields map | the 409 must be machine-readable; existing 409s stay byte-identical (§4.4) |

## 12. Risks

- **The ≤15-minute access-token window** (§6.1) — accepted, with the cheaper remedy named
  (shorten the TTL) rather than the expensive one (per-request lookup).
- **The reassign-first gate shares §5.1's write-skew shape** (§6.2) — a concurrent assignment
  can strand work on a member being disabled in the same window the tenant lock closes for
  the owner count. Accepted because closing it would serialise ordinary CRM traffic to guard
  a rare admin operation, and unlike the owner invariant it is fully recoverable in-product.
- **Runtime-discovered blocker set** (§4.3) — a new assigned-to aggregate joins the gate
  only if someone writes an `AssignedWorkload` for it. Silent if forgotten. Mitigation is
  this spec plus the handoff entry; an ArchUnit rule tying "has an `assigned_to` column" to
  "has an implementation" was considered and judged more machinery than the risk warrants.
- **Three new `ALLOWED_METHODS` entries** (§4.3) weaken a guard that is strongest when
  empty. Each is justified in the allowlist comment, and the guard still fails on any
  *unlisted* read.
