# EasyCRM P1 — Activity Log & Follow-Ups Design

**Status:** Design approved, pre-implementation
**Date:** 2026-08-30
**Parent spec:** `2026-07-22-easycrm-design.md` §data-model ("Activity"), §domain-events,
§mobile-scope, §async-jobs
**Target architecture:** `../../architecture/2026-07-29-current-architecture.md`
**Depends on:** everything merged on `main` through `c81f59f` (the record-visibility merge).
This slice depends on that one directly: `VisibleFinder` is the gate the activity table is
protected by, and `VisibilityPolicy` is where follow-up filtering is added.

---

## 1. Context & purpose

The one-line pitch for this product ends *"...and you never lose a follow-up again."* That half of
the pitch has no implementation. The wedge (enquiry → quotation → order) is complete and hardened,
but nothing in the system records **what was said** or **what happens next**. A distributor's real
working day is a sequence of calls, WhatsApp messages and shop visits against a lead — and the
single most common way a deal dies is that nobody rang back.

This slice builds the two entities the parent spec §data-model already names:

- **`activity`** — an append-mostly log of what happened, polymorphic against the funnel.
- **`follow_up`** — a due-dated, assigned, first-class task, linked to the same subjects.

The last four slices were hardening (RLS forcing, rate limiting, record visibility). The correctness
backlog is now empty; this is the first slice in some time whose job is to **move the product**.

### Why the two are one slice

They are separable on paper and inseparable in use. The moment that matters is *"logged the call,
ringing them Tuesday"* — one action, on a phone, on patchy 4G. Building the log first and the task
later means designing the same subject-linkage, the same visibility gate and the same timeline
surface twice, then retrofitting the compose flow that is the actual product value. §6.1 makes that
compose flow a single transaction, which is only possible because both tables land together.

---

## 2. Scope

**In scope.** The `activity` and `follow_up` tables and aggregates; the polymorphic subject link
across `Customer`, `Enquiry`, `Quotation`, `Order`; the visibility gate for both (§4); the REST
surface in §5; the three flows in §6; a `SYSTEM` activity written on quotation acceptance via the
existing event seam; the `AssignableUsers` extraction in §7.4; the two ArchUnit additions in §8.

