# Handoff — AWS re-platform design thread

**Session:** 2026-08-19 → 2026-08-20
**Nature:** **Design only. Zero code changed.** No migrations, no tests touched. The 231-test
baseline is exactly where it was at `908d9e6`.
**Start a new chat with:** "Read `docs/architecture/2026-08-20-aws-redesign-handoff.md`."

> **Continued by** [`2026-08-26-platform-modules-handoff.md`](2026-08-26-platform-modules-handoff.md)
> — the platform-module and service-split thread. It adds detail below these decisions and
> revises three of them (P4 supersession, `platform-web`'s scope, and R9's timeout ladder).
> **It also carries three hard blockers on sub-projects listed in §5 below** — S1 blocks
> sub-project 2, S2 blocks 8, S5 blocks 6. Read it before picking one up from here.

---

## 1. What this session produced

Eight commits on `main`, all documentation:

| Commit | What |
|---|---|
| `15e9818` | AWS target architecture — the five-service ECS split |
| `4dfe65a` | Billing, plans and entitlements design |
| `aaccd40` | Fixed three blocking flaws found reviewing the AWS design |
| `9847b87` | Monorepo decision, ShedLock leases, relay scale-out trigger |
| `f9c5ecc` | CI/CD pipeline and database change management |
| `02cb917` | Outbox low-level design |
| `aa36616` | Outbox test plan and likely-bug catalogue |
| `524d9f9` | Outbox interview Q&A |

**Four new documents:**

| Doc | Size | What it is |
|---|---|---|
| `2026-08-19-aws-target-architecture-design.md` | 1,075 lines | The end state. D1–D15 decisions, F1–F17 + F12b findings, cost, the honest case against, and Part 5's sub-project decomposition |
| `../superpowers/specs/2026-08-19-billing-and-entitlements-design.md` | 428 lines | Tiers, per-seat pricing, entitlement layer, metering, Chargebee, Indian payment/tax compliance. B1–B11, BF1–BF11 |
| `2026-08-19-outbox-lld.md` | 504 lines | Class-level design of `platform-outbox`, the request flow, the test plan, and 14 likely bugs. OF1–OF5, TB1–TB14 |
| `2026-08-19-outbox-interview-qa.md` | 24 Q&A | Interview prep. **Read its opening note** — Section D describes predicted failures, not production history |

**Also published:** an infrastructure diagram artifact with six hand-authored SVG figures —
https://claude.ai/code/artifact/903c9fde-6a1f-4718-b305-76e2d32e1a14

---

## 2. Decisions made — do not relitigate without cause

Full rationale and rejected alternatives are in each doc's Part 0 / decision table.

**Architecture (D1–D15):**

- **Five services**: identity (+ billing), master-data, sales, document, notification. `sales` is
  deliberately *not* split further — `accept` creating an order stays one local transaction.
- **One RDS Postgres**, schema per service, role per service, plus a `shared` schema every service
  may read. RLS, `@TenantId` and ArchUnit survive unchanged.
- **No Kinesis, no DMS CDC.** Reversed from the original request after the arithmetic — outbox →
  relay → SNS FIFO → per-consumer SQS FIFO instead.
- **In-process `@Scheduled` + ShedLock** for jobs; scale on request rate and backlog age, never CPU.
- **ECS Service Connect**; **one CloudFront distribution** for SPA + API + public routes.
- **Monorepo**, Gradle multi-project, path-filtered *deploys* (not tests).
- **CodeDeploy blue/green**, except `notification` which has no ALB and stays rolling.
- **Migrations pre-deploy** as a one-off task, owner role, direct to RDS; expand/contract mandatory.
- **Terraform owns roles and credentials; Flyway owns schemas, grants and objects.**
- **RS256 + JWKS** replacing HS256 — now a prerequisite, not hygiene, because the JWT carries
  entitlement claims.

**Billing (B1–B11):**

- Hybrid pricing: flat tier including N seats, per-seat above. Free / Pro ₹1,499 / Enterprise.
- A seat is any ACTIVE `app_user`, owner included. Seats auto-expand with **true proration
  settled on the next invoice** (not immediately — RBI one-off authorisation rules).
- **Chargebee Starter on Razorpay.** Chargebee issues the GST invoice.
- Limits derived by counting source tables; counters only for what has no source row.
- **Limits enforced on create, never on read.** Trial expiry downgrades to Free, not SUSPENDED.
- **Never block signup or a product write on the billing vendor.**
- `audit_log` removed from the target; `LOGIN_FAILED` becomes a CloudWatch metric filter.

---

## 3. Findings worth carrying forward

**Three that are about the code as it exists today, not the AWS design:**

- **F11 — a live bug.** `QuotationVersion` freezes items, totals and `placeOfSupply`, but
  `QuotationPdfService` reads buyer name/GSTIN/address *live*. Edit a customer's address and a
  re-rendered `SENT` quotation differs from the one they received — including through a public
  share link they already hold. Fix is a frozen `buyer_snapshot`. Independent of all AWS work.
