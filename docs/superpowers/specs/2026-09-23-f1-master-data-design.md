# F1 — Master Data: Design

**Date:** 2026-09-23
**Status:** Approved in brainstorming; not yet planned.
**Slice:** Item 4 (frontend) in [`docs/ROADMAP.md`](../../ROADMAP.md), sub-project **F1** of the D-d
decomposition (F0 → **F1** → F2 → F3).
**Predecessor:** [F0 design](2026-09-14-f0-frontend-foundation-design.md), built as F0a (backend auth
prep) + F0b (the frontend). F1 follows the same two-half shape.

---

## Part 0 — Scope

F1 delivers the four master-data areas a distributor must fill in before the wedge is usable:
**customers and their contacts**, **products**, **price lists and their items**, and **tenant
settings** (the business profile).

It is split into two slices, merged in order:

| Slice | Contents |
|---|---|
| **F1a** | Backend prep: search on list endpoints, a sort allowlist, `fieldCodes` on master-data errors, a price-list-item update endpoint, contact validation. Contract regenerated. |
| **F1b** | The frontend: four feature areas, a shared `src/domain/` data layer, list/detail/form screens, and the gates that keep them honest. |

F1a exists so F1b builds against a finished contract rather than a moving one — the same reasoning
that put F0a before F0b.

### 0.1 Non-goals

