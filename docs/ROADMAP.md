# EasyCRM — Execution Roadmap

**Date:** 2026-09-03
**Status:** Living document. Supersedes no design doc; sequences all of them.
**Code baseline:** `main` at `28f9ac8` — 586 tests, 0 failures, verified by `./gradlew clean check`.

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

**Verified at `28f9ac8`, not assumed.**

## 1.1 Application

| | State |
|---|---|
| Shape | One Spring Boot monolith. Boot 4.1.0, Java 25, Gradle. One extra Gradle module: `platform-primitives` |
| Packages | `sales` (88 classes), `iam` (38), `platform` (37), `catalog` (20), `crm` (15), `tenant` (7), `demo` (4) |
| Surface | 17 controllers. Two unauthenticated routes: `GET /public/q/{token}`, the invitation accept pair |
| Schema | 33 Flyway migrations, latest `V33__assigned_to_indexes.sql` |
| Wedge | enquiry → versioned GST quotation → order — **complete end to end and hardened**, plus activity/follow-up, nightly auto-expiry, PDF render and WhatsApp share |
| Multi-user | Invitations, accept, revoke, pending list; members list / change-role / disable / enable |
| Tests | 586 (558 root + 28 primitives), 0 failures |

## 1.2 Tenant isolation — the thing that is actually finished

Four layers, all built: `@TenantId` on every tenant entity; `TenantScopingArchTest` failing the
build on a new unscoped entity; Postgres RLS `ENABLE` + `FORCE` on all sixteen tenant tables;
`RlsCoverageIntegrationTest` as an independent layer-3 twin keyed on the `tenant_id` **column**
rather than the annotation. Record-level visibility (`assigned_to`) sits on top, funnelled through
a single `VisibleFinder` and guarded by `VisibilityScopingArchTest`.

## 1.3 Build and CI

| | State |
|---|---|
| Gate | `./gradlew clean check` = test + Spotless + SpotBugs (+find-sec-bugs) + JaCoCo, both projects |
| CI | GitHub Actions, `push: [main]` and `pull_request`, JDK 25, Testcontainers |
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
| **H1** | **`QuotationVersion` does not freeze the buyer.** `QuotationPdfService` reads `businessName`, `gstin`, `billingAddress` **live** from `customer` at render time. Edit a customer's address and a `SENT` quotation renders differently — through a public share link the buyer already holds | F11 · **live correctness bug** |
| **H2** | Rate-limit store is in-process. N app instances multiply every configured limit by N, silently | HANDOFF §8 |
| **H3** | The 00:30 IST expiry sweep takes no distributed lock. N instances all sweep every tenant. Fails safe (`@Version` blocks double-writes) but duplicates all the work | HANDOFF §8 |
| **H4** | `platform.visibility` and `platform.job` import `crm`/`sales`/`tenant` — three dependency cycles. Blocks service extraction | MF1/MF2 |
| **H5** | No index behind `assigned_to = :me OR assigned_to IS NULL` on `customer`/`enquiry`; no index supports a status-only order-list filter | HANDOFF §8 |
| **H6** | `SALES_MANAGER` is invitable but collapsed into the unrestricted visibility tier. The spec's three-tier rule is unbuilt | HANDOFF §8 |

---

# Part 2 — The eight tracks

What "finished" means per track. Tracks run concurrently; Part 3 sequences them.

### A — Application
The product a distributor uses. Wedge complete; remaining: buyer snapshot (H1), cursor pagination,
the `SALES_MANAGER` tier (H6), password reset and self-service profile, and whatever the frontend
demands once it is real.

### B — Build and CI
Wave 1 done. **1.5** supply chain (`gitleaks`, Dependabot/Renovate, OWASP Dependency-Check,
`squawk`, `actionlint`, and Trivy once a Dockerfile exists). **1.6** module boundaries (Modulith,
M1–M7). **Then the character change:** CI must become a *pre-merge gate* — PRs plus branch
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

**The naming decision is therefore open and is now the gating question for item 2.** A prefixed
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
1. **Buyer snapshot (SP1)** — freeze `buyer_snapshot` JSONB into `QuotationVersion`. Fixes H1;
   prerequisite for extracting `document-svc`; also folds in S2's primary-contact freeze so the
   share path stops reading `crm` live. One migration, done once.
2. **Wave 1.5 — supply chain.** Cheapest real security value on a public repo shipping JWT auth,
   bcrypt and GST data.
