# Handoff — the four remaining platform-module LLDs

**Session:** 2026-08-26 → 2026-08-27
**Nature:** **Design only. Zero code changed.** No migrations, no tests touched. The 231-test
baseline is exactly where it was at `80e74a3`.
**Start a new chat with:** "Read `docs/architecture/2026-08-27-platform-llds-handoff.md`."

**Continues from:** [`2026-08-26-platform-modules-handoff.md`](2026-08-26-platform-modules-handoff.md),
which in turn continues [`2026-08-20-aws-redesign-handoff.md`](2026-08-20-aws-redesign-handoff.md).
Read neither to *find out what to do next* — this document covers that. Read them for the decisions
still in force (D1–D15, B1–B11, F1–F17, P1–P9, I1–I5) and for the three blockers in the first one's §5.

**The six-LLD queue is closed.** Every module in the parent spec now has a low-level design.

---

## 1. What this session produced

Four commits on `main`, all documentation:

| Commit | What |
|---|---|
| `a2959a5` | `platform-security` LLD — the module shrinks and the chain gains a seam |
| `2dde1ea` | `platform-tenancy` LLD — RLS rests on a role, not on the schema |
| `9274e79` | `platform-outbox` LLD revised — the relay role loses `BYPASSRLS` |
| `a73a285` | `platform-entitlement` LLD — the last of the six |

**Three new documents, one revised in place:**

| Doc | Lines | What it is |
|---|---|---|
| `2026-08-26-platform-security-lld.md` | 650 | LLD #3. SD1–SD5, SR1–SR5, SF1–SF8, SB1–SB7 |
| `2026-08-27-platform-tenancy-lld.md` | 573 | LLD #4, the large one. CD1–CD3, CR1–CR6, CF1–CF9, CB1–CB8 |
| `2026-08-19-outbox-lld.md` | 625 | LLD #5 — **revised, not rewritten.** New Part 0 lists five changes; OF6–OF9 added |
| `2026-08-27-platform-entitlement-lld.md` | 315 | LLD #6, deliberately the smallest. ED1–ED3, ER1–ER3, EF1–EF6, EB1–EB5 |

The parent spec (`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`) was amended in
place after each one: Part 7 is now fully green, §2.3/§2.4/§2.6 corrected, and **PF11–PF19** added to
Appendix A.

**Published artifacts** (private unless shared):

| LLD | URL |
|---|---|
| #3 `platform-security` | https://claude.ai/code/artifact/ae01a33c-0577-458c-a476-152708f5ae3c |
| #4 `platform-tenancy` | https://claude.ai/code/artifact/fb129561-a8e4-4616-aed9-9e2c4c146b93 |
| #5 outbox (revised) | https://claude.ai/code/artifact/032ad740-95ab-41db-8ade-f660d9a5a7eb |

LLD #6 was **not** published. LLD #1's artifact is in the previous handoff's §1.

---

## 2. Where to pick up

**The LLDs are done. `writing-plans` has still never been reached on this track** — six low-level
designs and not one implementation plan. That is the next step, and it is a decision about *which
sub-project*, not about which module: the modules are a means to the split, not a deliverable.

Before planning anything, read §3 below. **Three findings outrank the entire module split**, and two
of them are about code that exists and runs today.

### The two things a fresh agent should verify first

Both gate work rather than merely informing it:

1. **`java-test-fixtures` must reach package-private members of the main source set.** P3 (sealing
   `TenantContext`), P9 (the test-support fixture) and CD3 (the sealed `TenantPrincipal`) all rest on
   it, and so does a 54-file test migration. If Gradle's separate compilation does not allow it, the
   plan changes rather than the code. Parent Appendix B item 2; LLD #4 Appendix B item 1.
2. **Postgres must OR permissive RLS policies.** The outbox revision replaces `BYPASSRLS` with a
   role-scoped policy, and assumes `relay_app` matching `relay_reads_all` gets every row even though
   `tenant_isolation` matches none. If policies AND, the relay reads zero rows and the fix is worse
   than what it replaced. Outbox LLD Appendix B item 7.

**A third is wanted by three separate LLDs and should be done once:** which Boot 4 artifact carries
which auto-configuration, and whether an auto-configured `@RestControllerAdvice` / `SecurityFilterChain`
/ `HibernatePropertiesCustomizer` is discovered the same way a component-scanned one is. LLD #1's open
question 2, LLD #2 Appendix B item 5, LLD #3 Appendix B item 2, LLD #4 Appendix B item 3 — the same
question four times, and in LLD #3/#4 the silent failure is *open routes* and *no tenant filtering*.

---

## 3. The findings that outrank the module work

All three are in the parent spec's Appendix A. Two describe code running today.

### PF14 — row-level security rests on a role assignment, not on the schema

