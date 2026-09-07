# Buyer snapshot — design

**Date:** 2026-09-07
**Status:** Designed, not yet planned
**Backlog item:** Sub-project 1 (D10/F11), roadmap item 1 — the only **live correctness bug** on `main`
**Baseline:** `main` at `13c01c4` (586 tests, 0 failures, 0 errors)
**Fixes:** hazard **H1** in `docs/ROADMAP.md` §1.5
**Reverses one decision and declines one fold.** D10 specifies a JSONB column; §3 stores flat
columns instead. The service-scope doc folds S2 into this slice; §7 declines it. Both are recorded
here with reasons rather than left as silent drift.

---

## 1. The bug

`QuotationVersion` is the frozen document. It freezes the line items, the three totals, the header
terms and `placeOfSupply`. It does **not** freeze the buyer.

`QuotationPdfService.render()` reads `businessName`, `gstin` and `billingAddress` **live** from
`crm.Customer` at render time. So:

1. A salesperson sends quotation `Q-2026-0041` to a distributor. The PDF shows their address.
2. Someone corrects that customer's billing address — an ordinary, correct edit.
3. The buyer opens the share link they were sent in WhatsApp weeks ago.
4. **They get a different document under the same quotation number.**

Nothing warns anyone. Nothing logs it. The version's `@Version` column does not change, because
the version was never written to.

Challenge #28 established that a given version renders byte-identically across renders. That
guarantee is real and it is narrower than it reads: it holds the *renderer* deterministic, and says
nothing about the *inputs* moving underneath it. F11 is precisely the gap between those two.

**This is worse through the public route than the authenticated one.** `/public/q/{token}` is
pre-auth, is the most exposed endpoint in the system, and serves a link that lives in someone
else's chat history indefinitely. A GST document that silently changes after issue is not a
cosmetic defect.

### 1.1 Why it is also a prerequisite

D10 in the AWS target architecture wants this fixed for a second reason. Under the service split,
`document-svc` renders the PDF and cannot reach `master-data`'s schema. Every live `crm` read on
the render path becomes a synchronous cross-service call on the most latency-sensitive and most
cached route in the system. Freezing the buyer deletes the call rather than making it fast.

The bug fix and the architectural change are the same edit. That is why the AWS design says to do
it "first regardless of whether anything else happens", and why it has stayed item 1 while nine
other slices landed around it.

---

## 2. Decisions

| # | Decision | Rejected alternative | Why |
|---|---|---|---|
| **B1** | Freeze at **`send()`**, not at version creation | Freeze in `create()` and copy forward in `revise()`, as `placeOfSupply` already does | The document means "the buyer as of when this was sent". A draft that sits for two weeks while someone corrects a typo'd GSTIN must send the *corrected* one. Freezing at creation silently discards that edit |
| **B2** | **Guard** `send()` on `customer.stateCode == version.placeOfSupply`, reject 422 if not | Freeze the new address and send anyway | `placeOfSupply` is frozen at creation *because it must agree with the per-line CGST/SGST/IGST already computed*. B1 alone would let a new address ride on an old tax split — a self-contradictory GST document. §4.1 |
| **B3** | **Flat columns** via an `@Embeddable`, not JSONB | `buyer_snapshot JSONB`, as D10 specifies | §3.1 |
| **B4** | **Decline the S2 fold.** No contact snapshot in this slice | Freeze the primary contact in the same migration, per the service-scope doc's Appendix A | §7 |
| **B5** | Backfill **per tenant, inside RLS**, with the assertion inside the loop | One cross-tenant `UPDATE`; or `NO FORCE` around the backfill; or `BYPASSRLS` on `easycrm_owner` | §3.3 — the naive form fails silently, and so does the naive check |
| **B6** | Column stays **nullable**; the invariant is *not-null when `SENT`*, enforced in code | `NOT NULL` with a placeholder default | A `DRAFT` genuinely has no buyer yet. A placeholder would make the unset state indistinguishable from a real one |
| **B7** | **No API surface.** `QuotationVersionResponse` unchanged | Expose the snapshot on the version DTO | Nothing consumes it — the frontend is zero lines. `docs/api/openapi.yaml` does not move, `OpenApiSnapshotTest` stays green, and oasdiff records no change. Additive later is cheap |

---

## 3. Schema