3. **Wave 1.6 — module boundaries.** Modulith `verify()` + `Documenter`, and the H4 cycle fix that
   comes with it.
4. **Domain + static marketing site (track H).** Register the domain, stand up Cloudflare Pages with
   TLS, ship a one-page site with a WhatsApp CTA. **Runs in parallel — it consumes none of the
   backend queue**, and the domain decision gates every durable public URL the product mints.

*Exit: the known correctness bug is gone, the supply chain is scanned, the service boundaries are
enforced by the build rather than asserted in prose, and EasyCRM exists on the internet.*

## Phase 1 — Make it a product
5. **Frontend foundation** — React + TypeScript, client generated from `docs/api/openapi.yaml`,
   auth shell (login/refresh/logout), and **`/invite/{token}` first**, since links are already
   being pasted into WhatsApp with no page behind them. It ships at `app.<domain>`, on the domain
   item 4 already settled and proved.
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
18. Prod environment, blue/green deploys (F17: it doubles the connection budget, not just compute),
    backup/restore rehearsed (R7), alarms routed, a runbook that exists.
19. Pilot tenants.

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
| **Application** | Wedge end-to-end, multi-user, activity/follow-up, auto-expiry, PDF + share, 586 tests | Buyer snapshot (H1), cursor pagination, `SALES_MANAGER` tier (H6), password reset, self-service profile |
| **Frontend** | Nothing. A drift-guarded contract to build against | Everything. `/invite/{token}` first |
| **Public presence** | Nothing — no domain, no site | Domain (~₹1,000/yr), Cloudflare Pages + TLS, one-page site, WhatsApp CTA |
| **Build/CI** | Wave 1, OpenAPI contract + guard, oasdiff changelog | Wave 1.5, Wave 1.6, blocking oasdiff, branch protection, 32 SpotBugs findings |
| **Local dev** | Gradle + Testcontainers + ngrok | Dockerfile, compose stack, seed data |
| **AWS** | Design only. Zero resources | SP2, SP3, SP4, SP7, all three environments, CD |
| **Observability** | Nothing | Wave 2 (app), SP3 (AWS) |
| **Auth** | bcrypt, HS256, rotating refresh, rate limiting, RLS, I1–I5 decided | SP7 (RS256/JWKS, IAM auth, WAF) |
| **Load/chaos** | Nothing | k6 baseline, AWS FIS suite — both need staging |
| **Split** | Design only. `platform-primitives` is the one extracted module | SP6, SP8, LLDs #2–#6 — **all conditional on a §4.5 trigger** |

---

# Part 6 — Recommended priority

Ranked. **Effort** is relative, not calendar.

| # | Item | Why now | Effort | Blocked by |
|---|---|---|---|---|
| **1** | **Buyer snapshot (SP1)** | The only **live correctness bug**. A `SENT` quotation silently re-renders differently after a customer edit, through a link the buyer holds. Both AWS docs say do it first "regardless of whether anything else happens", and it has been open since 2026-08-19 while nine slices landed around it | S | — |
| **2** | **Domain + static marketing site** | **The only item on this list that acquires a customer.** A weekend, ~₹1,000/yr, free hosting. It also settles the domain, and `easycrm.public-base-url` feeds the share and invite links that get pasted into WhatsApp and stay in other people's chat history — picking it after the frontend ships means stranding them. **Parallel: consumes none of the backend queue** | S | — |
| **3** | **Wave 1.5 — supply chain** | Cheapest real security value. Public repo, JWT auth, bcrypt, GST data. Finishes a programme already half-built | S | — |
| **4** | **Wave 1.6 — Modulith + cycle fix** | H4 blocks SP8, and nothing in the build has ever checked a boundary — two inversions landed in three days unnoticed. Cost rises with every slice added first | S–M | — |
| **5** | **Frontend** | The biggest product step and the only one a backend slice cannot finish. The contract is ready and guarded; building now means the contract shapes the client rather than the reverse | **L** | — (wants decomposing before a spec) |
| **6** | **Containerise** | Small, unblocks Trivy and every AWS item | S | — |
| **7** | **Wave 2 — observability** | Prerequisite for scaling, for reading load tests, and for seeing H2/H3 at all | M | — |
| **8** | **SP2 — AWS foundation + dev env** | Largest single piece; delivers a production-shaped deployment on its own. Fix S1 inside it | **L** | 6 |
| **9** | **Branch protection** | The day CD exists, a red `main` deploys itself | S | 8 |
| **10** | **SP7 — RS256/JWKS + hardening** | Splits token *minting* from *verification* (S7) before five roles could reach one signing key | M | 8 |
| **11** | **SP3 + staging environment** | Where load and chaos become possible | M | 8 |
| **12** | **H2 + H3 fixes (Redis store, ShedLock/SP5)** | Must precede any N>1 run, or the load test measures the wrong system | S–M | 11 |
| **13** | **Load baseline** | Decides cursor pagination and the H5 indexes on evidence rather than by elimination | M | 11, 12 |
| **14** | **Chaos via AWS FIS** | Task kill, AZ, RDS/Proxy failover; validates graceful shutdown and timeout ordering | M | 11 |
| **15** | **SP4 — scaling policies** | Scale on metrics you now emit | S | 7, 11 |
| **16** | **Production + pilot** | **The AWS design recommends stopping here** | L | 13–15 |
| — | SP6 outbox · SP8 extraction · SP10–13 billing · `platform-*` split · notification-svc | **Conditional.** Do not schedule against a date | XL | a §4.5 trigger |

