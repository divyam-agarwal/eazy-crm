# EasyCRM P1 — Enquiry → Quotation Conversion Wiring Design

**Status:** Design approved, pre-implementation
**Date:** 2026-07-27
**Parent spec:** `2026-07-22-easycrm-design.md` (§2 domain model — `enquiry`, `quotation`)
**Depends on:** P0 isolation + P0-auth + P1a master data + P1b quotation engine + order/accept +
enquiry slice (all merged on `main`)

---

## 1. Context & purpose

The product wedge is **enquiry → quotation → order**. The enquiry slice built the wedge's *head*
(lead capture) as a standalone aggregate with a 5-stage lifecycle
(`NEW → CONTACTED → QUALIFIED → CONVERTED / LOST`). It deliberately stopped short of wiring
conversion: `Enquiry.markConverted()` exists, and `quotation.enquiry_id` exists as a nullable
column, but **nothing flips an enquiry to `CONVERTED` or validates the `enquiryId` a quote is
raised with**.

This slice closes that gap — the thin follow-up that joins the wedge's head to its body. When a
quotation is raised *from a lead*, the lead is marked `CONVERTED` and stamped onto the quotation, in
one atomic step.

| Wedge stage | Slice | Status |
|-------------|-------|--------|
| enquiry (lead capture) | enquiry slice | **merged** |
| **enquiry → quotation conversion** | **this spec** | **this slice** |
| quotation (build → send → revise) | P1b | **merged** |
| order (accept a sent quote) | order/accept | **merged** |

## 2. Current state (what already exists)

- `QuotationCreateRequest` already carries an **optional** `enquiryId` field.
- `QuotationService.create()` already stamps it: `new Quotation(req.customerId(), req.enquiryId())`.
- `QuotationResponse` already exposes `enquiryId`.
- `Enquiry.markConverted()` already exists: sets `stage = CONVERTED`, guarded by `requireActive` so
  it throws `ValidationException("stage", …)` → **422** from a terminal (`CONVERTED`/`LOST`) stage.

**What is missing** — the only behavioural gap this slice fills: the `enquiryId` on create is
stored *blindly*. Nothing loads the enquiry, nothing validates it exists / is visible / is active,
and nothing flips it to `CONVERTED`.

## 3. Scope

**In scope:**
- Wire conversion into the existing quotation-create path: when the create body carries an
  `enquiryId`, load + validate the enquiry and flip it to `CONVERTED`, atomically with the quote
  build.

**Explicitly out of scope** (deferred, do not build here):
- Any new endpoint (no `/enquiries/{id}/convert`) — conversion rides the existing
  `POST /api/v1/quotations` path.
- Converting at `send`/`accept` instead of `create` (see §6 tradeoff — we chose `create`).
- Forcing `enquiry.customerId` to equal the quotation's `customerId` (see §5).
- A stage pre-requisite for conversion (convert is allowed from any *active* stage — §5).
- Multiple quotations per enquiry (one enquiry → one quotation falls out of the terminal guard — §5).
- `activity` / `follow_up`, record-level visibility filtering, cursor pagination, frontend — all
  still deferred as in the enquiry spec.

## 4. Modules & conventions

Lives under `com.easycrm.sales` — the change is entirely within `QuotationService`. New shared
plumbing: **none**. Conventions carried forward unchanged:

- **Tenant isolation is structural** — the enquiry is loaded via `EnquiryRepository.findById` on the
  tenant-scoped session (`@TenantId` + RLS); a cross-tenant id resolves to empty, never a foreign
  row. No hand-written `WHERE tenant_id`.