### 3.1 Flat columns, not JSONB (B3)

D10 says "freezing a `buyer_snapshot` JSONB column into `QuotationVersion`". That was written on
2026-08-19, in a document about service boundaries, where the snapshot's role is *a payload
travelling inside a `QuotationSent` event*. As a payload, JSONB is natural.

As a **column on a table this application validates against at startup**, it is not. Three reasons
to reverse it:

- **The precedent in this exact table family is flat.** `QuotationItem` freezes the product as
  `name_snapshot`, `hsn_snapshot`, `uom_snapshot` — three typed columns, not a blob. The buyer is
  the same kind of thing frozen for the same reason. A reader who understands one should not have
  to learn a second convention four columns away.
- **`ddl-auto: validate` checks columns, not blob contents.** Flat columns mean Hibernate fails at
  startup on a mapping/schema mismatch. Inside JSONB, the schema lives only in Java and every
  future field lands unvalidated by anything.
- **The extensibility argument is weaker than it looks.** `ALTER TABLE ... ADD COLUMN` with no
  default is O(1) on modern Postgres — no table rewrite, no lock of consequence. That is the same
  fact §7 rests on. JSONB buys nothing here that a migration does not.

The one thing JSONB would have bought — serialising straight into the `QuotationSent` payload — is
recoverable whenever SP6 arrives, by serialising the `@Embeddable`. That is a Jackson call, not a
migration.

**`AuditLog.detail` remains the codebase's only JSONB column, and correctly so:** its shape varies
per audit event by design. The buyer snapshot has exactly one shape.

### 3.2 The migration

`V34__quotation_version_buyer_snapshot.sql`:

```sql
ALTER TABLE quotation_version
  ADD COLUMN buyer_business_name   VARCHAR(255),
  ADD COLUMN buyer_gstin           VARCHAR(15),
  ADD COLUMN buyer_billing_address VARCHAR(512);
```

Widths mirror `customer` exactly (`business_name VARCHAR(255)`, `gstin VARCHAR(15)`,
`billing_address VARCHAR(512)`). A snapshot narrower than its source would truncate on freeze.

RLS needs no change: these are columns on an already-enabled, already-forced, already-policied
table. `RlsCoverageIntegrationTest` keys on the `tenant_id` column and is unaffected.

### 3.3 The backfill, and the trap under it (B5)

**Flyway connects as `easycrm_owner`** (`application.yml`: `spring.flyway.user`), which is the
table owner. `V26__force_rls.sql` `FORCE`s RLS on `quotation_version` and `customer`, and **FORCE
binds the owner too** — that is the entire point of V26.

Every tenant policy in this schema reads:

```sql
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
```

The `true` is `missing_ok`. In a Flyway session no GUC is set, so `current_setting` returns `NULL`,
`NULLIF(NULL, '')::uuid` is `NULL`, and `tenant_id = NULL` is `NULL` — never true. **A plain
cross-tenant `UPDATE` in a migration therefore matches zero rows, commits, and reports success.**
No error, no warning, no log line.

V26's own header predicted this by name: *"It stops holding the moment any process connects as the
owner — a migration tool reused for a backfill."* This slice would be the first DML migration in
the repo (there are none in V1–V33), so it is the first to meet it.

Three fixes were considered and two rejected:

- **`NO FORCE` around the backfill, then re-`FORCE`.** If the migration fails between the two, the
  table is left unforced — a silent isolation hole introduced by the fix for a silent bug.
- **`BYPASSRLS` on `easycrm_owner`.** A permanent role attribute, requiring superuser to grant,
  weakening layer 3 forever to serve one migration.
- **Adopted: drive the backfill per tenant, from inside the policy.** `tenant` has no `tenant_id`
  column and no RLS (V26 §"Deliberately NOT forced"), so the loop can read the registry freely.

```sql
DO $$
DECLARE t uuid; stale int;
BEGIN
  FOR t IN SELECT id FROM tenant LOOP
    PERFORM set_config('app.current_tenant', t::text, true);

    UPDATE quotation_version v
       SET buyer_business_name   = c.business_name,
           buyer_gstin           = c.gstin,
           buyer_billing_address = c.billing_address
      FROM quotation q
      JOIN customer c ON c.id = q.customer_id
     WHERE q.id = v.quotation_id
       AND v.status = 'SENT';

    SELECT count(*) INTO stale
      FROM quotation_version
     WHERE status = 'SENT' AND buyer_business_name IS NULL;

    IF stale > 0 THEN
      RAISE EXCEPTION 'tenant %: % SENT versions left unfrozen', t, stale;
    END IF;
  END LOOP;

  PERFORM set_config('app.current_tenant', '', true);
END $$;
```