**Out of scope — do not build.** Any scheduler, cron, or `@Scheduled` anything. Any notification,
email or WhatsApp nudge. A `notification` table. An `OVERDUE` status column. Attachments on
activities (the parent spec's `attachment` entity is a separate concern). A global cross-subject
activity feed. Recurring follow-ups. Snooze. Reassignment notifications. Any change to the four
existing aggregates' own tables.

---

## 3. What a "reminder" is in this slice, and why nothing is scheduled

The parent spec §data-model says `follow_up` is *"first-class, with its own reminder scheduler"* and
§async-jobs lists *"Follow-up reminders — every few min — due follow-ups → notification / WhatsApp
nudge."* **This slice deliberately builds neither.**

The reason is not effort, it is that there is nowhere for a reminder to go:

- **WhatsApp is a `wa.me` deep link.** There is no WhatsApp Business API integration, and R1 ships
  the zero-cost link by design. Nothing server-side can push a message.
- **Email exists (`iam/email`) but is the channel this product's users read least**, and a nudge
  channel needs delivery tracking and dedupe — *don't email the same follow-up every morning
  forever* — which no spec has scoped.
- **There is no frontend yet** to receive an in-app notification, so a `notification` table would be
  rows written on speculation against an unwritten consumer.

So a reminder here is **a read, not a push**: `GET /follow-ups?scope=OVERDUE`, `scope=DUE_TODAY`, and
a `summary` count for the dashboard tile. Nothing is lost, because the list always surfaces it. The
promise "you never lose a follow-up" is kept by the data being *there and queryable*, not by a job.

**`OVERDUE` is therefore not a status.** It is `status = PENDING AND due_at < now()`, computed at
read time. This is the load-bearing consequence: there is no denormalised flag, so there is no job
that can fall behind and leave a row **lying about its own state**. A status column plus a job that
flips it is strictly more machinery for strictly less truth.

When a real channel arrives, the scheduled job is additive — it reads the same predicate this slice
already implements (`DueWindow`, §7.3) and sends. Nothing here has to be undone.

> **Note for whoever builds the first scheduled job** (quotation auto-expiry is the likely
> candidate, and is a separate backlog item): a scheduled job has **no JWT**, so it has no
> `TenantContext`. It must iterate tenants and wrap each in `TenantContext.runAs(...)`, and it must
> establish the principal **before** the transaction opens — `TenantAwareTransactionManager` reads
> it in `doBegin` to set the RLS GUC, and Hibernate resolves a session's tenant once at session-open
> and never re-reads it (challenge #9). That seam exists; this slice simply does not use it.

---

## 4. Visibility — two tables, two strategies, deliberately

This is the design's central decision and the part most likely to be misread as an inconsistency.

An activity or follow-up hanging off an enquiry I cannot see **must not be readable**. The tables
look symmetrical, but their access patterns are not, and the parent spec already hints at why:
`follow_up` has an `assigned_to`; `activity` does not.

### 4.1 `follow_up` joins the visibility-scoped set

A follow-up carries its own owner, and the dashboard's primary query is *"my follow-ups, due today,
across every subject."* Resolving a subject per row to answer that would be a four-way union; owning
an `assigned_to` makes it one indexed predicate.

`VisibilityPolicy` gains:

```java
public Specification<FollowUp> followUps() {
    if (unrestricted()) return unrestrictedSpec();
    return (root, query, cb) -> cb.equal(root.get("assignedTo"), currentUserId());
}
```

Note this is **not** the `ownedOrUnassigned()` shape the other four aggregates use. `assigned_to` is
`NOT NULL` on this table — a follow-up nobody owns is precisely the failure mode the feature exists
to prevent — so the `IS NULL` branch would be unreachable code. A two-line method that says what it
means beats inheriting a vestigial branch that invites a reader to wonder when it fires.

`VisibleFinder` gains `findFollowUp(UUID)` and `pageFollowUps(Specification, Pageable)`.
`FollowUpRepository` joins `VisibilityScopingArchTest.GUARDED_REPOSITORIES` (§8).

### 4.2 `activity` stays out, and the exemption is structural

An activity is **always read in a subject's context** — an enquiry's detail page, a customer 360.
There is no global activity feed in the parent spec and §2 puts one out of scope. So activity never
needs a cross-subject visible list, and gating at the *subject* is sufficient.

`VisibleFinder` gains the single gate:

```java
/**
 * Resolves a polymorphic subject through the same visibility filter as a direct read.
 * Cross-tenant and not-visible-to-you both surface as NotFoundException — the house 404
 * rule. This is the ONLY thing protecting the activity table.
 */
public UUID requireVisibleSubject(SubjectType type, UUID id)
```

It switches over `CUSTOMER / ENQUIRY / QUOTATION / ORDER` onto the four `findX` methods that already
exist, and throws `NotFoundException` on an empty result. Because it lives *inside* `VisibleFinder`,
the guarded-repository rule needs no new exemption for subject resolution — the one class already
permitted to read those repositories is the one doing it.

**The exemption is made structural rather than promised**, and this requires one unusual choice:

```java
public interface ActivityRepository extends Repository<Activity, UUID> {
    Activity save(Activity activity);
    Page<Activity> findBySubjectTypeAndSubjectIdOrderByOccurredAtDesc(
        SubjectType subjectType, UUID subjectId, Pageable pageable);
    Optional<Activity> findByIdAndSubjectTypeAndSubjectId(
        UUID id, SubjectType subjectType, UUID subjectId);
}
```

**`ActivityRepository` extends the bare `Repository<T, ID>` marker, not `JpaRepository`.** This is
the whole mechanism, and it is deliberate. Every other repository in this codebase extends
`JpaRepository`, which inherits `findById`, `findAll`, `findAllById` and the rest. Those methods are
**not declared** on the sub-interface, so a rule phrased over declared methods would happily pass a
service that calls `activities.findById(id)` and never resolves a subject at all. `Repository` is a
pure marker and inherits nothing: the three methods above are the complete set of operations that
exist, so:

- You cannot read an activity without naming a subject — **there is no method that lets you**.
- You cannot name a subject without having passed it through `requireVisibleSubject` first.

This is also why `VisibilityScopingArchTest`'s owner-name approach could not have been reused here.
That test's own comment records the empirical finding that a reference to an inherited
`CrudRepository` method resolves its target owner to the Spring Data supertype rather than to the
local interface, and is therefore invisible to any owner-name check. Extending `Repository` sidesteps
the entire problem by leaving nothing to inherit: the guard in §8 then only has to police what is
declared, which is exactly what ArchUnit can see.

The cost is that `PATCH /api/v1/activities/{id}` must carry `subjectType` and `subjectId` in its
body, since no by-id-alone lookup exists. This is a small price and arguably an improvement: the
client is always editing a row it just rendered inside a subject's timeline, so it has both to hand,
and the write route ends up as self-consistent with the read route (§9), which requires the same two
parameters. The alternative — declaring `findById` and gating it with a convention at the single
call site — is exactly the "comment saying always resolve the subject first" that this section
exists to avoid.

### 4.3 Why not put `activity` in the guarded set anyway

Because `VisibilityPolicy` would have nothing to filter on. An activity has no `assigned_to`, and
its visibility is genuinely derived, not intrinsic. A `Specification` that re-derives the subject's
visibility per row would be a four-branch `CASE` over a subquery per subject type — slower, harder
to read, and no safer than the gate, since every call site must name a subject regardless.

---

## 5. Data model

Both entities live in **`com.easycrm.sales`**, per the parent spec's folder layout
(`sales/ # P1: enquiry, quotation, version, order, follow_up, activity`). No new top-level package.

`SubjectType` lives in **`com.easycrm.platform.visibility`**, because `VisibleFinder` owns the
resolve gate that switches over it. `sales` imports it. (`platform.visibility` already depends on
`crm` and `sales` for the four entity types — this follows the established direction, it does not
invert anything.)

### 5.1 `activity` — `V27__activity.sql`

| column | type | notes |
|---|---|---|
| `id` | `UUID PK` | `BaseEntity`, UUIDv7-style time-sortable |
| `tenant_id` | `UUID NOT NULL` | `TenantScopedEntity` / `@TenantId` |
| `subject_type` | `VARCHAR(16) NOT NULL` | `CUSTOMER` / `ENQUIRY` / `QUOTATION` / `ORDER` |
| `subject_id` | `UUID NOT NULL` | polymorphic, **no FK** |
| `type` | `VARCHAR(16) NOT NULL` | `CALL` / `WHATSAPP` / `EMAIL` / `VISIT` / `NOTE` |
| `body` | `VARCHAR(2000)` | |
| `outcome` | `VARCHAR(200)` | nullable, **free text** — see below |
| `occurred_at` | `TIMESTAMPTZ NOT NULL` | user-supplied, defaults to now, future rejected |
| `logged_by` | `UUID` | |
| `source` | `VARCHAR(8) NOT NULL` | `MANUAL` / `SYSTEM` |
| `created_at`, `updated_at`, `version` | | `BaseEntity` |

```sql
CREATE INDEX idx_activity_subject
    ON activity (tenant_id, subject_type, subject_id, occurred_at DESC);
```

**`outcome` is free text, not an enum, on purpose.** The parent spec names the field and never
enumerates its values. An enum invented now — `CONNECTED`, `NO_ANSWER`, `INTERESTED` — would be a
guess at how Indian distributors actually characterise a call, baked into a check constraint,
before a single real user has typed one. Nothing reports on it yet. Promoting it to an enum once
real outcomes have been observed is a migration and a backfill, not a redesign; inventing the wrong
enum now and living with it is the expensive direction.

**`occurred_at` is user-supplied and distinct from `created_at`.** A 3pm call gets logged at 9pm
after the shop closes. The timeline sorts on `occurred_at`; `created_at` remains the immutable
record of when the row was written. A future `occurred_at` is rejected (§7.1) — you cannot log a
call you have not had.

### 5.2 `follow_up` — `V28__follow_up.sql`

| column | type | notes |
|---|---|---|
| `id`, `tenant_id` | | as above |
| `subject_type`, `subject_id` | `VARCHAR(16)`, `UUID`, both `NOT NULL` | same polymorphic pair |
| `due_at` | `TIMESTAMPTZ NOT NULL` | past values allowed on create — see §7.2 |
| `assigned_to` | `UUID NOT NULL` | see §4.1 |
| `status` | `VARCHAR(16) NOT NULL` | `PENDING` / `DONE` / `CANCELLED` |
| `note` | `VARCHAR(500)` | what the follow-up is about |
| `completed_at` | `TIMESTAMPTZ` | |
| `completion_note` | `VARCHAR(500)` | stores the `note` sent to `/complete` **or** the `reason` sent to `/cancel`; one column, because a row is only ever one of the two, and which one is recoverable from `status` |
| `created_by` | `UUID` | |
| `created_at`, `updated_at`, `version` | | `BaseEntity` |

```sql
CREATE INDEX idx_follow_up_owner_due
    ON follow_up (tenant_id, assigned_to, status, due_at);
CREATE INDEX idx_follow_up_subject
    ON follow_up (tenant_id, subject_type, subject_id);
```

`idx_follow_up_owner_due` matters and is cheap. The record-visibility slice shipped
`assigned_to = :me OR assigned_to IS NULL` on `customer` and `enquiry` with **no index behind it**
(a known open item in `HANDOFF.md` §8, "Before the first large tenant"). This table's equivalent
predicate is the dashboard's hottest query, run on every login. Getting it right at creation costs
one line; retrofitting it costs a migration on a live table.

### 5.3 RLS — `V29__rls_activity_follow_up.sql`

Both tables get `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, and a `tenant_isolation`
policy matching V21/V26. This is self-enforcing rather than a matter of remembering:
`RlsCoverageIntegrationTest` keys on the presence of a `tenant_id` **column**, so it goes red on its
own if either table ships unforced. `TenantScopingArchTest` likewise fails the build if either
entity omits `@TenantId` — both get it by extending `TenantScopedEntity`.

---

## 6. The three flows

### 6.1 Log-and-schedule — one request, one transaction

`POST /api/v1/activities` accepts an optional nested `nextFollowUp` object and writes both rows
atomically.

This is the product's actual moment. A trader ends a call standing in a warehouse on a ₹8k Android
over patchy 4G; "logged it, ringing them Tuesday" has to be one tap. Two round-trips means the
second one fails somewhere and the follow-up — the thing the whole feature exists to protect — is
the half that goes missing. The parent spec §performance-budget's "optimistic follow-up completion"
is the frontend counterpart of the same concern.

The subject is resolved **once** via `requireVisibleSubject` and reused for both rows: one gate, one
404 decision, no window in which the two rows could disagree about which subject they hang off.

### 6.2 Complete-and-log — the mirror image

`POST /api/v1/follow-ups/{id}/complete` accepts an optional `{type, body, outcome}` and, when
present, writes an activity against **the follow-up's own subject** in the same transaction. Closing
a task and recording what happened are one user intention.

### 6.3 `QuotationAcceptedEvent` → a `SYSTEM` activity

A new `QuotationAcceptedActivityListener` sits beside the existing `OrderAcceptedAuditListener`:
same `@EventListener`, same synchronous, same-transaction semantics (the activity commits or rolls
back with the order — challenge #3), writing `source = SYSTEM` against the quotation subject.

The parent spec §domain-events already commits to this — *"On quote acceptance, an order is created
and an activity logged"* — and names the payoff explicitly: new behaviour arrives as **a new
subscriber, not an edit to `QuotationService`**. This slice is the first time that claim is tested
by someone other than its author, and it holds: `QuotationService` is not touched.

`SYSTEM` rows are never editable (§7.1). The listener runs with the accepting user's principal, so
`logged_by` is that user; `source` is what distinguishes the row, not a null actor.

---

## 7. Invariants and error contract

Invariants live in the **entity**, matching `Enquiry.requireActive` and `Order`'s named
preconditions. Services orchestrate; aggregates refuse.

### 7.1 Activity

| Case | Result |
|---|---|
| subject cross-tenant, or not visible to me | **404** `NotFoundException` — the §4.2 gate |
| `occurredAt` in the future | 422 `ValidationException("occurredAt", …)` |
| edit an activity whose `source` is `SYSTEM` | 422 `ValidationException("id", …)` |
| edit an activity I did not log | 422, not 404 |
| delete anything | no route exists |

The **422-not-404** choice for someone else's activity is deliberate and is *not* a departure from
the house rule. The rule (parent spec §errors) is that cross-tenant access returns 404 because a 403
would confirm existence. Here existence is **already confirmed** — the row is on a subject I can
see, and I can read it in the timeline. Returning 404 on the edit would be a lie that reveals
nothing extra and actively misleads the client into retrying a GET that succeeds.

Editing is scoped to `body` and `outcome`. `type`, `occurredAt`, `subject` and `source` are fixed at
creation: correcting a typo in a call note is a correction; changing which enquiry a call was about,
or when it happened, is rewriting history.

### 7.2 Follow-up

| Case | Result |
|---|---|
| subject not visible | 404 |
| `assignedTo` not an `ACTIVE` user | 422 `assignedTo` |
| complete or cancel a non-`PENDING` follow-up | 422, naming the current status |
| cancel without a reason | 422 `reason` — mirrors `Order.cancel` |
| reschedule a non-`PENDING` follow-up | 422 |

**`dueAt` in the past is allowed on create.** "I should have called them yesterday" is a real thing
to record, and it lands in `scope=OVERDUE`, which is exactly where it belongs. Rejecting it would
push users to enter a fake future date, which is worse data.

### 7.3 Time handling — `DueWindow`

`DueWindow` is a small pure class computing today's IST boundaries from an injected `Instant`:

```java
DueWindow.today(Instant now)  // -> [startOfTodayIST, endOfTodayIST)
```

It reuses `IndianFormats`' existing `ZoneId.of("Asia/Kolkata")` rather than introducing a second
timezone source. Every tenant is Indian by product definition; a per-tenant timezone column is
out of scope and would be the wrong thing to add before a non-Indian tenant exists.

Services take a `Clock` bean (`Clock.systemUTC()` in production, via a new
`platform.time.ClockConfig`). This is the first `Clock` in the codebase.

**Why a `Clock` bean and a pure class, rather than one or the other.** Midnight-boundary correctness
needs deterministic time, but overriding the `Clock` bean in an integration test would **fork the
Spring context** that all 64 `IntegrationTest` subclasses currently share — a real cost for a
12-second suite. Splitting it means the boundary logic is unit-tested at 23:59:59 and 00:00:00 IST
with no Spring at all, and the integration tests only ever use ±2-day offsets, which are not
midnight-sensitive. No forked context, no test that goes red because CI happened to run at 23:58.

### 7.4 Targeted cleanup — `AssignableUsers`

`requireAssignableUser` is currently copy-pasted verbatim into `EnquiryService` (line ~101) and
`CustomerService` (line ~100). `FollowUpService` needs the same check, which would make three
copies. Extract it to a small `com.easycrm.iam.AssignableUsers` component and route all three
through it.

Rule of three, and it is code this slice is already obliged to touch. This is scoped deliberately:
no other refactoring of those two services is in scope.

---

## 8. The guards

Two additions to the ArchUnit suite.

**1. `FollowUpRepository` joins `VisibilityScopingArchTest.GUARDED_REPOSITORIES`.** Note that
`ALLOWED_METHODS` in that test is a set of method *names* shared across all guarded repositories, so
any custom finder declared on `FollowUpRepository` must either go through `VisibleFinder` or be
added there with a stated reason — the same review bar as adding a table to
`TenantScopingArchTest.GLOBAL_TABLES`. The intent is that it needs **no** new allowlist entries.

**2. A new `ActivityRepositoryScopingArchTest`** with two assertions, both needed:

1. **`ActivityRepository` extends `org.springframework.data.repository.Repository` and does not
   extend `CrudRepository`, `ListCrudRepository`, `JpaRepository` or `PagingAndSortingRepository`.**
   Without this, assertion 2 is worthless — see §4.2. This is the assertion that actually carries
   the guarantee, and it is the one a future contributor is most likely to break innocently, by
   "fixing" what looks like an oversight.
2. **Every method declared on it is `save`, or takes both a `SubjectType` and a `UUID` subject
   id.** Checked on the parameter types, not on the method name, so `findByLoggedBy` fails and a
   correctly-scoped finder named something unexpected still passes.

This makes "activity reads are always subject-scoped" a build-enforced property of the interface
rather than a convention a future contributor has no way to discover.

Both are allowlist-shaped, not blocklist-shaped, for the reason `VisibilityScopingArchTest` already
documents: a blocklist of known-bad method names silently passes the derived query someone adds next
year, which is the exact failure the guard exists to prevent.

---

## 9. API surface

### Activities

| Route | Notes |
|---|---|
| `POST /api/v1/activities` | `subjectType`, `subjectId`, `type`, `body`, `outcome?`, `occurredAt?`, `nextFollowUp?` → **201** |
| `PATCH /api/v1/activities/{id}` | `subjectType`, `subjectId`, `body`, `outcome`; own `MANUAL` rows only |
| `GET /api/v1/activities?subjectType=&subjectId=` | both params **required**; paged, `occurredAt DESC` |

There is no unscoped activity route, read or write. `subjectType` and `subjectId` are required on
all three, and that is the API-level expression of §4.2 — no route has a shape in which the gate can
be skipped. The `PATCH` carrying them in its body is not redundancy with `{id}`: it is what lets
`ActivityRepository` avoid declaring a by-id-alone lookup at all.

A mismatched pair — a real activity id with the wrong subject — returns **404**, since
`findByIdAndSubjectTypeAndSubjectId` simply finds nothing. That is the correct answer and not a
special case worth coding around.

### Follow-ups

| Route | Notes |
|---|---|
| `POST /api/v1/follow-ups` | → **201** |
| `GET /api/v1/follow-ups` | `scope=OVERDUE\|DUE_TODAY\|UPCOMING\|ALL`, plus `status`, `assignedTo`, `subjectType`+`subjectId`; paged |
| `GET /api/v1/follow-ups/summary` | `{overdue, dueToday, upcoming}` counts — the dashboard tile |
| `GET /api/v1/follow-ups/{id}` | |
| `PATCH /api/v1/follow-ups/{id}` | reschedule / reassign / re-note; full-header-replace per house convention |
| `POST /api/v1/follow-ups/{id}/complete` | optional `{type, body, outcome}` → §6.2 |
| `POST /api/v1/follow-ups/{id}/cancel` | reason required |

**The three scopes are disjoint and exhaustive over `PENDING`**, which is a decision, not an
accident. The naive reading — `OVERDUE` is `due_at < now()` and `DUE_TODAY` is anything falling
inside today — makes a 9am follow-up read at 3pm belong to **both**, so the summary tile's three
numbers would double-count and would not sum to the pending total. A tile whose parts do not sum to
its whole is a bug report waiting to happen. Definitions, all implicitly `status = PENDING`:

| scope | predicate |
|---|---|
| `OVERDUE` | `due_at < now()` |
| `DUE_TODAY` | `due_at >= now() AND due_at < endOfTodayIST` — i.e. *still to do today* |
| `UPCOMING` | `due_at >= endOfTodayIST` |
| `ALL` | no `due_at` predicate; `status` filter still applies if given |

`OVERDUE + DUE_TODAY + UPCOMING == ` total `PENDING`, at any instant. `startOfTodayIST` is therefore
never needed by the query layer — only `now()` and `endOfTodayIST` — though `DueWindow` (§7.3)
computes both, since the midnight-boundary tests are written against the full window.

`PATCH` is **full-header-replace**, not a partial merge — consistent with `EnquiryController.patch`
and `QuotationController.patch`, and carrying the same house-wide caveat (deferred-backlog item 8):
an omitted nullable field is cleared. Document it on the method as those two do.

`FollowUpSpecifications` mirrors `EnquirySpecifications`/`OrderSpecifications`, including their
known limitation: string-keyed `root.get(...)` rather than a JPA static metamodel (deferred-backlog
item 9). Adding a fifth string-keyed specification class is the consistent choice; item 9 says fix
all of them together or none.

---

## 10. Testing summary

- **Pure aggregate unit tests** for every invariant in §7.1 and §7.2 — no Spring, following
  `OrderTest`. Rejected transitions must assert that `status`, `completedAt` and `completionNote`
  are left **unmutated**, not merely that an exception was thrown. This closes on a new aggregate
  the gap deferred-backlog item 11 records against `OrderTest`; there is no reason to reproduce it.
- **`DueWindowTest`** — pure, covering 23:59:59 IST, 00:00:00 IST, and a UTC instant that falls on
  the previous IST day. This is where midnight correctness is proven.
- **Visibility integration tests**, the load-bearing ones: a `SALES_EXEC` gets 404 logging an
  activity against another exec's enquiry; 404 reading that enquiry's timeline; a follow-up list
  that omits another exec's rows; and a `SALES_EXEC` who *can* see the subject succeeding at all
  three. Both restricted and unrestricted roles, or the test proves only that the route is broken.
- **Flow integration tests** for §6.1 (both rows written, one transaction), §6.2, and §6.3
  (accepting a quotation produces exactly one `SYSTEM` activity, and `QuotationService` was not
  modified to achieve it).
- **The two ArchUnit tests** of §8. `ActivityRepositoryScopingArchTest` must be shown falsifiable in
  **both** of its assertions, since the first is the load-bearing one: switch `ActivityRepository` to
  extend `JpaRepository`, watch it go red, switch it back; then add a `findByLoggedBy`, watch it go
  red, remove it. A guard whose most important assertion was never observed failing is not evidence.
- **A `DueWindow`/scope disjointness test** — three follow-ups placed at `now - 2d`, `now + 2h` and
  `now + 2d`, asserting each scope returns exactly one and that the summary's three counts sum to
  the pending total. This is what would catch a regression to the overlapping definitions §9 rejects.
- **RLS coverage is automatic** — `RlsCoverageIntegrationTest` fails on its own if either table
  ships without `FORCE`. No new test needed; confirm it actually goes red by deferring `V29` once.

Expect ~11 tasks and 40–50 new tests, against a `main` baseline of **352 tests, 0 failures,
0 errors** (verified green on 2026-08-30 before this branch was cut).

---

## 11. Documentation obligations (same change, per `CLAUDE.md`)

- **`engineering-challenges.md`** — two entries expected, logged by the implementing tasks rather
  than afterwards:
  1. **The polymorphic-subject visibility gate (§4).** Two tables that look symmetrical needing two
     different visibility strategies, and the move from a documented convention to a structural
     guarantee. The non-obvious half is *why the obvious guard doesn't work*: a rule over declared
     methods is silently defeated by `JpaRepository`'s inherited `findById`/`findAll`, so the fix is
     to give the interface nothing to inherit (`extends Repository<T, ID>`) rather than to write a
     cleverer rule. Worth logging with the empirical owner-name finding already recorded in
     `VisibilityScopingArchTest`, which is the same lesson met from the other side.
  2. **`OVERDUE` as a predicate rather than a status (§3).** Refusing the denormalised flag *and*
     the job that maintains it, on the grounds that a stale flag is a row lying about itself.
- **`annotations-reference.md`** — add rows for anything new this slice introduces. `@EventListener`
  should be checked: §6.3 uses it and it may already be present from the order-accept slice.
- **`HANDOFF.md`** — §3 inventory, and §8: backlog item #1 is closed by this slice. The parent spec
  §data-model's "with its own reminder scheduler" clause must be annotated as **deliberately not
  implemented**, with §3 above as the standing reason, so a future reader does not record it as an
  oversight. Note also that item #2 (scheduled auto-expiry) remains the first scheduled job, and
  that §3's tenant-iteration note is the head start for it.

---

## 12. Out-of-scope recap (do not build)

Any scheduler, cron or `@Scheduled` method. Any outbound notification — email, WhatsApp, push, or a
`notification` table. An `OVERDUE` status column. Attachments on activities. Recurring follow-ups,
snooze, or reassignment notifications. A global cross-subject activity feed. Activity deletion of
any kind, hard or soft. A per-tenant timezone column. Any narrowing of `SALES_MANAGER` (still
collapsed into the unrestricted tier — unchanged by this slice). Any change to the `customer`,
`enquiry`, `quotation`, or `sales_order` tables.
