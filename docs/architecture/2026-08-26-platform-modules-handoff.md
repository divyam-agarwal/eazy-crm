# Handoff — platform modules / service-split design thread

**Session:** 2026-08-24 → 2026-08-26
**Nature:** **Design only. Zero code changed.** No migrations, no tests touched. The 231-test
baseline is exactly where it was at `80e74a3`.
**Start a new chat with:** "Read `docs/architecture/2026-08-26-platform-modules-handoff.md`."

**Continues from:** [`2026-08-20-aws-redesign-handoff.md`](2026-08-20-aws-redesign-handoff.md) — read
that first if you have not. Its decisions (D1–D15, B1–B11, F1–F17) are still in force; this thread
adds detail below them and revises nothing except where stated in §4.

---

## 1. What this session produced

Eight commits on `main`, all documentation:

| Commit | What |
|---|---|
| `0d222ee` | Per-service scope and shared module decomposition |
| `3cbdad4` | Shared platform module boundaries and flows — the parent spec |
| `d95afd0` | `platform-primitives` LLD, and the P4 revision it forced |
| `28f568a` | Why `platform-primitives` does not use JavaMoney |
| `0d37a15` | Reframe the money module as a numeric-precision serialiser |
| `cba24db` | Correct `ValidationException` field map type |
| `e4c38f6` | `platform-web` LLD — the module widens to cover outbound HTTP |
| `4736571` | Third-party IdP evaluation — decided against, for now |

**Five new documents:**

| Doc | Lines | What it is |
|---|---|---|
| `2026-08-24-service-scope-and-shared-modules.md` | 491 | Per-service surface: routes, events emitted/consumed, platform imports, sync dependencies. Findings S1–S10 |
| `../superpowers/specs/2026-08-26-shared-platform-modules-design.md` | ~395 | **The parent spec.** Six modules, the dependency DAG, every flow through platform code. P1–P9, PF1–PF10 |
| `2026-08-26-platform-primitives-lld.md` | 434 | LLD #1. MF1–MF6, MB1–MB6, rules R1–R3 |
| `2026-08-26-platform-web-lld.md` | 365 | LLD #2. WF1–WF6, WB1–WB6, rules W1–W3 |
| `2026-08-26-identity-provider-evaluation.md` | 261 | Okta / Auth0 / Cognito / OSS evaluated. I1–I5, IF1–IF7 |

**Also published:** an artifact of LLD #1 —
https://claude.ai/code/artifact/7db6d17f-9618-4ac5-87b5-c156453bfa6f

---

## 2. Where to pick up

**All six LLDs are written** as of 2026-08-27. Nothing in the queue below is outstanding; the next
step is `writing-plans`, which this thread has still never reached.

| # | Module | Status |
|---|---|---|
| 1 | `platform-primitives` | **done** |
| 2 | `platform-web` | **done** |
| 3 | `platform-security` | **done** — `2026-08-26-platform-security-lld.md` |
| 4 | `platform-tenancy` | **done** — `2026-08-27-platform-tenancy-lld.md` |
| 5 | `platform-outbox` | **done** — revised in place, see its Part 0 |
| 6 | `platform-entitlement` | **done** — `2026-08-27-platform-entitlement-lld.md` (still *built* in sub-project 10) |

**Read this before planning any of it:** the four later LLDs found three things that outrank the
module work itself — PF14 (RLS is `ENABLE`d and never `FORCE`d, so isolation rests on a role
assignment), PF17 (`BYPASSRLS` on the outbox relay could not be constrained by that fix, now replaced
by per-table policies), and PF19 (the metric set does not respect the create/read boundary, and
`/public/q/{token}` is a metered route with nowhere to put a check). All three are in the parent
spec's Appendix A.

All four questions this section originally set for LLD #3 are answered in that document: the
`VerifiedClaims` shape, both halves of the RS256 seam, the issuer as a configuration input (I4/I5),
and `JwtService.mint` leaving for `identity-svc`.

**How this thread has been working**, and it has worked well: read the actual source first, let it
falsify the parent spec, ask the user the one or two genuinely material questions, write the LLD,
amend the parent spec in place with a new PF finding, commit. Three of the first two LLDs' biggest
findings came from the first read of the code contradicting a document written a day earlier.

---

## 3. Decisions made — do not relitigate without cause