- **BF5/BF6** — `tenant.plan` is in the design spec's data model but no migration ever added it,
  and nothing in the codebase ever transitions a tenant out of `TRIAL`.
- **BF7** — user invitations don't exist, so per-seat billing has an unbuilt hard prerequisite.

**The one that took two attempts to get right:**

- **F12 → F12b → OF1.** The relay must read the outbox across all tenants. RLS blocks it (F12,
  fixed with a `BYPASSRLS` `relay_app` role bounded by grants). **Hibernate's `@TenantId` blocks it
  again**, at a different layer, for a different reason (F12b/OF1 — fixed by making the read path
  `JdbcTemplate` on a separate DataSource). Both failures are silent. This is the single most
  important thing to remember about the outbox.

**Others most likely to bite:** F17 (blue/green doubles the DB connection budget), OF2
(`@TransactionalEventListener` silently restores the dual write), TB3 (a fresh `ObjectMapper` in
the outbox writer sends money as a JSON number, undoing challenges #2 and #17), TB11
(`save()` without `flush()` means the dedupe `catch` never fires), BF3 (default UPI mandate ceiling
is ₹5,000 — Pro plus nine seats is ₹5,090, silently breaking auto-debit).

---

## 4. What is NOT done

- **Nothing is built.** No Terraform, no service split, no outbox code, no billing code.
- The Gradle build is still one project under `backend/`.
- Splitting today's 25 migrations into five histories is unplanned work (sub-project 8).
- No implementation plan has been written for any sub-project — the `writing-plans` step was never
  reached.
- Several assertions rest on AWS/library behaviour I did not verify. **Read the "To verify"
  appendices before building anything**: parent doc Appendix B (10 items), outbox LLD Appendix B
  (6 items), billing spec Appendix B (7 items). The highest-stakes ones: RDS Proxy pinning
  behaviour, Chargebee's free-tier terms, and whether Hibernate's `@TenantId` filters silently or
  throws when no tenant resolves.

---

## 5. Where to pick up

Sub-projects are numbered continuously across the two design docs (parent Part 5, billing Part 7):

| # | Sub-project | Depends on |
|---|---|---|
| 1 | **Buyer snapshot** (F11) | — |
| 2 | AWS foundation — VPC, ECS, RDS + Proxy, CloudFront/ALB/WAF, CI/CD; deploy today's monolith | — |
| 3 | Observability | 2 |
| 4 | Scaling policies | 3 |
| 5 | Scheduled jobs (ShedLock) | app-only; 6 for event-emitting jobs |
| 6 | Outbox + relay + SNS/SQS + first consumer | 2 |
| 7 | Security hardening — RS256/JWKS, IAM auth to Proxy, WAF rules | 2 |
| 8 | Service extraction — `document` first | 2, 3, 6 |
| 9 | User invitations + seat counting | — |
| 10 | Entitlement layer | 9 |
| 11–13 | Chargebee integration, dunning state machine, billing analytics | 10 |

**Recommendation: #1 first.** It is small, fixes a real correctness bug, needs no AWS, and is a
prerequisite for extracting `document-svc` anyway. Then #2 → #3 → #4 and reassess — those four
deliver a properly observable, properly scaled production deployment of the system that exists
today, and the parent doc's Part 4 argues for stopping there until a trigger in §4.5 fires.

Whatever is picked, run the normal workflow: **brainstorming → writing-plans →
subagent-driven-development → finishing-a-development-branch**, on a feature branch off `main`.

---

## 6. Repo state and caveats

- **Branch `main`**, 8 commits ahead of where the session started (`908d9e6`).
- **`docs/superpowers/HANDOFF.md` has an uncommitted change that predates this session** — it
  documents the untracked `docs/architecture/` files. Left alone deliberately. It contains no
  pointer to this thread's docs; add one if a fresh agent should find them from the main handoff.
- **Four files in `docs/architecture/` remain untracked** and predate this session:
  `2026-07-29-current-architecture.md`, `2026-07-29-target-architecture.md`,
  `2026-08-05-interview-challenges-and-aws-kafka.md`, `interview-qa.md`. The main handoff §3 says
  they were left uncommitted deliberately for review. Still unresolved.
- The 2026-07-29 target-architecture doc argues *against* microservices for this product. That is
  not a contradiction — this thread's doc says the same thing in its Part 4 and designs the split
  anyway, deliberately.

**Working agreements** (from `CLAUDE.md`, unchanged): commit as `divyam`, never mention Claude or
AI in commit messages; log non-obvious problems to `engineering-challenges.md` in the same change;
keep `annotations-reference.md` current; money is never a `double`; tenant isolation is structural.

Nothing from this session belongs in `engineering-challenges.md` yet — it logs problems solved
while *building*, and nothing was built.
