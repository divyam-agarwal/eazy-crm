# EasyCRM — Handoff

**Last updated:** 2026-07-28 (order lifecycle merged to `main` as `8247579`).
**Purpose:** Everything a fresh agent needs to pick up this project and continue. Read this first, then the linked docs.

---

## 0. Resuming? Start here

**Nothing is in flight.** `main` is clean, all work is merged, and the next session begins by
choosing what to build — there is no half-finished task to rescue.

1. **Confirm the baseline before touching anything:** `open -a Docker`, wait for `docker info`,
   then `cd backend && ./gradlew clean test`. Expect **187 tests, 0 failures**. If that number
   differs, stop and reconcile before writing code — everything below assumes it.
2. **Read §1** (what this product is) and **§7** (non-negotiable working agreements).
3. **Go to §8** and pick the next chunk *with the user*. Do not start one unilaterally.
4. Then run the standard workflow on a feature branch off `main`:
   **brainstorming → (design spec →) writing-plans → subagent-driven-development →
   finishing-a-development-branch.**

§3 is the detailed inventory of what exists; §4 is history and standing gotchas. Read them when
you need the detail, not to find out what to do next.

---

## 1. What this project is

**EasyCRM** — a multi-tenant SaaS CRM for Indian tier-2/3 **distributors, traders, and small manufacturers**. React (frontend, not started) + Spring Boot + PostgreSQL. It is a real product intended to be sold.

- **Wedge:** enquiry → GST quotation → order. Stops hard at the Order (no invoicing/stock/ledger — that's Tally's job, and every target customer already runs Tally).
- **Positioning:** vertical-first (distributors/traders), not a horizontal Zoho clone.
- Full rationale is in the design spec (below). Read it before making product decisions.

## 2. Read these, in order

All under `docs/superpowers/`:

1. **`../../CLAUDE.md`** (repo root) — working agreements loaded every session. **Non-negotiable rules live here.**
2. **`specs/2026-07-22-easycrm-design.md`** — the full design spec (architecture, domain model, 4-layer isolation, import module, frontend plan, release plan). The source of truth for *what* to build.
3. **`plans/2026-07-24-p0-tenant-isolation-foundation.md`** — P0 isolation plan (DONE, merged).
4. **`plans/2026-07-25-p0-auth-core.md`** — P0-auth plan (**DONE, merged** — see §4 for what changed vs the plan).
5. **`specs/2026-07-25-p1a-master-data-design.md`** — P1a design spec (product/customer/contact/price-list master data). The source of truth for *what* P1a built.
6. **`plans/2026-07-25-p1a-master-data.md`** — P1a implementation plan (**DONE, merged** — see §4 for execution-time deviations).
7. **`specs/2026-07-26-p1b-quotation-engine-design.md`** — P1b design spec (quotation/version/item aggregate, price resolution, GST calc, lifecycle). The source of truth for *what* P1b built.
8. **`plans/2026-07-26-p1b-quotation-engine.md`** — P1b implementation plan (**DONE, merged**).
9. **`specs/2026-07-27-order-accept-design.md`** — order/accept design spec (`Order` aggregate, accept transition, event/audit seam, idempotency). The source of truth for *what* the order/accept slice built.
10. **`plans/2026-07-27-order-accept.md`** — order/accept implementation plan (**DONE, merged to `main` as `ea11d3f`**).
11. **`specs/2026-07-27-enquiry-design.md`** — enquiry design spec (`Enquiry` aggregate, 5-stage guarded lifecycle, phone-normalized one-active-per-phone dedupe, filtered list). The source of truth for *what* the enquiry slice built.
12. **`plans/2026-07-27-enquiry-slice.md`** — enquiry implementation plan (**DONE, merged to `main` as `a68035d`**).
13. **`specs/2026-07-27-enquiry-conversion-design.md`** — enquiry→quotation conversion design spec (convert-at-quotation-create; flip enquiry to `CONVERTED` + stamp `quotation.enquiry_id`, atomically). Source of truth for *what* the conversion slice built.
14. **`plans/2026-07-27-enquiry-conversion.md`** — conversion implementation plan (**DONE, merged to `main` as `06e6014`**).
15. **`specs/2026-07-27-sales-hardening-design.md`** — sales hardening design spec (optimistic-lock→409 handler + `UNIQUE(tenant_id, enquiry_id)` quote backstop). Source of truth for *what* the hardening slice built.
16. **`plans/2026-07-27-sales-hardening.md`** — sales hardening implementation plan (**DONE, merged to `main` as `abc2bd3`**).
17. **`engineering-challenges.md`** — running log of non-obvious problems + solutions (27 entries). Great context on the stack's quirks.
18. **`annotations-reference.md`** — living glossary of every Spring/JPA annotation used.
19. **`specs/2026-07-28-order-lifecycle-design.md`** — order lifecycle design spec (`DISPATCHED`/`CLOSED`/`CANCELLED` transitions + the deferred order-list filter fix). Source of truth for *what* this slice built. **DONE** — spec committed directly as `8a6c9dd`; the slice it describes is implemented and merged as `8247579`.
20. **`plans/2026-07-28-order-lifecycle.md`** — order lifecycle implementation plan. **DONE** — plan committed directly as `8c0703f`; executed in full and merged as `8247579`.