- **Hindi.** F1 ships English only. The parked copy debts (duplicate `errors.fields.*` /
  `validation.*` pairs, R45's missing entered-value context in length errors) stay parked, and the
  three untested Devanagari clipping cases from R64 (`label`'s `leading-none`, `alert`'s
  `line-clamp-1`, the `md:text-sm` variant) stay open. Strings still land in namespaced JSON, because
  the i18n lint zone enforces that structurally — that is a code-organisation fact, not a copy pass.
- **Mobile layouts.** F1 is **laptop-first**, matching the Phase 1 exit criterion ("a distributor can
  actually use EasyCRM, on a laptop"). The responsive pass across master-data screens is a later
  slice. Nothing here may *prevent* that pass, but nothing here delivers it.
- **Optimistic concurrency.** See §5.1.
- **Toasts.** See §3.4.
- **The import wizard and owner analytics**, both deferred at D-d for lack of a backend.
- **H5's remaining half.** The roadmap's H5 entry is **stale on its first half**: `V33__assigned_to_indexes.sql`
  already ships `idx_customer_assigned` and `idx_enquiry_assigned`. What remains true is that no index
  supports a status-only order-list filter — `sales_order` carries only `(tenant_id, id)` and
  `(tenant_id, customer_id)`. That is out of scope here: it belongs to order-list work, and the load
  baseline (item 12) decides it on evidence. F1a adds only the indexes its own new queries require
  (§1.1), following the house pattern V33 states in its own comment: *the slice adding the query adds
  the index.*

### 0.2 Decisions

Referenced from here on as **F1-n**. Plan decisions get their own P-numbers at planning time.

| # | Decision |
|---|---|
| **F1-1** | Two slices, F1a then F1b, merged separately. |
| **F1-2** | `q` search on the three paged list endpoints: case-insensitive **substring**, backed by `pg_trgm` GIN indexes. |
| **F1-3** | Per-endpoint sort allowlist; unknown field → **422** `SORT_INVALID`, not the 500 it throws today. Page size capped at 100. |
| **F1-4** | Stable `fieldCodes` on every master-data validation and conflict error, enforced by a build-failing test. |
| **F1-5** | `PUT` for price-list items, taking rate fields only — `productId` is immutable after create. |
| **F1-6** | Contact validation mirrors the client's rules; setting a contact primary demotes its siblings in the same transaction. |
| **F1-7** | Per-entity frontend features (`customers`, `products`, `priceLists`, `settings`), not one `masterdata` blob. |
| **F1-8** | A new `src/domain/<entity>/` layer owns each entity's query keys, hooks and shared picker. Features own screens. |
| **F1-9** | One shared `masterdata` i18n namespace across the four features; `BOOT_NAMESPACES` unchanged. |
| **F1-10** | List state (`page`, `sort`, `q`, `active`) lives in URL search params as the single source of truth. |
| **F1-11** | Confirm dialogs use the native `<dialog>` element. No Radix, no injected styles, no CSP exemption. |
| **F1-12** | No toast system in F1. Success is announced in the destination page's permanently-mounted live region. P8 stays parked. |
| **F1-13** | Money stays a **string** from input to wire. No `parseFloat`, no `Number`, no client-side arithmetic. |
| **F1-14** | Last-write-wins on concurrent master-data edits is an accepted residual risk for F1 (§5.1). |
| **F1-15** | R107's entry-chunk protection is an **artifact-level** assertion driven by a build-time chunk→modules map, not a lint rule. |

---

## Part 1 — F1a: the backend contract

Every endpoint named here already exists. F1a extends five of them and adds one. Nothing is removed
or renamed, so the change is additive and oasdiff reports additions only (flipping oasdiff to blocking
is Phase 1 item 7, not this slice).

### 1.1 Search

A `q` parameter on the three paged list endpoints, alongside the existing `active` filter (AND, not
OR):

| Endpoint | `q` matches |
|---|---|
| `GET /api/v1/customers` | `businessName` **or** `gstin` |
| `GET /api/v1/products` | `name` **or** `sku` |
| `GET /api/v1/price-lists` | `name` |

Case-insensitive **substring** match. `q` is trimmed; blank is treated as absent; `@Size(max = 100)`.

Substring rather than prefix because business names here are routinely searched by a middle word — a
user looking for "Shri Ram Traders" types "ram". Prefix-only matching would fail the common case, not
an edge one.

**Indexes — two kinds, both new.** There is no index today on `customer.business_name`,
`product.name`, `product.sku` or `price_list.name`, so F1a adds:

1. **btree** on `(tenant_id, <sort column>)` to support §1.2's default `ORDER BY`, which would
   otherwise sort every tenant's rows in memory on every list request.
2. **GIN trigram** on the searched columns, because a leading-wildcard `ILIKE '%ram%'` cannot use a
   btree index and would seq-scan — which requires `CREATE EXTENSION IF NOT EXISTS pg_trgm`. This is a new extension dependency: available on RDS, present in the Testcontainers
image, and visible to squawk in the migration. It is justified here — unlike H5's indexes — because
F1a introduces the exact query that needs it, in the same change.

**Search must not widen visibility.** `CustomerVisibility` restricts a `SALES_EXEC` to rows where
`assignedTo = me OR assignedTo IS NULL`. The search predicate composes *inside* that scoping, never
around it. A new query path that quietly bypasses a scoping rule is the failure mode here, and §4.6
tests for it directly.

**Tenant isolation is unchanged and structural.** Search goes through Specifications with `@TenantId`
and RLS intact. No hand-written `WHERE tenant_id = ?`, per `CLAUDE.md` and challenge #1.

### 1.2 Sort allowlist

The paged endpoints bind Spring `Pageable` via `@ParameterObject` and return the
`platform/web/PageResponse.java` envelope, so `sort` is user-controllable. There is no allowlist
today: any entity property is sortable and an unknown one throws — **a live 500 on user input across
every paged list**.

F1a gives each paged endpoint an explicit allowlist and an explicit default sort — customers by
`businessName`, products by `name`, price lists by `name`, all ascending — so paging is stable. An unrecognised sort
field yields **422** with field `sort` and code `SORT_INVALID`. Page size is capped at **100**; today
it is effectively unbounded.

**Why 422 and not the 400 that plain HTTP reasoning suggests** — a deliberate departure, declared here
rather than left as a code comment (R30). An unknown `sort` property *binds successfully*: Spring
produces a valid `Sort`, and the failure surfaces later from JPA, so none of the existing 400
machinery (`MethodArgumentNotValidException`) is on this path. Reaching 400 would mean a new exception
type in `platform-primitives` plus a handler branch, for one family of call sites. Throwing the
existing `ValidationException` reuses the envelope, the `fieldCodes` path and the frontend's mapping —
which treats 400, 409 and 422 identically for field errors — at the cost of a status code that is
defensible rather than ideal.

### 1.3 `fieldCodes`

`platform/error/ApiExceptionHandler.java` already emits `fieldCodes` for bean-validation 400s, and
`platform/gst/Gstin.java` / `StateCode.java` emit real codes. But the master-data services throw with
a field and **no** code, so F1b's four-level error resolution falls through to raw server English on
exactly the errors these forms produce:

| Service | Rule | Code |
|---|---|---|
| `crm/CustomerService` | stateCode diverges from the GSTIN's state | `STATE_CODE_GSTIN_MISMATCH` — **reused**, not new: `docs/api/error-codes.md` already defines it for `AuthService.signup`'s identical meaning, and one meaning must not acquire two codes |
| | stateCode required when GSTIN absent | `STATE_CODE_REQUIRED` |
| | duplicate GSTIN (409) | `GSTIN_DUPLICATE` |
| `catalog/ProductService` | `hsnCode` not 4/6/8 digits | `HSN_CODE_INVALID` |
| | `gstRate` outside {0, 0.25, 3, 5, 12, 18, 28} | `GST_RATE_INVALID` |
| | negative `baseRate` | `BASE_RATE_NEGATIVE` |
| | duplicate SKU (409) | `SKU_DUPLICATE` |
| `catalog/PriceListItemService` | exactly one of `overrideRate`/`discountPct` | `RATE_RULE_XOR` |
| | negative `overrideRate` | `OVERRIDE_RATE_NEGATIVE` |
| | `discountPct` outside 0–100 | `DISCOUNT_PCT_RANGE` |
| | product already in this list (409) | `PRODUCT_DUPLICATE` |
| `catalog/PriceListService` | duplicate name (409) | `NAME_DUPLICATE` |

**And a test that fails the build** if any master-data service throws a `ValidationException` or
`ConflictException` carrying a field but no code. Without it the next rule someone adds silently
regresses to prose, and the regression is invisible from the frontend side until a user sees English
where a translated message belongs. This is the same defence-in-depth shape as
`TenantScopingArchTest`: the rule is enforced by the build, not remembered by a reviewer.

### 1.4 Price-list-item update

`PriceListItemController` has POST, GET (list) and DELETE but **no update** — changing a rate today
means DELETE then POST: two requests, not atomic, no idempotency key, and a crash between them loses
the line.

F1a adds `PUT /api/v1/price-lists/{priceListId}/items/{itemId}` taking a new
`PriceListItemUpdateRequest` carrying **`overrideRate` and `discountPct` only**. `productId` is
absent: changing which product a line refers to is a delete plus a create, not an edit. This mirrors
the existing `ProductCreateRequest` / `ProductUpdateRequest` split, where SKU is immutable after
create.

The XOR and range rules from §1.3 apply identically to update.

### 1.5 Contacts

`ContactRequest` carries `@NotBlank name` and nothing else — `email` has no `@Email`, `phone` and
`whatsappNumber` have no length or pattern constraint. A malformed email is accepted today, so any
client-side rule F1b writes would have no server mirror.

F1a adds `@Email` on `email` and `@Size` on `name`, `phone` and `whatsappNumber` matching the actual
column widths. These land in the bean-validation 400 branch, so they get `fieldCodes` for free.

**Primary-contact uniqueness.** `isPrimary` is stored with no enforcement, so a customer can have
three primary contacts, and F1b's customer row has to show *a* contact. Setting a contact primary now
demotes its siblings **in the same transaction**, scoped to that one customer.

### 1.6 Contract regeneration

`docs/api/openapi.yaml` regenerates and `OpenApiSnapshotTest`'s snapshot updates **in the same
commit** — it is a byte-for-byte guard and a stale snapshot fails the build. The file is generated
output: never hand-edited, and a conflict in it is resolved by regenerating.

---

## Part 2 — F1b: structure

### 2.1 Layers

```
src/api/                 generated client, authFetch, error classifier      (imports nothing from src)
src/lib/ src/components/ pure helpers and presentation
src/domain/<entity>/     keys.ts · queries.ts · mutations.ts · <Entity>Picker.tsx
src/session/             session read side
src/features/<entity>/   pages, forms, route-local components
src/app/                 router, providers, shell
```

`src/domain/` is new. It may import `api`, `lib` and `components`; it may **not** import `session`,
`features` or `app`. Features may import `domain`.

**Why a new layer rather than pickers under `components/`.** Master data is cross-referential: the
customer form needs a price-list picker and an assignee picker, the price-list-item form needs a
product picker, and F2's wedge needs customer *and* product pickers on every quotation line.
`frontend/eslint.config.js:175-180` generates a zone per directory under `src/features` — read from
the filesystem at lint time, so creating a feature directory creates its zone — and **features may
never import each other**. A picker therefore cannot live inside a feature.

Putting only the *widget* below `features/` would be legal but wrong: if `features/products` owns a
`productKeys` factory and a picker owns another, they are two TanStack Query cache keys for the same
rows. Creating a product and invalidating `productKeys.all` would refresh the list and leave every
picker stale. So the **data access** moves down with the widget: one key factory per entity, one
cache, one invalidation target.

The rule is uniform — **all four entities get a `domain/` module** — so there is no per-entity
judgement call about what counts as shared.

### 2.2 Routes and navigation

| Route | Screen |
|---|---|
| `/customers` | list |
| `/customers/new`, `/customers/:id/edit` | form |
| `/customers/:id` | detail, including contacts |
| `/products`, `/products/new`, `/products/:id/edit` | list, form |
| `/price-lists`, `/price-lists/new` | list, form |
| `/price-lists/:id` | detail — the items editor |
| `/settings` | tenant business profile |

All sit under `RequireSession` → `AppShell`. Each route is lazy through the existing
`withImportRetry` pattern, each declares `errorElement`, and each gets its own **sized** Suspense
skeleton rather than one top-level `fallback={null}` (R47, R70). `AppShell`'s currently-empty `<nav>`
gains the four links with an active state.

**Settings and roles.** `GET /api/v1/tenant` is readable by any member, but `PATCH` is owner-only,
enforced imperatively in `tenant/TenantService.java:30` with no annotation — there is no
`@PreAuthorize` anywhere in the codebase — so the frontend must gate itself. The page renders
read-only for everyone and shows the editable form only on an explicit `role === 'OWNER'` equality
check. Fail-closed, and it satisfies R49: a future platform-admin role from a newer server falls into
"not owner" rather than escaping as a rejection.

Only `address`, `phone` and `email` are writable. `businessName`, `gstin` and `stateCode` are
read-only — no endpoint mutates them — and the page says so rather than showing dead inputs.

### 2.3 Types

F1b is the moment R43 named for the split. `src/api/types.ts` keeps only what `src/session/` needs;
each `domain/<entity>/` owns its own aliases off `components['schemas']`. For the grey area: **a type
crossing layers stays in `api/types.ts`; a type used in one place lives there.**

### 2.4 i18n

One shared `masterdata` namespace across all four features, loaded on the first master-data route and
cached thereafter — not one namespace per feature. R46 established that namespaces compile to
*sibling* chunks, so per-feature namespaces would add a sequential network leg to every navigation
between these screens. `BOOT_NAMESPACES` stays exactly as it is, and the test pinning it stays; a
second test pins the new namespace list so it cannot regrow silently either.

A shared namespace does not weaken §2.1's boundaries. A namespace is a fetch unit, not an import edge.

---

## Part 3 — F1b: behaviour

### 3.1 List state in the URL

`page`, `sort`, `q` and `active` are read from and written to `useSearchParams`, and the query key is
derived from them. One source of truth, a shareable filtered list, and a correct back button.

Search debounces ~300 ms and writes with `replace: true`, so eight keystrokes do not push eight
history entries. Page changes use `placeholderData` so the previous page stays visible instead of
collapsing to a skeleton — on 4G that flash reads as breakage, not as speed.

Default filter is `active=true` with a visible toggle for inactive records.

### 3.2 The table, and one live region

A laptop-first table built on the existing `ui` primitives.

The announcement needs care. Per R85 and R90 the status region is **mounted once, with state-driven
content** — never inserted together with its text, because a region that appears already carrying its
message is silent on most screen readers, at exactly the moment a user most needs telling. It carries
"Showing 1–20 of 137", the loading state, and the "Acme Traders deactivated" confirmations.

Three distinct empty states, not one:

| State | Offers |
|---|---|
| No records yet | "Add your first customer" |
| No matches for this search | clear the search |
| Failed to load | retry |

R91 requires each dead end to carry an actionable next step.

### 3.3 Confirm dialogs

Radix `Dialog` and `AlertDialog` are lint-banned because `react-remove-scroll` injects a runtime
`<style>` tag that `style-src 'self'` refuses (R83, R86). The native `<dialog>` element with
`showModal()` provides focus trapping, Esc-to-close and a backdrop from the platform, at zero bundle
cost, with nothing injected — `::backdrop` styling lives in the static stylesheet.

Confirmation is required for all destructive actions, with wording matched to severity: **deactivate**
(reversible) for customers, products and price lists; genuine **hard delete** for contacts and
price-list items.

*Known stopping point:* jsdom's `HTMLDialogElement` support is historically incomplete and
`showModal()` may be missing under Vitest. Fallback order: a test-setup polyfill, then — only if that
proves inadequate — a hand-built focus-trapped overlay. Probe this before building on it.

### 3.4 No toasts

P8 parks the toast container until "F1's first background failure", and F1 produces none: every
mutation here is foreground, with the user looking at the screen that initiated it. Success is
announced in the destination page's permanent live region (§3.2). Less code, less bundle, one fewer
notification system to get wrong. P8 stays parked.

### 3.5 Money is a string

`baseRate`, `overrideRate` and `discountPct` are `BigDecimal` server-side and **JSON strings on the
wire**. The form carries them as strings from input to submit, validated with a decimal regex. No
`parseFloat`, no `Number`, no arithmetic in the client.

This is challenge log #2's rule applied on the frontend. A well-meaning "parse it so we can validate
it" is how binary floating point gets back into rupee amounts, and the server is authoritative
regardless.

### 3.6 Forms

The F0 machinery is reused as-is: `TextField`, `PasswordField`, `SelectField`, `FormAlert`,
`FieldFooter`, `fieldIds`, `useFieldError`, `applyApiError`. New field shapes needed: GSTIN with the
existing upper-casing `forceCase`, a state select off `lib/gst/states.ts`, enum selects (`source`,
`uom`, the GST slab), and an integer field for `creditDays`.

Three F0 rules are easy to lose and must hold on every new form:

- **An explicit resubmit guard** (`if (mutation.isPending) return`) on every submit. R100: the Web
  Lock serializes duplicate submits, it does not deduplicate them. The false invariant was copied
  verbatim into three F0 pages before review caught it.
- **`FormAlert attempt={submitCount}`**, driven by a genuine user-action counter, never a constant and
  never a retry counter. R65/R63/R89: otherwise a repeated identical error never re-announces, and a
  constant typechecks while silently restoring the bug.
- **`aria-disabled`, not `disabled`**, on pending submits (R79), so focus is not dropped to `<body>`
  for the full round trip on patchy 4G.

Server errors resolve through the existing four levels documented in F0 spec Part 10 —
`errors.fields.<field>.<CODE>` → `errors.fields.<CODE>` → `errors.codes.<code>` → server text (R30:
four levels, not §4.6's three). Which is precisely why §1.3 is a prerequisite and not a nicety.

---

## Part 4 — Gates and testing

### 4.1 Per-route budget

Every new route gets its `ROUTE_ENTRIES` entry **in the task that creates the route**, never in a
cleanup pass: defect I1 recorded `/` being built but never measured, and an unmeasured route is where
weight hides. Each page task runs `pnpm build && pnpm budget` as a verification step and records the
figure (R3, R12, R82).

Headroom is the number to watch — `/login` already sits at ~176 KB against the 200 KB ceiling. The
table, dialog and pickers land on master-data routes, but they share the entry chunk, which is what
R87's baseline-delta column exists to surface.

### 4.2 R107 — the entry-chunk gate F1 was assigned

F0b's I7 moved `cn` out of the entry chunk, worth ~13 KB on **every** route at once, and it has no
regression test. Re-importing `cn` or `components/ui/*` into anything router-reachable costs it
straight back, and `pnpm budget` would not notice, because every route carries 23–55 KB of headroom to
absorb it silently.

F1 adds an **artifact-level** assertion: a small Rollup plugin emits a chunk→modules map at build
time, and `check-budget.mjs` fails if the entry chunk contains any module from a named forbidden set.
Artifact-level rather than a lint rule because it measures the built output — it cannot be fooled by a
re-export or by an import spelled a different way. Like every other gate here, it is not done until it
has been made red on purpose.

### 4.3 Gates must be proven to bite

**A gate is not done until it has been made red by breaking the code it guards, with the red run
recorded.** F0b found sixteen assertions that measured nothing, every one by mutation rather than by
reading, across fourteen separate incidents (R39, R54, R69, R99). The handoff calls this the
highest-yield verification technique the project has found. F1 budgets for it rather than hoping.

Per R74/R75, each new lint zone gets an **allow** case as well as a deny case. The allow half was the
one missed every round.

### 4.4 Frontend tests

`renderApp` already mounts the real router, providers and session runtime, so pages plug in directly.
MSW handlers stay contract-typed through `createOpenApiHttp<paths>` — an illegal path, status or body
fails `tsc`, which is what keeps the mocks honest about F1a's new shapes. Customer, product and
price-list fixtures join `test/fixtures.ts`.

The coverage floor stays at **97 / 92 / 96 / 98**. If a new area dips below it, the answer is tests,
not a lower floor.

New `eslintGates.test.ts` cases cover the `domain` zone in both directions: deny `domain → features`,
`domain → session`, `domain → app`; allow `features → domain` and `domain → api|lib|components`.

### 4.5 E2E

Five paths, each proving something unit tests cannot:

1. Create a customer → it appears in the list → search finds it → edit → deactivate through the
   native dialog → the inactive filter shows it.
2. Submit a product with an invalid GST rate → the server's `GST_RATE_INVALID` renders as a field
   error. **The highest-value assertion in the slice:** it proves the F1a→F1b `fieldCodes` contract
   end to end, across both halves.
3. Price list → add an item → change its rate via the new `PUT` → delete it.
4. Settings: a non-owner sees read-only; an owner can save.
5. A paged, searched list survives reload and back-navigation (proves F1-10).

`expectAccessible` (axe) runs on **every page state** each spec visits, per R28 — with the standing
caveat from R102/R62 that axe checks text contrast only, so the known `--ring` (1.54:1) and `--input`
(1.26:1) non-text failures stay invisible to it, and F1's form-heavy screens inherit those tokens.

### 4.6 Backend tests for F1a

- **Search:** matching, non-matching, case-insensitivity, substring-not-merely-prefix, composition
  with `active` — and **that search respects `CustomerVisibility`**, so a `SALES_EXEC` searching
  cannot surface a customer assigned to someone else. A new query path bypassing a scoping rule is
  this change's characteristic failure.
- **Sort allowlist:** allowed fields pass; an unknown field yields 400 `SORT_INVALID` rather than 500;
  the default sort is stable; the size cap holds.
- **`fieldCodes`:** one test per code in §1.3, plus the build-failing rule that catches the next
  omission.
- **Price-list-item `PUT`:** happy path, XOR validation, cross-parent 404, tenant isolation.
- **Contacts:** the new constraints, and primary-demotion confined to the one customer.
- **Migration:** `pg_trgm` and the GIN indexes apply cleanly, squawk passes, and RLS still applies to
  the indexed queries.

---

## Part 5 — Accepted residual risks

### 5.1 Last-write-wins on concurrent master-data edits

No response DTO exposes `@Version`, and there is no ETag or If-Match support anywhere in the API, so
two users editing the same customer is last-write-wins: the second save silently overwrites the first
and the client cannot do compare-and-set. The generic 409 from `ApiExceptionHandler` fires only on a
true row-level race, not on a stale-read overwrite.

**Accepted for F1.** These shops run one to three users, master data changes rarely, and the fix —
versions on responses plus If-Match plumbing — is a contract-wide decision that deserves its own
slice rather than being smuggled into F1a. The cost of deferring is that F2 builds on the same DTOs,
which makes the eventual change larger; that trade was made knowingly.

### 5.2 At-most-one-primary-contact is enforced in application code, not the schema

The "one primary contact per customer" rule is enforced inside a transaction, in application code
(F1a Task 8). There is no partial unique index on `contact` over `(tenant_id, customer_id) WHERE
is_primary`, so two concurrent creates that both set `isPrimary: true` for the same customer can
each pass the in-transaction check and commit — leaving two primary contacts, with nothing in the
database to detect it afterward.

This is a different failure mode from 5.1's last-write-wins: 5.1 is a stale-read field *overwrite*
(the row still satisfies every invariant afterward, just not with the value the losing writer
expected); this is an invariant the row set as a whole can end up *violating* outright, with no
constraint anywhere to catch it.

**Accepted for F1.** The fix — a partial unique index on `(tenant_id, customer_id) WHERE
is_primary` — needs care over demote/promote flush ordering (an update that moves the primary flag
from one contact to another must not transiently violate the index mid-transaction, e.g. by
demoting the old primary and flushing before promoting the new one), and was deliberately not taken
in this slice.

### 5.3 Inherited, unchanged by F1

- The `--ring` and `--input` non-text contrast failures (R102, R62), invisible to axe.
- R64's three untested Devanagari clipping cases, deferred with Hindi.
- Contacts and price-list items return bare `List<…>` rather than a `PageResponse`. Acceptable while
  a customer has a handful of contacts and a price list a bounded number of lines; it becomes a real
  gap if either grows, and F1b's types will differ from the paged endpoints in shape.

---

## Part 6 — Operational notes

**CI cannot vet this branch.** The workflow triggers on `push: [main]` and `pull_request`, and this
repo has never used a PR for real work — so a feature-branch push fires nothing and the first signal
arrives post-merge. Gates run locally; the merge is the test. CI is five jobs: `check`,
`supply-chain`, `dependency-check` (~16 min, dominates wall-clock), `frontend`, `e2e`.

**Every `pnpm` command needs `fnm exec --using=24 -- pnpm <cmd>`.** A non-interactive agent shell
starts on the system Node v25.2.1 even though `~/.zshrc` activates fnm, because the hook never runs.
`engine-strict` then fails, and the wrong fix — loosening `engines` — is the one a subagent reaches
for first.

**Installed toolchain is ground truth** (R37): vite 8.3.0, `@vitejs/plugin-react` 6.1.1, vitest 5.0.1,
typescript 5.9.3 pinned `^5` (typescript-eslint caps below 6.1.0). Do not "correct" these toward any
plan's expectations.

**Reviews consult the specialist registry.** All five frontend lenses match F1b and are callable by
`subagent_type` directly. F1a is backend and takes the general review plus the security lens where it
touches query construction.

**Challenge log.** Candidates already visible: the picker cache-coherence argument behind F1-8, the
visibility-composition requirement in §1.1, and the artifact-level entry-chunk gate in §4.2. Numbers
are assigned at commit time, never reserved (R38).

---

## Part 7 — Known stopping points

Each needs a fallback written into the plan step that hits it, per F0b's practice:

| # | Risk | Fallback |
|---|---|---|
| 1 | jsdom lacks `HTMLDialogElement.showModal()` | test-setup polyfill, then a hand-built focus-trapped overlay |
| 2 | `CREATE EXTENSION pg_trgm` needs privileges the Testcontainers or dev role lacks | grant in the container init; if genuinely unavailable, fall back to prefix matching and record the reversal as a spec amendment |
| 3 | The Rollup chunk→modules map is not obtainable cleanly from the plugin hook | fall back to the ESLint rule barring `cn`/`components/ui/*` from router-reachable files, and record that R107 is then source-level, not artifact-level |
| 4 | Spring's `Pageable` resolution makes a per-endpoint sort allowlist awkward to express | a shared resolver-level allowlist keyed by endpoint, rather than per-controller annotations |