`set_config(..., true)` is transaction-local, and Flyway wraps each migration in a transaction, so
the GUC cannot leak into a later session.

**The assertion must be inside the loop.** Written after it — the obvious place — it would run with
the GUC reset, see zero rows through RLS, and pass vacuously. The check would then be exactly as
broken as the bug it exists to catch, in exactly the same way. That is what makes this
challenge-log material rather than boilerplate (§8, Challenge 68).

**What the backfilled values mean.** For a `SENT` version whose customer was already edited, the
true historical buyer is unrecoverable — it was never stored. The backfill writes the current
customer, which is *precisely what that version renders today*. So the backfill changes no rendered
output; it freezes the status quo and stops it moving again. That is the honest ceiling on this
fix, and it is why the fix is urgent rather than merely tidy.

---

## 4. Code

### 4.1 `BuyerSnapshot` and the write path

A `BuyerSnapshot` `@Embeddable` in `com.easycrm.sales` holds the three `String` fields;
`QuotationVersion` gains an `@Embedded` field and a `freezeBuyer(...)` method alongside the existing
`markSent(...)`.

`QuotationService.send()` gains one read and one guard. It already has `VisibleFinder` injected:

```java
Customer customer = finder.findCustomer(q.getCustomerId())
        .orElseThrow(() -> new NotFoundException("customer not found"));
if (!customer.getStateCode().equals(v.getPlaceOfSupply())) {
    throw new ValidationException(
            "placeOfSupply", "the customer's state has changed; raise a new quotation");
}
v.freezeBuyer(customer.getBusinessName(), customer.getGstin(), customer.getBillingAddress());
```

**Why the guard, and why "raise a new quotation" and not "revise" (B2).** `placeOfSupply` is frozen
at *creation* because the per-line `cgst`/`sgst`/`igst` on `QuotationItem` were computed against it
at the same moment. Buyer identity carries no such coupling, which is what makes B1 safe — but a
customer who moves state breaks the coupling that does exist. Freezing the new address over the old
split would print a Maharashtra address beside a Karnataka intra-state tax breakup.

`revise()` copies `prev.getPlaceOfSupply()` forward and copies the frozen items verbatim, so a
revision inherits the stale split and the guard correctly fires again. **The escape hatch is a new
quotation, not a revision** — `create()` re-reads the customer and recomputes the split from
scratch. `accept()` already uses the same phrasing for the cancelled-order case, so the vocabulary
is not new.

`revise()` itself is **untouched**. The new version is a `DRAFT` with no snapshot and freezes its
own buyer at its own `send()` — which is the mechanism by which a corrected GSTIN reaches a
revision.

### 4.2 The render path

`QuotationPdfService.render()` drops `finder.findCustomer(...)` and reads `v.getBuyer()`.
**`import com.easycrm.crm.Customer` is deleted** — that deletion is D10's architectural payoff, one
live `crm` read removed from the public route.

A `SENT` version with a null snapshot throws an `IllegalStateException` naming the invariant.
It is unreachable after §3.3 and after B1's write path, and a 500 is the right answer for a
violated invariant — loud, rather than a PDF with a blank buyer block.

`ShareLinkService` is unchanged; see §7.

---

## 5. Testing

TDD, per the repo's standing practice. **The regression test is written first and must fail on
`main` before anything else is touched:**

| Test | Asserts |
|---|---|
| **Determinism across a customer edit** | create → send → render → edit the customer's `businessName`, `gstin` *and* `billingAddress` → render again → **byte-identical**. This is F11. It fails today |
| Same, via `renderByVersionId` | The public share path — where the bug is worst, and a different entry point into `render()` |
| Freeze on send | After `send()`, the three columns hold the customer's values; before it, they are null |
| State-change rejection | Customer moves state while the quotation is `DRAFT` → `send()` returns 422 naming `placeOfSupply`, and nothing is frozen |
| Revision re-freezes | send → edit the customer's name → `revise()` → `send()` → the new version carries the **new** name and the old version still carries the old one |
| Render reads the snapshot, not the customer | Freeze, then mutate the customer, then assert the rendered bytes contain the frozen values |

