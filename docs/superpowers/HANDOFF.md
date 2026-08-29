# EasyCRM — Handoff

**Last updated:** 2026-08-29 — **Per-IP rate limiting is built on branch `public-rate-limiting`,
complete and pending merge (not yet on `main`). Merging it, or deciding not to, is the first thing a
new session must do — see §0.** It caps abuse of `/public/q/{token}` and the auth
routes — the two things backlog item #3 and PF19 flagged — with a Bucket4j token bucket per
`(policy, client-IP)` pair behind a `RateLimitStore` port, an in-memory implementation bounded by a
Caffeine cache (challenge #41), and a filter that runs ahead of Spring Security so failed-auth
traffic is capped too. **PF19 is only partly addressed:** this slice stops the route from being
hammered; it does not give it entitlement metering, which PF19 is actually about — see §8. The
previous slice (RLS `FORCE`d + a layer-3 guard, closing PF14/PF15) is now **merged to `main`**;
see §3's "Previous code work" for its detail.
**Purpose:** Everything a fresh agent needs to pick up this project and continue. Read this first, then the linked docs.

---

## 0. Resuming? Start here

### Your first three actions, in order

**You are not starting from a clean `main`. Do these before anything else.**

**Action 1 — check out the right branch and know where you are.**
`git branch --show-current` will say **`public-rate-limiting`** if nobody has moved it. That branch is
**finished and unmerged**: 11 commits, `bc542c2`..`5a11f33`, off `main` at `e69d7ac`. Every task was
reviewed clean, a whole-branch review returned MERGE after its two findings were fixed, and the tree
is clean. `main` does **not** contain any of it.

**Action 2 — settle the merge with the user.** Do not start new work on top of an unmerged finished
branch, and do not merge it silently either. Run `superpowers:finishing-a-development-branch`, which
presents the options (merge locally / open a PR / leave it). The house pattern for the last three
slices has been: `--no-ff` merge to `main`, then a follow-up docs commit recording the merge hash and
clearing this section's in-flight note, then delete the branch. If the user merges, **update this §0
to say nothing is in flight** — a stale "in flight" line is the single most misleading thing this
document can contain.

**Action 3 — confirm the baseline** (item 1 below) on whatever you ended up on. On the branch it is
**296 tests**; on unmerged `main` it is **287**. If you merge, it is 296.

**One loose end that is not code:** the Bucket4j entry written for
`/Users/divyam/Documents/dsa/good-repos/CATALOG.md` is **on disk but unversioned** — that directory is
not a git repository, so nothing was committed there. Design spec §7 asked for the entry; it exists;
it is just untracked. Decide with the user whether that repo should be `git init`ed. Do not init it
unilaterally.

Before this slice, `rls-force-and-guard` ran to completion and **merged to `main` as `3c239d1`**
(commits `cfc8928`..`b8b2ecb`); that feature branch is deleted, as was `platform-primitives-module`
before it (merged as `210545e`).

1. **Confirm the baseline before touching anything:** `open -a Docker`, wait for `docker info`,
   then `cd backend && ./gradlew clean test`. The number depends on where you are: **296 tests,
   0 failures, 0 errors** on `public-rate-limiting` (273 root + 23 `platform-primitives`), or **287**
   on unmerged `main` (264 + 23). Merging the branch makes `main` read 296. Gradle prints no total for a multi-project
   build, so count it yourself:

   ```bash
   cd backend && ./gradlew clean test
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
   find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
     | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
   ```

   If that number differs, stop and reconcile before writing code — everything below assumes it.

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
   Read that handoff's §3 before planning anything: three findings there outrank the module work,
   and two of them describe code running today — RLS is `ENABLE`d and never `FORCE`d, so tenant
   isolation currently rests on which database role a deployment happens to connect with. Building
   one module did not change that ordering.
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
17. **`engineering-challenges.md`** — running log of non-obvious problems + solutions (41 entries). Great context on the stack's quirks.
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
    **Branch `public-rate-limiting` is complete, reviewed clean, and pending merge** — see §0 and §3.
    Worth reading even if you never touch rate limiting: the plan's own code was wrong in five
    separate places that only surfaced during execution (a record that could not bind, a static
    factory colliding with a record accessor, a Jackson 2 import on a Jackson 3 project, a
    `BindResult` overload that does not exist in Boot 4.1, and a test-property precedence rule that
    is the reverse of what the plan assumed). Challenges #38–#42 are the write-ups.

## 3. Current state

- **Latest code work: per-IP rate limiting on the public and auth routes** — **branch
  `public-rate-limiting`, complete and reviewed clean, NOT YET MERGED to `main`.** Commits
  `bc542c2`..`5a11f33` off `main` at `e69d7ac` — 11 in total: seven tasks, one whole-branch fix wave
  (`3bfb99d`), and two docs commits. Seven tasks: a `RateLimitPolicy` value type +
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

- **Previous code work: RLS forced on all fourteen tenant tables, with a layer-3 guard** — **merged
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

- **Before that: `platform-primitives` extracted into its own Gradle module** — **merged to
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

1. **`activity` / `follow_up` entities** — the "never lose a follow-up" promise (CALL/WHATSAPP/EMAIL/
   VISIT/NOTE logs + first-class reminders). New aggregate(s); the accept event seam already exists
   to hang activity listeners on.
2. **Scheduled auto-expiry** of quotations past `validUntil` — only a manual `expire` action exists
   today; nothing runs on a schedule. Small, introduces the first scheduled job.
3. **P0-auth follow-up** — now **only** user invitations + **record-level visibility filtering**
   (`assigned_to`, still open from P1a — every user in a tenant reads every record). Its
   rate-limiting third is **done** on branch `public-rate-limiting` (§0/§3, pending merge):
   `/public/q/{token}` and the auth routes are capped per-IP with a 429 + `Retry-After` contract.
   That closes the *abuse-of-rate* half of PF19 and **not** the *entitlement-metering* half — PF19
   stays open below. Record-level visibility is the larger and more overdue of the two remainders:
   it is a **tenant-internal confidentiality gap that exists in code running today**, which is the
   same category of claim that made PF14/PF15 outrank everything else two slices ago.
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
  `V26__force_rls.sql` forces all fourteen. Read the caveat in §3: this removes the *silent*
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

**Suggested default — read this before proposing anything.** Three slices in a row have now been
hardening (RLS forcing, then rate limiting). The security items that described *running code* are
closed or downgraded, so the honest ranking has changed:

- **Record-level visibility filtering (the rest of #3) is the last "it is wrong in code that runs
  today" item on this list.** Every user in a tenant currently reads every record — a
  tenant-internal confidentiality gap, the same category of claim that put PF14/PF15 ahead of
  everything else two slices ago. It is also a prerequisite the frontend will assume exists.
- **#1 (activity/follow-up) is the strongest product claim** and the wedge's most conspicuous
  missing surface: "never lose a follow-up" is a headline promise with no implementation. The accept
  event seam already exists to hang listeners on. Pick this if the next slice should move the
  product rather than the platform.
- **PF19's entitlement-metering half** is now the only part of PF19 left, and it is genuinely
  blocked on design, not effort: the public route has no JWT, so there is nowhere to hang a
  per-tenant check. It needs the billing thread's decisions before code.
- **`platform-web`** is next by dependency order, which remains the weakest of these claims.

A reasonable reading is: **visibility filtering if you want the last correctness gap closed, #1 if
three hardening slices is enough and the product needs to move.** Confirm with the user rather than
assuming — and note that #2 (scheduled auto-expiry) is still the cheapest item on the board if a
small slice is wanted between two large ones.

**Before any second app instance:** the rate limiter's store is in-process (§3) — running N
instances behind a load balancer multiplies every configured limit by N, silently. Build the
design's Redis-backed `RateLimitStore` implementation before multi-instance deployment, not after;
today it does not exist.

### Smaller deferred-Minor backlog

Open and non-blocking. This list is the complete record of every `minor (deferred)` line the SDD
ledgers of **two** slices accumulated — items 1–22 from the quotation PDF/share slice (ten tasks),
items 23–24 from the `platform-primitives` slice (eight tasks) — each cross-checked line-by-line
against its ledger before that workspace was deleted at merge. So it really is **self-contained**:
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
   slice (§3, pending merge): a per-IP Bucket4j token bucket in front of `/public/q/{token}` and the
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
9. **`OrderSpecifications`, `EnquirySpecifications`, and now `QuotationSpecifications` all use
   string-keyed `root.get(...)`** rather than a JPA static metamodel, so a field rename fails at
   runtime rather than compile time. All three have immediate test coverage. If fixed, fix them
   together — doing one alone just makes the others inconsistent.
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
