# EasyCRM — Execution Roadmap

**Date:** 2026-09-03
**Status:** Living document. Supersedes no design doc; sequences all of them.
**Code baseline:** `main` at `f81362b` — **626 tests (598 root + 28 primitives), 0 failures**, verified by `./gradlew clean
check` from clean on the merged result. Wave 1.5 (item 2) merged fast-forward on 2026-09-12 and
the `supply-chain` branch was deleted; 591 before it, plus 13 in `SupplyChainWorkflowTest`. (The
buyer-snapshot branch merged the same way on 2026-09-08; the baseline before that was 586.)

**`main` is pushed and current at `f81362b` (2026-09-13).** Wave 1.6 merged fast-forward the same day
and the `wave-1.6-module-boundaries` branch was deleted; the baseline before it was 604. Wave 1.5's
scans first ran in CI earlier that day (run `34713136667`, green on all three jobs), so the "never
executed in CI" caveat that travelled with every Wave 1.5 claim is **discharged**.

**One thing to watch on the first `check` run from a different machine.** Wave 1.6 added
`ModulithDocsSnapshotTest`, which compares generated C4 documentation byte-for-byte and **fails**
rather than reports. It canonicalizes Modulith's unordered `Rel(...)` and `Component(...)` output before
comparing, which is what makes it viable — but cross-machine and cross-JDK-patch byte stability was
never measured locally. HANDOFF.md has the diagnostic path; the answer is never to delete the guard.

**Verify before relying on any of this** — `git rev-parse --short main`,
`git rev-parse --short origin/main` (as two separate invocations; passing both refs to one
`git rev-parse --short` call fails with `fatal: Needed a single revision` on this git), and
`git rev-list --count origin/main..main`. An earlier pass of this file claimed `main` was fully
pushed and was wrong, and a later pass got the commit count wrong twice.

This is the layer **above** `docs/superpowers/plans/`. Those are per-slice TDD implementation plans;
this is the programme that decides which slice is next and why. Every numbered item here gets its
own `brainstorming → writing-plans → subagent-driven-development → finishing-a-development-branch`
pass when it starts. **Nothing here replaces a spec.**

**Where the detail lives**

| Thread | Document |
|---|---|
| Session state, in-flight work | [`superpowers/HANDOFF.md`](superpowers/HANDOFF.md) |
| Product scope | [`superpowers/specs/2026-07-22-easycrm-design.md`](superpowers/specs/2026-07-22-easycrm-design.md) |
| AWS end state, sub-projects 1–8 | [`architecture/2026-08-19-aws-target-architecture-design.md`](architecture/2026-08-19-aws-target-architecture-design.md) |
| Billing, sub-projects 9–13 | [`superpowers/specs/2026-08-19-billing-and-entitlements-design.md`](superpowers/specs/2026-08-19-billing-and-entitlements-design.md) |
| Per-service surface | [`architecture/2026-08-24-service-scope-and-shared-modules.md`](architecture/2026-08-24-service-scope-and-shared-modules.md) |
| Outbox | [`architecture/2026-08-19-outbox-lld.md`](architecture/2026-08-19-outbox-lld.md) |
| Auth / IdP decision | [`architecture/2026-08-26-identity-provider-evaluation.md`](architecture/2026-08-26-identity-provider-evaluation.md) |
| Module boundaries | [`architecture/2026-09-03-spring-modulith-evaluation.md`](architecture/2026-09-03-spring-modulith-evaluation.md) |
| Build waves 1–3 | [`superpowers/specs/2026-09-01-build-hygiene-design.md`](superpowers/specs/2026-09-01-build-hygiene-design.md) |

---

# Part 1 — Where we are today

**Verified at `f81362b`, not assumed — `main` and `origin/main` are in sync as of 2026-09-13 with
Wave 1.6 merged.**

## 1.1 Application

| | State |
|---|---|
| Shape | One Spring Boot monolith. Boot 4.1.0, Java 25, Gradle. One extra Gradle module: `platform-primitives` |
| Packages | `sales` (88 classes), `iam` (38), `platform` (37), `catalog` (20), `crm` (15), `tenant` (7), `demo` (4) |
| Surface | 17 controllers. Two unauthenticated routes: `GET /public/q/{token}`, the invitation accept pair |
| Schema | 34 Flyway migrations, latest `V34__quotation_version_buyer_snapshot.sql` |
| Wedge | enquiry → versioned GST quotation → order — **complete end to end and hardened**, plus activity/follow-up, nightly auto-expiry, PDF render and WhatsApp share |
| Multi-user | Invitations, accept, revoke, pending list; members list / change-role / disable / enable |
| Tests | **626 (598 root + 28 primitives), 0 failures** — Wave 1.6 added 22 |

## 1.2 Tenant isolation — the thing that is actually finished

Four layers, all built: `@TenantId` on every tenant entity; `TenantScopingArchTest` failing the
build on a new unscoped entity; Postgres RLS `ENABLE` + `FORCE` on all sixteen tenant tables;
`RlsCoverageIntegrationTest` as an independent layer-3 twin keyed on the `tenant_id` **column**
rather than the annotation. Record-level visibility (`assigned_to`) sits on top: since Wave 1.6 it is
derived independently in `crm.CustomerVisibility` and `sales.SalesVisibility` — deliberately, for
split-readiness, since post-split those are separate deployables that cannot share a decision — with
`VisibilityScopingArchTest` naming exactly one permitted reader per guarded repository and a
test-scope `VisibilityContract` keeping the two interpreters in agreement.

