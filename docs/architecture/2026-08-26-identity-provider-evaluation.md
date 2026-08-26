# Third-party identity provider — evaluation and decision

**Date:** 2026-08-26
**Status:** **Decision — do not adopt a third-party IdP now.** Revisit on the trigger in §1.2.
**Code baseline:** `80e74a3`
**Feeds:** LLD #3 `platform-security` — the swappable-issuer seam in §5 is a requirement, not a nicety
**Related:** [`2026-08-19-aws-target-architecture-design.md`](2026-08-19-aws-target-architecture-design.md) D11 ·
[`../superpowers/specs/2026-08-19-billing-and-entitlements-design.md`](../superpowers/specs/2026-08-19-billing-and-entitlements-design.md) B7, BF8 ·
[`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md)

The question asked: should EasyCRM replace its self-built identity service with Okta, Auth0, Cognito
or similar, rather than managing its own?

---

# Part 1 — Decision

## 1.1 What was decided

| # | Decision | Why |
|---|---|---|
| **I1** | **Do not adopt a third-party IdP now.** Complete sub-project 7 (RS256 + JWKS) on the existing stack | Three independent reasons, in §4: only ~27% of the surface is replaceable, neither adoption shape is attractive, and the dangerous failure mode moves from reviewable code into vendor console configuration |
| **I2** | **If one is ever adopted, it is AWS Cognito** | It is the only managed option that survives the DPDP residency filter (§2) at a price this business can carry (§3) |
| **I3** | **Do not self-host an OSS IdP** | It relocates work rather than removing it, and hands a one-person team someone else's CVE cadence on the most security-sensitive surface in the product (§3.3) |
| **I4** | **Treat "swappable issuer" as a design requirement** in `platform-security` | Makes I1 cheap to reverse. `TokenVerifier.verify(token) → VerifiedClaims` against a published JWKS does not care who signed the token |
| **I5** | **Never delegate token *minting*, only credential *verification*** — if I1 is ever reversed | The `tenant_id` claim drives `@TenantId` and RLS; B7's entitlement claims must stay local. See §4.2 and §4.3 |

## 1.2 The trigger to revisit

**When the field-rep mobile app (P5) or WhatsApp-based login needs OTP, social login or MFA.** That
is real work Cognito would absorb and that would otherwise be built from scratch. Reassess then.

Not before. In particular, "we should use a managed service" is not on its own a reason — §4 is why.

---

# Part 2 — The filter that decided it: DPDP residency

ap-south-1 was chosen because India's DPDP Act makes data residency a requirement rather than a
latency preference. That infrastructure decision silently eliminated most of the identity market.

| Vendor | India region | Consequence |
|---|---|---|
| **AWS Cognito** | ✅ ap-south-1 since 2019 | **The only managed option that survives** |
| Auth0 — self-serve plans | ❌ US / EU / AU only | India requires Private Cloud, enterprise-priced, which destroys the cost case |
| Okta CIC | ✅ India cells, launched Jan 2026 | Disqualified on price instead (§3.2) |
| Clerk · WorkOS · Stytch · Descope | ❌ | Blocked as hosted SaaS |
| Zitadel Cloud · Ory Network | ❌ | Self-host is the only path |
| Keycloak · FusionAuth · SuperTokens · Zitadel OSS | ✅ by construction | Compliant, but you are operating it — see §3.3 |

Worth stating plainly because it inverts the usual build-vs-buy intuition: **the residency constraint
is what makes this a two-option decision, not the price.**

---

# Part 3 — Cost, at this scale

Figures are list prices as of 2026-08-26. Self-hosted compute figures are **directional estimates**,
not quotes — see Appendix A.

| Option | 500 MAU (launch) | 5,000 MAU | Against $580–820/mo infra |
|---|---:|---:|---|
| **Cognito Essentials** | **$0** | **$0** | free — but see IF1 |
| Auth0 B2B Essentials | $150 | $1,300 | 18–26% |
| Auth0 B2B Professional | $800 | $1,500 | **97–138%** |
| Okta CIC base platform | $3,000+ | $3,000+ | 366–517% |
| FusionAuth, self-hosted | ~$30–50 | ~$30–50 | plus operator time |
| Keycloak, self-hosted | ~$85–160 | ~$85–160 | plus operator time; 3 pods minimum |

## 3.1 Auth0 — the B2B track is the real price

Multi-tenancy requires **Organizations**, which puts you on Auth0's B2B price track rather than the
B2C numbers usually quoted — 3–4× more per MAU. At launch scale, B2B Professional costs about as
much as the entire AWS estate, and roughly half of theoretical maximum revenue if all 100 tenants
paid ₹1,499. The curve is backwards: worst exactly when the business can least afford it.

Technically it is capable — `org_id` maps to `tenant_id`, Actions can mint entitlement claims,
bcrypt import works. Two structural problems kill it regardless: **no India residency on any
affordable plan**, and **no SLA below Enterprise** against a tracked cadence of roughly 56 incidents
since Feb 2025.

## 3.2 Okta CIC — disqualified

A mandatory **$3,000/month** base platform, billed annually, with the B2B suite quote-gated on top.
Four to five times the entire infrastructure budget, roughly twice gross revenue at 100 tenants. It
is priced and sold for enterprises buying workforce and customer identity together.

Its January 2026 India residency is the one thing no other vendor offers — which is why it was
checked rather than dismissed on sight.

## 3.3 Self-hosted OSS — the security data undercuts the premise

The case for self-hosting is "compliant by construction, no per-MAU fee." The counter-evidence is
that adopting one means inheriting its patch cadence:

- **Keycloak** shipped a fix for a **critical CVSS 9.1 unauthenticated account-takeover** flaw in
  password reset on **19 August 2026** — days before this evaluation — after fixing twelve CVEs in
  the release a fortnight earlier. It also needs ~1.5–2 GB per pod, recommends three pods, and has a
  documented problem where Fargate rolling updates break Infinispan cluster sync.
- **Zitadel** has 29 published advisories clustered heavily in 2026, including cross-org account
  takeover via passkey enrolment (14 Aug 2026) and account pre-hijack via forged IdP callback
  (29 Jul 2026). Its licence also moved Apache 2.0 → AGPL 3.0 in v3.
- **SuperTokens** carries **CVE-2026-37171**: *a lack of tenant separation allowing an authenticated
  party in one tenant to access sessions, data and endpoints of another tenant* — a bug in precisely
  the feature EasyCRM would be adopting it for. Its self-hosted multi-tenancy is also paid and
  unpriced.

**FusionAuth is the strongest of the group** if this path is ever taken: free unlimited tenants,
512 MB–1 GB footprint, bcrypt import, claims via a small JS lambda rather than a compiled Java SPI,
and no CVEs found in two years of searching. Even so, it means a new stateful service, its schema,
and its upgrade obligations — for a one-person team, against ~320 lines of working Spring Security.

**Ory** was dismissed outright: Kratos has no OAuth2/OIDC layer and no multi-tenancy (single-tenant
by its own documentation), Hydra has no login UI, and org support starts at $9,350/year — more than
the entire infrastructure budget. More assembly than the status quo, not less.

---

# Part 4 — What the codebase says

This is the half that actually decided I1. Vendor economics were the smaller argument.

## 4.1 Only about a quarter of the surface is replaceable

Measured at `80e74a3`:

| Package | Lines | Replaceable by an IdP? |
|---|---:|---|
| `iam/` | 604 | partly — credential mechanics only |
| `platform/security/` | 153 | partly |
| `platform/tenancy/` | 180 | **no** — RLS plumbing, consumes whatever claim arrives |
| `tenant/` | 239 | **no** — no IdP models a GSTIN or a Tally-shaped tenant |
| **Total** | **1,176** | |
| *of which credential mechanics* | **~270–320** | **~27%** |

What stays regardless of who checks passwords: the `Tenant` entity, `tenant.plan`, seat counting
(defined as a count of ACTIVE `app_user` rows — a local Postgres concern by design, B2/B4), the
audit trail, and the whole four-layer isolation stack.

`GET /public/q/{token}` is entirely unaffected — no JWT, tenant resolved from the global
`share_link` table.

## 4.2 The two adoption shapes are not close

| | IdP verifies credentials, **we mint the JWT** | IdP mints the bearer token |
|---|---|---|
| `tenant_id` → `@TenantId` → RLS | untouched | vendor console configuration |
| B7 entitlement claims | preserved | at risk permanently |
| Effort | ~3–4 weeks | ~6–10 weeks |
| What you actually buy | a bcrypt comparison | a great deal of risk |

The first shape leaves token minting, refresh rotation, the user table and the tenant record exactly
where they are — so the vendor ends up performing a password check that fifteen lines already do.
The second shape means a Pre-Token-Generation Lambda reading plan limits from Postgres **inside the
VPC, on every login and every refresh**, and it **hard-fails**: if that Lambda errors, sign-in is
blocked rather than degraded. B7 exists specifically to keep entitlement checks local and free of
network calls. This shape puts a VPC-attached Lambda with unverified latency in the hottest path in
the product.

## 4.3 The dangerous failure mode moves in the wrong direction

The four isolation layers protect against an **absent** tenant claim: no tenant → `NO_TENANT` nil
UUID → Hibernate matches nothing, RLS GUC unset → zero rows → 404. Safe, and deliberately so.

They do **not** protect against a **wrong-but-syntactically-valid** claim. Every layer honours it
faithfully and serves another tenant's data. There is no fifth layer for this, because none is
possible — it is a correctness property of whoever mints the claim.

Today that risk is contained inside `JwtService.mint`, called only from `AuthService`: reviewable
code, unit-testable, under version control. Under an external IdP it becomes a custom-claim mapping
configured in a vendor console, changeable without a code review and hard to test.

**That is the strongest single argument for I5.**

## 4.4 Signup has no atomic story across a network boundary

`AuthService.signup` creates the `Tenant` and the owner `User` in one transaction, with
`TenantContext` deliberately installed **before** the session opens — which is why `Tenant` carries
an application-assigned UUIDv7 and implements `Persistable`.

Split that across a vendor and there is no two-phase commit available, only two failure modes:

- **Vendor succeeds, local transaction rolls back** → an orphaned IdP user with no tenant. Retry
  reports "email already exists" for an account EasyCRM has no record of.
- **Local commits, vendor call fails** → a tenant nobody can log into.

The billing spec's B11 already established the pattern for this shape — local-first plus a
reconciliation job, never block signup on a vendor. The risk profile here is worse: a stranded
billing record is a nuisance; a stranded login is a locked-out customer on day one.

---

# Part 5 — The seam that makes this reversible

I1 is a decision not to adopt, not a decision never to adopt. What makes it cheap to revisit is
already scheduled work.

`platform-security` (LLD #3) owns `TokenVerifier.verify(token) → VerifiedClaims`. Verification
against a **published JWKS does not care who signed the token**. If the seam is drawn there:

- adopting Cognito later is a configuration change plus the signup-coordination saga of §4.4, not a
  rewrite;
- the RS256/JWKS work of sub-project 7 is required anyway, for BF8's reason — a shared symmetric
  secret across five services lets any of them mint itself `plan: ENTERPRISE`;
- nothing about `platform-tenancy`, the four layers, or B7 has to move.

**LLD #3 must therefore treat the issuer as a configuration input, not a constant.** That is the one
concrete obligation this evaluation places on work in flight.

---

# Appendix A — What was not verified

Recorded so nobody treats an estimate as a quote.

| # | Unverified | Why it matters |
|---|---|---|
| **IF1** | **Whether Cognito Essentials carries the same 10,000-MAU free tier as Lite.** Essentials is required, because only its V2_0 Pre-Token trigger writes custom claims into *access* tokens | The entire "$0 at our scale" case rests on this one number. AWS restructured this pricing in Nov 2024 with a grandfathering grace period that ended Nov 2025. **Check the console before anyone plans on $0** |
| **IF2** | Auth0 B2B **Essentials'** organization cap. Free tier allows 5 orgs, B2C tiers cap at 10; whether Essentials supports 100+ is not public | A 5× swing: $150 vs $800/month at launch scale |
| **IF3** | Whether Cognito's bcrypt import is live for pools in ap-south-1. It requires "next-generation infrastructure"; AWS says existing pools get it with no action required, but rollout status is not confirmable from docs | Load-bearing for a no-forced-reset migration. Create a test pool and confirm the `password_hash` option appears |
| **IF4** | Added latency and cold-start impact of the Pre-Token-Generation Lambda. No official figure found | It sits in the hot path of every login and refresh, and hard-fails |
| **IF5** | Whether Cognito Plus-tier threat protection has feature gaps in ap-south-1 | Only matters if Plus is ever needed |
| **IF6** | All self-hosted compute figures. AWS's pricing page is JS-rendered; no region-specific numbers were obtainable, so an APAC premium was assumed | Directional only |
| **IF7** | Okta's password-hash export policy on exit, and whether its India cells are purchasable outside the enterprise motion | Moot while Okta is disqualified on price |

**One-way door, and it is confirmed rather than unverified:** Cognito has **no password-hash export,
in any form**. The July 2026 import fix is strictly one-directional. Leaving Cognito means forcing
every user through a password reset. Also confirmed: Cognito's API rate quotas are **per AWS account
per region, shared across every tenant** in a shared pool — a shared-fate risk with no analogue in
the Postgres RLS model, where one bursty tenant can throttle sign-ins for all others.

---

# Appendix B — Sources

Pricing and capability claims were checked against vendor documentation on 2026-08-26. Prices change;
re-check before acting on any figure here.

- [Amazon Cognito pricing](https://aws.amazon.com/cognito/pricing/) ·
  [multi-tenant best practices](https://docs.aws.amazon.com/cognito/latest/developerguide/multi-tenant-application-best-practices.html) ·
  [quotas](https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html) ·
  [pre-token-generation trigger](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-lambda-pre-token-generation.html) ·
  [password-hash import announcement, Jul 2026](https://aws.amazon.com/about-aws/whats-new/2026/07/amazon-cognito-password-hash-import/)
- [Auth0 pricing](https://auth0.com/pricing) ·
  [Organizations overview](https://auth0.com/docs/manage-users/organizations/organizations-overview) ·
  [custom claims](https://auth0.com/docs/secure/tokens/json-web-tokens/create-custom-claims) ·
  [bulk user imports](https://auth0.com/docs/manage-users/user-migration/bulk-user-imports) ·
  [data export policy](https://auth0.com/docs/support/policies/data-export-and-transfer-policy)
- [Okta pricing](https://www.okta.com/pricing/) ·
  [India data residency, Jan 2026](https://www.okta.com/newsroom/press-releases/okta-brings-data-residency-and-enhanced-disaster-recovery-to-india/)
- [Keycloak 26 release](https://www.keycloak.org/2024/10/keycloak-2600-released) ·
  [HA sizing](https://www.keycloak.org/high-availability/multi-cluster/concepts-memory-and-cpu-sizing) ·
  [CVE-2026-18963 coverage](https://thehackernews.com/2026/08/critical-keycloak-password-reset-flaw.html)
- [Zitadel Actions v2 complement-token](https://zitadel.com/docs/apis/actions/complement-token) ·
  [Apache → AGPL licence change](https://zitadel.com/blog/apache-to-agpl)
- [SuperTokens pricing](https://supertokens.com/pricing) ·
  [account migration](https://supertokens.com/docs/migration/account-migration) · CVE-2026-37171 (NVD/MITRE)
- [FusionAuth tenants](https://fusionauth.io/docs/get-started/core-concepts/tenants) ·
  [JWT populate lambda](https://fusionauth.io/docs/extend/code/lambdas/jwt-populate) ·
  [password hashes](https://fusionauth.io/docs/reference/password-hashes)
- [Clerk pricing](https://clerk.com/pricing) · [WorkOS pricing](https://workos.com/pricing.md)
