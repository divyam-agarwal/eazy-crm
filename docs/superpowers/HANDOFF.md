# EasyCRM — Handoff

**Last updated:** 2026-08-31 — **Scheduled quotation auto-expiry is built on branch
`quotation-auto-expiry`, all seven tasks complete and green, pending merge (not yet on `main`).**
The codebase's first non-request execution path now exists: a nightly job iterates every
job-eligible tenant with no JWT behind it, and the reusable seam it landed —
`platform/job/TenantJobRunner` — is what every future scheduled job should build on rather than
growing its own tenant loop. `TenantJobRunner` owns the ordering that makes tenant-scoped reads
work at all outside a request (`TenantContext.runAs` wraps its own `PROPAGATION_REQUIRES_NEW`
transaction, never the reverse — challenge #52) so that ordering is structural, not something each
job author has to remember. A `Quotation` past `validUntil` and still `SENT` is now expired
automatically at 00:30 IST, with an audit row and a timeline activity, using the same IST
calendar-date arithmetic `DueWindow` already had for follow-ups (challenge #53 covers the
UTC-vs-IST trap this raises for a `LocalDate` column). Backlog item #2 (scheduled auto-expiry) is
now **DONE** — see §8. The previous slice (activity log and follow-ups) is **merged to `main` as
`f97c62c`**; see §3's "Previous code work" for its detail.
**Purpose:** Everything a fresh agent needs to pick up this project and continue. Read this first, then the linked docs.

---

## 0. Resuming? Start here

### One thing is in flight

**`quotation-auto-expiry` is complete but not merged.** Seven tasks (`DueWindow.todayDate` for
IST-vs-UTC calendar-date comparisons, `Quotation.expire()`'s own `SENT` precondition,
`QuotationSpecifications.expirableAsOf` + `VisibleFinder.listQuotations`, `TenantJobRunner` +
`TenantRepository.findByStatusIn`, the `QuotationExpiredEvent` sweep with its audit and activity
listeners, `SchedulingConfig`/`QuotationExpiryJob`/the cron property/the test-suite cron disable,
and this docs wrap-up) are done on branch `quotation-auto-expiry`, commits `63a2865`..`7d89963` off
`main` at `d7eae98`, every task reviewed clean. **It has not been merged to `main`** — run
`finishing-a-development-branch` on it before starting anything new, unless you're picking this
session up specifically to review or merge it.

**One loose end that is not code, carried forward from before this slice:** the Bucket4j entry
written for `/Users/divyam/Documents/dsa/good-repos/CATALOG.md` is **on disk but unversioned** — that
directory is not a git repository, so nothing was committed there. The rate-limiting design spec §7
asked for the entry; it exists; it is just untracked. Decide with the user whether that repo should
be `git init`ed. Do not init it unilaterally.

Before it, `activity-follow-up` ran to completion and **merged to `main` as `f97c62c`**; that
feature branch is deleted, as was `record-visibility` before it (merged as `c81f59f`),
`public-rate-limiting` before that (merged as `d7725b0`), `rls-force-and-guard` before that
(merged as `3c239d1`), and `platform-primitives-module` before that (merged as `210545e`).

1. **Confirm the baseline before touching anything:** `open -a Docker`, wait for `docker info`,
   then `cd backend && ./gradlew clean test`. On this branch (`quotation-auto-expiry`) this is
   **461 tests, 0 failures, 0 errors** (438 root + 23 `platform-primitives`), up from `main`'s
   **432 tests** baseline (409 root + 23 `platform-primitives`). Gradle prints no total for a
   multi-project build, so count it yourself:

   ```bash
   cd backend && ./gradlew clean test
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
   ```

   If that number differs, stop and reconcile before writing code — everything below assumes it.
   **Counting only the root project's XML files produces a phantom 23-test gap** — `find .
   -path './build/test-results/test/*.xml'` alone reports 438, not 461, and this tripped an
   implementer on an earlier branch. The unqualified `find . -path '*/build/test-results/test/*.xml'`
   above spans both projects; use it, not a root-only variant.

   **A filtered run must now be project-qualified.** Unqualified `./gradlew clean test` deliberately
   spans both projects, but `./gradlew test --tests '<filter>'` applies the filter to *every*
   project and then **fails on whichever project has no match**. Use `./gradlew :test --tests '…'`
   for a root-project test and `./gradlew :platform:platform-primitives:test --tests '…'` for a
   module test. This tripped an implementer on the branch that introduced the split; it will trip
   you too if you copy a `--tests` command out of any pre-2026-08-27 doc.
2. **Read §1** (what this product is) and **§7** (non-negotiable working agreements). For a
   whole-system orientation in one sitting — every module, endpoint, state machine and data flow
   that exists today — read `docs/architecture/2026-07-29-current-architecture.md` instead of
   reconstructing it from §3 and the per-slice specs.
3. **If you are here about the AWS re-platform, microservices, the platform modules, billing,
   the outbox or identity**, read `../architecture/2026-08-27-platform-llds-handoff.md` instead of
   §8 — it is the most recent thread and chains back through
   `../architecture/2026-08-26-platform-modules-handoff.md` to
   `../architecture/2026-08-20-aws-redesign-handoff.md`. All three are design-only (no code was
   written in any of them), and each carries its own decisions, findings and ordering. **All six
   platform modules now have a low-level design.** Those handoffs say no implementation plan exists
   for any of them; as of 2026-08-27 that is half false — **module 1, `platform-primitives`, has a
   plan (`plans/2026-08-27-platform-primitives-module.md`) and is built** (§8). The other five are
   still design only.
   Read that handoff's §3 before planning anything: it records three findings (PF14, PF15, PF19).
   **Two are now closed and that handoff has not been updated to say so** — trust §8 here over it.
   PF14 (RLS `ENABLE`d but never `FORCE`d) and PF15 (no layer-3 guard) were both closed by the
   `rls-force-and-guard` slice; **PF19 is the only one still open**, and it is blocked on the
   billing thread's design, not on effort. See §8 for the current status of each.
4. **Go to §8** and pick the next chunk *with the user*. Do not start one unilaterally.
5. Then run the standard workflow on a feature branch off `main`:
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
17. **`engineering-challenges.md`** — running log of non-obvious problems + solutions (53 entries). Great context on the stack's quirks.
18. **`annotations-reference.md`** — living glossary of every Spring/JPA annotation used.
19. **`specs/2026-07-28-order-lifecycle-design.md`** — order lifecycle design spec (`DISPATCHED`/`CLOSED`/`CANCELLED` transitions + the deferred order-list filter fix). Source of truth for *what* this slice built. **DONE** — spec committed directly as `8a6c9dd`; the slice it describes is implemented and merged as `8247579`.
20. **`plans/2026-07-28-order-lifecycle.md`** — order lifecycle implementation plan. **DONE** — plan committed directly as `8c0703f`; executed in full and merged as `8247579`.
21. **`specs/2026-07-28-quotation-pdf-share-design.md`** — quotation PDF/share design spec
    (server-side rendering, the tenant-resolution seam for a public link, `share_link`'s
    plaintext-token design, the `wa.me` deep link, and the deferred `QuotationService.list`
    filter fix). Source of truth for *what* this slice built. **DONE, merged as `8b6644b`.**
22. **`plans/2026-07-28-quotation-pdf-share.md`** — quotation PDF/share implementation plan (10
    tasks: PDF engine spike, Indian-format money, tenant profile columns, the Thymeleaf template,
    the render endpoint, the `share_link` table, the share endpoint, the public endpoint, the
    list-filter fix, this docs wrap-up). Executed in full, every task reviewed clean.

**Not under `docs/superpowers/`** — whole-system architecture, added 2026-07-29 (no code change):

23. **`../architecture/2026-07-29-current-architecture.md`** — HLD, LLD and data flow for **what
    exists on `main` today**, derived by reading the source at `908d9e6`, not the specs. Module map,
    the four isolation layers, full ER diagram, the complete REST surface, the error contract, all
    three state machines, the GST and numbering algorithms, and six end-to-end data flows. Its
    Part 4 is an explicit inventory of **what is deliberately absent** — read that before planning
    anything, so you don't assume a feature exists.
24. **`../architecture/2026-07-29-target-architecture.md`** — the same three views for the system
    **once every feature on record is built** (P0–P5, import, frontend, and the §8 backlog). Ends
    in a sized gap ledger, today → target. Everything in it is a target; nothing in it is built
    unless it also appears in doc 23.
25. **`../architecture/2026-08-20-aws-redesign-handoff.md`** — handoff for the **AWS re-platform
    design thread** (2026-08-19/20, docs only, no code). Points to four new docs: the five-service
    ECS target architecture, the billing/entitlements spec, the outbox LLD with its test plan and
    bug catalogue, and outbox interview Q&A. **Read it before proposing anything about AWS,
    microservices, messaging or billing** — those decisions are already made and reasoned, and it
    lists what remains unverified. It also records three findings about the code *as it stands*,
    including a live bug: `QuotationVersion` does not snapshot the buyer, so re-rendering a `SENT`
    quotation after a customer edit produces a different document.

26. **`../architecture/2026-08-27-platform-llds-handoff.md`** — handoff for the **platform-module LLD
    thread** (2026-08-26/27, docs only, no code), which closes the six-module queue. The four LLDs it
    produced — `platform-security`, `platform-tenancy`, the revised `platform-outbox`, and
    `platform-entitlement` — sit beside it in `docs/architecture/`, and the parent spec they amend is
    `specs/2026-08-26-shared-platform-modules-design.md`. **Read its §3 before anything else in this
    area**: PF14/PF15 are about tenant isolation as it works on `main` today, not about the future
    split.

**The first module built from that LLD queue** — the only entries below that changed code:

27. **`../architecture/2026-08-26-platform-primitives-lld.md`** — LLD #1 of 6, and the only one that
    is **IMPLEMENTED**. Read its **Appendix B** rather than re-deriving it: it now carries the
    verified answers (the Boot 4 Jackson auto-config artifact, how a `JacksonModule` bean is
    discovered, which of `EventJson`'s pinned settings actually change behaviour today, whether
    ArchUnit can express R1, the `WRITE_NUMBERS_AS_STRINGS` default, and what `api(...)` on
    jackson-databind actually pulls in). Its Appendix A now carries the outcome of MF1–MF6 plus four
    new findings MF7–MF10, and a new Appendix C lists what the implementation added that the design
    did not specify.
28. **`plans/2026-08-27-platform-primitives-module.md`** — the eight-task implementation plan for it
    (**DONE** — see §3). Task 5's brief is the one worth reading even if you never touch this
    module: its mandatory prove-it-can-fail step is the only reason challenge #33 was caught.

**Not a platform-LLD module — a hardening slice off the `rls-force-and-guard` baseline:**

29. **`specs/2026-08-27-public-rate-limiting-design.md`** — per-IP rate limiting design spec
    (Bucket4j token buckets per `(policy, client-IP)`, the public/auth policy set, the
    `RateLimitStore` port, and the deliberate choice to key on socket address rather than
    `X-Forwarded-For`). Source of truth for *what* this slice built.
30. **`plans/2026-08-28-public-rate-limiting.md`** — the seven-task implementation plan for it.
    **Merged to `main` as `d7725b0`** — see §3.
    Worth reading even if you never touch rate limiting: the plan's own code was wrong in five
    separate places that only surfaced during execution (a record that could not bind, a static
    factory colliding with a record accessor, a Jackson 2 import on a Jackson 3 project, a
    `BindResult` overload that does not exist in Boot 4.1, and a test-property precedence rule that
    is the reverse of what the plan assumed). Challenges #38–#42 are the write-ups.
31. **`specs/2026-08-29-record-visibility-design.md`** — record-level visibility design spec (the
    two-tier `assigned_to` rule, `VisibilityPolicy`/`VisibleFinder`, why quotation/order visibility
    derives from the customer rather than adding a column, the deliberately unfiltered dedupe/GSTIN
    lane, and why `SALES_MANAGER` is deliberately collapsed into the unrestricted tier). Source of
    truth for *what* this slice built.
32. **`plans/2026-08-29-record-visibility.md`** — the nine-task implementation plan for it.
    **Merged to `main` as `c81f59f`** — see §3.
33. **`specs/2026-08-30-activity-follow-up-design.md`** — activity log & follow-ups design spec (the
    polymorphic subject link across the four visibility-scoped aggregates, the two-strategy
    visibility gate in §4, why `OVERDUE` is a read-time predicate rather than a status column, the
    three flows, and the deliberate non-implementation of the parent spec's reminder scheduler).
    Source of truth for *what* this slice built. §5.3 was corrected during the slice from the
    single `V29__rls_activity_follow_up.sql` originally described to the four-file split the plan
    actually uses.
34. **`plans/2026-08-30-activity-follow-up.md`** — the fourteen-task implementation plan for it.
    **Merged to `main` as `f97c62c`** — see §0 and §3.
35. **`specs/2026-08-31-quotation-auto-expiry-design.md`** — quotation auto-expiry design spec (the
    `TenantJobRunner` seam for jobs with no JWT, the `TenantContext.runAs`-before-transaction
    ordering, why `asOf` must be computed in IST rather than the server's UTC clock, and the
    audit + activity event pair on expiry). Source of truth for *what* this slice built.
36. **`plans/2026-08-31-quotation-auto-expiry.md`** — the seven-task implementation plan for it.
    **Branch `quotation-auto-expiry` is complete, reviewed, and pending merge** — see §0 and §3.

## 3. Current state

- **Latest code work: quotation auto-expiry** — **branch `quotation-auto-expiry`, complete and
  reviewed, NOT YET MERGED to `main`.** Commits `63a2865`..`7d89963` off `main` at `d7eae98`.
  Seven tasks delivering the codebase's first non-request execution path and a reusable seam for
  every scheduled job after it.

  **`platform/job/TenantJobRunner`** iterates every job-eligible tenant (`TenantStatus.TRIAL` or
  `ACTIVE`, via the new `TenantRepository.findByStatusIn`) and runs a caller-supplied body once
  per tenant, each in its own `PROPAGATION_REQUIRES_NEW` transaction with `TenantContext` bound
  **before** that transaction opens — the ordering that makes a scoped read return real rows
  instead of silently zero (challenge #52). The template is built by the runner itself rather than
  injected, closing two independent traps with one motion: a `@Transactional` method called from
  the runner's own loop would be a self-invocation (proxy bypassed), and Boot's autoconfigured
  `TransactionTemplate` is `PROPAGATION_REQUIRED`, so a caller that already holds a transaction
  would make the per-tenant work silently join it. `TenantJobRunnerTest.eachTenantsBodySeesOnlyItsOwnRows`
  is verified to fail with the ordering inverted; a sibling test pins the `REQUIRES_NEW` half the
  same way. One tenant's failure (including one optimistic-lock retry) never aborts the sweep for
  the rest. The principal bound is synthetic — `(tenantId, null, "SYSTEM")` — the same shape
  `AuthService` uses pre-authentication; `VisibilityPolicy` treats it as unrestricted and
  `AuditLog.actorUserId` is nullable so the null user id records honestly that no human did this.

  **`QuotationExpirySweep`** is the one job built on that seam so far. It reads candidates through
  `VisibleFinder.listQuotations(QuotationSpecifications.expirableAsOf(asOf))` — a correlated
  subquery over the current version's `validUntil`, never a hand-written repository query — calls
  the entity's own `Quotation.expire()` (now guarded by its own `SENT` precondition, matching
  `QuotationService.requireSent`'s message so the API contract is unchanged whether a human or the
  sweep triggers it), and publishes one `QuotationExpiredEvent` per quotation. Two
  `@EventListener`s pick it up exactly like the accept-event pair: `QuotationExpiredAuditListener`
  writes a `QUOTATION_EXPIRED` audit row (synchronous, same transaction, null actor), and
  `QuotationExpiredActivityListener` puts the expiry on the quotation's own timeline as a `SYSTEM`
  `NOTE` activity, so a salesperson sees *why* a quote stopped being live instead of finding a
  status that changed overnight with no explanation.

  **`asOf` is computed in IST, not the server's UTC clock (challenge #53).** `validUntil` is a
  `LocalDate` a user typed while thinking in IST; comparing it against a UTC-derived date gets the
  bug's direction backwards from the obvious guess — a UTC date is never later than the IST one,
  so the naive comparison matches fewer rows and *delays* expiry rather than hastening it.
  `DueWindow.todayDate(Instant)` sits beside the existing IST window arithmetic from the
  activity/follow-up slice (challenge #51) — one home for the zone, not two — and
  `QuotationExpiryJob`'s `@Scheduled(cron = "${easycrm.jobs.quotation-expiry.cron}", zone =
  "Asia/Kolkata")` pins the fire time to 00:30 IST regardless of the deploying server's own
  timezone. The cron is a property, not a constant, specifically so the test suite can disable it
  outright (`"-"`, Spring's `CRON_DISABLED` sentinel) — `QuotationExpiryJobSchedulingTest` asserts
  against the registered task list itself, not just the property value, so a disabled cron that
  somehow still registered a task would be caught.

  **461 tests, 0 failures, 0 errors** — 438 in the root project, 23 in `platform-primitives`, up
  from the 432-test `activity-follow-up` baseline (+29). New challenges #52–#53; annotations
  reference gained `@EnableScheduling` and `@Scheduled`. **What this slice does *not* do:** run
  more than one instance safely without duplicating work (no distributed lock — see the "Before
  any second app instance" note below); batch-load candidate versions (one `findById` per
  candidate — see the deferred-Minor backlog); or touch `QuotationService`, `Quotation`'s other
  transitions, or any existing REST endpoint.

- **Previous code work: activity log and follow-ups** — **merged to `main` as `f97c62c`**; the
  branch is deleted. Commits `212099f`..`48d35a6` off `main` at `830fd47`.
  Fourteen tasks delivering the two aggregates the design spec's §data-model names and nothing else
  (§2's out-of-scope recap: no scheduler, no notification table, no `OVERDUE` column, no attachments,
  no global feed). Both entities live under `com.easycrm.sales`, tenant-scoped and RLS-covered, in
  **four migrations, not one** — `V27__activity.sql`, `V28__rls_activity.sql`, `V29__follow_up.sql`,
  `V30__rls_follow_up.sql` — because the two tables land in different tasks and a single combined RLS
  migration would leave `RlsCoverageIntegrationTest` red between them (the design spec §5.3 originally
  described one file; corrected to the four-file split during the slice).

  **Two visibility strategies for two tables that only look symmetrical (challenge #50).**
  `follow_up` has its own `assigned_to` and joins the guarded set exactly like the four existing
  aggregates — `VisibilityPolicy.followUps()`, `VisibleFinder.findFollowUp`/`pageFollowUps`, and
  `FollowUpRepository` added to `VisibilityScopingArchTest.GUARDED_REPOSITORIES`. `activity` has no
  owner column at all — its visibility is derived, not intrinsic — so it is gated once, at its
  polymorphic subject, via the new `VisibleFinder.requireVisibleSubject(SubjectType, UUID)`, which
  switches over `CUSTOMER`/`ENQUIRY`/`QUOTATION`/`ORDER` onto the four existing `findX` methods and
  throws the house 404 on anything invisible or cross-tenant. That gate is made structural rather
  than promised: `ActivityRepository extends Repository<T, ID>` (the bare marker, not `JpaRepository`)
  with exactly three declared methods, none a by-id-alone lookup, so there is nothing unscoped left
  to inherit — `ActivityRepositoryScopingArchTest` asserts both the forbidden-supertype list (which
  also had to name `QueryByExampleExecutor`/`QuerydslPredicateExecutor`, found in review, not on the
  first pass) and that every declared method takes a subject.

  **A third assertion was added by the final review's fix wave, and it is the one to understand
  before touching this area:** the first two guard the repository's *shape*, but nothing stopped a
  future service from injecting `ActivityRepository` directly and passing a request-supplied
  `subjectId` without ever calling `requireVisibleSubject` — a `SALES_EXEC` could then have read a
  colleague's customer's whole call log with every guard green. `ActivityRepositoryScopingArchTest`
  now also pins an **allowlist of permitted caller classes, currently just `ActivityService`**,
  checking both `repo.method()` calls and `repo::method` references. Adding a name to that
  allowlist is a visibility decision, not a formality.

  **`OVERDUE` is a predicate, computed at read time, not a status column (challenge #51).** The
  parent spec's reminder scheduler is deliberately not built — see §8 for the annotation and the
  standing reason. `DueWindow` computes IST day boundaries as a pure function; `FollowUpScope`'s
  `OVERDUE`/`DUE_TODAY`/`UPCOMING` are disjoint and exhaustive over `PENDING` rows, so the dashboard
  `summary` endpoint's three counts sum to the total by construction.

  **REST surface:** `POST /api/v1/activities` (optional nested `nextFollowUp`, one transaction —
  §6.1), `GET /api/v1/activities` (by subject), `PATCH /api/v1/activities/{id}` (body carries
  `subjectType`/`subjectId`, since no by-id-alone lookup exists to gate on); `POST
  /api/v1/follow-ups`, `GET /api/v1/follow-ups/{id}`, `GET /api/v1/follow-ups` (owner + scope
  filters), `GET /api/v1/follow-ups/summary` (dashboard tile), `PATCH /api/v1/follow-ups/{id}`,
  `POST /api/v1/follow-ups/{id}/complete` (optional activity, same transaction — §6.2), `POST
  /api/v1/follow-ups/{id}/cancel`. A new `QuotationAcceptedActivityListener` (`@EventListener`, same
  synchronous/same-transaction shape as `OrderAcceptedAuditListener`) writes a `SYSTEM` activity on
  quote acceptance — the parent spec's "a new subscriber, not an edit to `QuotationService`" claim,
  now tested by someone other than its author (§6.3): `QuotationService` is untouched.

  **`AssignableUsers` extracted** (`com.easycrm.iam`) from the identical private
  `requireAssignableUser` copies duplicated in `CustomerService` and `EnquiryService` — closing
  deferred-backlog item 40 below — because `FollowUpService` needed a third copy and three was the
  line. **432 tests, 0 failures, 0 errors** — 409 in the root project, 23 in `platform-primitives`,
  up from the 352-test `record-visibility` baseline (+79). New challenges #47–#51; annotations
  reference gained `@JsonInclude` (`@EventListener`/`@Enumerated`/`@Configuration`/
  `@Bean` were already present). **What this slice does *not* do:** any scheduler, notification
  channel, or `OVERDUE` column (§3/§8 of the design spec, challenge #51); any narrowing of
  `SALES_MANAGER`, unchanged from the visibility slice; any change to the four existing aggregates'
  own tables.

- **Before that: intra-tenant record-level visibility filtering** — **merged to `main` as
  `c81f59f`.** Branch `record-visibility`, off `main` at `29b59ca`, now deleted. Nine tasks: `VisibilityPolicy` (role → `Specification`
  per aggregate, `unrestricted()` fail-open for every role but `SALES_EXEC`), `VisibleFinder` (the
  single permitted reader of the four guarded repositories), customer and enquiry reads/writes
  re-pointed at it, quotation/order visibility derived from their customer (a correlated `EXISTS`
  subquery, no new columns), the nested paths (`Contact`, PDF render, share-link mint) gated on
  their parent, `assignedTo` validated to name an `ACTIVE` user, a `VisibilityScopingArchTest`
  allowlist guard that fails the build on a new repository read bypassing the layer (prove-it-can-fail
  verified — see challenge #46), and a docs wrap-up, plus one whole-branch-review fix wave that
  strengthened three tests which passed for the wrong reason. **352 tests, 0 failures, 0 errors** —
  329 in the root project, 23 in `platform-primitives`, up from the 296-test `public-rate-limiting`
  baseline (+56). New challenges #43–#46; the annotations reference needed no new rows (this slice uses JPA
  specifications, not method security — `@PreAuthorize` appears nowhere). **What this slice does
  *not* do:** it does not implement the parent spec §6's three-tier rule — `SALES_MANAGER` is
  collapsed into the unrestricted tier because no team table or manager→report edge exists, and
  narrowing it later is a schema-plus-admin-surface slice of its own. It also does not make
  unassigned records confidential: `assigned_to IS NULL` is visible to every `SALES_EXEC`, the
  standard CRM shared-pool idiom — confidentiality begins at assignment, not at record creation.
  Backlog item #3 (§8) is now fully closed except for user invitations.

  **If you add a new tenant-scoped aggregate, read this.** The visibility layer does *not* extend
  itself, and its guard will not tell you so. `VisibilityScopingArchTest.GUARDED_REPOSITORIES` is a
  hardcoded list of exactly four repository names — a fifth aggregate with an `assigned_to` column
  gets no filtering at all, and every test stays green, because the rule only ever asks about those
  four. This is the opposite of how `TenantScopingArchTest` behaves: that one is keyed on the
  `@Entity` annotation, so a new entity is caught by default and must be *explicitly* allowlisted to
  escape. Extending visibility to a new aggregate means four deliberate edits: a `Specification`
  builder on `VisibilityPolicy`, a `find*`/`page*` pair on `VisibleFinder`, the service's by-id
  choke point re-pointed, and the repository added to `GUARDED_REPOSITORIES`. Miss the last one and
  the other three are the only thing standing between a new aggregate and a silent leak.

- **Earlier still: per-IP rate limiting on the public and auth routes** — **merged to `main`
  as `d7725b0`.** Branch `public-rate-limiting`, commits `bc542c2`..`18eaccd` off `main` at
  `e69d7ac`, now deleted: seven tasks, one whole-branch fix wave (`3bfb99d`), and
  the docs wrap-ups after it. Seven tasks: a `RateLimitPolicy` value type +
  `RateLimitProperties` `@ConfigurationProperties` binding (challenge #38), a `RateLimitStore` port
  with an `InMemoryRateLimitStore` implementation bounded by a Caffeine cache (challenge #39), a
  `RateLimitFilter` returning 429 + `Retry-After` on exhaustion, registering that filter ahead of
  Spring Security so failed-auth traffic is capped too, end-to-end integration tests proving the
  filter ordering, and this docs wrap-up. **296 tests, 0 failures, 0 errors** — 273 in the root
  project, 23 in `platform-primitives`, up from the 264-test `rls-force-and-guard` baseline (+23).
  Delivered: token-bucket limits (Bucket4j) keyed on `(policy name, client IP)` so one policy's
  allowance can't drain another's exhausted client's traffic against a different route (challenge
  #39); buckets keyed on `getRemoteAddr()` only — never `X-Forwarded-For`, which is client-supplied
  and would let any caller mint a fresh bucket per request (challenge #41); the bounded cache itself
  closing the mirror-image risk, an attacker-rotated-IP memory-exhaustion vector (challenge #41);
  and a `@DynamicPropertySource`-vs-`@TestPropertySource` fix so the limiter can default OFF for the
  other 62 integration test classes sharing one cached context while turning ON only for
  `RateLimitIntegrationTest` (challenge #40). New challenges #38–#41; annotations-reference gained
  `@ConfigurationProperties`, `@EnableConfigurationProperties`, `@DefaultValue`, and
  `@TestPropertySource` rows during the branch's own tasks (checked, not re-added, by this
  docs task). **What this slice does *not* do:** it caps request *rate*, not request
  *entitlement* — **PF19 remains open** (§8). **What multi-instance deployment now needs:** the
  store is in-process, so each app instance keeps its own buckets — with N instances behind a load
  balancer the effective limit for any client is N × the configured value, not the configured value.
  The design's Redis-backed `RateLimitStore` implementation is the prerequisite for running more
  than one instance; it does not exist yet.

- **Further back: RLS forced on all fourteen tenant tables, with a layer-3 guard** — **merged
  to `main` as `3c239d1`**. Branch `rls-force-and-guard`, commits `cfc8928`..`b8b2ecb` off `main`
  at `455c237`, closing **PF14 and PF15**. `V26__force_rls.sql` adds
  `FORCE ROW LEVEL SECURITY` to every tenant table (previously all fourteen were `ENABLE`d and
  none forced, so the owner role bypassed every policy silently), and
  `RlsCoverageIntegrationTest` is the guard layer 3 never had: it reads `pg_class` for every
  table carrying a `tenant_id` column and requires RLS enabled, forced and policied, allowlisting
  `refresh_token` and `share_link` in step with `TenantScopingArchTest.GLOBAL_TABLES`. A second
  test creates an un-forced probe table and asserts the guard trips on it — necessary because
  **Testcontainers' owner user is a superuser, so the failure being defended against cannot be
  reproduced behaviourally in this harness at all** (challenge #37). Forcing was behaviourally
  inert: no migration does DML and no test writes as owner. **264 tests, 0 failures, 0 errors.**
  Note this closes the gap *in the schema* — a deployment must still connect as `easycrm_app`;
  forcing removes the silent-failure mode, not the requirement.

- **Further back still: `platform-primitives` extracted into its own Gradle module** — **merged to
  `main` as `210545e`**. Branch `platform-primitives-module`, eight tasks, commits
  `4d43d75`..`6c255d4` off `main` at `ac4eaca`. Every task reviewed clean (Tasks 4 and 5 each took
  one fix round; Task 7 returned zero findings at any severity), and the whole-branch review found
  one more: the MF1 fix had made seller GSTIN *validation* symmetric with the buyer path but not
  *normalisation*, so a lowercase GSTIN was stored lowercase and printed that way on every PDF
  letterhead. Fixed in `6c255d4`. **262 tests, 0 failures, 0 errors** from a clean build — 239 in
  the root project, 23 in the new module — up from the 231-test PDF/share baseline (+31).

  **What it delivered.** `backend/platform/platform-primitives`, a plain `java-library` jar with
  **no runtime Spring dependency at all** (Spring is `compileOnly`), holding every zero-dependency
  primitive: the five exception *types* moved down out of `platform-web` (the handler stayed),
  `BigDecimalStringModule`, `Gstin`, `StateCode`, and a new `EventJson`. Specifically:
  - **The build is now two Gradle projects.** `settings.gradle.kts` includes
    `:platform:platform-primitives`; the root project takes it as `implementation(project(...))`.
    Ten files moved as pure renames, 0 lines changed. The Boot BOM is the single version source
    in both projects; the Spring Boot Gradle plugin is deliberately *not* applied to the module.
  - **`MoneyJacksonConfig` → `MoneyAutoConfiguration`**, registered through
    `AutoConfiguration.imports` rather than component scan, so the money wire format reaches a
    future `sales-svc` that scans from `com.easycrm.sales` and never sees `com.easycrm.platform`.
    `MoneyModuleWiringTest` proves the `.imports` file was actually read, not merely that the bean
    exists — the two are not the same thing (challenge #35).
  - **`EventJson`** — a second, separately-built `JsonMapper` for anything persisted or published
    (outbox JSONB, SNS, SQS), deliberately **not** the application `ObjectMapper`, so that slimming
    an API response with `spring.jackson.default-property-inclusion=non_null` cannot silently start
    dropping null fields from every event (challenge #32). Its settings are pinned explicitly even
    where they match today's Jackson 3 defaults; the LLD's Appendix B item 3 says exactly which is
    which, and the source says why they stay.
  - **Two ArchUnit rules.** R1: nothing outside the module may construct a JSON mapper. R2: the
    module may depend on nothing but `java..`, Jackson, its own packages, and `org.springframework..`
    wholesale (the last because `MoneyAutoConfiguration` legitimately needs it, and the rule covers
    every class in the module) — written as an allowlist closure, not an enumeration. The separate
    `carriesNoRuntimeSpringDependency` test is what actually confines Spring usage to the
    auto-configuration.
  - **Two live-bug fixes on the way through.** `Gstin.parse` now validates the state prefix, not
    just the checksum; and `AuthService.signup` now validates the *seller's* GSTIN and state code,
    which it never did — an invalid seller state code silently decides CGST+SGST vs IGST on every
    quotation that tenant ever issues (challenge #34, LLD finding MF1).
  - **New challenges #32–#36**; new annotations-reference rows for `@AutoConfiguration` and
    `@ConditionalOnClass`.

  **Two things not to overstate.** `EventJsonDivergenceTest` found **no** divergence between the
  application mapper and `EventJson` today — it is a tripwire for the future, most likely to fire
  the day someone sets `spring.jackson.default-property-inclusion=non_null`, and it has caught
  nothing. And this is **one module of six**: see §8 for why that does not make the module queue the
  next thing to do.
- **Prior branch:** `main` at `8b6644b` (quotation PDF/share; feature branch and its worktree
  deleted). All 10 tasks were reviewed clean, as was the whole-branch review.
- **Design-only work between them:** 9 commits (`15e9818`…`5d6bfd7`) adding the AWS re-platform
  design set. **Zero code, zero migrations, zero test impact — the 231-test baseline was untouched
  at that point.** See §2 item 25.
- **`docs/architecture/` is now fully tracked.** The four docs left uncommitted since 2026-07-29 —
  the as-built and target architectures (§2 items 23–24) plus two interview briefs — were reviewed
  and committed on 2026-08-27 as `a76e563` and `e9bbd81`. Docs only; the then-current 231-test
  baseline was untouched. **One caveat travels with them:**
  `2026-08-05-interview-challenges-and-aws-kafka.md` walks the system as it would look with Kafka on
  Amazon MSK, and the later AWS design went the other way — D3 chose a transactional outbox into SNS
  FIFO and SQS FIFO, D4 rejected Kinesis and DMS CDC outright at this event rate. Keep it as
  interview prep; do not read it as a plan.
- **The two long-uncommitted tracked files are gone from the outstanding list.**
  `docs/architecture/2026-08-19-aws-target-architecture-design.md` and
  `docs/superpowers/engineering-challenges.md` (challenge #31, head-vs-tail sampling) — flagged as
  uncommitted by three successive handoffs — were reviewed and committed as `ac4eaca`, immediately
  before the `platform-primitives` branch was cut from it. Nothing is uncommitted now.
- **Merged & done on `main`:** the design docs (including the order-lifecycle slice's
  `specs/2026-07-28-order-lifecycle-design.md` `8a6c9dd` and `plans/2026-07-28-order-lifecycle.md`
  `8c0703f`, both committed directly) + **P0 tenant-isolation foundation** + **P0-auth core** +
  **P1a master data** (merge commit `2f9a2f4`) + **P1b quotation engine** (merge commit `43e9642`)
  + **order + accept** (merge commit `ea11d3f`) + **enquiry** (merge commit `a68035d`) +
  **enquiry→quotation conversion** (merge commit `06e6014`) + **sales hardening** (merge commit
  `abc2bd3`) + **order lifecycle** (merge commit `8247579`).
- **Last feature slice merged to `main`: quotation PDF + `wa.me` share** (merge commit `8b6644b`). **231 tests was the baseline at that time; it is 262 now** — see the `platform-primitives` bullet above. 10 tasks
  (PDF engine spike + determinism, Indian digit-grouping money
  formatting, seller-profile columns on `Tenant`, a Thymeleaf quotation template, the
  authenticated PDF render endpoint, the global `share_link` table, the idempotent share
  endpoint + `wa.me` deep link, the public no-auth render endpoint, the `QuotationService.list`
  filter fix, and this docs wrap-up), every task reviewed clean (one adversarial, execution-based
  review on the public endpoint). Delivered: server-side quotation rendering (Thymeleaf →
  openhtmltopdf, byte-identical across renders of the same frozen version — challenge #28);
  `Tenant.address/phone/email` for the letterhead; `GET /api/v1/quotations/{id}/pdf?version=<n>`
  (JWT-gated, defaults to the latest SENT version); a global, RLS-exempt `share_link` table
  mapping a plaintext token to `(tenant_id, quotation_version_id)` — deliberately unhashed, unlike
  `refresh_token`, so `POST /api/v1/quotations/{id}/share` is genuinely idempotent (see the design
  spec §4 for the full blast-radius reasoning); `GET /public/q/{token}` serving the PDF with **no**
  JWT at all, `TenantContext.runAs` installing the resolved tenant before the rendering
  transaction opens (challenges #29–#30); and the `QuotationService.list` two-filter fix
  (challenge #24's pattern, closing backlog item #1 below). **231 tests passing** from a clean
  build, up from the 187 order-lifecycle baseline (+44).
- **Prior: order lifecycle** — merged to `main` as `8247579` (5 code/test tasks + a docs wrap-up + one final-review fix wave, each review clean). Delivered: the four-state guarded machine `CONFIRMED → DISPATCHED → CLOSED` (terminal) with `cancel()` legal from either active state, all three transitions guarded entity-side (`Order.dispatch()`/`close()`/`cancel(reason)`, each naming its own precondition rather than coupling to enum ordinal order); a required, non-blank `cancelReason` (`VARCHAR(500)`, migration `V23__order_cancel_reason.sql`); `POST /api/v1/orders/{id}/dispatch|close|cancel` (422 on an illegal transition, 400 on a blank cancel reason, 404 cross-tenant); a generic `OrderStatusChangedEvent` + synchronous same-transaction `OrderStatusChangedAuditListener` writing `ORDER_DISPATCHED`/`ORDER_CLOSED`/`ORDER_CANCELLED` audit rows; `OrderSpecifications.filter(status, customerId)` AND-composing both list filters (`OrderRepository` now extends `JpaSpecificationExecutor<Order>`), closing the challenge #24 dropped-filter bug for orders; and a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED`, instead of silently handing back a dead order with 200 (challenge #27). The final whole-branch review added audit-detail-payload assertions (proving `from` carries the *pre*-transition status and `cancelReason` appears only on the cancel row) and cross-tenant coverage on the new `Specification` query path. **187 tests passing** from a clean build, up from the 166 sales-hardening baseline (+21).
- **Prior latest merged: sales hardening** — 2 code/test tasks + docs closing the two Minors deferred from the conversion review. **166 tests passing** from a clean build (`cd backend && ./gradlew clean test`), up from the 162 conversion baseline (+4). Delivered: (1) a global `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 so a lost-update race (concurrent `accept`/convert-at-create) returns 409 not 500 — a sibling of the challenge #15 `DataIntegrityViolation` backstop on the disjoint concurrency subtree; (2) `UNIQUE(tenant_id, enquiry_id)` on `quotation` (migration `V22` + entity `@Table`; NULLs distinct so enquiry-less quotes coexist) making one-quote-per-enquiry structural, a guard-bypassed/raced second insert now routing through the challenge #15 handler → 409. Both proven deterministically (no threads): a handler unit test, a single-threaded stale-write repo test, and repo constraint tests. Challenge #26 logged; challenge #25's 500-gap note updated to "closed".
- **Enquiry→quotation conversion** (prior): 2 code/test tasks + docs, merged as `06e6014` (162 tests). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when raised with an `enquiryId`, atomically. Challenge #25.
- **Enquiry slice** (prior): 7 tasks + a post-review re-enquiry test + PATCH-contract docs, merged as `a68035d` (154 tests).
- **Enquiry scope:** the wedge's *head* (lead capture) — 7 tasks (phone normalizer, the `Enquiry` aggregate + guarded 5-stage lifecycle, migration/RLS/partial-index dedupe, create endpoint, get + filtered list via a JPA `Specification`, edit/advance/lose transitions, and this challenges/annotations/handoff wrap-up).
- **What enquiry delivered:** the `Enquiry` aggregate (table `enquiry` — not reserved; tenant-scoped, RLS-covered) carrying nullable `customerId` (walk-ins), raw contact fields (`contactName`, `contactPhone` + derived `normalizedPhone`, `contactEmail`), `source` (own `EnquirySource` enum — same six values as `crm.CustomerSource`, kept separate so `sales` stays decoupled), `requirementText`, `assignedTo`, `stage` (`EnquiryStage`), optional `expectedValue` (money, JSON-string wire), and `lostReason`. **5-stage guarded lifecycle** `NEW → CONTACTED → QUALIFIED → CONVERTED / LOST`: guards live in the entity (mirroring `Quotation`'s transition methods), `advanceTo` allows only a later *active* stage (skips ok, no backward/terminal-target), `lose` requires a reason, `markConverted()` was **reserved for the later conversion slice** at the time — it is now reached by `QuotationService.create()` when a quote is raised with an `enquiryId` (merged `06e6014`), so a phone is freed for re-enquiry via either `CONVERTED` or `LOST`. **Dedupe = one active enquiry per phone**, enforced structurally by a Postgres **partial unique index** `UNIQUE(tenant_id, normalized_phone) WHERE stage NOT IN ('CONVERTED','LOST')` plus an app-level active-only pre-check (→409) with the challenge #15 `DataIntegrityViolation`→409 backstop (challenge #23). **List** uses a JPA `Specification` that AND-composes any subset of `?stage=&assignedTo=&source=` — deliberately avoiding the "drops a filter when two are supplied" bug (challenge #24) that the order list had at the time, and which was fixed for orders in `8247579` and for quotations in `8b6644b` — all three list endpoints now AND-compose their filters. REST: `POST` (201), `GET /{id}` (cross-tenant 404), `GET` (filtered, offset `PageResponse`, cross-tenant empty), `PATCH /{id}` (active-only edit, re-dedupes on phone change), `POST /{id}/advance`, `POST /{id}/lose`. Lives under `com.easycrm.sales` (+ `.web`, `.web.dto`). Challenges #23–#24; no new annotations (`JpaSpecificationExecutor`/`Specification` noted in the annotations reference as concepts, not annotations).
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

**The quotation PDF/share slice is DONE and merged to `main` (`8b6644b`).** Every test count in this subsection is the count *at that time*; the current baseline is 262. 10
tasks, every review clean: (1) a PDF-engine spike proving openhtmltopdf 1.0.10 renders on JDK 25
and can be made byte-deterministic (challenge #28 — a PDFBox writer branch silently ignores
`setDocumentId()` when the trailer already carries an inherited `/ID`); (2) Indian digit-grouping
money formatting (`java.text.DecimalFormat` cannot do it — single `groupingSize`, hand-rolled
instead); (3) `Tenant.address/phone/email` for the letterhead; (4) the Thymeleaf quotation
template (CGST+SGST xor IGST, base-14 fonts, no ₹ glyph); (5) `GET
/api/v1/quotations/{id}/pdf?version=<n>`; (6) the global `share_link` table; (7) `POST
/api/v1/quotations/{id}/share` + the `wa.me` deep link (RFC 3986 space encoding, a deterministic
primary-contact tie-break); (8) `GET /public/q/{token}` — no auth, tenant resolved from the
global table and installed via `TenantContext.runAs` before the rendering transaction opens
(challenges #29–#30); (9) the `QuotationService.list` two-filter fix (closes backlog item #1
below); (10) this docs wrap-up. **231 tests passing** from a clean build, up from the 187
order-lifecycle baseline (+44). See the §3 bullet above for the fuller feature list and the
design spec for the tenant-resolution seam and the plaintext-token reasoning.

**The order-lifecycle slice is DONE and merged to `main` (`8247579`).** 5 code/test tasks plus this docs wrap-up landed and reviewed clean: (1) `OrderStatus` widened to `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with `isTerminal()`/`isActive()` and entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions, plus a required non-blank `cancelReason` (migration `V23`); (2) `POST /api/v1/orders/{id}/dispatch|close|cancel`, with `OrderResponse` gaining `cancelReason` as its 7th component; (3) a generic `OrderStatusChangedEvent` + `OrderStatusChangedAuditListener` writing the three new audit action rows; (4) `OrderSpecifications.filter` closing the challenge #24 dropped-filter bug for orders; (5) a 422 on `QuotationService.accept`'s idempotent branch when the existing order is `CANCELLED` (challenge #27). The whole-branch review then added audit-detail and cross-tenant assertions. Clean-build total was **187 tests** at the time, up from the 166 sales-hardening baseline.

**Prior:** the sales-hardening slice is DONE and merged to `main` (`abc2bd3`). 3 tasks landed and reviewed (optimistic-lock→409 handler + tests; `UNIQUE(tenant_id, enquiry_id)` migration/entity + tests; docs), each task-review clean, and the whole-branch review returned READY TO MERGE with no Critical/Important findings. It closes the two Minors that the enquiry→quotation conversion whole-branch review consciously deferred (both now struck from the deferred list below).

**Deferred out of enquiry scope** (explicit — do not assume any of this exists):
- ~~**Enquiry → quotation conversion wiring**~~ — **DONE, merged** (`06e6014`). `QuotationService.create()` flips the enquiry to `CONVERTED` and stamps `quotation.enquiry_id` when a quote is raised with an `enquiryId`. Note: still convert-*at-create* only; no standalone `/enquiries/{id}/convert` endpoint, and one enquiry maps to at most one quotation (a second create against a converted enquiry → 422).
- **`activity` / `follow_up` entities** — the spec's Activity section (CALL/WHATSAPP/EMAIL/VISIT/NOTE logs + first-class follow-up reminders) is still unbuilt.
- ~~**Order status transitions beyond `CONFIRMED`**~~ — **DONE, merged** (`8247579`). `OrderStatus` now has `CONFIRMED, DISPATCHED, CLOSED, CANCELLED` with entity-side guarded `dispatch()`/`close()`/`cancel(reason)` transitions and a required `cancelReason` — see the order-lifecycle summary above. Challenge #27.
- ~~**PDF generation** and the **`wa.me` WhatsApp share link**~~ — **DONE, merged** (`8b6644b`). Server-side quotation PDF rendering, a public tokenized share link, and the `wa.me` deep link all exist — see the §3/§4 summaries above. **Order PDF is still out of scope** (design spec §8 — the quotation is the document customers actually ask for at this stage), as are link expiry/revoke and rate limiting on the public route (see the backlog below).
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
- **Run tests:** `cd backend && ./gradlew test` (or `clean test` for a full run). Integration tests spin up one shared Postgres container (singleton pattern) — 296 tests run in ~13s once the image is cached (it was ~4s before the PDF slice; rendering real PDFs is the difference).
- **The build is two Gradle projects** since 2026-08-27: `backend` (root) and
  `backend/platform/platform-primitives`. Unqualified `./gradlew clean test` spans both and is what
  every "expect N tests" claim in this document means; Gradle prints no combined total, so count it
  with the `find`/`awk` snippet in §0 item 1. **A `--tests` filter must be project-qualified**
  (`./gradlew :test --tests '…'` or `./gradlew :platform:platform-primitives:test --tests '…'`) —
  unqualified, Gradle applies the filter to both projects and fails on the one with no match.
- **Sandbox note:** in this harness, network + Docker operations may need the Bash tool's sandbox disabled (`dangerouslyDisableSandbox: true`). SDKMAN's reachability check is blocked by the sandbox even when network works.

## 6. Stack quirks already discovered (see challenges log for detail)

This is **Spring Boot 4.1 + Java 25 + Hibernate 7** — all recent. Watch for:
- **Spring Boot 4 split auto-config into per-integration modules.** `flyway-core` alone doesn't bring `FlywayAutoConfiguration` → use `spring-boot-starter-flyway`. `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure` (module `spring-boot-webmvc-test`). `HibernatePropertiesCustomizer` moved to `org.springframework.boot.hibernate.autoconfigure`. Jackson's auto-configuration is `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration` in artifact `spring-boot-jackson` (transitive via `spring-boot-starter-web`); `spring-boot-autoconfigure` remains the right `compileOnly` coordinate for `@AutoConfiguration`/`@ConditionalOnClass`. **If an import "does not exist," search the resolved jars for the class's new package** rather than assuming the plan is wrong.
- **ArchUnit 1.4.1** (not 1.3.0) — 1.3.0 silently skips Java 25 bytecode. Two further traps, both hit in one afternoon and both producing a rule that passes while checking nothing: (a) **`noClasses().should(customCondition)` inverts every event the condition emits**, so a hand-rolled condition must emit `SimpleConditionEvent.satisfied(...)` for the case it forbids — challenge #33; (b) **`importPackages("com.easycrm")` now spans two build outputs**, since the `platform-primitives` jar shares the prefix, so an `isNotEmpty()` vacuity guard no longer proves the root project's own bytecode was read — challenge #36. **Never add an ArchUnit rule without deliberately introducing a violation and watching it fail.**
- **Jackson 3 moved the date/timestamp serialization switches.** `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` and `WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` **do not exist**; they are now on `tools.jackson.databind.cfg.DateTimeFeature` (a `DatatypeFeature`), reached via `disable(DatatypeFeature...)`. `JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS` is **off** by default (verified directly against `jackson-core-3.1.4`), whatever secondary sources claim.
- **Testcontainers BOM pinned to 1.21.3** (Boot 4 BOM doesn't manage those versions).
- **`BindResult<T>.orElseThrow()` lost its zero-arg overload.** Only `orElseThrow(Supplier<? extends X>)` exists in Boot 4.1 — call `.get()` instead when you just want the bound value or a `NoSuchElementException` if binding failed (`RateLimitDefaultsTest`).
- **RLS + custom GUC:** a referenced custom GUC resets to `''` not NULL, so policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`. An RLS `USING` clause also acts as `WITH CHECK` for inserts.
- **Two DB roles:** Flyway runs as the **owner** (Testcontainers superuser); the app connects as **`easycrm_app`** (non-owner, no BYPASSRLS) — this is what makes RLS real. `IntegrationTest` wires both datasources.
- **`ddl-auto: validate`** is on — migration column types must match entity mappings exactly (e.g. `VARCHAR` not `CHAR` for a `String`).
- **`spring.jpa.open-in-view: false` is load-bearing, not a preference.** `GET /public/q/{token}` resolves its tenant from the `share_link` row and installs it with `TenantContext.runAs` **before** the rendering transaction opens. With OSIV enabled, the `EntityManager` opens in an interceptor *before* the controller runs, so Hibernate would pin the wrong tenant at session-open (challenge #9's rule) and the public endpoint would silently read under no tenant. **Nothing in the build would catch it** — no test fails, no exception is thrown. Do not flip this flag. See challenge #29.

## 7. Working agreements (also in CLAUDE.md — enforced)

- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already set). Plain `git commit`. **Never** add a `Co-Authored-By: Claude` trailer or mention Claude/AI in commit messages.
- **Log engineering challenges:** when a task surfaces a non-obvious problem, append to `engineering-challenges.md` (Problem → why hard → Solution → Lesson) in the same change.
- **Keep the annotations reference current:** add a row when a new annotation appears.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit. One task per commit.
- **Money is never `double`** (BigDecimal / NUMERIC / JSON string). P1a got the Java/Postgres side right (`NUMERIC`, `compareTo` not `equals`) but still shipped `BigDecimal` fields on the wire as plain JSON numbers; **P1b closed that gap globally** with `platform.money.BigDecimalStringModule` (challenge #17) — every `BigDecimal`, including P1a's already-shipped fields, now serializes as a JSON string. **Since 2026-08-27** that class lives in the `platform-primitives` Gradle module (same package, `com.easycrm.platform.money`) and is registered by `MoneyAutoConfiguration` through `AutoConfiguration.imports`, not component scan. **The event wire is a separate mapper on purpose:** use `EventJson.mapper()` for anything persisted or published, and inject Boot's `ObjectMapper` for HTTP — an ArchUnit rule fails the build if you construct your own anywhere else (challenge #32).
- **Tenant isolation is structural:** never hand-write `WHERE tenant_id`; rely on `@TenantId` + RLS; new entities extend `TenantScopedEntity` or get allowlisted (ArchUnit enforces).

## 8. The next chunk — pick one with the user

The wedge (**enquiry → quotation → order**) is functionally complete end-to-end and hardened,
including the order aggregate's own lifecycle, and quotations can now be rendered as a PDF and
shared over WhatsApp. All four candidates below are scoped in the design spec
(`specs/2026-07-22-easycrm-design.md`). Present them, take the user's choice, and only then start
the workflow from §0 step 4.

1. ~~**`activity` / `follow_up` entities**~~ — **DONE**, merged as `f97c62c` (§0, §3). CALL/WHATSAPP/EMAIL/VISIT/NOTE logs against any of the four visibility-scoped
   aggregates, log-and-schedule in one transaction, complete/cancel/reschedule, and a `SYSTEM`
   activity on quote acceptance via the accept event seam — exactly as this item described. **The
   parent spec's `follow_up` data-model clause "first-class, with its own reminder scheduler" is
   deliberately not implemented** — see the design spec §3 for the standing reason (no channel to
   push into: no WhatsApp Business API, email has no delivery-tracking/dedupe design, no frontend
   for in-app), and challenge #51 for why the eventual fix is additive, not a redesign. Record this
   as a decision, not an oversight, if it's ever asked why no scheduler exists.
2. ~~**Scheduled auto-expiry**~~ — **DONE**, on branch `quotation-auto-expiry` (§0, §3), pending
   merge. A nightly job at 00:30 IST expires every lapsed `SENT` quotation, with an audit row and a
   timeline activity — exactly as this item described. It also landed the codebase's first
   non-request execution path and a reusable `TenantJobRunner` seam: every future scheduled job
   should build on that runner rather than growing its own `TenantContext.runAs`-before-transaction
   loop (challenge #52 is why getting that ordering wrong is silent rather than loud). The IST
   day-boundary trap this raised for comparing a `LocalDate` column against the server's UTC clock
   is challenge #53.
3. **P0-auth follow-up** — **fully closed except for user invitations.** This item started as three
   things: rate limiting, record-level visibility, and user invitations. Rate limiting landed in the
   `public-rate-limiting` slice (`d7725b0`; §3): `/public/q/{token}` and the auth routes are capped
   per-IP with a 429 + `Retry-After` contract — that closed the *abuse-of-rate* half of PF19 and
   **not** the *entitlement-metering* half, which stays open below. Record-level visibility landed in
   the `record-visibility` slice (merged as `c81f59f`; §3): every read and
   write on `Customer`, `Enquiry`, `Quotation`, and `Order` is now filtered by `assigned_to` through
   a single `VisibleFinder`, guarded by `VisibilityScopingArchTest`. **User invitations are now the
   sole remaining P0-auth follow-up.**
   Note what visibility filtering did *not* ship, in §3's slice detail: `SALES_MANAGER` is collapsed
   into the unrestricted tier, so the parent spec §6's three-tier rule is not yet built.
4. **Cursor pagination** — quotation/order/enquiry lists are all offset-based `Pageable`/
   `PageResponse`; large tenants will need cursor pagination. Cross-cutting, lower urgency.

**A fifth candidate now exists, and it is deliberately not in the numbered list above:
continuing the platform-module track.** `platform-primitives` (LLD #1) is built; the next module by
**dependency order** is `platform-web` (LLD #2,
`docs/architecture/2026-08-26-platform-web-lld.md`), which now depends on `platform-primitives` for
the exception types that sank into it. Dependency order is not priority order, and nothing about
having built one module makes the queue urgent — read the next paragraph before proposing it.

**What still outranks the module queue.** The platform-LLD thread's own handoff
(`../architecture/2026-08-27-platform-llds-handoff.md` §3) records three findings that describe
code running **today**, not the future split. **Two of the three are now closed** — the
`rls-force-and-guard` slice took PF14 and PF15 (§3); PF19 remains open:

- ~~**PF14** — RLS `ENABLE`d on all fourteen tables and `FORCE`d on none.~~ **DONE** —
  `V26__force_rls.sql` forces the fourteen that existed then. **There are sixteen now**
  (`activity` and `follow_up` arrived later): a new tenant table does NOT get retrofitted into
  `V26`, it ships `ENABLE` + `FORCE` + its own `tenant_isolation` policy in its own migration,
  as `V28__rls_activity.sql` and `V30__rls_follow_up.sql` do. `RlsCoverageIntegrationTest` keys
  on the `tenant_id` column, so forgetting this fails the build rather than leaking quietly. Read the caveat in §3: this removes the *silent*
  failure mode, but a deployment still has to connect as `easycrm_app` for layers 3 to do
  anything; forcing means a wrong role now fails loudly instead of leaking quietly.
- ~~**PF15** — ArchUnit guards layer 2; nothing guards layer 3.~~ **DONE** —
  `RlsCoverageIntegrationTest` is the layer-3 twin, keyed on the `tenant_id` **column** rather
  than the `@TenantId` annotation (which is what makes it a real second layer and not a re-read of
  the first). Its allowlist must be extended in step with `TenantScopingArchTest.GLOBAL_TABLES`.
  One residual gap, noted rather than fixed: the guard keys on a column *named* `tenant_id`, so a
  tenant table naming it something else would slip past. Nothing in the repo does that today.
- **PF19** — the entitlement metric set does not respect the create/read boundary, and
  `/public/q/{token}` renders a PDF with no JWT, so there is structurally nowhere to put an
  entitlement check on the app's most expensive uncapped operation. **PF19 REMAINS OPEN** even
  after `public-rate-limiting`: that slice caps how *often* the route can be hit, which closes the
  abuse-of-rate reading of this finding, but it adds no entitlement metering at all — there is
  still nowhere in the request path that knows or charges *whose* quota a render came out of. Do
  not infer PF19 is finished from the rate-limiting slice landing; it addresses a different half of
  the same finding.

**PF19 was also a second, independent argument for #3's rate-limiting half** — arriving from
billing/COGS rather than from security — and that half is now done (§3). The entitlement-metering
half PF19 is actually about is still unstarted.

**Suggested default — read this before proposing anything.** Six slices in a row have now been
either hardening or moving the product (RLS forcing, rate limiting, record-level visibility,
activity/follow-up, then scheduled auto-expiry). **Backlog items #1 and #2 are now both done** —
see §3's `activity-follow-up` and `quotation-auto-expiry` entries — so the honest ranking has
changed again:

- **#3 (user invitations) is now the largest genuinely-open product item, and the strongest
  remaining claim.** It is the sole surviving piece of the P0-auth follow-up, and unlike PF19 it is
  blocked on nothing — no design thread, no missing channel. With #1 and #2 both closed, nothing
  else on the board is a bigger open piece of product.
- **#4 (cursor pagination)** — cross-cutting, lower urgency, unchanged by this slice.
- **PF19's entitlement-metering half stays blocked on design, not effort.** The public route has no
  JWT, so there is nowhere to hang a per-tenant check; it needs the billing thread's decisions before
  code, same as before this slice.
- **`platform-web` stays the weakest claim.** Next by dependency order only; unchanged by this
  slice.

A reasonable reading is: **#3 by default now that #1, #2, and the correctness backlog are all
empty.** Confirm with the user rather than assuming.

**Before any second app instance:** the rate limiter's store is in-process (§3) — running N
instances behind a load balancer multiplies every configured limit by N, silently. Build the
design's Redis-backed `RateLimitStore` implementation before multi-instance deployment, not after;
today it does not exist. **The nightly quotation-expiry sweep has the same shape of problem, one
notch sharper.** `TenantJobRunner`/`QuotationExpiryJob` take no distributed lock, so with N app
instances all N run the cron at 00:30 IST and all N sweep every tenant. It cannot double-*write* —
the first writer's `Quotation.expire()` flips the row, `@Version` optimistic locking makes every
later instance's write attempt on the same row fail, and `TenantJobRunner`'s bounded one-retry
finds nothing left to expire the second time — but it does duplicate the *work*: N redundant reads
of the candidate set, N audit rows attempted (N-1 of them losing the race), N event-publish
attempts. This sits alongside the rate limiter's in-process store as the second thing that needs a
real fix (a leader-election lock, or a `SELECT ... FOR UPDATE SKIP LOCKED` claim step) before a
second instance runs, though it fails safe rather than silently, which the rate limiter's version
of this problem does not.

**Before the first large tenant:** the visibility predicate `assigned_to = :me OR assigned_to IS NULL`
has **no index behind it** on either `customer` or `enquiry` — it is a sequential scan within the
tenant partition, and today *every* row matches it because nothing has ever written the column. This
is fine at current volumes and is the one performance consequence the visibility design does not
otherwise call out. It sits alongside item 15 below (no index supports a status-only order-list
filter) as the second thing to look at when a tenant's tables grow.

**The activity/follow-up slice deliberately did not repeat that mistake**, which is the pattern to
copy: `follow_up` shipped `(tenant_id, assigned_to, status, due_at)` in its creating migration
because that is the dashboard's every-login query, and `activity` shipped
`(tenant_id, subject_type, subject_id, occurred_at DESC)` to cover the timeline read. Adding the
index when the table is created costs one line; retrofitting it costs a migration on a live table.

### Smaller deferred-Minor backlog

Open and non-blocking. This list is the complete record of every `minor (deferred)` line the SDD
ledgers of **six** slices accumulated — items 1–22 from the quotation PDF/share slice (ten tasks),
items 23–24 from the `platform-primitives` slice (eight tasks), items 25–32 from
`public-rate-limiting` (seven tasks), items 33–41 from `record-visibility` (nine tasks plus a
whole-branch fix wave), items 42–46 from `activity-follow-up` (fourteen tasks), and item 47 from
`quotation-auto-expiry` (seven tasks) — each cross-checked line-by-line against its ledger before
that workspace was deleted at merge (or, for `activity-follow-up` and `quotation-auto-expiry`,
whose workspaces are deleted or about to be — this list is the durable copy). So it really is
**self-contained**:
don't go looking for an SDD ledger to corroborate it, there won't be one. Roughly highest-value
first *within* each slice's block; 23–24 are not lower-value than 22, they are just newer.

1. ~~**`QuotationService.list` has the dropped-filter bug**~~ — **DONE.** Closed by the quotation
   PDF/share slice's Task 9: `QuotationSpecifications.filter` mirrors `OrderSpecifications`,
   `QuotationRepository` now extends `JpaSpecificationExecutor<Quotation>`, and a two-filter
   regression test (`?status=` + `?customerId=` together) guards it.
2. **Non-Latin script silently renders as `#` in the quotation PDF — the most user-visible
   limitation this slice ships with.** Base-14 Helvetica (the template's font) covers WinAnsi
   (Latin-1) only. Confirmed empirically through the real `PdfEngine`: Devanagari text came back
   as `Shri Ram #### Traders`. A `businessName`, `billingAddress` or product name in Devanagari,
   Gujarati, Tamil or any other Indian script is entirely ordinary for this product's actual
   customers, and the result is a corrupted document sent over WhatsApp with no exception, no log
   line, and no test failure — the substitution is silent. See design spec §2 for the full
   reasoning. Fix: embed a Unicode-capable font (Noto Sans or DejaVu Sans, subset) — the
   jar-weight/font-licensing trade-off §2 already declined once for the `₹` glyph alone, now with
   its real cost visible.
3. ~~**No rate limiting on `/public/q/{token}`**~~ — **DONE.** Closed by the `public-rate-limiting`
   slice (§3, merged as `d7725b0`): a per-IP Bucket4j token bucket in front of `/public/q/{token}` and the
   auth routes, 429 + `Retry-After` on exhaustion. **This closes only the rate half of PF19, not the
   entitlement-metering half** — PF19 stays open above. It also does not yet support more than one
   app instance: the store is in-process, so N instances multiply the effective limit by N until the
   Redis-backed `RateLimitStore` is built.
4. **No expiry or revoke on a share link, and no way to invalidate one by any means today.** A
   link minted once renders forever. Resharing the same version does **not** replace anything —
   `ShareLinkService.share()` returns the version's existing stored token (that is the point of
   the plaintext-idempotency design in the design spec §4), and there is no delete path anywhere
   in the codebase. The `share_link` row is exactly where expiry/revoke columns belong when this
   is prioritized — see the design spec §4/§8 for the reasoning already on record.
5. ~~**`Totals.totalTax` is carried in the quotation PDF's view model but rendered nowhere in
   `quotation.xhtml`.**~~ **DONE.** A "Total tax" row now sits between the tax rows and Grand
   total, matching the design spec §2 template contract (`subTotal`, `totalTax`, `grandTotal`).
6. **Cancelling an enquiry-linked order has no path back to that enquiry** (challenge #27). The
   422 message says "raise a new quotation", which only fully works for enquiry-less quotations:
   `Enquiry.requireActive()` rejects a second `markConverted()` and `UNIQUE(tenant_id,
   enquiry_id)` blocks a second quotation, so the replacement must go in with `enquiryId: null`,
   silently severing lead traceability. Re-opening the enquiry on cancel, or relaxing
   one-quote-per-enquiry, is an **open design decision, not a bug** — decide it deliberately.
7. **PDF rendering runs inside `@Transactional(readOnly = true)`** (`QuotationPdfService`),
   holding a database connection open for the duration of CPU-bound render work. Fine at today's
   volumes; if rendering gets heavier, consider fetching inside the transaction and rendering
   outside it.
8. **PATCH endpoints house-wide are full-header-replace**, not partial merges — an omitted
   nullable field is cleared. The PUT-vs-PATCH-vs-partial decision is deliberately deferred until
   the frontend lands and can state what it needs. This semantic is documented on
   `Tenant.updateProfile` (the PDF/share slice's new tenant-profile PATCH) but, house-wide, is
   asserted by no test — a regression test would be cheap if this is ever revisited.
9. **`OrderSpecifications`, `EnquirySpecifications`, `QuotationSpecifications`, `CustomerSpecifications`
   (added by the record-visibility slice, §3), and now `FollowUpSpecifications` (added by
   `activity-follow-up`, §3) all use string-keyed `root.get(...)`** rather than a JPA static
   metamodel, so a field rename fails at runtime rather than compile time. All five have immediate
   test coverage. If fixed, fix them together — doing one alone just makes the others inconsistent.
   Fixing this now means touching five classes, not four.
10. **Only `Seller`'s optional fields have a null-render test in `QuotationPdfRendererTest`.**
   `Buyer.gstin`, `Buyer.address`, `validUntil`, payment/delivery terms and notes are all
   `th:if`-guarded in `quotation.xhtml`, but no test renders any of them absent — the same
   category of gap as the `OrderTest` item below, just on the newer surface.
11. **`OrderTest`'s three rejected-transition tests assert only the exception type**, not that
    `status`/`cancelReason` are left unmutated; only the blank-reason test snapshots state. Safe
    today (every guard runs before any assignment), but a future guard reorder would go uncaught.
12. **Four near-identical order-building test fixtures** now exist across the sales test classes
    (`OrderReadTest`, `OrderTransitionTest`, `OrderStatusAuditTest`, plus
    `QuotationAcceptAuditTest`'s inlined variant). Extracting a shared sales test-fixture helper
    is a candidate cleanup; it was consciously declined to keep slices independent.
13. **`Enquiry.advanceTo` couples to enum ordinal order** (guarded, but a reorder changes
    behaviour). `Order`'s transitions deliberately avoid this by naming each precondition — that
    is the pattern to copy if `Enquiry` is ever revisited.
14. **`expectedValue` / `contactEmail` lack `@PositiveOrZero` / `@Email`** on the enquiry DTOs.
15. **No index supports a status-only order-list filter.** `sales_order` has
    `(tenant_id, customer_id)` and `(tenant_id, id)`; `?status=` alone has none. Irrelevant at
    current volumes — worth revisiting before the first large tenant.
16. **`PdfEngine` catches broad `Exception` in both its render and metadata-stamp paths.** Matches
    the brief's reference code as given; narrowing it to something that distinguishes malformed
    input from an environment failure belongs with whichever caller first needs to tell the two
    apart. No caller does yet.
17. **Two dead null-checks guard a value that's never actually null.**
    `QuotationPdfService.requireCurrentVersion`'s null-`currentVersionId` branch (`create()`
    always sets it) and `ShareLinkService.share`'s equivalent `q.getCurrentVersionId() == null`
    check are both harmless defensive code inherited from the plan. Deliberately left as-is:
    fixing one without the other would just make them inconsistent, so revisit together if ever.
18. **Quotation-totals `sum()` references `java.util.function.Function` / `java.util.Objects`
    fully-qualified inline** rather than importing them — a style inconsistency inherited from
    the plan, not introduced by this slice.
19. **A malformed `/public/q/{token}` containing a literal `/` returns 401, not 404.** Ruled
    acceptable as-is: the path never resolves as this route at all, and the 401 reveals nothing
    beyond "`/public/**` is auth-gated" — it isn't a usable oracle for probing real tokens.
20. **`PdfEngineTest.sameInputRendersToIdenticalBytes` renders XHTML with no `<title>`,** so
    byte-determinism is never exercised with a title present. The re-reviewer decompiled the
    renderer and confirmed title is a pure function of input, so this is a coverage gap, not a
    suspected risk — lowest priority on this list.
21. **`easycrm.public-base-url` has a bare `http://localhost:8080` dev default with no validation
    that a real deployment overrode it to an `https://` origin.** A deploy that forgets
    `PUBLIC_BASE_URL` mints and WhatsApps a `localhost` link to a customer — silent, because the
    only place the bad URL surfaces is the customer's chat, not the server's logs. The default
    itself is intentional (no production profile exists yet; local dev and the demo flow need it
    to work out of the box, same shape as the `JWT_SECRET` dev default) and is commented in
    `application.yml` accordingly. What's still open: bind it through a validated
    `@ConfigurationProperties` class that requires an `https` scheme outside a dev profile, so a
    misconfigured deployment fails loudly at startup instead of shipping a broken link silently.
22. **The inter-state PDF assertion cannot distinguish the IGST row from the Total tax row.** In
    the endpoint test's fixture both happen to be `Rs. 180.00`, so a regression that dropped the
    inter-state Total tax row specifically would not turn the test red. The row does render
    (verified by inspection); the intra-state branch has no such ambiguity. Asserting on the
    literal `Total tax` label, or choosing a fixture where the two amounts differ, closes it.

**From the `platform-primitives` slice (2026-08-27).** Both were raised by the whole-branch review,
judged real, and deliberately deferred rather than fixed — each is a decision better made once,
when module 2 lands and there are three places to keep in step instead of two.

23. **`PrimitivesModuleArchTest.carriesNoRuntimeSpringDependency` exempts by *name suffix*.** The
    rule is `noClasses().that().haveSimpleNameNotEndingWith("AutoConfiguration").should()
    .dependOnClassesThat().resideInAnyPackage("org.springframework..")`, so **any** class named
    `*AutoConfiguration` silently opts out of the module's no-runtime-Spring guarantee — a naming
    convention, not a real constraint. Today it exempts exactly one class
    (`MoneyAutoConfiguration`) and is not over-exempting anything. Scoping the exemption by the
    `@AutoConfiguration` annotation, or by package, would not be forgeable. Worth doing when
    `platform-web` and `platform-tenancy` arrive with auto-configurations of their own, because
    that is when the convention starts carrying weight it was never designed to carry.
24. **The Spring Boot version is pinned in two independent places and can drift silently.** The
    root project pins it via the plugin (`backend/build.gradle.kts`, `id("org.springframework.boot")
    version "4.1.0"`), and the module pins it via the BOM
    (`backend/platform/platform-primitives/build.gradle.kts`,
    `platform("org.springframework.boot:spring-boot-dependencies:4.1.0")`). Nothing checks the two
    agree, and a mismatch would surface as a confusing resolution error rather than as "you edited
    one of two". The right fix is a Gradle version catalog (`gradle/libs.versions.toml`) or a shared
    convention plugin — a build-structure decision worth making **once**, at module 2, rather than
    twice.

**From the `public-rate-limiting` slice (2026-08-28).** Items 25–28 are per-task review findings;
29–32 came from the whole-branch review, which triaged all four as genuinely safe to defer (its two
must-fix findings — the eviction window and the context path — were fixed on the branch, not deferred).

25. **Jackson's `AUTO_CLOSE_TARGET` closes the servlet output stream** when `RateLimitFilter.reject`
    writes the 429 body via `writeValue(response.getOutputStream(), …)`. Harmless today because the
    deny path is terminal — nothing runs after it. It stops being harmless the moment anything is
    layered after the limiter in the chain.
26. **`org.springframework.lang.NonNull` is `@Deprecated` since Spring 7.0** (JSpecify annotations are
    the replacement). `RateLimitFilter` is its only use in `src/main/java`. Cosmetic, but it is the
    kind of thing that becomes a compile warning wall later; fix it when JSpecify is adopted repo-wide.
27. **Three of the five `RateLimitFilterTest` cases would also pass against a pass-through filter**
    (allowed / unmatched / disabled). They are negative controls. The two load-bearing cases — deny,
    and the bucket key — are properly falsifiable, and four integration tests cover the rest, so this
    is thin rather than wrong.
28. **`HarnessRateLimitDisabledTest` guards the shared context only.** It proves the suite-wide
    `enabled=false` default still applies to a plain `IntegrationTest` subclass, and the re-review
    proved it falsifiable by deleting the annotation and watching it go red. It cannot catch a future
    test class that forks its own context with the limiter ON, nor a new `@SpringBootTest` base that
    does not extend `IntegrationTest`. All 64 current subclasses inherit cleanly.
29. **`UrlPathHelper.getPathWithinApplication` URL-decodes the path**, so a `%2F` inside a share token
    would split into segments and miss the `/public/q/*` pattern where the raw URI matched. Not
    reachable today: Tomcat rejects encoded slashes with a 400 before the filter runs. It becomes
    reachable if `ALLOW_ENCODED_SLASH` is ever turned on, or behind a proxy that normalises differently.
30. **`refillPeriod` carries `@NotNull` but no positivity constraint,** so `refill-period: 0s` binds
    successfully at startup and then throws inside Bucket4j on the first request — the one
    misconfiguration in this properties class that still fails late rather than fast. `capacity` is
    correctly `@Positive`.
31. **CGNAT collateral: customers behind one carrier NAT share a bucket.** A share link forwarded to
    several people on the same mobile carrier can 429 legitimate recipients. This is inherent to the
    per-IP keying decision (design spec §3 argues why per-token is worse), not a defect — but it is the
    failure mode to look for first if a distributor ever reports "my customer says the link is broken."
32. **The shipped `auth` policy is never exercised end-to-end.** `RateLimitIntegrationTest` overrides
    `policies[1]` with its throwaway `api-protected` policy, so the 30/minute login cap is covered only
    by `RateLimitDefaultsTest`'s matching assertions. Adequate — the mechanism is identical and proven
    on the other policy — but no test ever drives a real login to 429.

**From the `record-visibility` slice (2026-08-29/30).** Items 35–41 are open. Items 33 and 34 were
open when the slice's docs task ran and were **closed afterwards** by the whole-branch review's fix
wave — that review ran *after* the docs task, which is why items 37–41 below exist at all: they are
its findings, and they were never going to appear in a handoff written before it. Four other per-task
`minor (deferred)` findings from this slice's ledger resolved themselves before the slice ended
(`VisibilityPolicy.enquiries()` lacked a direct test in Task 1 — covered end-to-end by Task 4;
`VisibleFinder`'s javadoc forward-referenced `VisibilityScopingArchTest` before Task 8 created it;
no test drove a `page*` method with a non-null caller filter — covered by Task 3's `?active=` list
test; and Task 3's reviewer flagged that `CustomerService`'s changed constructor arity was checked
for other direct-construction call sites but not independently re-verified — Task 5's reviewer did
that independent check two tasks later, grepping the whole codebase and confirming no manual
`new XService(...)` call site exists anywhere, closing the concern) and are not carried forward as
open items. The `PriceResolver`/`QuotationService` comment sweep this same ledger flagged for Task 9
is also done — see §3.

33. ~~**Unused `@Autowired TestTokens` field in `VisibilityPolicyIntegrationTest`.**~~ **DONE** —
    removed by the whole-branch fix wave.
34. ~~**`activeFilterStillWorksForAnOwner` never creates an inactive customer.**~~ **DONE** — the fix
    wave deactivates a customer in the fixture and asserts both directions (`?active=false` returns
    it and omits the active one; `?active=true` the reverse). It was the only test of
    `CustomerSpecifications.filter`, the new code that replaced the deleted
    `CustomerRepository.findByActive`, so its positive behaviour had been unproven.
35. **`CustomerService.update` validates `assignedTo` before `find(id)`; `EnquiryService.update`
    validates it after.** Considered as an existence oracle and dismissed during Task 7's review — a
    plain `GET` already discloses visible-vs-not, so the ordering leaks nothing incremental.
    Cosmetic inconsistency only.
36. **The two ArchUnit tests in `com.easycrm.arch` now use visibly different idioms** —
    `TenantScopingArchTest` uses declarative built-in rules, while `VisibilityScopingArchTest`
    (Task 8) needs a hand-rolled `ArchCondition` because method-call inspection has no built-in
    predicate for "was this call routed through class X." Noted for a future reader only, not a
    defect.

**From the whole-branch review (2026-08-30).** Three of its findings were fixed before merge (the
count query that never ran, four list tests that asserted only absence, and `activeFilterStillWorks`
above). These five were judged safe to defer. **Items 37–39 are all blind spots in the same guard**,
and they are worth fixing together rather than one at a time.

37. **`VisibilityScopingArchTest`'s allowlist is keyed on a bare method name, not a
    `(repository, method)` pair.** So `findByQuotationId` is permitted from *any* class, even though
    its §6.1 justification — "reached only from an already-checked quotation" — is a property of its
    single current call site, not something the guard enforces. Likewise `findByGstin` and
    `findByNormalizedPhone` are permitted on all four repositories rather than only the one each
    belongs to. Fix: allowlist `owner + "." + name` pairs. This is the most substantive of the three
    and the one that makes the allowlist mean what it says.
38. **A field declared as `JpaRepository<Customer, UUID>` would be invisible to the guard.** The rule
    keys on `getTargetOwner()`, which resolves to the *statically declared* receiver type — that is
    exactly what makes it catch inherited methods like `findById` as calls against
    `CustomerRepository`. The flip side is that a field typed as the Spring Data supertype (which
    Spring resolves by type perfectly happily) has a target owner outside `GUARDED_REPOSITORIES`.
    Obscure, but it is the one way to reach these rows that the allowlist cannot see.
39. **Method references to *inherited* repository methods still bypass the guard.** The fix wave
    unioned `getMethodReferencesFromSelf()` into the rule, which closes `someRepo::someDeclaredMethod`
    — but a reference to an inherited method (`customers::findById`) resolves its ArchUnit target
    owner to `CrudRepository`, not to the local interface, so it slips through. There are no such
    references in the codebase today. This residual is disclosed in a comment in the test itself
    rather than being quietly claimed closed; closing it properly means widening
    `GUARDED_REPOSITORIES` or matching on the declaring hierarchy.
40. ~~**`requireAssignableUser` is duplicated verbatim, javadoc included, in `CustomerService` and
    `EnquiryService`.**~~ **DONE** — closed by the `activity-follow-up` slice (§3): `FollowUpService`
    needed a third copy, which was the extraction trigger. Both call sites now delegate to
    `com.easycrm.iam.AssignableUsers.require(UUID)`.
41. **The three count-query tests assert row *count* but not row *identity*.** After the fix wave,
    `pagingAppliesVisibilityToBothTheDataAndCountQueries` and its quotation/order siblings page with
    `PageRequest.of(0, 1)` to force the count supplier to run, and assert `getContent()` has size 1
    — dropping the `containsExactlyInAnyOrder(mine, pool)` identity check the pre-fix customer test
    had. A broken filter still surfaces through the total, so the defect they were written for stays
    covered, but they discriminate less on the data-query side than their own docstrings claim.
    Asserting the returned row is one of the visible set closes it.

**From the `activity-follow-up` slice (2026-08-30).** All four open. Item 45 is a deliberate,
disclosed deviation from `CLAUDE.md`'s "log it in the same change" rule, not an oversight — the other
three are ordinary per-task findings judged safe to defer.

42. **`FollowUpService` imports `org.springframework.data.domain.Sort` but never uses it.** Cosmetic;
    no checkstyle gate is configured to catch an unused import at build time.
43. **`ActivityEditEndpointTest.aMismatchedSubjectIs404` passes for the wrong reason.** It supplies a
    random `subjectId`, so it 404s at `VisibleFinder.requireVisibleSubject` before the
    subject-scoped repository lookup (`findByIdAndSubjectTypeAndSubjectId`) is ever reached — it
    proves the gate works, not that a *mismatched-but-real* subject is rejected. The underlying
    property this test's name promises **is** covered, just by different tests:
    `ActivityRepositoryTest.findByIdIsScopedToTheSubjectItWasFiledUnder` proves it at the repository
    level, and `ActivityRepositoryScopingArchTest` proves it structurally (§3, challenge #50). Fix:
    seed a second real, visible enquiry and use its id as the mismatched subject, so the test
    actually exercises the repository-level scoping it's named for.
44. **`@JsonInclude(Include.NON_NULL)` sits on the whole `ActivityResponse` record**, so it also
    suppresses a null `outcome`, not just the `followUpId` field it was added for. Dormant today —
    the client already treats a missing key and an explicit `null` the same way — but the blast
    radius is wider than the one field that motivated it. Narrow it to `@JsonInclude` on the
    `followUpId` field alone if it ever bites.
45. **Challenge #50's log entry (the bare-`Repository` mechanism) was written in the slice's
    docs task (Task 14) rather than in the task that introduced the mechanism (Task 2, `ff456fb`)** — a
    literal deviation from `CLAUDE.md`'s "same change" rule. Accepted because the guard that
    demonstrates *why* the mechanism matters (`ActivityRepositoryScopingArchTest`, Task 3, `642c94c`)
    only landed a task later; logging at Task 2 would have had no guard to point to yet.
46. **Cross-assignment on `follow_up` is a one-way door, undecided by design, not a bug.** A
    `SALES_EXEC` who creates a follow-up assigned to a colleague gets a `201` carrying a
    `followUpId`, then `404`s on `GET /follow-ups/{id}` and cannot cancel or reschedule it — only
    the assignee or an unrestricted role can, because `VisibilityPolicy.followUps()` filters
    strictly on `assignedTo = me` (design spec §4.1). The API is coherent with that policy but
    returns a link the caller cannot follow, and nothing documents or tests the boundary today
    beyond the spec paragraph added alongside this item. Whether a creator should retain any
    visibility or control over work they assigned to someone else is an open design question —
    who may assign work to whom is out of scope for this slice.

**From the `quotation-auto-expiry` slice (2026-08-31).** One item, open.

47. **`QuotationExpirySweep.run` issues one `findById` per expiry candidate** to read that
    candidate's `QuotationVersion` (for `validUntil`, to attach to `QuotationExpiredEvent`), rather
    than batch-loading the versions for the whole candidate set in one query. Irrelevant at current
    volumes — a tenant's nightly lapsed-quote count is small — but if a tenant ever accumulates
    thousands of lapsed quotes overnight, this is N+1 by construction. Fix: fetch all candidate
    version ids from `expirableAsOf`'s own subquery result and batch-load them with a single
    `findAllById`, keyed by the quotation's `currentVersionId`.