## 1.3 Build and CI

| | State |
|---|---|
| Gate | `./gradlew clean check` = test + Spotless + SpotBugs (+find-sec-bugs) + JaCoCo, both projects. **Since Wave 1.6 it also carries six boundary guards: `ModuleDirectionArchTest` (H4 — `platform` depends on no domain package), `CrossDomainRepositoryArchTest` (Layer 2 register), `VisibilityScopingArchTest` (one permitted reader per guarded repository), `ModularityTest` (Modulith `verify()`, with its OPEN blind spot documented), `ModulithDocsSnapshotTest` (C4 docs drift), plus the pre-existing tenant/activity/primitives rules. Each has a non-vacuity assertion beside it** |
| CI | GitHub Actions, `push: [main]` and `pull_request`, JDK 25, Testcontainers. Three jobs: `check`, `supply-chain` (gitleaks + actionlint + squawk, blocking), `dependency-check` (OWASP, reports rather than gates). **Note the trigger: a feature-branch push fires NOTHING — CI on a branch requires a PR, which is why this repo's gates are all post-merge** |
| Contract | `docs/api/openapi.yaml` committed, byte-for-byte drift-guarded by `OpenApiSnapshotTest`; oasdiff changelog in CI, `continue-on-error: true` |
| Debt | 32 baselined SpotBugs findings (26 are the defensive-copy family) |
| **Nature** | **Post-merge smoke alarm, not a pre-merge gate.** The repo has never used a PR for real work |

## 1.4 What does not exist at all

**Frontend** (zero lines) · **Dockerfile** · **Terraform / any IaC** · **any AWS account resource** ·
**dev, staging or prod environment** · **CD pipeline** · **structured logging, metrics or tracing** ·
**load tests** · **chaos tests** · **no domain, no website, no public presence of any kind** ·
**password reset** · **`/invite/{token}` page** (the token works;
nothing serves the URL that gets pasted into WhatsApp) · **outbox, SNS/SQS, any second service**.

## 1.5 Known live defects and hazards

| # | Item | Where |
|---|---|---|
| ~~**H1**~~ | ~~**`QuotationVersion` does not freeze the buyer.** `QuotationPdfService` reads `businessName`, `gstin`, `billingAddress` **live** from `customer` at render time. Edit a customer's address and a `SENT` quotation renders differently — through a public share link the buyer already holds~~ **FIXED 2026-09-07** — `c24ae5e`. `QuotationVersion` carries a `BuyerSnapshot` frozen at `send()`, the render path reads it, and `import com.easycrm.crm.Customer` is gone from `sales/pdf/`. F11 closed. See [`superpowers/specs/2026-09-07-buyer-snapshot-design.md`](superpowers/specs/2026-09-07-buyer-snapshot-design.md) | F11 · ~~live correctness bug~~ **closed** |
| **H2** | Rate-limit store is in-process. N app instances multiply every configured limit by N, silently | HANDOFF §8 |
| **H3** | The 00:30 IST expiry sweep takes no distributed lock. N instances all sweep every tenant. Fails safe (`@Version` blocks double-writes) but duplicates all the work | HANDOFF §8 |
| ~~**H4**~~ | ~~`platform.visibility` and `platform.job` import `crm`/`sales`/`tenant` — **12 cycles, measured, not three**. Blocks service extraction~~ **FIXED 2026-09-13** — Wave 1.6. `platform.visibility` was **deleted**, not inverted; `platform` now has **zero** outbound domain imports (was 18 across three files), enforced by `ModuleDirectionArchTest` plus a non-vacuity companion. Note the gate is that hand-written rule and **not** `ApplicationModules.verify()`, which cannot see these cycles once `platform` is OPEN — challenge #75. [Wave 1.6 spec](superpowers/specs/2026-09-13-wave-1.6-module-boundaries-design.md) | MF1/MF2 · ~~blocks SP8~~ **closed** |
| **H5** | No index behind `assigned_to = :me OR assigned_to IS NULL` on `customer`/`enquiry`; no index supports a status-only order-list filter | HANDOFF §8 |
| **H6** | `SALES_MANAGER` is invitable but collapsed into the unrestricted visibility tier. The spec's three-tier rule is unbuilt. The rule lives in two independently-derived interpreters (`crm.CustomerVisibility` and `sales.SalesVisibility`); building the tier into only one of them degrades `SalesVisibility.viaCustomer`'s restricted path to a bare `EXISTS (SELECT id FROM customer WHERE id = q.customer_id)` — a pure existence check letting a `SALES_EXEC` see every quotation whose customer row exists — and fails **OPEN**. Tripwire: flipping `SALES_MANAGER`'s expectation in `VisibilityContract.cases()` reddens both contract tests until both interpreters are edited | HANDOFF §8 |
| **H7** | **The seller is not frozen on a sent quotation, and the tax presentation depends on it.** `QuotationPdfService` reads the buyer from the frozen `BuyerSnapshot` but the seller — `businessName`, `gstin`, `address`, `phone`, `email` — **live** from `tenant`, two lines apart. Worse, `interState` is computed from the frozen `placeOfSupply` against the **live** tenant `stateCode`, so a tenant changing registered state flips an already-`SENT` quotation between CGST/SGST and IGST rows on re-render, through the public share link the buyer holds. `send()` guards the *customer's* `stateCode` divergence with a 422; nothing guards the seller's. H1's exact class, on the other side of the document. Found 2026-09-13 while auditing what `sales` reads from `tenant`; the buyer-snapshot spec never mentions the seller | **live correctness bug** · [Wave 1.6 spec §5.3](superpowers/specs/2026-09-13-wave-1.6-module-boundaries-design.md) |