- **Cross-tenant / missing reads return 404** (P0 pattern) — `NotFoundException`.
- **Illegal state → 422** — `markConverted()` on a terminal enquiry throws `ValidationException`,
  mapped to 422 (the codebase's illegal-transition convention; *not* `IllegalStateException`).
- **`ddl-auto: validate`**, money-as-JSON-string, etc. — untouched; no schema change.

## 5. Behaviour

`QuotationService.create(QuotationCreateRequest req)` gains one step, executed **only when
`req.enquiryId() != null`**, inside the method's existing `@Transactional` boundary:

1. `Enquiry enquiry = enquiries.findById(req.enquiryId()).orElseThrow(() -> new
   NotFoundException("enquiry not found"))` — 404 if the id is unknown or belongs to another tenant
   (RLS).
2. `enquiry.markConverted()` — flips `stage` to `CONVERTED`; the existing `requireActive` guard
   throws `ValidationException("stage", …)` → **422** if the enquiry is already terminal.
3. The existing `new Quotation(req.customerId(), req.enquiryId())` line stamps `quotation.enquiry_id`
   — no change.

The enquiry is a **managed** entity, so the `CONVERTED` mutation flushes on transaction commit; no
explicit `save` needed. `EnquiryRepository` is injected into `QuotationService`'s constructor.

When `req.enquiryId() == null`, `create()` behaves exactly as today (a quote raised without a lead).

**Consequences that require no extra code:**

- **Atomic conversion + quote build.** The load + flip + quote build share one transaction. If quote
  build fails validation (e.g. a bad item quantity throws `ValidationException`) *after* the flip,
  the whole transaction rolls back and the enquiry stays active. There is no window where the lead
  is `CONVERTED` but no quotation exists.
- **One enquiry → one quotation.** A second `create` against the now-`CONVERTED` enquiry hits
  `markConverted()`'s terminal guard → 422. Raising two separate quotes from one lead is not
  supported in this slice (YAGNI); if it's needed later, it becomes an explicit design change.
- **Frees the phone for re-enquiry.** `CONVERTED` leaves the partial-unique-index predicate
  `WHERE stage NOT IN ('CONVERTED','LOST')` (enquiry spec §5.2 / challenge #23), so a fresh enquiry
  on the same normalized phone is allowed immediately after conversion — the same mechanism that
  `LOST` already used, now reachable via conversion too.
- **Concurrent double-convert is guarded.** Two racing `create`s with the same `enquiryId` both load
  the enquiry (`NEW`) and both call `markConverted()`; the enquiry's inherited `@Version` optimistic
  lock makes the second commit fail — exactly one create wins. (The sequential case is already
  covered by the terminal guard.)

**No customer match forced.** `enquiry.customerId` (nullable — walk-ins have none) is a hint, not a
constraint. The quotation uses `req.customerId()` authoritatively: a walk-in enquiry may have a
`Customer` created for it before the quote is raised, so the two need not agree. This slice does not
read or compare `enquiry.customerId`.

## 6. Design decision: convert at *create*, not *send*

Conversion fires when the quote is **created** (a DRAFT quote raised from the lead), not when it is
sent or accepted. This matches the wedge semantics — `CONVERTED` means "this lead became a
quotation," not "this deal was won" (a won deal is an `Order`).

**Accepted tradeoff:** an abandoned DRAFT quote permanently marks the lead `CONVERTED` (terminal —
it can no longer be advanced or lost). We accept this over deferring the flip to `send` because:
(a) it keeps the flip in one obvious place with the stamping that's already there; (b) `CONVERTED`
frees the phone anyway, so a re-enquiry can restart the lead cleanly; and (c) deferring to `send`
would leave a raised-but-unsent quote with the lead still active *and* the dedupe phone still
blocked — worse drift than the accepted case. Recorded here so the tradeoff is a decision, not an
accident.

## 7. Testing (TDD, Testcontainers + real Postgres/RLS)

Use `TestTokens.provisionOwner(stateCode)` — quotation-create reads `Tenant.state_code` for the GST
split, so a phantom-owner tenant is insufficient (handoff §4 testing note).

1. **Happy path:** create a quotation with a valid active `enquiryId` → 201; the enquiry is now
   `CONVERTED`; `quotation.enquiryId` equals the enquiry id.
2. **Unknown / cross-tenant enquiryId** → 404 (and, for the cross-tenant case, the other tenant's
   enquiry is untouched).
3. **Terminal enquiry** (already `CONVERTED` or `LOST`) as `enquiryId` → 422.
4. **One enquiry → one quotation:** a second create against the just-converted enquiry → 422.
5. **Re-enquiry after conversion succeeds** (end-to-end dedupe tie-in): convert a lead, then create a
   fresh enquiry on the *same normalized phone* → 201 (proves `CONVERTED` leaves the partial index).
6. **Atomicity:** create with a valid active `enquiryId` **and** an invalid item (e.g. qty ≤ 0) →
   422 from quote build, and the enquiry remains active (`NEW`/its prior stage), not `CONVERTED`.
7. **Regression — no enquiry:** create with `enquiryId == null` → 201, behaves exactly as before
   (guards the existing path).

## 8. Documentation obligations (same change, per CLAUDE.md)

- **`engineering-challenges.md`:** evaluate at the end of implementation whether the create-time-flip
  design warrants a (short) entry — the interesting, non-obvious content is the *transaction boundary
  coupling* (conversion + quote build atomic, so a failed build un-converts the lead) and the
  *invariant tie-in* (conversion is the second way, besides `LOST`, to free the dedupe phone). Log it
  only if it clears the CLAUDE.md bar; do not pad the log with routine wiring.
- **`annotations-reference.md`:** no new annotation is introduced — no change expected.
- **`HANDOFF.md`:** update state (conversion wired; move it out of "deferred") on merge.

## 9. Out-of-scope recap (do not build)

New convert endpoint · convert-at-send/accept · forced customer match · stage pre-requisite ·
multiple quotations per enquiry · `activity` / `follow_up` · record-level visibility filtering ·
cursor pagination · frontend.