**Fourteen tables `ENABLE ROW LEVEL SECURITY`. Zero `FORCE ROW LEVEL SECURITY`.** A table's owner is
exempt from its own policies unless the table is forced. Today that is safe and deliberate — Flyway
runs as `easycrm_owner`, the app connects as `easycrm_app`, and `V4`'s comment says so.

Under the split it becomes five services holding five sets of Terraform-issued credentials. One
service issued the owner role loses layer 3 **silently**: no error, no log line, no failing test,
because the harness wires both roles correctly by construction. Layers 2 and 4 keep passing.

Fix: `FORCE ROW LEVEL SECURITY` on every tenant-scoped table. Gated on whether Flyway then needs
`BYPASSRLS` for data migrations (LLD #4 Appendix B item 2).

### PF15 — ArchUnit guards layer 2; nothing guards layer 3

A new table that declares `@TenantId` and omits its two RLS lines passes the whole suite. Hibernate
filters it, so behaviour is correct, and it ships with one of the four isolation layers simply
absent. A `pg_policy`/`pg_class` test over the entity set closes it (LLD #4, CR3).

Related and separate: **`RlsIntegrationTest` has exactly one test, and it covers the no-context
case.** Nothing anywhere asserts that tenant A's raw query cannot see tenant B's rows — the case RLS
exists for, and the one `@TenantId` cannot cover. LLD #4 §5.2 calls it the highest-value test missing
from the codebase.

### PF17 — `BYPASSRLS` could not be constrained by PF14's fix

The outbox relay was designed to read across tenants via a `relay_app` role holding `BYPASSRLS`.
That is a **role attribute, not a table permission**: it applies to every table in the database and it
*overrides* `FORCE`. So the one role whose job is to bypass tenant isolation was also the one role
PF14 could not reach, and its containment rested on a GRANT list never widening.

Replaced by two role-scoped permissive policies on the outbox table alone (outbox LLD revision 4,
OF6). **OF1 survives unchanged** — Hibernate's `@TenantId` still filters a JPA read to zero rows
whatever the database permits, so the relay still needs `JdbcTemplate` on its own DataSource.

### PF19 — a metered route with nowhere to put a check

`PDF_RENDER` is incurred by a `GET`; `POST /{id}/share` mints a link that renders forever; and
`/public/q/{token}` renders with no JWT at all, so no claims and no entitlements. The application's
only unauthenticated route is also its most expensive metered one.

This is a **rate-limiting** problem, not an entitlement one, and it is the second independent
argument this session produced for pulling backlog item 3 forward — LLD #3 made the first, on the
grounds that the route is uncapped and renders a PDF per hit.

---

## 4. Decisions made — do not relitigate without cause

**`platform-security` (LLD #3):**

- **`VerifiedClaims.role` is an opaque `String`.** `Role` lives in `identity-svc`, and the application
  has exactly *one* authorization check in it (`TenantService.requireOwner`, a string compare). The
  `ROLE_` authorities the filter puts in `SecurityContextHolder` are read by nothing.
- **The RS256 seam is two seams**: key resolution behind `SigningKeyResolver`, and the claim set.
  `identity-svc` mints `iss`/`aud`/`kid` from day one under HS256, so sub-project 7 swaps one bean.
- **`PasswordConfig` leaves for `identity-svc`** (PF12), by P5's rule. Flagged as the call most likely
  to be overridden; nothing else depends on it.
- **`HttpSecurityContribution`** — services declare public routes beside the controller that serves
  them; the base chain is sealed default-deny *after* contributions apply.

**`platform-tenancy` (LLD #4):**

- **`TenantPrincipal` is a sealed interface.** Provenance is the type, so `SystemPrincipal` has no
  `role()` for a role check to reach, and `userId` exists only where it is real. It rides the P3 seal
  migration because that migration already touches all 137 call sites.
- **All six entry adapters specified; three built now, three with their first consumer.** P1's
  ownership is unchanged — only the schedule. There is no `@Scheduled`, no `@Async` and no SQS
  listener anywhere in the codebase today.
- **Adapter 5 is not a class**: `platform-tenancy` owns the binding primitive, `IdempotentConsumer`
  composes it.
- **`@GlobalTable(reason)`** replaces the FQN allowlist, which spans two future services.

**`platform-outbox` (LLD #5, revised):** the five revisions are in that document's Part 0.

**`platform-entitlement` (LLD #6):**

- **`@RequiresEntitlement` xor `@NotMetered(reason)` on every create.** The rule does not try to know
  what is metered; it requires that someone decided in writing at the site.
- **The guard is advisory under concurrency, and that is a property.** A plan limit is commercial, not
  a correctness invariant. Recorded so a future limit that genuinely must not be exceeded is
  recognised as a *different* problem needing challenge #26's treatment.
- **The 402 advice ships in this module**, because `platform-web` may not name it (W2).

**One principle, named late and worth keeping (PF18):** *a lower module carries the value; an upper
module supplies the meaning.* `role` as a `String`, `Entitlements` as an untyped map, `VerifiedClaims`
as data — three local judgement calls that turned out to be one rule, and the thing that kept a
two-level DAG from becoming a knot. Each of the three prevented a cycle.

---

## 5. Live bugs in code that exists today

Unchanged from the previous handoff unless noted, and none of them needs the split to matter:

- **F11** — `QuotationPdfService` reads buyer name/GSTIN/address live while `QuotationVersion` freezes
  everything else. Re-rendering a `SENT` quotation after a customer edit produces a different
  document.
- **MF1** — the seller's GSTIN is never validated and the state code is only `@Pattern("\\d{2}")`,
  while a buyer's goes through `Gstin.parse` **and** `StateCode.requireValid`. Since `isInterState`
  compares the two, a bad seller state code silently decides CGST+SGST vs IGST on every quotation
  that tenant ever issues.
- **SF5 / PF13 (new)** — `easycrm.jwt.secret` has a committed dev default and no validation. A
  deployment that forgets `JWT_SECRET` boots happily and signs every token in the fleet with a secret
  that is in the repository. Same shape as backlog item 21 (`public-base-url`); **land the two
  together**, since fixing one alone makes the other look deliberate.
- **SB1 (new, latent)** — `JwtAuthenticationFilter` catches `RuntimeException` and proceeds
  unauthenticated. Harmless today; under JWKS a failed key fetch becomes a fleet-wide 401 storm
  indistinguishable from every user mistyping their password. LLD #3's SR4 makes the fix a
  review-blocking rule on LLD #4's filter.
- **OF7 (new)** — the outbox LLD specified `@SchedulerLock` with no lock table and no library. Its
  test 18 could not have passed. Now `V902`.

Also still open from the previous handoff: **S1** (`/api/v1/tenant` absent from the ALB routing
table), **S2** (`ShareLinkService.whatsappLink()` is a fifth cross-service call site), **S5**
(`notification-svc` has no schema but must write `processed_event`).

---

## 6. What is NOT done

- **Nothing is built.** No Gradle modules, no code moved, no `EventJson`, no client, no ArchUnit
  rules. The build is still one project under `backend/`.
- **No implementation plan exists** for any of the six modules or any AWS sub-project. This thread has
  now spanned three sessions without reaching `writing-plans`.
- **Several LLD assertions rest on unverified Boot 4 / Hibernate 7 / Postgres / ArchUnit behaviour.**
  Every LLD has an Appendix B; §2 above lists the two that gate work and the one wanted four times.
- **`@Entitlements` is a placeholder**, and `platform-entitlement` is *specified* now but *built* in
  sub-project 10.

---

## 7. Repo state and caveats

- **Branch `main`**, four commits ahead of where this session started (`1432469`). Docs only.
- **The 231-test baseline is untouched.** Confirm before writing code: `open -a Docker`, wait for
  `docker info`, then `cd backend && ./gradlew clean test`.
- **Two tracked files still carry uncommitted modifications that did not come from this thread or the
  last one** — `2026-08-19-aws-target-architecture-design.md` and
  `../superpowers/engineering-challenges.md` (the latter adds a challenge #31 on head-vs-tail
  sampling). Left alone deliberately for the third handoff running. Review them before committing
  anything else.
- **Four files in `docs/architecture/` remain untracked** and predate all three threads:
  `2026-07-29-current-architecture.md`, `2026-07-29-target-architecture.md`,
  `2026-08-05-interview-challenges-and-aws-kafka.md`, `interview-qa.md`. **Unresolved across three
  handoffs now** — commit or delete them before starting a slice, so the tree is clean when you
  branch.
- **Nothing belongs in `engineering-challenges.md` from this session.** That log records problems
  solved while *building*, and nothing was built.

**Working agreements** (from `CLAUDE.md`, unchanged): commit as `divyam`, never mention Claude or AI
in commit messages; log non-obvious problems to `engineering-challenges.md` in the same change; keep
`annotations-reference.md` current; money is never a `double`; tenant isolation is structural.

---

## 8. How this thread works

Unchanged from the previous handoff, because it keeps paying: **read the actual source first, let it
falsify the parent spec, ask the user the one or two genuinely material questions, write the LLD,
amend the parent spec in place with a new PF finding, commit.**

Every significant finding this session came from the first read of the code contradicting a document
written days earlier — that there is no `@Scheduled` anywhere, that RLS is never forced, that
`SecurityConfig` takes the filter by constructor, that ShedLock has no table. None of it was visible
from the documents alone.