## 3. Current state

- **Branch:** `main`, working tree clean (order lifecycle merged; feature branch deleted).
- **Merged & done on `main`:** the design docs (including this slice's `specs/2026-07-28-order-lifecycle-design.md` `8a6c9dd` and `plans/2026-07-28-order-lifecycle.md` `8c0703f`, both committed directly) + **P0 tenant-isolation foundation** + **P0-auth core** + **P1a master data** (merge commit `2f9a2f4`) + **P1b quotation engine** (merge commit `43e9642`) + **order + accept** (merge commit `ea11d3f`) + **enquiry** (merge commit `a68035d`) + **enquiry→quotation conversion** (merge commit `06e6014`) + **sales hardening** (merge commit `abc2bd3`) + **order lifecycle** (merge commit `8247579`).
- **Latest merged: order lifecycle** — merged to `main` as `8247579` (5 code/test tasks + a docs wrap-up + one final-review fix wave, each review clean). Delivered: the four-state guarded machine `CONFIRMED → DISPATCHED → CLOSED` (terminal) with `cancel()` legal from either active state, all three transitions guarded entity-side (`Order.dispatch()`/`close()`/`cancel(reason)`, each naming its own precondition rather than coupling to enum ordinal order); a required, non-blank `cancelReason` (`VARCHAR(500)`, migration `V23__order_cancel_reason.sql`); `POST /api/v1/orders/{id}/dispatch|close|cancel` (422 on an illegal transition, 400 on a blank cancel reason, 404 cross-tenant); a generic `OrderStatusChangedEvent` + synchronous same-transaction `OrderStatusChangedAuditListener` writing `ORDER_DISPATCHED`/`ORDER_CLOSED`/`ORDER_CANCELLED` audit rows; `OrderSpecifications.filter(status, customerId)` AND-composing both list filters (`OrderRepository` now extends `JpaSpecificationExecutor<Order>`), closing the challenge #24 dropped-filter bug for orders; and a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED`, instead of silently handing back a dead order with 200 (challenge #27). The final whole-branch review added audit-detail-payload assertions (proving `from` carries the *pre*-transition status and `cancelReason` appears only on the cancel row) and cross-tenant coverage on the new `Specification` query path. **187 tests passing** from a clean build, up from the 166 sales-hardening baseline (+21).
- **Prior latest merged: sales hardening** — 2 code/test tasks + docs closing the two Minors deferred from the conversion review. **166 tests passing** from a clean build (`cd backend && ./gradlew clean test`), up from the 162 conversion baseline (+4). Delivered: (1) a global `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 so a lost-update race (concurrent `accept`/convert-at-create) returns 409 not 500 — a sibling of the challenge #15 `DataIntegrityViolation` backstop on the disjoint concurrency subtree; (2) `UNIQUE(tenant_id, enquiry_id)` on `quotation` (migration `V22` + entity `@Table`; NULLs distinct so enquiry-less quotes coexist) making one-quote-per-enquiry structural, a guard-bypassed/raced second insert now routing through the challenge #15 handler → 409. Both proven deterministically (no threads): a handler unit test, a single-threaded stale-write repo test, and repo constraint tests. Challenge #26 logged; challenge #25's 500-gap note updated to "closed".
- **Enquiry→quotation conversion** (prior): 2 code/test tasks + docs, merged as `06e6014` (162 tests). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when raised with an `enquiryId`, atomically. Challenge #25.
- **Enquiry slice** (prior): 7 tasks + a post-review re-enquiry test + PATCH-contract docs, merged as `a68035d` (154 tests).
- **Enquiry scope:** the wedge's *head* (lead capture) — 7 tasks (phone normalizer, the `Enquiry` aggregate + guarded 5-stage lifecycle, migration/RLS/partial-index dedupe, create endpoint, get + filtered list via a JPA `Specification`, edit/advance/lose transitions, and this challenges/annotations/handoff wrap-up).
- **What enquiry delivered:** the `Enquiry` aggregate (table `enquiry` — not reserved; tenant-scoped, RLS-covered) carrying nullable `customerId` (walk-ins), raw contact fields (`contactName`, `contactPhone` + derived `normalizedPhone`, `contactEmail`), `source` (own `EnquirySource` enum — same six values as `crm.CustomerSource`, kept separate so `sales` stays decoupled), `requirementText`, `assignedTo`, `stage` (`EnquiryStage`), optional `expectedValue` (money, JSON-string wire), and `lostReason`. **5-stage guarded lifecycle** `NEW → CONTACTED → QUALIFIED → CONVERTED / LOST`: guards live in the entity (mirroring `Quotation`'s transition methods), `advanceTo` allows only a later *active* stage (skips ok, no backward/terminal-target), `lose` requires a reason, `markConverted()` was **reserved for the later conversion slice** at the time — it is now reached by `QuotationService.create()` when a quote is raised with an `enquiryId` (merged `06e6014`), so a phone is freed for re-enquiry via either `CONVERTED` or `LOST`. **Dedupe = one active enquiry per phone**, enforced structurally by a Postgres **partial unique index** `UNIQUE(tenant_id, normalized_phone) WHERE stage NOT IN ('CONVERTED','LOST')` plus an app-level active-only pre-check (→409) with the challenge #15 `DataIntegrityViolation`→409 backstop (challenge #23). **List** uses a JPA `Specification` that AND-composes any subset of `?stage=&assignedTo=&source=` — deliberately avoiding the "drops a filter when two are supplied" bug (challenge #24) that the order list had at the time, and which was fixed for orders in `8247579`. **The quotation list still has it** — see the backlog in §8. REST: `POST` (201), `GET /{id}` (cross-tenant 404), `GET` (filtered, offset `PageResponse`, cross-tenant empty), `PATCH /{id}` (active-only edit, re-dedupes on phone change), `POST /{id}/advance`, `POST /{id}/lose`. Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`). Challenges #23–#24; no new annotations (`JpaSpecificationExecutor`/`Specification` noted in the annotations reference as concepts, not annotations).
- **P1a scope:** product/customer/contact/price-list CRUD — 13 planned tasks plus three execution-time additions (Task 7b global 409 handler, Task 13b test-hardening, and a final-review fix — see §4).
- **P1b scope:** the quotation engine on top of P1a's master data — 12 planned tasks (money-as-JSON-string wire format, GST calc, gapless document numbering, price resolution, the quotation/version/item aggregate + RLS, create/get/list/versions, edit with the frozen-version guard, send, revise, reject/expire, and its challenges/annotations/handoff wrap-up).
- **Order/accept scope:** the wedge's final stage on top of P1b — 7 tasks (the `Order` aggregate on physical table `sales_order`, gapless `ORD/FY/NNNN` numbering, the `accept` transition, `QuotationAcceptedEvent` + audit subscriber, order read endpoints, and this challenges/annotations/handoff wrap-up).
- **What order/accept delivered:** the `Order` aggregate — tenant-scoped, RLS-covered, physical table **`sales_order`** because `order` is a reserved SQL word (class stays `Order`, challenge #20) — carrying `orderNo`, `quotationId`/`quotationVersionId`, `customerId`, optional `poReference`/`poDate`, `subTotal`/`totalTax`/`grandTotal`, and `status` (**`CONFIRMED`** only *at the time of that slice* — `DISPATCHED`/`CLOSED`/`CANCELLED` and their guarded transitions arrived later in the order-lifecycle slice `8247579`; see the order-lifecycle bullet above). Gapless per-tenant/per-FY order numbering (`ORD/FY/NNNN`) reuses `DocumentNumberService`/`document_counter` under a distinct `"ORDER"` counter key (challenge #16's pattern, second doc type). `QuotationService.accept(id, AcceptRequest)`: validates the quotation is `SENT`, creates the `Order` inline (so the HTTP response carries it immediately), flips the quotation to `ACCEPTED`, then publishes `QuotationAcceptedEvent` for decoupled subscribers — a deliberate deviation from the parent spec's "the order handler subscribes" wording, keeping the event as a side-effect seam rather than a return channel while preserving same-transaction atomicity (challenge #22). `OrderAcceptedAuditListener` (`@EventListener`, synchronous, same-transaction) writes the `QUOTATION_ACCEPTED` audit row. Idempotency is **natural/state-based**, not a client idempotency key: a re-accept of an already-`ACCEPTED` quotation returns the existing order (`OrderRepository.findByQuotationId`), backed by `UNIQUE(tenant_id, quotation_id)` on `sales_order` plus the quotation's inherited `@Version` optimistic lock for the raced case (challenge #21). Read endpoints: `GET /api/v1/orders/{id}` and `GET /api/v1/orders` (status/customerId filters, offset-paginated `PageResponse`, cross-tenant → 404). Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`).
- **What P1b delivered:** the `Quotation`/`QuotationVersion`/`QuotationItem` aggregate (tenant-scoped, RLS-covered); a price resolver (customer + product → effective rate off `PriceList`/`PriceListItem`, falling back to `Product.baseRate`); server-side GST calc (per-line round-then-sum, intra-state CGST+SGST vs inter-state IGST, keyed off `Tenant.state_code` vs the customer's GSTIN-derived state); gapless per-tenant/per-FY document numbering (`document_counter` + `SELECT … FOR UPDATE`, see challenge #16); the global `BigDecimal`-as-JSON-string wire format for money (challenge #17, also retroactively fixing P1a's money fields); and the full lifecycle — create → edit (header patch / full item replace, guarded to DRAFT only) → send (freezes the version, assigns the quote number) → revise (spawns a new DRAFT version copying the frozen items verbatim) → reject/expire (challenge #18). Lives under `com.easycrm.sales` (+ `platform.money.BigDecimalStringModule`).
- **What P1a delivered:** tenant-scoped REST CRUD for `Product`, `Customer` (+ GSTIN checksum validation and GST-state-code derivation via the new `platform.gst.Gstin`/`StateCode` value types), `Contact` (nested under customer), `PriceList`, and `PriceListItem` (override-rate/discount-percent mutually-exclusive pricing). New shared plumbing: `platform.error.ValidationException` → 422 with field errors, `platform.web.PageResponse` (offset-paginated list envelope). Cross-tenant reads return 404 (not 403/200), matching the P0 pattern. Lives under `com.easycrm.catalog` and `com.easycrm.crm`.
- **What P0-auth delivered:** self-serve auth on top of the isolation foundation — atomic signup (tenant + first OWNER in one transaction), bcrypt login, rotating opaque JWT refresh tokens (SHA-256 at rest), tenant-scoped audit log, public auth endpoints with generic 401s. Lives under `com.easycrm.iam` (+ `platform.persistence.UuidV7`, `platform.error.{Conflict,Unauthorized}Exception`). Working `signup → login → GET /api/v1/auth/me → refresh` loop, all verified against Postgres + RLS.
- **What P0 (isolation) delivered:** the 4-layer multi-tenant isolation, all provably enforced by tests:
  1. **JWT resolution** (`platform/security` — `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`)
  2. **Hibernate `@TenantId`** (`platform/tenancy` — `TenantIdentifierResolver`, `HibernateTenancyConfig`; `TenantScopedEntity`)
  3. **Postgres RLS** (`TenantAwareTransactionManager` sets `app.current_tenant` per transaction; policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`)
  4. **ArchUnit** (`arch/TenantScopingArchTest` — every `@Entity` must extend `TenantScopedEntity` unless allowlisted in `GLOBAL_TABLES`)
  - Plus: `BaseEntity` (UUIDv7 ids, auditing, `@Version`), `TenantContext` (ThreadLocal + `runAs`), `TenantAwareTaskDecorator` (async propagation), `Tenant` (global entity), `DemoRecord` (isolation test subject — throwaway, replaced by real entities later), 404-not-403 error mapping, `DemoSeeder` + `backend/DEMO.md`.

## 4. Recently completed, and what was deliberately left out

**This section is history plus standing gotchas — the *next* task is chosen in §8, not here.**
Read it before extending any of the areas it describes, so you don't rebuild something that
exists or assume something that doesn't.

**The order-lifecycle slice is DONE and merged to `main` (`8247579`).** 5 code/test tasks plus this docs wrap-up landed and reviewed clean: (1) `OrderStatus` widened to `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with `isTerminal()`/`isActive()` and entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions, plus a required non-blank `cancelReason` (migration `V23`); (2) `POST /api/v1/orders/{id}/dispatch|close|cancel`, with `OrderResponse` gaining `cancelReason` as its 7th component; (3) a generic `OrderStatusChangedEvent` + `OrderStatusChangedAuditListener` writing the three new audit action rows; (4) `OrderSpecifications.filter` closing the challenge #24 dropped-filter bug for orders; (5) a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED` (challenge #27). The whole-branch review then added audit-detail and cross-tenant assertions. Clean-build total is **187 tests**, all green, up from the 166 sales-hardening baseline. Next step is to pick the next chunk with the user (see §8).

**Prior:** the sales-hardening slice is DONE and merged to `main` (`abc2bd3`). 3 tasks landed and reviewed (optimistic-lock→409 handler + tests; `UNIQUE(tenant_id, enquiry_id)` migration/entity + tests; docs), each task-review clean, and the whole-branch review returned READY TO MERGE with no Critical/Important findings. It closes the two Minors that the enquiry→quotation conversion whole-branch review consciously deferred (both now struck from the deferred list below).

**Deferred out of enquiry scope** (explicit — do not assume any of this exists):
- ~~**Enquiry → quotation conversion wiring**~~ — **DONE, merged** (`06e6014`). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when a quote is raised with an `enquiryId`. Note: still convert-*at-create* only; no standalone `/enquiries/{id}/convert` endpoint, and one enquiry maps to at most one quotation (a second create against a converted enquiry → 422).
- **`activity` / `follow_up` entities** — the spec's Activity section (CALL/WHATSAPP/EMAIL/VISIT/NOTE logs + first-class follow-up reminders) is still unbuilt.
- ~~**Order status transitions beyond `CONFIRMED`**~~ — **DONE, merged** (`8247579`). `OrderStatus` now has `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions and a required `cancelReason` — see the order-lifecycle summary above. Challenge #27.
- **PDF generation** and the **`wa.me` WhatsApp share link** — no rendering/sharing of a quotation or order exists yet.
- **Scheduled auto-expiry** — only a manual `expire` action exists on quotations; nothing runs on a schedule to expire quotations past `validUntil` automatically.
- **Record-level visibility filtering** — still open from P1a (§4 P1a notes); quotations, orders, and now enquiries inherit the same gap (every user in a tenant reads every enquiry in it).
- **Cursor pagination** — quotation, order, and enquiry list endpoints use the same offset-based `Pageable`/`PageResponse` as P1a; large tenants will need cursor pagination later.
- ~~**Optimistic-lock → 409 (codebase-wide)**~~ — **DONE, merged** (`abc2bd3`). A global `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 now maps a lost-update race (concurrent `accept` challenge #21, or convert-at-create challenge #25) to 409 instead of 500 — a sibling of the challenge #15 `DataIntegrityViolation` backstop on the disjoint transient/concurrency subtree. Challenge #26.
- ~~**Structural backstop for one-quote-per-enquiry**~~ — **DONE, merged** (`abc2bd3`). `UNIQUE(tenant_id, enquiry_id)` on `quotation` (migration `V22` + entity `@Table`; Postgres NULLs distinct, so enquiry-less quotes coexist) makes the one-quote-per-enquiry invariant structural. A guard-bypassed/raced second insert now routes through the challenge #15 handler → 409. Challenge #26.

**Testing note for anyone extending quotation flows:** quotation reads a real `Tenant.state_code` (to compute the intra-/inter-state GST split against the customer's GSTIN-derived state), so a phantom tenant — `TestTokens.owner(UUID.randomUUID())`, which mints a JWT for a tenant id that has no backing row — is **not enough** here, even though it's sufficient for RLS-only tables elsewhere in the codebase. Quotation tests use the new `TestTokens.provisionOwner(stateCode)`, which inserts a real `Tenant` row (with the given GST state code) before minting the token. Reach for `provisionOwner` whenever a test path reads anything off the `Tenant` row itself, not just whenever it needs *a* tenant id.

### What P1a changed vs its plan (read before extending master data)

Two things happened mid-execution that weren't in `plans/2026-07-25-p1a-master-data.md` verbatim:

- **Task 7b (added, not originally planned): a global `@ExceptionHandler(DataIntegrityViolationException.class)` in `ApiExceptionHandler`.** The plan's per-entity services already do an app-level "does this already exist?" pre-check before insert (e.g. duplicate GSTIN, duplicate SKU) and throw `ConflictException` → 409. That pre-check is a check-then-act race, not a guarantee: two concurrent creates can both pass it, and the update path has no pre-check at all. Added a global handler that catches the DB unique-constraint violation itself and still returns 409 (generic message) instead of a raw 500 — the backstop that makes the uniqueness guarantee hold under concurrency and on update. Logged as challenge #15.
- **Task 9 deviation: `ContactRequest.isPrimary` boxed from primitive `boolean` to `Boolean`.** Jackson 3 (Boot 4) fails a request body that omits a primitive field with a 400 before the controller ever runs — there's no way for a primitive to represent "absent." Boxed to `Boolean`, defaulted explicitly in `ContactService` (`Boolean.TRUE.equals(...)`). Logged as challenge #12.

Also logged from P1a's core design (not deviations, just the two hardest correctness problems it solved): the GSTIN Luhn-mod-36 checksum (challenge #13) and the override-rate/discount-percent XOR + `BigDecimal.compareTo`-not-`equals` (challenge #14).

**Deferred to P1b** (explicit, from the P1a plan's Global Constraints — do not assume these exist yet):
- **Money-as-JSON-string wire format.** P1a is the first code to put a `BigDecimal` on the wire (`Product.gstRate/baseRate`, `PriceListItem.overrideRate/discountPct`, etc.) and it currently serializes as a plain JSON **number**, not the string format challenge #2 specifies (`WRITE_BIGDECIMAL_AS_PLAIN` + string). P1b must add the global Jackson-3/Boot-4 serializer customizer before the quotation wire contract and frontend money handling ship — otherwise JS's `double` re-introduces the rounding error challenge #2 exists to prevent.
- **Price resolution** (customer + product → effective rate, reading `PriceList`/`PriceListItem`). Entities exist; no resolver yet.
- **Record-level visibility filtering** on `customer.assigned_to`. Column exists; nothing currently filters reads by it — every user in a tenant can read every customer in that tenant.
- **Cursor pagination.** P1a's list endpoints use offset-based `Pageable`/`PageResponse`; large tables will need cursor pagination later.

### What P0-auth changed vs its plan (read before extending auth)

Two design points in `plans/2026-07-25-p0-auth-core.md` did not survive contact with the stack and were changed (all logged in `engineering-challenges.md` #8–#11):

- **No `TenantBinder`.** The plan's Task 7 rebound an *open* transaction to a new tenant mid-flight. That can't work: Hibernate resolves a session's tenant **once, at session-open**, and never re-reads it — so `@TenantId` kept writing the wrong tenant and the owner insert failed RLS `WITH CHECK`. **Instead:** `Tenant` carries an **application-assigned UUIDv7 id** (`platform.persistence.UuidV7`, and `Tenant` implements `Persistable` so `save()` inserts), and signup sets the tenant context **before** the `TransactionTemplate` transaction opens. `AuthService.signup/login/refresh` all follow this "set context, then open the tx" shape rather than being `@Transactional` themselves. (#9)
- **RLS-scoped derived finders are `@Transactional(readOnly = true)`** (`UserRepository.findByEmail`, `AuditLogRepository.countByAction`). Spring Data doesn't wrap derived queries in a transaction by default, so without this the tenant GUC isn't set and RLS returns **zero rows** (fails safe, easy to miss). (#8)
- **`LOGIN_FAILED` audit uses `AuditService.recordIndependently` (`REQUIRES_NEW`)** so it survives the rollback caused by the 401 throw. Success-path audits stay on default propagation. (#11)
- **Jackson 3 gotcha:** Boot 4 ships Jackson under `tools.jackson`, not `com.fasterxml.jackson`. Tests extract JSON with jayway `JsonPath` to sidestep the mapper API. (#10)

**Design decisions locked** (don't relitigate): bcrypt (not Argon2), HS256 (not RS256), opaque refresh tokens hashed at rest, `refresh_token` is a *global* allowlisted table while `app_user`/`audit_log` are tenant-scoped, generic 401 (no enumeration).

## 5. Environment (macOS, already set up)

- **JDK 25** installed (`~/Library/Java/JavaVirtualMachines/openjdk-25.0.1`). Shell default is JDK 21, but the **Gradle toolchain uses 25** — do NOT change the shell default.
- **Gradle 9.6.1** (via Homebrew) — but always use the wrapper: `cd backend && ./gradlew ...`.
- **Docker** must be running (Testcontainers needs it). Start Docker Desktop: `open -a Docker`, then wait for `docker info` to succeed. Note: a user Postgres container (`langfuse-postgres-1`) runs on `localhost:5432` — leave it alone; Testcontainers uses its own random-port container.
- **Run tests:** `cd backend && ./gradlew test` (or `clean test` for a full run). Integration tests spin up one shared Postgres container (singleton pattern) — the suite runs in ~4s once the image is cached.
- **Sandbox note:** in this harness, network + Docker operations may need the Bash tool's sandbox disabled (`dangerouslyDisableSandbox: true`). SDKMAN's reachability check is blocked by the sandbox even when network works.

## 6. Stack quirks already discovered (see challenges log for detail)

This is **Spring Boot 4.1 + Java 25 + Hibernate 7** — all recent. Watch for:
- **Spring Boot 4 split auto-config into per-integration modules.** `flyway-core` alone doesn't bring `FlywayAutoConfiguration` → use `spring-boot-starter-flyway`. `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure` (module `spring-boot-webmvc-test`). `HibernatePropertiesCustomizer` moved to `org.springframework.boot.hibernate.autoconfigure`. **If an import "does not exist," search the resolved jars for the class's new package** rather than assuming the plan is wrong.
- **ArchUnit 1.4.1** (not 1.3.0) — 1.3.0 silently skips Java 25 bytecode.
- **Testcontainers BOM pinned to 1.21.3** (Boot 4 BOM doesn't manage those versions).
- **RLS + custom GUC:** a referenced custom GUC resets to `''` not NULL, so policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`. An RLS `USING` clause also acts as `WITH CHECK` for inserts.
- **Two DB roles:** Flyway runs as the **owner** (Testcontainers superuser); the app connects as **`easycrm_app`** (non-owner, no BYPASSRLS) — this is what makes RLS real. `IntegrationTest` wires both datasources.
- **`ddl-auto: validate`** is on — migration column types must match entity mappings exactly (e.g. `VARCHAR` not `CHAR` for a `String`).

## 7. Working agreements (also in CLAUDE.md — enforced)

- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already set). Plain `git commit`. **Never** add a `Co-Authored-By: Claude` trailer or mention Claude/AI in commit messages.
- **Log engineering challenges:** when a task surfaces a non-obvious problem, append to `engineering-challenges.md` (Problem → why hard → Solution → Lesson) in the same change.
- **Keep the annotations reference current:** add a row when a new annotation appears.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit. One task per commit.
- **Money is never `double`** (BigDecimal / NUMERIC / JSON string). P1a got the Java/Postgres side right (`NUMERIC`, `compareTo` not `equals`) but still shipped `BigDecimal` fields on the wire as plain JSON numbers; **P1b closed that gap globally** with `platform.money.BigDecimalStringModule` (challenge #17) — every `BigDecimal`, including P1a's already-shipped fields, now serializes as a JSON string.
- **Tenant isolation is structural:** never hand-write `WHERE tenant_id`; rely on `@TenantId` + RLS; new entities extend `TenantScopedEntity` or get allowlisted (ArchUnit enforces).

## 8. The next chunk — pick one with the user

The wedge (**enquiry → quotation → order**) is functionally complete end-to-end and hardened,
including the order aggregate's own lifecycle. All five candidates below are scoped in the design
spec (`specs/2026-07-22-easycrm-design.md`). Present them, take the user's choice, and only then
start the workflow from §0 step 4.

1. **PDF + `wa.me` WhatsApp share** for a quotation/order — the **first external-I/O slice**, and the
   trigger to move the accept-audit event from same-transaction to **after-commit + outbox**
   (challenge #22 flagged this seam). Highest product value, but introduces rendering + the outbox
   pattern.
2. **`activity` / `follow_up` entities** — the "never lose a follow-up" promise (CALL/WHATSAPP/EMAIL/
   VISIT/NOTE logs + first-class reminders). New aggregate(s); the accept event seam already exists
   to hang activity listeners on.
3. **Scheduled auto-expiry** of quotations past `validUntil` — only a manual `expire` action exists
   today; nothing runs on a schedule. Small, introduces the first scheduled job.
4. **P0-auth follow-up** — user invitations + **record-level visibility filtering** (`assigned_to`,
   still open from P1a — every user in a tenant reads every record) + rate limiting.
5. **Cursor pagination** — quotation/order/enquiry lists are all offset-based `Pageable`/
   `PageResponse`; large tenants will need cursor pagination. Cross-cutting, lower urgency.

**Suggested default:** **#1 (PDF + `wa.me` WhatsApp share)** — with order status transitions now
done, this is the highest-product-value chunk left and the natural trigger for the challenge #22
outbox migration. But confirm with the user.

### Smaller deferred-Minor backlog

Open and non-blocking. This list is the complete record — it is **self-contained**, so don't go
looking for an SDD ledger to corroborate it (those workspaces are deleted once a slice merges).
Roughly highest-value first.

1. **`QuotationService.list` has the dropped-filter bug** the order-lifecycle slice fixed for
   orders — `QuotationService.java`, the `if (status != null) … else if (customerId != null) …`
   block, so `?status=` and `?customerId=` together silently ignores the customer. Found while
   fixing the order list; left out only because that spec scoped the fix to orders. **The fix is
   mechanical:** a `QuotationSpecifications.filter` mirroring `OrderSpecifications`, plus
   `JpaSpecificationExecutor<Quotation>` on the repository and a two-filter regression test.
   *The whole-branch reviewer recommended this lead the next slice, whatever that slice is.*
2. **Cancelling an enquiry-linked order has no path back to that enquiry** (challenge #27). The
   422 message says "raise a new quotation", which only fully works for enquiry-less quotations:
   `Enquiry.requireActive()` rejects a second `markConverted()` and `UNIQUE(tenant_id,
   enquiry_id)` blocks a second quotation, so the replacement must go in with `enquiryId: null`,
   silently severing lead traceability. Re-opening the enquiry on cancel, or relaxing
   one-quote-per-enquiry, is an **open design decision, not a bug** — decide it deliberately.
3. **PATCH endpoints house-wide are full-header-replace**, not partial merges — an omitted
   nullable field is cleared. The PUT-vs-PATCH-vs-partial decision is deliberately deferred until
   the frontend lands and can state what it needs.
4. **`OrderSpecifications` and `EnquirySpecifications` use string-keyed `root.get(...)`** rather
   than a JPA static metamodel, so a field rename fails at runtime rather than compile time. Both
   have immediate test coverage. If fixed, fix them together — doing one alone just makes them
   inconsistent.
5. **`OrderTest`'s three rejected-transition tests assert only the exception type**, not that
   `status`/`cancelReason` are left unmutated; only the blank-reason test snapshots state. Safe
   today (every guard runs before any assignment), but a future guard reorder would go uncaught.
6. **Four near-identical order-building test fixtures** now exist across the sales test classes
   (`OrderReadTest`, `OrderTransitionTest`, `OrderStatusAuditTest`, plus
   `QuotationAcceptAuditTest`'s inlined variant). Extracting a shared sales test-fixture helper
   is a candidate cleanup; it was consciously declined to keep slices independent.
7. **`Enquiry.advanceTo` couples to enum ordinal order** (guarded, but a reorder changes
   behaviour). `Order`'s transitions deliberately avoid this by naming each precondition — that
   is the pattern to copy if `Enquiry` is ever revisited.
8. **`expectedValue` / `contactEmail` lack `@PositiveOrZero` / `@Email`** on the enquiry DTOs.
9. **No index supports a status-only order-list filter.** `sales_order` has
   `(tenant_id, customer_id)` and `(tenant_id, id)`; `?status=` alone has none. Irrelevant at
   current volumes — worth revisiting before the first large tenant.