`ddl-auto: validate` covers mapping-vs-schema drift for free at context startup.

**One gap, stated rather than papered over.** Testcontainers starts from an empty database, so the
§3.3 backfill loop iterates zero tenants and updates zero rows under test. **No test in this repo
can prove the backfill works.** Its only real check is the in-migration `RAISE`, which runs wherever
there is data. Since every environment from Phase 3 onward is built from `V1` on an empty database,
the loop will only ever do real work against a developer's local Postgres — which is the reason the
gap is acceptable, not a reason to pretend it is closed.

---

## 6. What this does not change

- **No API change** (B7). `QuotationVersionResponse`, `docs/api/openapi.yaml` and the oasdiff
  changelog are all untouched, and `OpenApiSnapshotTest` must stay green as written.
- **No `revise()` change**, **no `create()` change**, **no `ShareLinkService` change**.
- **No RLS change.** No new table, no new policy, no `GLOBAL_TABLES` edit.
- **`placeOfSupply` still freezes at creation.** It is correct there (§4.1); B2 guards it rather
  than moving it.

---

## 7. Why S2 is declined (B4)

`docs/architecture/2026-08-24-service-scope-and-shared-modules.md` Appendix A folds S2 — freezing
the customer's primary `Contact`, which `ShareLinkService.waMeUrl()` reads live — into this slice.
Its stated reason is: *"Doing it later means a second migration over the same table."*

**That reason does not survive contact with the code.** `ALTER TABLE ... ADD COLUMN` with no default
is O(1) on modern Postgres. Deferring S2 costs one extra Flyway file. That is not a cost worth
paying anything for.

And there is something to pay. **The PDF and the `wa.me` link look alike and are not alike.** The
PDF is a document that must not change after issue. The `wa.me` link is built at **share** time, not
send time, and it is a *routing address* — how the salesperson reaches the buyer right now. Freeze
the number at send, and a typo'd phone becomes uncorrectable without `revise()`, burning a version
number on the quotation to fix a contact's digits. That is a real regression on a path used daily,
traded for an architectural benefit that only pays out at SP8 — which the roadmap marks
**conditional on a §4.5 trigger that may never fire.**

S2 is therefore taken when SP6 or SP8 actually needs it, and `docs/ROADMAP.md` and the service-scope
doc are amended in this change to say so. The finding S2 records stays valid; only its scheduling
changes.

---

## 8. Documentation owed in the same change

Per `CLAUDE.md`'s working agreements, all of this lands **with** the code, not after:

- **`engineering-challenges.md` — Challenge 68** — RLS `FORCE` versus a DML backfill migration. Qualifies on
  three counts: the naive approach is silently wrong, the naive *check* is silently wrong the same
  way, and it touches multi-tenancy. Establishes the pattern for every future DML migration here.
- **`annotations-reference.md`** — rows for `@Embeddable` and `@Embedded` (origin, purpose,
  composition). Neither appears in the codebase today.
- **`docs/ROADMAP.md`** — H1 struck from §1.5; item 1's "folds in S2" clause corrected per §7.
- **`docs/architecture/2026-08-24-service-scope-and-shared-modules.md`** — Appendix A item 2
  amended: S2 no longer folds into sub-project 1, with §7's reason.
- **`docs/superpowers/HANDOFF.md`** — §0 and §3 updated; F11 marked closed wherever it is listed
  open (`2026-08-20-aws-redesign-handoff.md`, `2026-08-26-platform-modules-handoff.md`,
  `2026-08-27-platform-llds-handoff.md` all name it).

---

## 9. Scope of the change

| Area | Files |
|---|---|
| Migration | `V34__quotation_version_buyer_snapshot.sql` (new) |
| Domain | `BuyerSnapshot.java` (new), `QuotationVersion.java` |
| Write path | `QuotationService.java` |
| Render path | `QuotationPdfService.java` |
| Tests | quotation-send tests, PDF-render tests, share/public-render tests |
| Docs | five files, §8 |

Roughly six source files, one migration, three test files, five docs. Small — which is the point:
it has been item 1 since 2026-08-19 and it is a weekend's work.
