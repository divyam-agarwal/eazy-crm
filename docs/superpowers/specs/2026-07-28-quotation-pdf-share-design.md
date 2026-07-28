# Quotation PDF + `wa.me` share — design spec

**Date:** 2026-07-28
**Status:** designed, not yet implemented
**Slice:** server-side quotation PDF rendering + a public tokenized share link + the `wa.me`
deep link, plus the deferred `QuotationService.list` filter fix (challenge #24)
**Builds on:** the P1b quotation engine (`specs/2026-07-26-p1b-quotation-engine-design.md`,
merged `43e9642`) and the P0 tenant-isolation foundation
(`plans/2026-07-24-p0-tenant-isolation-foundation.md`)

---

## 1. Why this slice

The product's one-line pitch is *"your IndiaMART enquiries turn into GST quotations on WhatsApp
in 60 seconds."* Every part of that sentence exists today except the last third: a quotation can
be built, priced, taxed, frozen and sent, but nothing renders it as a document and nothing puts
it in front of the customer. This slice closes that gap.

It is also the agreed home for a deferred bug the order-lifecycle whole-branch review asked to
lead the next slice, whatever it turned out to be: `QuotationService.list` composes its two
optional filters with `if / else if`, so supplying `status` **and** `customerId` silently drops
the customer filter — the same defect fixed for orders in `8247579` and logged generally as
challenge #24.

### This slice makes no outbound network calls

Worth stating plainly, because the handoff flagged this chunk as "the first external-I/O slice"
and the trigger to migrate the accept-audit event from same-transaction to after-commit + outbox
(challenge #22). On inspection that trigger does **not** fire here:

- Rendering is local CPU — an in-process library, no service call.
- `wa.me` is a **deep link the salesperson taps on their own phone**. The server composes a URL
  string; WhatsApp itself is never contacted by us.

There is therefore no at-least-once delivery problem to solve, and no work that must survive a
crash after commit. The outbox belongs with the real WhatsApp Business API in P2, where an actual
external send exists to be retried. Building it now would be speculative infrastructure.

## 2. What gets rendered, and from what

The document is rendered from a **frozen `QuotationVersion` and its `QuotationItem` rows** — the
immutable snapshot P1b already produces on `send`. Nothing about the document is recomputed:
totals, the tax split, and the product name/HSN/UOM snapshots are read as stored.

| Block | Source |
|---|---|
| Letterhead | `Tenant.businessName`, `gstin`, `stateCode`, + new `address` / `phone` / `email` |
| Buyer | `Customer.businessName`, `gstin`, `billingAddress` |
| Document header | `Quotation.quoteNo`, version no., `sentAt` date, `validUntil`, `placeOfSupply` |
| Item table | `QuotationItem`: `nameSnapshot`, `hsnSnapshot`, `uomSnapshot`, `qty`, `rate`, `discountPct`, `taxableValue`, `lineTotal` |
| Tax summary | Per-item `cgst`/`sgst`/`igst` — **CGST+SGST or IGST, never both** |
| Totals | `subTotal`, `totalTax`, `grandTotal` |
| Footer | `paymentTerms`, `deliveryTerms`, `notes` |

Money renders as `Rs. 1,23,456.78` — Indian digit grouping, formatted from `BigDecimal`, never
via `double`. The `₹` glyph (U+20B9) is **not** used: it is absent from the base-14 PDF fonts, and
embedding a TTF to obtain it was declined as not worth the jar weight and font-licensing note for
this slice. The template is pinned to base-14 Helvetica accordingly.

Only a **SENT** (frozen) version is renderable. A `DRAFT` has no quote number yet — `send` is what
assigns it — so there is no document to produce.

## 3. Rendering: Thymeleaf → openhtmltopdf

`spring-boot-starter-thymeleaf` renders an XHTML template to a string; `openhtmltopdf-pdfbox`
converts that string to PDF bytes. Pure Java, no external binary, nothing extra to install in CI
or Testcontainers.

Chosen over programmatic composition (OpenPDF `PdfPTable`) because the template stays readable
and tweakable by eye, and the frontend's later read-only quotation view can reuse the same HTML.
Chosen over headless Chrome / wkhtmltopdf because those require an external binary in every
environment, including CI.

### Risk gate: the spike comes first

openhtmltopdf is mature but old, and this stack has been bitten twice by newness — Boot 4's
auto-config module split, and ArchUnit 1.3.0 silently skipping Java 25 bytecode. The
implementation plan's **first task is therefore a spike**: render trivial XHTML to a valid PDF on
JDK 25 and assert extracted text. If it fails there, the fallback is OpenPDF programmatic
composition, at a cost of one task rather than the slice.

### Determinism is a requirement, not an accident

The parent design spec requires that shown, emailed and WhatsApped output be **byte-identical**.
openhtmltopdf stamps a creation date and a document ID by default, so two renders of the same
frozen version would otherwise differ byte-for-byte.

The renderer must therefore pin **creation date, producer string, and document ID** to values
derived from the version itself (its `sentAt`), making the render a pure function of stored data.
This is what turns byte-identity from an aspiration into an assertable test, and it is why
"render on demand, store nothing" is safe.

## 4. Persistence

### `share_link` — a global, RLS-exempt table

```sql
CREATE TABLE share_link (
    id                   UUID PRIMARY KEY,
    token_hash           VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex
    tenant_id            UUID        NOT NULL,          -- plain column, NOT @TenantId
    quotation_version_id UUID        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_share_link_version ON share_link (quotation_version_id);
```

The entity is **global**, not `TenantScopedEntity`, and must be added to the ArchUnit
`GLOBAL_TABLES` allowlist — exactly the treatment `refresh_token` already receives, and for the
same reason: it is read on a request that has no tenant yet.

The token is **hashed at rest with SHA-256**, mirroring `refresh_token`. The raw token is a bearer
credential living in a URL; a database leak must not hand over every live quote link. Lookup is by
hash on the unique index, so the cost is unchanged.

### Share is additive, not idempotent

Hashing at rest has a direct consequence worth stating rather than discovering during
implementation: **the raw token exists only in the mint-time response and can never be
recovered.** A second `POST /share` for the same version therefore cannot return the same URL —
the server no longer knows it.

So each share **mints a new row**, and several tokens may resolve to the same version. The index
on `quotation_version_id` is non-unique for exactly this reason. The alternative — replacing the
row so only the newest link is live — was rejected because it silently kills a link already
WhatsApped to someone: sharing the same quote with a second customer would break the first
customer's copy. A link that was sent keeps working.

This is the price of hashing, paid deliberately. Plaintext storage would allow returning a stable
URL, and was rejected above: a table of live credentials is worse than a table of duplicate rows. Revocation is out of scope (§8), and when it arrives the `share_link` row is the
natural place for it.

### The token points at a version, not a quotation

v1's link must keep rendering v1 forever after v2 is sent. This is what the immutable-snapshot
design already means, and the parent spec's "must see exactly what was sent when" requires it.
Sharing v2 mints a token against the v2 row; the v1 row and its link are untouched.

### `tenant` gains seller-profile columns

```sql
ALTER TABLE tenant ADD COLUMN address VARCHAR(512);
ALTER TABLE tenant ADD COLUMN phone   VARCHAR(20);
ALTER TABLE tenant ADD COLUMN email   VARCHAR(255);
```

All nullable — existing tenants keep working, and the letterhead simply omits what is absent.
Without these the letterhead is a business name and a GSTIN floating on white space, which
undercuts the whole point of the feature.

## 5. The tenant-resolution seam

This is the slice's one genuinely hard problem.

A public request carries no JWT, so `TenantContext` is empty, so the RLS GUC
`app.current_tenant` is unset, so **every tenant-scoped query returns zero rows**. A
`share_token` column on `quotation_version` would therefore be unlookupable — the row cannot be
found without already knowing the tenant it belongs to. The separate global table is not a
convenience; it is forced by the isolation model.

```
GET /public/q/{token}                       no JWT, no tenant
  → ShareLinkService.resolve(sha256(token))  global table, RLS-exempt
      → 404 if absent
  → TenantContext.runAs(tenantId, () -> …)   P0's existing mechanism
      → load version + items + customer + tenant, @TenantId and RLS enforced as normal
      → render, stream bytes
```

The exception to *"tenant comes from the JWT only"* is narrow and structural, and worth naming
precisely: exactly **one** table is readable without a tenant, and it contains nothing but an
opaque hash mapped to a tenant id and a version id. No document data is reachable through it
directly. Every actual read of quotation content still goes through `@TenantId` + RLS, with the
tenant established by `runAs` before the transaction opens — the same ordering P0-auth's signup
already requires (challenge #9: Hibernate resolves a session's tenant once, at session-open).

`SecurityConfig` gets a `permitAll` rule for `/public/**`, and `JwtAuthenticationFilter` must skip
that path rather than reject it.

## 6. HTTP surface

### New endpoints

| Method | Path | Auth | Returns |
|---|---|---|---|
| `GET` | `/api/v1/quotations/{id}/pdf?version=<n>` | JWT | `application/pdf` |
| `POST` | `/api/v1/quotations/{id}/share` | JWT | `{ publicUrl, waMeUrl }` |
| `GET` | `/public/q/{token}` | **none** | `application/pdf`, `Content-Disposition: inline` |
| `PATCH` | `/api/v1/tenant` | JWT, OWNER | tenant profile |

`GET .../pdf` defaults to the **latest SENT version**; the optional `?version=<n>` selects an
earlier frozen version, since traders revise 3–4× and need to see what was actually sent.

`POST .../share` mints a **new** token against the latest sent version and returns both URLs (see
§4 — sharing twice yields two working links, not one reused link). The `waMeUrl`
is `https://wa.me/<number>?text=<url-encoded message>`, where the message carries the buyer's
contact name, quote number, grand total, validity date, the public URL and the seller's business
name.

**Recipient number resolution:** the customer's primary `Contact` — `whatsappNumber` first, else
`phone`. If neither exists, the URL is emitted **without** a number
(`https://wa.me/?text=…`), and WhatsApp opens its own contact picker. Sharing never hard-fails on
missing contact data; it degrades to one extra tap. Blocking the share at the exact moment a
salesperson wants to send — for a walk-in whose contact row was never created — was considered and
rejected.

### Status codes

| Situation | Code |
|---|---|
| PDF or share requested for a quotation with no SENT version | 422 |
| `?version=<n>` names a non-existent or unfrozen version | 422 |
| Quotation id belongs to another tenant | 404 |
| Public token unknown or malformed | 404 |
| `PATCH /api/v1/tenant` by a non-OWNER | 403 |

The public 404 is deliberately indistinguishable across "never existed" and "wrong format" — the
same reasoning as the codebase's cross-tenant 404 rule: a distinguishable response confirms
existence.

### Changed behaviour on existing endpoints

`GET /api/v1/quotations` (list) begins honouring `status` **and** `customerId` together.
`QuotationSpecifications.filter` AND-composes any subset, mirroring `OrderSpecifications`;
`QuotationRepository` extends `JpaSpecificationExecutor<Quotation>`. No contract change — a
request that previously returned too many rows now returns the correct ones.

## 7. Testing

**Rendering** — PDFBox's `PDFTextStripper` extracts text from the produced bytes:

- quote number, buyer business name, and every item's HSN code appear
- amounts appear in `Rs. 1,23,456.78` form
- an intra-state quote shows CGST and SGST and **not** IGST; an inter-state quote shows IGST and
  **not** CGST/SGST
- a quotation whose tenant has no address/phone/email still renders (no null leakage into the
  letterhead)

**Determinism** — render the same version twice, assert the two byte arrays are equal.

**Share + public access** — the isolation-critical cases:

- a valid token returns tenant A's PDF **with no JWT on the request at all**
- an unknown token returns 404
- a token minted for tenant A's quotation never exposes tenant B data, and the render performed
  under `runAs` sees only tenant A's rows
- after v2 is sent and shared, v1's earlier token **still renders v1** — the snapshot a customer
  was sent does not change under them — while v2's token renders v2
- sharing the same version twice yields two distinct tokens that both resolve
- `POST /share` on a DRAFT-only quotation returns 422

**wa.me composition** — number resolution prefers `whatsappNumber` over `phone`, omits the number
entirely when neither exists, and URL-encodes the message body.

**List fix** — a two-filter regression test: `?status=` + `?customerId=` returns only rows
matching both, plus the existing single-filter and cross-tenant cases.

**Tenant profile** — `PATCH` updates the three fields; a non-OWNER gets 403.

Test tenants must be provisioned with `TestTokens.provisionOwner(stateCode)`, not a phantom
tenant id: the letterhead reads real `Tenant` row data, exactly as the GST split already does.

## 8. Explicitly out of scope

- **Order PDF.** The wedge's promise is the quotation; the order is an internal confirmation the
  customer already agreed to. A second template, share path and test suite for a document rarely
  asked for at this stage.
- **Link expiry and revoke.** The `share_link` row is the seam when we want it; replace-on-reshare
  already gives a crude revoke.
- **Rate limiting** on the public endpoint. Already on the backlog with the rest of rate limiting.
- **Logo upload.** Needs file storage, which this project does not yet have.
- **PDF caching or blob storage.** Rendering is deterministic and cheap; storing bytes would add a
  backfill story and prevent template fixes from reaching already-sent quotes.
- **The outbox / after-commit event migration** — see §1: no external I/O here.
- **WhatsApp Business API** — P2, behind a port, per the parent spec.