## 6.1 If you only do four things

**1, 2, 3, 4.** All small, none blocked. Together they close the only live correctness bug, put
EasyCRM on the internet under a name that will not change, scan a public repo's supply chain, and
stop the module graph drifting further before the frontend doubles the surface. Then take **5**
with its own session and a decomposition pass.

**Item 2 is the one to start today if the queue is contended**, because it is the only item that
brings a customer and the only one that does not touch Java.

## 6.2 Sequencing traps

- **Do not start AWS before observability.** SP4 depends on SP3 for a stated reason: you cannot
  scale on metrics you do not emit. The same applies to reading a load test.
- **Do not load-test before fixing H2/H3.** N>1 multiplies every rate limit and duplicates the
  nightly sweep. The numbers would be wrong in a direction that looks fine.
- **Do not extract a service before H4.** `platform` currently imports `sales` entities; every
  service would inherit them.
- **Do not mint public links on a domain you have not decided.** `easycrm.public-base-url` feeds
  `/public/q/{token}` and `/invite/{token}`; both end up in someone else's WhatsApp history. The
  domain is cheap and the redirect you would otherwise owe is permanent.
- **Do not put Cloudflare's proxy in front of CloudFront.** Two CDNs in series, and F10's argument
  that the `/api/*` cache policy is a security control stops holding. `app.<domain>` is DNS-only.
- **Do not treat sub-project numbering as priority.** SP9 (invitations) is done, SP1 is not.

---

# Part 7 — Open decisions that gate work

| # | Decision | Gates |
|---|---|---|
| **D-a** | **S5 — `notification-svc` has no schema**, yet at-least-once consumption requires a `processed_event` table. It is "a name, not a scope" until its four open questions (provider abstraction, template store, suppression/quiet hours, delivery-status ingestion) are answered | SP6, SP8 |
| **D-b** | **S8 — `QuotationSent` carries the full render payload** and is bounded by SNS's 256 KB limit. Decide the claim-check fallback *before* the first extraction | SP6 |
| **D-c** | **PF19 — entitlement metering.** `/public/q/{token}` has no JWT, so there is structurally nowhere to charge the most expensive uncapped operation. Needs billing's *design* decisions, not effort | SP10–13 |
| **D-d** | **Frontend decomposition.** Unscoped and large enough to need sub-projects before a spec | Item 4 |
| **D-e** | **M7 — packages or Gradle modules as the boundary source of truth.** Both can grow rules; only one may own them | Wave 1.6, LLDs #2–#6 |
| **D-g** | **The domain name itself, and the DNS provider.** All four obvious candidates are taken (track H has the RDAP results and the costs); the live choice is a prefixed `.com`, a `.in` at a second registrar, or buying `eazycrm.com` off Afternic at an unknown price. The AWS design names ACM and `us-east-1` (F15) but never a DNS provider, and `easycrm.public-base-url` still defaults to `http://localhost:8080`. Both share links and invite links are durable and get pasted into WhatsApp | Items 2 and 5; every public URL the product mints |
| **D-f** | Two Boot 4 / Postgres behaviours the platform LLDs rest on: whether `java-test-fixtures` reaches package-private main-source members, and whether Postgres ORs permissive RLS policies. If policies AND, the outbox relay reads zero rows | SP6, LLDs #2–#6 |
