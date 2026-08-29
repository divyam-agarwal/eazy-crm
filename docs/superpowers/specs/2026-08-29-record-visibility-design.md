# EasyCRM P1 — Record-Level Visibility Design (intra-tenant `assigned_to` filtering)

**Status:** Design approved, pre-implementation
**Date:** 2026-08-29
**Parent spec:** `2026-07-22-easycrm-design.md` §6 ("Record-level visibility layer")
**Also promised by:** `2026-07-25-p1a-master-data-design.md` (out-of-scope list: "`customer.assigned_to`
is stored as a column now but is **not** yet used to filter reads — P0-auth follow-up")
**Target architecture:** `../../architecture/2026-07-29-target-architecture.md`
**Depends on:** everything merged on `main` through `d7725b0` (the rate-limiting merge). Nothing in
this slice depends on rate limiting; it is simply the immediately prior state.

---

## 1. Context & purpose

**Every authenticated user in a tenant can currently read and mutate every record in that tenant.**
`Role` (`OWNER`, `SALES_MANAGER`, `SALES_EXEC`) is minted into the JWT, carried on
`TenantContext.TenantPrincipal`, and then consulted by nothing: there is no `@PreAuthorize` anywhere
in `src/main/java`, and `SecurityConfig` distinguishes only authenticated from unauthenticated.
`customer.assigned_to` (V10) and `enquiry.assigned_to` (V20) exist as nullable columns, are settable
and returned through the API, and are read by no query.

This is a **tenant-internal confidentiality gap in code running today** — the same category of claim
that put PF14/PF15 ahead of everything else two slices ago, and the last such item on the §8 board.
It is also a prerequisite the frontend will assume exists: the design spec's dashboard is described
as role-aware ("`SALES_EXEC` sees only today's follow-ups and my open quotes") and there is nothing
underneath that description.

**This layer is not tenant isolation.** RLS enforces the tenant wall, which is a security boundary.
This layer enforces intra-tenant visibility, which is a product rule. Different jobs, different
places — the parent spec §6 is explicit about the separation and this design preserves it. A
consequence worth stating: a bug in this layer is a product bug, not a tenancy breach.

## 2. Scope

**In scope**

- A two-tier visibility rule over four aggregates: `Customer`, `Enquiry`, `Quotation`, `Order`.
- Applied to **reads and writes alike** — an invisible record 404s on `GET`, update (`PUT` for
  `Customer`, `PATCH` for `Enquiry`), `accept`, `cancel`, `deactivate`, share-link mint and PDF
  render.
- Applied to **nested reads** reached through a parent (`Contact`, `QuotationVersion`,
  `QuotationItem`).
- One deliberately **unfiltered lane** for two uniqueness checks, made explicit and guarded (§6), and
  an explicit classification of every other derived query on the guarded repositories (§6.1).
- A fourth `*Specifications` class for `Customer`, replacing the `findByActive` derived query (§6.1).
- Validation that a non-null `assigned_to` names an `ACTIVE` user in the tenant (§7).
- An ArchUnit guard that fails the build when a new repository read bypasses the layer (§8).

**Out of scope — deliberately, so the spec's silence is not read as an oversight**

- **Teams, role hierarchy, or any narrowing of `SALES_MANAGER`.** See §3.
- Sharing rules, manual per-record shares, record teams (the Salesforce features above the
  owner/hierarchy layer). Nothing asks for them.
- Per-field visibility. Visibility is per row.
- `Contact.assigned_to` of its own. Contacts inherit from their customer.
- Audit-log read filtering — `AuditLog` has no read endpoint to filter.
- Dashboard aggregates — no dashboard exists.
- `/public/q/{token}`. It has no JWT by construction; see §5.
- Cursor pagination, and any change to the existing `PageResponse` contract.

## 3. The rule — two tiers, not three

The parent spec §6 says "`SALES_EXEC` sees records assigned to them, `SALES_MANAGER` their team,
`OWNER` everything." **The middle tier has no implementation, because it has no data model:** there
is no team table and no manager→report edge on `app_user`. Inventing one is a larger slice with its
own admin surface, and nothing in the product asks for it yet.

So this slice ships **two tiers**:

| Role | Sees |
|---|---|
| `OWNER` | every record in the tenant |
| `SALES_MANAGER` | every record in the tenant |
| `SALES_EXEC` | records assigned to them, plus unassigned records |

`SALES_MANAGER` is deliberately collapsed into the unrestricted tier. This is a **widening** relative
to the eventual intent, which means a later team model narrows it — and narrowing later cannot break
a caller that was relying on seeing less. The reverse (shipping a guess at team semantics, then
changing it) would.

### Only `SALES_EXEC` is restricted — a deliberate fail-open

The rule is written as "restrict `SALES_EXEC`", not "permit `OWNER` and `SALES_MANAGER`". Every other
role, and a request with **no principal at all**, is unrestricted.

This is fail-open, and it is safe only because this layer is not a security boundary — RLS still
applies to every query built here, so failing open means "sees the whole tenant", never "sees another
tenant". Two things depend on it:

- **Internal flows with no principal, and flows that install a synthetic one.** Tenant provisioning
  (`TestTokens.provisionOwner` uses a `SYSTEM` role today) runs with a synthetic principal, not an
  absent one — same reasoning as the public-share case below. A request where nothing ever set a
  `TenantContext` principal lands on `unrestricted()`'s `orElse(true)` for the more literal reason:
  there is no principal to restrict against. The public share route is a different case, not the
  same one under another name: `PublicShareController` installs a **synthetic principal with role
  `"PUBLIC"`** via `TenantContext.runAs` before rendering, so it is unrestricted for the ordinary
  reason any non-`SALES_EXEC` role is — `"PUBLIC" != "SALES_EXEC"` — not because the principal is
  absent. If anything this is a *stronger* argument for the restrict-`SALES_EXEC`-only framing: a
  role added later is unrestricted for free, with no separate "absent principal" case to maintain. A
  fail-closed default would break all of these silently and in ways that look like data loss.
- **Async work inherits the submitter's principal — it does not run principal-less.**
  `TenantAwareTaskDecorator.decorate`, wired onto the pool executor by `AsyncConfig`, captures the
  submitting thread's `TenantContext.TenantPrincipal` before handing the task to the pool and
  reinstalls it on the pool thread, clearing it once the task completes. A future `@Async` method
  triggered by a `SALES_EXEC` therefore runs **restricted**, not unrestricted — the opposite of what
  an earlier version of this section claimed. Inheriting the submitter's principal is arguably the
  right behaviour, since work triggered by someone should not see more than they could; the point
  here is only that the spec must describe the mechanism correctly. Nothing depends on this today:
  both `@EventListener`s in this codebase are synchronous, and neither reads a guarded aggregate.
- **Any role added later** must not start hiding rows from users who could see them the day before.
  Restricting a new role should be an explicit edit, not something it inherits by not being on a list.

The trade is that a typo in a role name grants access rather than denying it. Acceptable here, and it
would not be if this were the tenant wall.

### Unassigned means visible

`assigned_to IS NULL` is visible to everyone, the standard CRM shared-pool idiom (Salesforce models
this as a Queue owner). Three reasons:

1. **Every row in existence is NULL.** Nothing has ever written the column. Any other reading empties
   every `SALES_EXEC`'s CRM on the day this deploys, and would need a backfill that invents historical
   attribution.
2. It fails open only on records **nobody has claimed** — access narrows monotonically as records get
   assigned, which is the safe direction for a rollout.
3. Claiming from an unassigned pool is ordinary sales behaviour, and it needs no new endpoint.

The cost is stated plainly: an unassigned record is not confidential. Confidentiality begins at
assignment.

## 4. Attribution per aggregate

Salesforce splits objects into those that carry their own owner (Lead, Account, Opportunity, Order)
and those that are *controlled by parent* (Quote, and optionally Contact). This design applies the
same split, resolved against what EasyCRM's schema actually supports:

| Aggregate | Attribution | Predicate for `SALES_EXEC` |
|---|---|---|
| `Customer` | own column | `assigned_to = :me OR assigned_to IS NULL` |
| `Enquiry` | own column | `assigned_to = :me OR assigned_to IS NULL` |
| `Quotation` | derives from its customer | `EXISTS (SELECT 1 FROM customer c WHERE c.id = customer_id AND (c.assigned_to = :me OR c.assigned_to IS NULL))` |
| `Order` | derives from its customer | same subquery on its `customer_id` |
| `Contact`, `QuotationVersion`, `QuotationItem` | inherit via parent load | none of their own (§5) |

**No new columns and no migration.** Deriving quotation and order visibility from the customer means
this slice adds no schema at all.

Why derive rather than add `assigned_to` to `quotation` and `sales_order`:

- `customer_id` is `NOT NULL` on **both** tables (V15, V18), so there is no optional-parent hole. The
  nullable-parent problem belongs to `enquiry_id`, not `customer_id` — a quotation created after a
  cancelled order legitimately carries `enquiryId: null` (backlog #6), so an enquiry-derived rule
  would have needed a fallback for a documented flow. A customer-derived rule needs none.
- Both tables already carry a `(tenant_id, customer_id)` index, so the correlated subquery has support.
- The subquery runs under RLS like any other query, so it cannot reach across tenants.

The accepted consequence: **reassigning a customer moves that customer's entire quotation and order
history between reps.** For this product — one rep owns an account — that is arguably correct rather
than merely tolerable, but it is a consequence, not an accident.

A quotation's own visibility and its source enquiry's visibility can diverge, since the table above
attributes them differently — a quotation via its customer, an enquiry via its own `assigned_to` — so
a `SALES_EXEC` can hold a visible quotation whose `enquiryId` points at an enquiry that 404s for them
if fetched directly, and vice versa; only the id crosses that boundary, never enquiry data, but the
attribution split is not as clean as the table makes it look.

The `assigned_to = :me OR assigned_to IS NULL` predicate has no index behind it — it's a sequential
scan within the tenant partition, currently masked because every existing row satisfies the `IS NULL`
arm. Fine at today's scale; it's the first thing to look at once a tenant's `customer` or `enquiry`
table grows large enough for a scan to show up in a slow-query log.

## 5. Architecture

### 5.1 Components — `com.easycrm.platform.visibility`

A new package parallel to `platform/tenancy`. It is deliberately **not** inside `platform/tenancy` or
`platform/security`: this is a product rule, and filing it under either would erode the separation
§1 depends on.

**`VisibilityPolicy`** — a `@Component`. Reads `TenantContext`, exposes:

- `boolean unrestricted()` — true for `OWNER` and `SALES_MANAGER`.
- Four typed `Specification` builders: `customers()`, `enquiries()`, `quotations()`, `orders()`.

Four typed methods rather than the parent spec's sketched `forCurrentUser(Enquiry.class)`. The
predicates differ in kind — two are column compares, two are subqueries — so a generic signature
would need a type registry that buys nothing and hides the difference.

For an unrestricted principal each builder returns an **empty conjunction** (`cb.and()` with no
predicates), matching the idiom `OrderSpecifications.filter` already uses for "no filters supplied".

**`VisibleFinder`** — a `@Component` holding the four repositories and the policy. It is the **only**
class in the codebase permitted to call an inherited repository read method on those four aggregates
(§8 enforces this). It exposes:

- `Optional<T>` by-id finders: `findCustomer(id)`, `findEnquiry(id)`, `findQuotation(id)`,
  `findOrder(id)`, each AND-ing the policy's specification with an id predicate.
- Paged list equivalents — `pageCustomers`, `pageEnquiries`, `pageQuotations`, `pageOrders` — that
  AND the policy's specification onto a caller-supplied user filter.

The `find`/`page` prefixes are load-bearing: the class holds fields named `customers`, `enquiries`
and so on, and a bare `customers(spec, pageable)` beside a `customers` field reads as a collision
even though Java permits it.

Consequence: the four services stop touching their repositories for reads and use them only for
`save`. This is invasive-looking but mechanical — four `find` methods and four `list` methods.

`CustomerRepository` gains `JpaSpecificationExecutor<Customer>`; `EnquiryRepository`,
`QuotationRepository` and `OrderRepository` already have it.

### 5.2 Coverage — the choke points

Each aggregate already funnels **both reads and mutations** through a single private by-id method:
`CustomerService.find`, `EnquiryService.find`, `OrderService.find`,
`QuotationService.findQuotation`. Re-pointing those four at `VisibleFinder` covers every by-id path
at once — `GET`, `PATCH`, `accept`, `cancel`, `deactivate` all 404 identically on an invisible record.

`OrderService.find` carries the comment *"Cross-tenant rows are invisible to RLS, so 'not mine' and
'not there' both 404."* It must be reworded: "not mine" now has two distinct meanings, one a tenancy
boundary and one a product rule, and a future reader must not collapse them.

Three paths need attention beyond the choke points:

- **`QuotationPdfService.renderVersion(versionId)`** loads a `QuotationVersion` *before* its
  quotation. It must re-check the resolved quotation through the finder.
- **`ContactService`** reaches a contact by id, and separately loads a customer to validate a parent.
  Both go through the customer finder.
- **`ShareLinkService.share`** loads a quotation by id. Through the finder — an exec must not be able
  to mint a public link for a quotation they cannot see.

`QuotationVersion` and `QuotationItem` carry no assignment and are always reached through a quotation
that has already been checked. They inherit; they get no predicate of their own.

### 5.3 What stays outside the layer

**`/public/q/{token}` is unfiltered.** It has no JWT, so `ShareLinkService.resolve` recovers the
tenant from the share token rather than from a principal — but before the rendering transaction
opens, `PublicShareController` installs a **synthetic principal with role `"PUBLIC"`** via
`TenantContext.runAs`. `VisibilityPolicy.unrestricted()` returns true for it for the same reason it
does for any non-`SALES_EXEC` role — `"PUBLIC" != "SALES_EXEC"` — not because the principal is
absent. The no-JWT fact is real, and is the same structural fact that keeps PF19's
entitlement-metering half open; it just isn't why this route is unfiltered. Its protections are the
128-bit token and the per-IP rate limiter.

**Not-visible returns 404, not 403.** This matches the existing cross-tenant behaviour and does not
disclose that a record exists. The two cases are indistinguishable to the caller by design.

## 6. The deliberately unfiltered lane

Two derived queries must **not** be filtered, or they stop protecting an invariant:

- **`EnquiryRepository.findByNormalizedPhone`** — backs the one-active-enquiry-per-phone dedupe.
- **`CustomerRepository.findByGstin`** — backs GSTIN uniqueness.

If either saw only the caller's own rows, two reps would each successfully create a row that the
invariant forbids: the check-then-act pre-check would pass, and the database backstop (the partial
unique index plus the `DataIntegrityViolation`→409 handler, challenge #15) would fire as a confusing
500-shaped conflict at a random later moment rather than as the intended field-level 409.

Both are **existence checks feeding a conflict**, not reads that return a record to the caller. They
stay on the raw repository, and they are allowlisted **by name** in the guard test (§8) with a comment
stating why.

**The accepted disclosure:** the 409 tells exec A that *someone* in the tenant already holds that
phone number or GSTIN, even when exec A cannot see the record. This is the correct trade — the
alternative is a broken uniqueness invariant — but it is a disclosure and it is recorded here as one.

This tension is challenge-log-worthy under `CLAUDE.md`: a visibility filter that must be deliberately
*not* applied at exactly the two points where a naive implementation would apply it everywhere.

### 6.1 The remaining derived queries, classified

The four guarded repositories expose three other derived queries. The guard's allowlist (§8) forbids
each by default, so each is classified here rather than left for the implementer to guess:

- **`CustomerRepository.findByActive`** — **replaced, not allowlisted.** `CustomerService.list`
  currently branches between `findAll(pageable)` and `findByActive(active, pageable)`. Both are
  guarded methods, and the branch cannot survive. Introduce `CustomerSpecifications.filter(active)`
  mirroring `QuotationSpecifications`/`OrderSpecifications`, and delete `findByActive`. This is the
  fourth `*Specifications` class and inherits deferred-minor #9's string-keyed `root.get(...)`
  caveat — do not fix that here, fix all four together or none.
- **`OrderRepository.findByQuotationId`** — **allowlisted as inherit-via-parent.** It is reached only
  from a quotation that has already been checked, and because a quotation and its order derive
  visibility from the *same* customer, applying the order predicate to it would be a provable no-op.
  Allowlisted with that reasoning recorded, rather than wrapped in a filter that can never change an
  outcome.
- **`ContactRepository.findByCustomerId`** — **outside the rule.** `ContactRepository` is not one of
  the four guarded repositories. Contacts are gated by the customer load in `ContactService` (§5.2),
  not by a predicate of their own.

## 7. `assigned_to` validation

On `Customer` create/update and `Enquiry` create/update, a non-null `assigned_to` must resolve to an
`ACTIVE` `User`. `User` extends `TenantScopedEntity`, so RLS already makes a cross-tenant id return
empty; the check is a `users.findById(...)` plus a status test, raising the existing 422
`ValidationException` (field-level) from the standard error contract.

This closes a footgun **this slice creates**: once `NULL` means "everyone sees it", a typo'd or stale
UUID means "nobody below manager sees it" — silently, permanently, and with no error at write time.
Today the column is inert, so the same typo is harmless. It stops being harmless the moment the
column is load-bearing.

### Who may reassign

Reassignment stays open to anyone who can see the record. No new endpoint: `assignedTo` is already on
`CustomerRequest` and `EnquiryUpdateRequest`.

- An exec can only `PATCH` a record they can already see, so the reachable moves are claiming from the
  unassigned pool (ordinary CRM behaviour) and handing a record to a colleague (losing sight of it).
- Restricting it would require a fourth concept — "may manage assignments" — that the two-tier model
  deliberately does not have, and any move is reversible by a manager or the owner.

Stated here so it is a decision on record rather than something discovered later.

## 8. The guard

`VisibilityScopingArchTest`, in the spirit of `TenantScopingArchTest`:

> No class outside `com.easycrm.platform.visibility` may call **any** method on
> `CustomerRepository`, `EnquiryRepository`, `QuotationRepository` or `OrderRepository`, except an
> explicit allowlist: `save`, `saveAndFlush`, `delete`, the two deliberately-unfiltered queries
> `findByGstin` and `findByNormalizedPhone`, and the inherit-via-parent `findByQuotationId`.

(`findByActive` is absent from that list on purpose — §6.1 deletes it rather than exempting it.)

**The allowlist direction is the design decision.** A rule phrased as a blocklist of known read
methods (`findById`, `findAll`, `findOne`, …) passes any new side door silently — a `findByFooId`
added next year would be unguarded and no test would say so. Phrased as an allowlist, a new derived
query defaults to **forbidden**, and its author must come to this test and declare which lane it
belongs in. That forcing function is exactly what `TenantScopingArchTest.GLOBAL_TABLES` provides for
tenant scoping, and it is the reason the layer stays honest after this slice ends.

The allowlist must be kept in step with `VisibleFinder`: a method added to one and not the other is
the failure mode to look for.

This is the second challenge-log-worthy item: allowlist-vs-blocklist is the difference between a
guard that decays silently and one that cannot.

**Prove-it-can-fail is mandatory.** The implementing task must delete one service's delegation to
`VisibleFinder`, observe the test go red, and restore it. Challenge #33 was caught only because the
`platform-primitives` plan forced the equivalent step; a guard never seen to fail is not known to work.

## 9. Testing summary

- **Unit — `VisibilityPolicy`:** predicate shape per role, including that `unrestricted()` yields an
  empty conjunction rather than a tautology, and that `SALES_EXEC` yields the two-armed
  own-or-unassigned disjunction.
- **Per-aggregate integration:** one tenant, four principals (owner, manager, exec A, exec B). Exec A
  sees own + unassigned and not exec B's; manager and owner see everything. Run across all four
  aggregates, including the two that derive through the customer.
- **Write coverage:** exec A receives 404 on both a `GET` and a mutation (`PATCH`, `cancel`) against
  exec B's record. Without this pair the layer is cosmetic — it would look closed while leaving the
  mutation path open.
- **Nested:** a contact under an invisible customer, a PDF render of an invisible quotation, and a
  share-link mint on an invisible quotation all 404.
- **The load-bearing §6 test:** exec A creating an enquiry for a phone held by exec B's *invisible*
  enquiry still receives 409, and no duplicate active row is created. This proves the unfiltered lane
  is deliberate and correct rather than an oversight.
- **The guard**, plus its prove-it-can-fail step.

**Regression expectation.** `TestTokens` mints an `OWNER` token for every existing integration test,
so all 296 current tests fall on the unrestricted path. This slice should be **additive** to the
suite rather than a rewrite of it. If existing tests do start failing, that is a signal the
unrestricted path is wrong — investigate before adjusting a test.

## 10. Documentation obligations (same change, per `CLAUDE.md`)

- **`engineering-challenges.md`** — two entries expected: the invariant/confidentiality tension in §6,
  and the allowlist-vs-blocklist guard framing in §8. Log them as part of the implementing tasks, not
  afterwards.
- **`annotations-reference.md`** — add rows for any annotation this slice introduces that is not
  already present (`@PreAuthorize` is *not* expected; this slice uses specifications, not method
  security).
- **`HANDOFF.md`** — §3 inventory, and §8: backlog item #3 is fully closed by this slice, which
  leaves user invitations as the only remaining P0-auth follow-up. The parent spec §6's three-tier
  sentence should be annotated as partially implemented, with §3 above as the reason.

## 11. Out-of-scope recap (do not build)

Teams or role hierarchy. Any narrowing of `SALES_MANAGER`. Sharing rules, manual shares, record
teams. Per-field visibility. `Contact.assigned_to`. Audit-log read filtering. Dashboard aggregates.
A visibility predicate on `/public/q/{token}`. New columns or migrations of any kind — §4 is designed
to need none.