---

# Part 2 — The eight tracks

What "finished" means per track. Tracks run concurrently; Part 3 sequences them.

### A — Application
The product a distributor uses. Wedge complete, buyer snapshot done (H1 closed, 2026-09-07);
remaining: cursor pagination, the `SALES_MANAGER` tier (H6), password reset and self-service
profile, and whatever the frontend demands once it is real.

### B — Build and CI
Waves 1 and 1.5 done — supply chain landed 2026-09-12 with `gitleaks`, `actionlint`, `squawk`,
Dependabot and OWASP Dependency-Check; Trivy still waits on a Dockerfile. ~~**1.6** module boundaries~~ **DONE 2026-09-13** — H4 closed, `platform.visibility` deleted, four
hand-written boundary gates plus Modulith (M1–M7 settled; M2 amended, since `verify()` cannot be the H4
gate while `platform` is OPEN — challenge #75). **Then the character change:** CI must become a *pre-merge gate* — PRs plus branch
protection — the moment CD exists, because from that moment a red `main` deploys itself.

### C — Local development
Today: Gradle plus Testcontainers, backend only. Target: `docker compose up` giving backend +
Postgres + frontend + seed tenant, one command, no AWS credentials, plus the existing
`backend-restart-ngrok-up` flow for WhatsApp-share testing against a real phone.

### D — AWS: dev, staging, prod
Terraform in `ap-south-1`; ECS Fargate, RDS + Proxy, CloudFront/ALB/WAF, ECR. Three environments —
dev ~$120/mo, staging ~$250/mo, prod $580–820/mo, **~$950–1,190/mo all in**. Note F15: the
CloudFront WAF and its ACM certificate must be created in `us-east-1` — a second aliased provider.
**Deploy the monolith unchanged first** (SP2). Nothing here requires the service split.

### E — Observability
Structured JSON logs; an MDC filter carrying `requestId`/`tenantId`/`userId`; redaction for
GSTIN/phone/email; Micrometer with `/actuator/prometheus`; tracing over OTLP; then CloudWatch
dashboards and alarms (SP3). **This is the prerequisite for both scaling and load-test
interpretation** — H2 and H3 are invisible today, and a load test you cannot read is a number, not
a result.

### F — Auth and security
Built: bcrypt, HS256 JWT, rotating SHA-256-hashed refresh tokens, per-IP rate limiting on the auth
and public routes, RLS everywhere. Target is **SP7**: RS256 + JWKS with the private key held by
identity alone (S7), IAM auth to RDS Proxy, WAF rate rules, cache-policy tests (F10).
**Decision I1 stands: no third-party IdP.** If one is ever adopted it is Cognito (I2), never
self-hosted (I3), and **minting is never delegated — only verification** (I5), because the
`tenant_id` claim drives `@TenantId` and RLS. Keep the `TokenVerifier` seam (I4) so that stays
cheap to reverse. Revisit only when the field-rep mobile app or WhatsApp login needs OTP/MFA.

### G — Testing: load and chaos, in staging
**Load** — k6 or Gatling against staging, driving the real wedge (quotation create → send → public
render), with the H5 indexes and offset-vs-cursor paging as the named hypotheses. Prod-shaped data
volumes, not synthetic uniformity.
**Chaos** — honest scoping: Chaos Monkey for Spring Boot has little to find in a monolith with no
downstream calls, so **the chaos that pays here is infrastructure chaos via AWS FIS**: task kill
mid-transaction, AZ impairment, RDS failover, Proxy failover, and a deliberate N>1 run to *prove*
H2 and H3 before they are fixed and to prove they are fixed after.

### H — Public presence and acquisition
Today: **nothing.** No domain, no website, no way for a distributor to discover EasyCRM. Target: a
static marketing site on a custom domain with TLS, hosted free — plus the domain itself, which the
product needs regardless of marketing.

**Host: Cloudflare Pages.** Free tier, unlimited bandwidth, custom domain, automatic TLS, deploy on
git push. **GitHub Pages is the equally-free fallback** (the repo is already public) with a ~100 GB/
month soft bandwidth cap and Let's Encrypt TLS. **Do not use Vercel's Hobby tier** — its terms
exclude commercial use, and a customer-acquisition site is commercial use.

**Cost: hosting is free, the domain is not.** Budget roughly ₹800–1,200/year for a `.in` or `.com`.
That is the entire cost of this track.

**Build it as plain HTML + CSS**, or Astro if components are wanted. No framework runtime, no
client-side rendering. The audience is tier-2/3 distributors on low-end Android over 4G; page
weight is the feature.

**Content:** what EasyCRM does, the wedge in three screenshots, pricing intent, and **one CTA —
WhatsApp**, via a `wa.me` link plus a phone number.

**No lead form. Two reasons, and the second is load-bearing.** A `wa.me` link converts better than
an email form for this audience — they already live in WhatsApp, and the product already mints
`wa.me` links. And a form collecting name, phone and business is **personal data under the DPDP
Act** — the exact filter that decided the identity-provider evaluation (§2 of that doc). Posting it
into a third-party form service outside India reopens a residency question that was closed on
purpose. A `wa.me` link collects nothing and stores nothing.

**This track is deprioritised as of 2026-09-12 — the *site* is item 16. The *name* is not.** The
rest of this section still describes what to build when the site's turn comes; the paragraph
immediately below is the half that stays on the critical path.

**Register the domain before the frontend, not alongside it.** `easycrm.public-base-url` is a single
property (`application.yml`, defaulting to `http://localhost:8080`) that feeds three things: the
`/public/q/{token}` share link, the `/invite/{token}` accept link (D10), and the OpenAPI `servers`
block. **The first two get pasted into WhatsApp and then live in other people's chat history.**
Changing the domain later strands them or forces a permanent redirect out of a URL namespace that
was reserved deliberately. Pick the name once, early, and let the marketing site be the thing that
proves the DNS works.

**The obvious names are gone. Checked 2026-09-05 by RDAP** (`rdap.verisign.com` for `.com`,
`rdap.nixiregistry.in` for `.in`; a nonsense control domain returned 404, so the method is sound):

| Domain | State | Registered | Expires | Notes |
|---|---|---|---|---|
| `eazycrm.com` | **taken** | 2025-01-18 | 2027-01-18 | Dynadot. **Afternic nameservers — listed on the aftermarket**, so it is buyable at a markup. Asking price not retrieved |
| `easycrm.com` | **taken** | 2002-03-30 | 2027-03-30 | GoDaddy; redirects to `/lander`, i.e. parked, not a business |
| `eazycrm.in` | **taken** | 2010-12-15 | **2026-12-15** | GoDaddy DNS, serves a page. Expiry is ~3 months out — worth re-checking in January, but assume it renews |
| `easycrm.in` | **taken** | 2017-08-04 | 2031-08-04 | DigitalOcean DNS, serves a page. Renewed out to 2031 — held deliberately |
| `easycrm.co.in`, `eazycrm.co.in`, `easycrmapp.com` | **taken** | | | |

**Available at the same check:** `geteasycrm.com`, `tryeasycrm.com`, `useeasycrm.com`,
`easycrmindia.com`, `eazycrmindia.com`, `easycrm.net`, `eazycrm.net`.

**Cost.** `.com` at Cloudflare Registrar is **at-cost — $10.44/year** ($10.26 registry + $0.18
ICANN, no markup, no renewal premium). **Verisign raises the wholesale fee to $10.97 from
1 November 2026**, and Cloudflare has no margin to absorb it, so registering before that date holds
the lower rate for one more year. `.in` runs roughly **₹250–₹1,100/year** depending on registrar
(GoDaddy ~₹599 first year, ~₹800 renewal), **plus 18% GST**, and **Cloudflare Registrar does not
appear to support `.in`** — so a `.in` means a second registrar. Compare *renewal* prices, not
first-year promotions. **These figures come from vendor pages and search results, not from a
completed checkout — re-check before buying.**

**The naming decision is therefore open. It gated item 2 when the site was item 2; since the 2026-09-12 reprioritisation it gates item 4, the frontend** — the site moved, the name did not. A prefixed
`.com` (`geteasycrm.com`, `tryeasycrm.com`) keeps one registrar and at-cost pricing; a `.in` reads
as local to the audience but costs more and adds a registrar. Buying `eazycrm.com` off Afternic is
the third option and the only one with an unknown price.

**DNS: one provider, chosen now.** If Cloudflare hosts the site, use Cloudflare DNS — apex CNAME
flattening points the root at Pages, and `app.<domain>` later CNAMEs to CloudFront **DNS-only (grey
cloud)**. Proxying Cloudflare in front of CloudFront puts two CDNs in series and invalidates F10's
reasoning that the `/api/*` cache policy is a security control. The AWS design names ACM and
`us-east-1` (F15) but **never names a DNS provider**, so this is an open decision (D-g), not a
contradiction of anything.

---

# Part 3 — Phased execution

Each phase ends somewhere it is safe to stop.

## Phase 0 — Close the gaps on `main` *(no new infrastructure)*
1. ~~**Buyer snapshot (SP1)**~~ — **DONE 2026-09-07, `c24ae5e`.** Three flat columns on
   `quotation_version` (`V34`) behind a `BuyerSnapshot` `@Embeddable`, frozen at `send()` and read
   by the PDF renderer. Fixes H1, closes F11, and removes the live `crm` read from the public
   render path — the prerequisite for extracting `document-svc`. **S2's primary-contact freeze is
   *not* folded in:** it was declined and rescheduled to whenever SP6 or SP8 needs it — see
   [design spec §7](superpowers/specs/2026-09-07-buyer-snapshot-design.md) and Appendix A of the
   service-scope doc. Design reversed D10's JSONB column to flat columns; see §3.1 of the spec.
2. ~~**Wave 1.5 — supply chain.**~~ **DONE 2026-09-12, merged at `7f6a700`.** `gitleaks`,
   `actionlint` and `squawk` blocking; Dependabot weekly; OWASP Dependency-Check reporting. See
   [`superpowers/specs/2026-09-12-supply-chain-design.md`](superpowers/specs/2026-09-12-supply-chain-design.md).
   **Not yet pushed — CI has never run these gates.**
3. ~~**Wave 1.6 — module boundaries.**~~ **DONE 2026-09-13.** Modulith `verify()` + `Documenter`, and the H4 cycle fix that
   comes with it.
4. **Settle D-g and register the domain — the name only, not the site.** The marketing site moved
   to the bottom of Part 6 on 2026-09-12; **the naming decision did not move with it.** Registering
   costs a day and ~₹1,000/yr, and it is a prerequisite of Phase 1 rather than of the site:
   `easycrm.public-base-url` feeds `/public/q/{token}` and `/invite/{token}`, and both get pasted
   into WhatsApp where they stay. Buy the name, point DNS at nothing, and let the site come later.

*Exit: the known correctness bug is gone, the supply chain is scanned, the service boundaries are
enforced by the build rather than asserted in prose, and the product has a name it will not have to
change.*

## Phase 1 — Make it a product
5. **Frontend foundation** — React + TypeScript, client generated from `docs/api/openapi.yaml`,
   auth shell (login/refresh/logout), and **`/invite/{token}` first**, since links are already
   being pasted into WhatsApp with no page behind them. It ships at `app.<domain>`, on the domain
   Phase 0 item 4 already registered. **Note what changed on 2026-09-12:** the marketing site used
   to be the thing that proved the DNS worked before the frontend needed it. With the site at item
   16, this is the first consumer of that domain — so the `app.<domain>` CNAME and its certificate
   get proved here, by this step, rather than inherited already-working.
6. **Frontend core flows** — customers/contacts, products/price lists, the wedge, PDF and share,
   activity timeline, members admin.
7. **Flip oasdiff to blocking.** Its documented trigger is "the frontend exists and consumes this
   spec." At that point there is a real party to break.

*Exit: a distributor can actually use EasyCRM, on a laptop.*

## Phase 2 — Make it shippable and legible
8. **Containerise** — Dockerfile plus `docker compose` local stack. Unblocks Trivy (deferred from
   item 2) and every AWS item.
9. **Wave 2 — observability, application side.** JSON logs, MDC, redaction, Micrometer, OTLP. Take
   Modulith's actuator endpoint and module spans here (M6), decided once alongside tracing.

*Exit: one command runs the whole stack locally, and the running system can be read.*

## Phase 3 — AWS, up to staging
10. **SP2 — AWS foundation, dev environment.** VPC, ECS, RDS + Proxy, CloudFront/ALB/WAF, ECR,
   Terraform, and CD. **Deploy today's monolith unchanged.** Fix S1 here — `/api/v1/tenant` is
   missing from the ALB routing table and it must be right the first time.
   Also set `lock_timeout` and `statement_timeout` on the migration connection here (the
   `easycrm_owner` role, or Flyway's connection init SQL). `backend/squawk.toml` switches off
   squawk's `require-lock-timeout`/`require-statement-timeout` on the argument that this is the
   right place for them — all 34 existing migrations are frozen by Flyway checksums and cannot
   carry a per-file `SET`, and all 34 run at this cutover. Until this is done, nothing enforces a
   lock timeout on this schema.
11. **Branch protection.** The moment step 10 auto-deploys `main`, post-merge CI stops being
    defensible. PRs and a required green `check`.
12. **SP7 — security hardening.** RS256 + JWKS, IAM auth to Proxy, WAF rules, cache-policy tests.
13. **SP3 — observability, AWS side.** ADOT sidecar, EMF metrics, dashboards, alarms.
14. **Staging environment** — production-shaped, one task per service.

*Exit: a real deployment pipeline, and a staging environment worth testing against.*

## Phase 4 — Prove it in staging
15. **Fix H2 and H3 before running anything multi-instance** — Redis-backed `RateLimitStore`, and
    ShedLock on the expiry sweep (**SP5**). A load test against N>1 without these measures the
    wrong system and reports the wrong limits.
16. **Load baseline** (k6/Gatling). Hypotheses named in advance: the two missing indexes (H5), and
    whether offset paging on eight published endpoints actually hurts — **this is what decides
    cursor pagination**, rather than deciding it by elimination.
17. **Chaos, via AWS FIS.** Task kill mid-transaction, AZ impairment, RDS and Proxy failover.
    Confirm graceful shutdown (R6) and timeout ordering (R9) behave as designed.
18. **SP4 — scaling policies.** Target tracking and scheduled scaling. You cannot scale on metrics
    you do not emit, which is why this follows 13.

*Exit: known behaviour under load and under failure, with the numbers to prove it.*

## Phase 5 — Production
19. Prod environment, blue/green deploys (F17: it doubles the connection budget, not just compute),
    backup/restore rehearsed (R7), alarms routed, a runbook that exists.
20. Pilot tenants.

*Exit: real users. **The AWS design's own recommendation is to stop here.***

## Phase 6 — Conditional, only on a trigger
**§4.5 of the AWS design lists the four triggers:** PDF render load diverging enough that a shared
scaling signal is unusable; more than ~three engineers contending on one pipeline; a tenant large
enough to justify dedicated isolation; a regulatory requirement to separate identity from business
data. **Until one fires, the modular monolith on ECS is the correct production answer** — the
design document says so about itself.

Then, in order: **SP6** outbox + relay + SNS/SQS + first consumer (resolve S5 first — notification
has no schema and cannot dedupe without one) → **SP8** service extraction, `document` first →
**SP10–13** entitlements and billing → the `platform-*` Gradle module split (LLDs #2–#6).

---

# Part 4 — The CI/CD pipeline, end state

```
 local              CI (GitHub Actions)              CD
 ─────              ────────────────────             ──
 docker compose up  on PR:                           on merge to main:
   backend            clean check (both projects)      build image → ECR
   postgres           gitleaks                         deploy dev
   frontend           dependency-check                 smoke test
   seed tenant        actionlint · squawk
                      oasdiff (BLOCKING, post-Ph.1)  on tag / manual:
 ./gradlew check      modulith verify                  deploy staging
 ngrok for WhatsApp   → required for merge             Flyway migrate
                                                       load + chaos suites
                    on schedule (nightly):
                      dependency-check DB refresh    on approval:
                      staging load baseline            blue/green to prod
```

**Three notes.**
- **Flyway and rolling deploys are mutually hostile** with `ddl-auto: validate` (F14). Migrations
  must be expand/contract, and that constraint starts at step 9, not at the first outage.
- **`docs/api/openapi.yaml` is generated output.** Never hand-edit; a merge conflict in it is
  resolved by regenerating, never by hand-merging YAML.
- The **dev** environment is where CD proves itself. Staging is for *testing the system*; dev is
  for *testing the pipeline*.

---

# Part 5 — What we have, what is left

| Track | Have | Left |
|---|---|---|
| **Application** | Wedge end-to-end, multi-user, activity/follow-up, auto-expiry, PDF + share, **buyer snapshot (H1 closed)**, 604 tests | Cursor pagination, `SALES_MANAGER` tier (H6), password reset, self-service profile |
| **Frontend** | Nothing. A drift-guarded contract to build against | Everything. `/invite/{token}` first |
| **Public presence** | Nothing — no domain, no site | Domain (~₹1,000/yr), Cloudflare Pages + TLS, one-page site, WhatsApp CTA |
| **Build/CI** | Wave 1, OpenAPI contract + guard, oasdiff changelog, Wave 1.5 supply chain (green in CI since 2026-09-13), **Wave 1.6 module boundaries — four hand-written boundary gates plus Modulith, C4 docs drift-guarded** | Blocking oasdiff, branch protection, 32 SpotBugs findings |
| **Local dev** | Gradle + Testcontainers + ngrok | Dockerfile, compose stack, seed data |
| **AWS** | Design only. Zero resources | SP2, SP3, SP4, SP7, all three environments, CD |
| **Observability** | Nothing | Wave 2 (app), SP3 (AWS) |
| **Auth** | bcrypt, HS256, rotating refresh, rate limiting, RLS, I1–I5 decided | SP7 (RS256/JWKS, IAM auth, WAF) |
| **Load/chaos** | Nothing | k6 baseline, AWS FIS suite — both need staging |
| **Split** | Design only. `platform-primitives` is the one extracted module. **Layer 3 (schema) is already clean — zero FK constraints in 34 migrations** | **Layer 1 DONE (Wave 1.6, H4 closed)**; **Layer 2 design owed (item 3b)**; then SP6, SP8, LLDs #2–#6 — **all conditional on a §4.5 trigger** |

---

# Part 6 — Recommended priority

Ranked. **Effort** is relative, not calendar.

| # | Item | Why now | Effort | Blocked by |
|---|---|---|---|---|
| ~~**1**~~ | ~~**Buyer snapshot (SP1)**~~ — **DONE 2026-09-07, `c24ae5e`** | Was the only **live correctness bug**: a `SENT` quotation silently re-rendered differently after a customer edit, through a link the buyer holds. Open since 2026-08-19 while nine slices landed around it. Closed by freezing the buyer onto `QuotationVersion` at `send()`; H1 and F11 are both closed. **Item 2 is done too, as of 2026-09-12; item 3 is next.** | S | — |
| ~~**2**~~ | ~~**Wave 1.5 — supply chain**~~ — **DONE 2026-09-12, merged fast-forward at `7f6a700`** (eleven commits, `5053d42`..`7f6a700`; 604 tests, 0 failures). `gitleaks`, `actionlint` and `squawk` block; Dependabot opens weekly PRs; OWASP Dependency-Check reports without blocking (D1, flip trigger = branch protection). None is wired into `./gradlew check` — `SupplyChainWorkflowTest`'s 13 assertions are what make that safe, and they guard against `if:`, `continue-on-error`, `\|\| true`, a shallow `fetch-depth` and floating tags, not merely against a step's absence. **Still unpushed, so CI has never run any of it.** | Cheapest real security value. Public repo, JWT auth, bcrypt, GST data. Finishes a programme already half-built | S | — |
| ~~**3**~~ | ~~**Wave 1.6 — module boundaries + cycle fix**~~ — **DONE 2026-09-13**, [spec](superpowers/specs/2026-09-13-wave-1.6-module-boundaries-design.md) · [plan](superpowers/plans/2026-09-13-wave-1.6-module-boundaries.md). Twelve commits; **598 tests, 0 failures**; `clean check` green end to end. **H4 closed:** `platform.visibility` deleted rather than inverted (its returns are domain aggregates, so no port in `platform` could name them — unlike `iam.AssignedWorkload`, which returns a `long`), the rule now derived independently per module from the JWT claim for split-readiness. The H4 gate is a hand-written ArchUnit direction rule, **not** `verify()`, because declaring `platform` OPEN suppresses all 12 cycles (challenge #75). A second rule registers cross-domain repository reads with an exit per edge (item 3b), Modulith is adopted for module detection and C4 docs with its blind spot documented, and the generated docs are committed and drift-guarded. Challenges #75–79 | S–M | — |
| **3b** | **Cross-service data access design** (Layer 2) — **owed, does not exist** | Wave 1.6 closes Layer 1 (package acyclicity) and *freezes* Layer 2 rather than fixing it. Layer 2 is the actual extraction work and **no gate can see it** — a cross-service read is not a cycle. Four direct reads exist (`sales → crm.ContactRepository`, `sales → catalog.{Product,PriceListItem}Repository`, `sales`/`iam` → `tenant.TenantRepository`), plus the `viaCustomer` **cross-service SQL join** that Wave 1.6 relocates into `sales` where it looks local. Decide port-vs-event-vs-freeze per edge, and the `quotation`/`sales_order` owner denormalisation. **Ahead of SP8, which has no design for these edges** — the service-scope doc that names them is stale by its own §3.2. Layer 3 needs nothing: zero FK constraints in all 34 migrations | M | 3 |
| **4** | **Frontend** | The biggest product step and the only one a backend slice cannot finish. The contract is ready and guarded; building now means the contract shapes the client rather than the reverse | **L** | — (wants decomposing before a spec) |
| **5** | **Containerise** | Small, unblocks Trivy and every AWS item | S | — |
| **6** | **Wave 2 — observability** | Prerequisite for scaling, for reading load tests, and for seeing H2/H3 at all | M | — |
| **7** | **SP2 — AWS foundation + dev env** | Largest single piece; delivers a production-shaped deployment on its own. Fix S1 inside it | **L** | 5 |
| **8** | **Branch protection** | The day CD exists, a red `main` deploys itself | S | 7 |
| **9** | **SP7 — RS256/JWKS + hardening** | Splits token *minting* from *verification* (S7) before five roles could reach one signing key | M | 7 |
| **10** | **SP3 + staging environment** | Where load and chaos become possible | M | 7 |
| **11** | **H2 + H3 fixes (Redis store, ShedLock/SP5)** | Must precede any N>1 run, or the load test measures the wrong system | S–M | 10 |
| **12** | **Load baseline** | Decides cursor pagination and the H5 indexes on evidence rather than by elimination | M | 10, 11 |
| **13** | **Chaos via AWS FIS** | Task kill, AZ, RDS/Proxy failover; validates graceful shutdown and timeout ordering | M | 10 |
| **14** | **SP4 — scaling policies** | Scale on metrics you now emit | S | 6, 10 |
| **15** | **Production + pilot** | **The AWS design recommends stopping here** | L | 12–14 |
| **16** | **Domain + static marketing site** | **Deprioritised 2026-09-12 at the user's direction** — it acquires a customer but there is no product to send them to yet, so the site waits until there is. **The naming half does NOT wait:** `easycrm.public-base-url` feeds `/public/q/{token}` and `/invite/{token}`, both of which live in other people's WhatsApp history, so **D-g must still be settled before item 4 ships any public link** — otherwise the redirect you owe is permanent. Registering the name is a day; the site is the part that moved | S | — (but see D-g, which gates item 4) |
| — | SP6 outbox · SP8 extraction · SP10–13 billing · `platform-*` split · notification-svc | **Conditional.** Do not schedule against a date | XL | a §4.5 trigger |

## 6.1 If you only do three things

**Items 1, 2 and 3 are all DONE, and pushing is done. The next three are: settle D-g, item 3b, and
item 4 (frontend) — with H7 arguably jumping the queue, see below.**

**Wave 1.6 landed 2026-09-13** (598 tests, `clean check` green; H4 closed). What it deliberately did
NOT do is Layer 2 — it *froze* cross-service data access behind a register rather than resolving it,
which is item **3b**, and 3b is the thing SP8 silently assumed existed.

**H7 arguably outranks everything below it on severity.** It is a live correctness bug of H1's exact
class — the seller is not frozen on a sent quotation, and the *tax presentation* is computed against
the live tenant `stateCode`, so a tenant changing registered state flips an already-`SENT` quotation
between CGST/SGST and IGST on re-render, through a share link the buyer holds. It is small (a seller
snapshot beside the existing buyer one) and it mis-states tax on a document a customer already has.
It was found by Wave 1.6's design pass, not by its build, and recorded rather than fixed because it
needs its own freeze decision.

**The older standing advice still holds for the rest:** Items 1 and 2 are both done and merged. Wave 1.6 is designed as of
2026-09-13 and unblocked, and stops the module graph drifting further before the frontend doubles
the surface. Settling D-g is not a build item at all — a decision plus a registrar checkout — and it
is the one thing on this page that gets more expensive the longer it waits, because every public
link minted before it is settled is a link you later have to strand or permanently redirect.

**Two things moved onto the board on 2026-09-13, both found by designing Wave 1.6 rather than by
building it.** **H7** is a live correctness bug of H1's exact class — the seller is not frozen on a
sent quotation and the *tax presentation* is computed against the live tenant `stateCode`, so it
outranks most of this list on severity even though it is small. And **item 3b** is a design that
does not exist and that SP8 silently assumed: Wave 1.6 can only *freeze* cross-service data access,
not fix it.

**The third is not a slice: push.** Thirteen commits sit on local `main`, including every scan
Wave 1.5 added, and CI has executed none of them. Until that push happens the supply-chain gates
exist only on one laptop, which is very nearly the same as not existing.

Then take **4 (frontend)** with its own session and a decomposition pass.

**What changed on 2026-09-12:** the domain-and-marketing-site item was item 2 and is now item 16.
The reasoning that put it at 2 — that it is the only item that acquires a customer — still holds,
and was outweighed: there is no product to send an acquired customer to until item 4 ships. The
site waits for the product. The name does not wait for anything.

## 6.2 Sequencing traps

- **Do not start AWS before observability.** SP4 depends on SP3 for a stated reason: you cannot
  scale on metrics you do not emit. The same applies to reading a load test.
- **Do not load-test before fixing H2/H3.** N>1 multiplies every rate limit and duplicates the
  nightly sweep. The numbers would be wrong in a direction that looks fine.
- **Do not extract a service before H4.** `platform` currently imports `sales` entities; every
  service would inherit them.
- **Do not mint public links on a domain you have not decided** — and note this trap survived the
  site's deprioritisation unchanged. `easycrm.public-base-url` feeds `/public/q/{token}` and
  `/invite/{token}`; both end up in someone else's WhatsApp history. The domain is cheap and the
  redirect you would otherwise owe is permanent. Moving the *marketing site* to item 16 does not
  move the *naming decision*: that is still owed before the frontend ships a public link.
- **Do not put Cloudflare's proxy in front of CloudFront.** Two CDNs in series, and F10's argument
  that the `/api/*` cache policy is a security control stops holding. `app.<domain>` is DNS-only.
- **Do not treat sub-project numbering as priority.** SP9 (invitations) and SP1 (buyer snapshot)
  are both done while SP2–SP8 are not; the numbering records the order they were *designed*, never
  the order to build them.

---

# Part 7 — Open decisions that gate work

| # | Decision | Gates |
|---|---|---|
| **D-a** | **S5 — `notification-svc` has no schema**, yet at-least-once consumption requires a `processed_event` table. It is "a name, not a scope" until its four open questions (provider abstraction, template store, suppression/quiet hours, delivery-status ingestion) are answered | SP6, SP8 |
| **D-b** | **S8 — `QuotationSent` carries the full render payload** and is bounded by SNS's 256 KB limit. Decide the claim-check fallback *before* the first extraction | SP6 |
| **D-c** | **PF19 — entitlement metering.** `/public/q/{token}` has no JWT, so there is structurally nowhere to charge the most expensive uncapped operation. Needs billing's *design* decisions, not effort | SP10–13 |
| **D-d** | **Frontend decomposition.** Unscoped and large enough to need sub-projects before a spec | Item 4 |
| **D-e** | **M7 — packages or Gradle modules as the boundary source of truth.** Both can grow rules; only one may own them | Wave 1.6, LLDs #2–#6 |
| **D-g** | **The domain name itself, and the DNS provider.** All four obvious candidates are taken (track H has the RDAP results and the costs); the live choice is a prefixed `.com`, a `.in` at a second registrar, or buying `eazycrm.com` off Afternic at an unknown price. The AWS design names ACM and `us-east-1` (F15) but never a DNS provider, and `easycrm.public-base-url` still defaults to `http://localhost:8080`. Both share links and invite links are durable and get pasted into WhatsApp | Item 4 (frontend) and item 16 (the site); every public URL the product mints. **Deprioritising the site on 2026-09-12 did not deprioritise this decision** — it is now the gate in front of the frontend, not in front of the site |
| **D-f** | Two Boot 4 / Postgres behaviours the platform LLDs rest on: whether `java-test-fixtures` reaches package-private main-source members, and whether Postgres ORs permissive RLS policies. If policies AND, the outbox relay reads zero rows | SP6, LLDs #2–#6 |