**Module structure (P1–P9, in the parent spec's Part 0):**

- **Six modules**, split by change cadence: `platform-primitives`, `platform-tenancy`,
  `platform-web`, `platform-security`, `platform-outbox`, `platform-entitlement`.
  (`platform-db` is Flyway ordering, not Java.)
- **`platform` owns all six tenant-context entry points** and auto-configures them — HTTP filter,
  public-token resolve, credential flow, `@Scheduled` tenant loop, SQS consumer, `@Async`. Five of
  the six fail *silently*; a service that never writes an entry point cannot get one wrong.
- **`TenantContext` is sealed**: `runAs` public, `set`/`clear` package-private. `ScopedValue`
  (JEP 506) was considered and rejected — same migration cost plus a primitive rewrite, for a
  failure mode the seal already closes.
- **Transport dependencies are `compileOnly`** with `@ConditionalOnClass` guards, so
  `notification-svc` never activates a filter chain it has no use for.
- **Three things leave `platform`:** `PdfEngine` and `IndianFormats` → `document-svc`;
  `JwtService.mint` → `identity-svc`.
- A second test was added to D12: **a mechanism used by exactly one service is not a platform
  mechanism.** It has no automated form (PF6) and stays a review rule.

**Identity (I1–I5):**

- **Do not adopt a third-party IdP now.** If one is ever adopted it is **Cognito**, and it verifies
  credentials only — it never mints the token.
- Trigger to revisit: **when the field-rep app (P5) or WhatsApp login needs OTP, social login or
  MFA.** Not before.

---

## 4. Revisions to earlier documents

Both were forced by reading the source, and both are recorded in place rather than silently applied.

- **P4 revised** (parent spec Part 0, and LLD #1 Part 0). The module is `platform-primitives`, not
  `platform-money`, and it also holds the five exception types. Cause: `Gstin` and `StateCode`
  import `ValidationException`, so the original grouping would have dragged the servlet stack into
  `notification-svc`. Recorded as **PF8**.
- **`platform-web` widened** to cover outbound HTTP as well as inbound. Cause: nothing in the
  six-module plan owned cross-service calls, and there is **no HTTP client anywhere in the codebase
  today**. Recorded as **PF9**.
- **The timeout ladder (parent doc R9) is extended inward** — 3 s internal call inside a 20 s task
  budget. R9 as written stops at the task boundary. Recorded as **PF10**; the 3 s is invented, not
  measured (**WF1**).

---

## 5. Blockers and live bugs to carry forward

**Three hard blockers on sub-projects already on the AWS plan.** None was visible from the ownership
tables; all three surfaced from writing the per-service surface down.

| # | Blocker | Blocks |
|---|---|---|
| **S1** | `/api/v1/tenant` (GET + PATCH, both built) is **absent from the ALB routing table**. Under the split it 404s at the edge, so the seller cannot edit the business name, GSTIN and address that appear on every PDF letterhead | sub-project 2 |
| **S2** | `ShareLinkService.whatsappLink()` reads the customer's primary `Contact` — a fifth cross-service call site the AWS doc's table of four misses. F13 solves the render path and leaves the share path with a live dependency on a schema `document_app` cannot reach. **Recommended fix:** fold the primary contact into the `QuotationSent` render payload, same migration as the buyer snapshot | sub-project 8 |
| **S5** | `notification-svc` is given **no schema**, but it consumes at-least-once and must write `processed_event` somewhere | sub-project 6 |

**Two live bugs in code that exists today, independent of all module work:**

- **F11** (from the earlier thread, still open) — `QuotationPdfService` reads buyer name/GSTIN/address
  live while `QuotationVersion` freezes everything else. Sub-project 1.
- **MF1** (new) — **the seller's GSTIN is never validated**, and the seller's state code is checked
  only as `@Pattern("\\d{2}")`. A *buyer's* GSTIN goes through `Gstin.parse` **and**
  `StateCode.requireValid` in `CustomerService`; the seller's goes through neither at signup. Since
  `QuotationService.isInterState` compares `tenant.getStateCode()` against the customer's to choose
  **CGST+SGST vs IGST**, an invalid seller state code silently decides the tax split of every
  quotation that tenant ever issues, and the unvalidated GSTIN prints on every PDF.

**Others most likely to bite:** MB1 (component scan stops finding `MoneyJacksonConfig` after the
split; money silently crosses the wire as a number — `MoneyWireFormatTest` is the only tripwire, and
MF4 notes it asserts through `ProductController`), WB1 (an unreachable downstream reading as
"customer not found"), PF7 (five of six entry points fail silently and no test proves an adapter was
used rather than bypassed), **IF1** (the Cognito "$0 at our scale" case rests on one unverified
number — check the console before anyone plans on it).

---

## 6. What is NOT done

- **Nothing is built.** No Gradle modules, no code moved, no `EventJson`, no client, no ArchUnit
  rules. The build is still one project under `backend/`.
- **No implementation plan** has been written for any of this — the `writing-plans` step has not been
  reached in this thread either.
- LLDs #3–#6 are unwritten (#5 is a revision of an existing doc).
- Several assertions rest on Boot 4 / Jackson 3 / ArchUnit behaviour that was **not** verified.
  **Read the "To verify" appendices before building anything**: LLD #1 Appendix B (6 items), LLD #2
  Appendix B (5 items), IdP evaluation Appendix A (7 items). The highest-stakes: which Boot 4
  artifact carries the Jackson auto-configuration, whether `java-test-fixtures` can reach
  package-private members, and whether `RestClient` interceptors see timeouts as exceptions.

---

## 7. Repo state and caveats

- **Branch `main`**, 8 commits ahead of where this session started (`34e19e2`).
- **Two tracked files have uncommitted modifications that did not come from this thread** —
  `docs/architecture/2026-08-19-aws-target-architecture-design.md` and
  `docs/superpowers/engineering-challenges.md`. They were left alone deliberately. Review them
  before committing anything else, and do not assume this thread's docs account for their content.
- **Four files in `docs/architecture/` remain untracked** and predate both design threads:
  `2026-07-29-current-architecture.md`, `2026-07-29-target-architecture.md`,
  `2026-08-05-interview-challenges-and-aws-kafka.md`, `interview-qa.md`. Still unresolved, as noted
  in the previous handoff.
- **Nothing belongs in `engineering-challenges.md` from this session.** That log records problems
  solved while *building*, and nothing was built.

**Working agreements** (from `CLAUDE.md`, unchanged): commit as `divyam`, never mention Claude or AI
in commit messages; log non-obvious problems to `engineering-challenges.md` in the same change; keep
`annotations-reference.md` current; money is never a `double`; tenant isolation is structural.
