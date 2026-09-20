# Backend Engineer Pre-Screening — Answers

**Date:** 2026-09-05
**Status:** Living. Personal interview prep, not a design document.
**Companion artifact:** https://claude.ai/code/artifact/df51aafd-e55a-418f-9455-515020e21ce4

Answers to the five pre-screening questions, sourced from this repo's own record —
[`engineering-challenges.md`](../superpowers/engineering-challenges.md) (67 entries),
[`ROADMAP.md`](../ROADMAP.md), and the architecture set in this directory.

**Read this first.** Every technical claim below traces to something in the repo. The
placeholders marked **`[FILL: …]`** are the facts only Divyam can supply — production numbers,
years, and real service ownership. They are marked rather than guessed **on purpose**: Q3 and Q4
are scored on their follow-ups, and every follow-up to an invented number is "and what did the
graph look like."

Sibling document: [`interview-qa.md`](interview-qa.md) answers *architecture* questions about the
completed system. This one answers questions about *the engineer*.

---

## Q1 — The stack, and what each piece is for

> *Walk me through the tech stack you work with day-to-day. What languages, frameworks and
> databases, and for what purpose? How long, and what depth? Have you migrated from one stack to
> another — what drove it?*

Lead with the shape, not the list. A list of twelve technologies sounds like a CV; a sentence about
what the system *is* sounds like an engineer.

> "Java and Spring Boot on Postgres, and I use it the way you'd use it for a multi-tenant SaaS
> product — where the database is not just storage, it's the last line of defence."

Then go layer by layer, pairing each tool with the **reason**:

| Layer | What I use | What it's actually for | Depth |
|---|---|---|---|
| Language | Java 25 | Records for DTOs, value objects, events and config binding; sealed interfaces where the set of cases is closed; `Specification` because visibility is a composable predicate ([detail below](#the-java-layer--what-the-language-features-are-actually-doing)) | Proficient+ |
| Framework | Spring Boot 4.1 — Web, Data JPA, Security, Validation, Actuator | The transaction boundary is the design tool; I extend the transaction manager rather than work around it | Proficient+ |
| Database | PostgreSQL | RLS, partial and functional unique indexes, `SELECT … FOR UPDATE`, `SKIP LOCKED`, `NUMERIC` for money | Proficient |
| Persistence | Hibernate/JPA, Flyway (33 migrations) | `@TenantId` discriminator, `@Version` optimistic locking, pessimistic locks where the invariant needs them | Proficient |
| Auth | Spring Security + JJWT | Stateless JWT access + rotating refresh tokens; tenant identity comes from the signed token and nowhere else | Proficient |
| Testing | JUnit 5, Testcontainers, ArchUnit | Real Postgres in every integration test — RLS cannot be tested against a mock. ArchUnit turns conventions into build failures | Proficient+ |
| Build/CI | Gradle (Kotlin DSL, version catalog, convention plugins), GitHub Actions | One `./gradlew clean check` = tests + Spotless + SpotBugs/find-sec-bugs + JaCoCo | Proficient |
| Supporting | Bucket4j + Caffeine, openhtmltopdf, springdoc/OpenAPI | Rate limiting, server-side PDF rendering, a committed API contract that fails the build on drift | Familiar→Proficient |
| Frontend | React + TypeScript | **`[FILL: real React exposure]`** — EasyCRM's frontend is not written yet (ROADMAP §1.4), so do **not** source this claim from it | Say honestly |
| Cloud | AWS — ECS, RDS, SNS/SQS, KMS, CloudFront | **`[FILL: what has been run vs. designed]`** — for EasyCRM this is a design with zero deployed resources | Say honestly |

**`[FILL: years per technology]`**, and which of these were used *in production under load* versus
on this project. One honest word per row — familiar / proficient / expert. A defensible
"proficient" beats an "expert" that collapses on the second follow-up.

### The Java layer — what the language features are actually doing

The stack table says "Java 25." The follow-up is always *"show me where the language choice
changed the code."* Six answers, each tied to a file.

#### 1. Records — five different jobs, not one

77 records across the backend, and it is worth naming the jobs separately, because "I use records
for DTOs" is the shallow version of this answer.

| Job | Example | Why a record |
|---|---|---|
| Request DTO | `ItemRequest(@NotNull UUID productId, @NotNull BigDecimal qty, BigDecimal rate, BigDecimal discountPct)` | Bean-validation annotations sit on the components; the type is immutable by construction, so a controller cannot mutate a request body half-way through a service call |
| Response DTO | `QuotationVersionResponse` with a static `of(QuotationVersion, List<QuotationItem>)` | The mapping lives *next to* the wire shape, not in a mapper class nobody opens. Entity → DTO is one factory method, and the DTO has no setters for a later caller to abuse |
| Value object | `GstCalculator.LineInput / LineResult / Totals` | The calculator is pure and static; the records are its type signature. `LineResult(taxable, cgst, sgst, igst, lineTotal)` makes "the result of one line" a *thing*, instead of five loose `BigDecimal`s in argument order nobody remembers |
| Domain event | `QuotationAcceptedEvent`, `OrderStatusChangedEvent`, `QuotationExpiredEvent` | An event published to `ApplicationEventPublisher` and consumed after commit must be a snapshot. A record cannot be mutated by one listener before the next one sees it |
| Config binding | `JwtProperties(String secret, long accessTtlSeconds)`, `RateLimitProperties`, `RateLimitPolicy` | Boot's constructor binder targets the canonical constructor. Config becomes immutable and final at startup rather than a mutable `@ConfigurationProperties` bean with setters |

Two more that don't fit the table and are worth mentioning because they show records used *inside*
a service, not just at the edges:

- **Internal result carriers.** `PriceResolver.Resolved(rate, name, hsn, uom, gstRate)`,
  `RefreshTokenService.RotationResult`, `InvitationService.Minted`, `ShareLinkService.Resolved`,
  `TenantJobRunner.JobSummary`, `RateLimitStore.Decision(allowed, nanosToWaitForRefill)`. Each one
  exists because the method returns *more than one thing*, and the alternatives — an out-parameter,
  a `Map`, a two-element array, or an overloaded pair of methods — are all worse.
- **A generic envelope.** `PageResponse<T>(content, page, size, totalElements, totalPages)` with
  `of(Page<T>)`. Records are generic, and this one exists specifically so Spring's `PageImpl` is
  never serialized directly — that class's JSON shape is not a contract Spring promises to keep.

`DueWindow.Window(startOfToday, endOfToday)` is the smallest one and my favourite, because it
names a half-open interval. The pair is meaningless apart; the record makes them inseparable.

#### 2. The API's error shape — one record, produced by one `@RestControllerAdvice`

Every 4xx in this API is the same document: `{"error": {"code", "message", "fields"?}}`. That is
two records and one handler class.

```java
public record ApiErrorResponse(ApiError error) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> fields) {
    public ApiError {
        fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
```

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }
    // ... Unauthorized 401, Forbidden 403, Conflict 409, Validation 422, bean-validation 400
    // plus two backstops: DataIntegrityViolationException and OptimisticLockingFailureException,
    // both → 409, so a lost @Version race is a retryable conflict and never a raw 500.

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(new ApiError(code, message, fields)));
    }
}
```

Three details worth having ready, because this is where a record's sharp edges show:

1. **A record component of type `Map` is not immutable.** The canonical constructor stores the
   caller's reference and the accessor hands it straight back — SpotBugs flags exactly this as
   `EI_EXPOSE_REP2` / `EI_EXPOSE_REP`. The **compact constructor** is the fix, and it is the one
   place a record gets to defend itself.
2. **`LinkedHashMap`, not `Map.copyOf`.** `Map.copyOf` returns an immutable map whose iteration
   order is salt-randomized per JVM boot, so the same multi-field validation error would serialize
   its keys in a different order on each deploy. `LinkedHashMap` preserves the producer's order, so
   the emitted bytes are stable.
3. **`@JsonInclude` on the type, not the component.** `code` and `message` are never null, so
   type-level `NON_NULL` is equivalent to annotating `fields` alone — one annotation instead of
   one per component, and it doesn't rest on an assumption about record-component annotation
   propagation.

`ApiErrorResponse` exists as a *named* type rather than a raw `Map` for one reason: springdoc
then emits a single referenced schema for every 4xx in the generated OpenAPI document, instead of
a property-less `object`. The handler methods carry `@ApiResponse(... schema = ApiErrorResponse.class)`
so the contract the client generator sees is the contract the server actually sends.

#### 3. Record projection with JPA — reading three numbers instead of three entities

The dashboard tile needs three counts (`overdue`, `dueToday`, `upcoming`). Hibernate 6 supports a
**constructor expression** straight into a record, so the projection type *is* the DTO:

```java
public record FollowUpCounts(long overdue, long dueToday, long upcoming) {}

public interface FollowUpRepository extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {

    @Query("""
        select new com.easycrm.sales.FollowUpCounts(
            count(case when f.dueAt <  :now         then 1 end),
            count(case when f.dueAt >= :now and f.dueAt < :endOfToday then 1 end),
            count(case when f.dueAt >= :endOfToday  then 1 end))
        from FollowUp f
        where f.status = com.easycrm.sales.FollowUpStatus.PENDING
        """)
    FollowUpCounts countsByWindow(@Param("now") Instant now, @Param("endOfToday") Instant endOfToday);
}
```

*(Shape as designed — the shipped `FollowUpService.summary()` currently derives the same three
numbers from `finder.pageFollowUps(...).getTotalElements()`, deliberately, so the counts pass
through the identical visibility filter as the lists they summarise. Know both, and know the
trade: the projection is one round trip instead of three, the current code is provably consistent
with the list view. That tension is the interesting half of the answer.)*

The point of the projection is not tidiness, it is **what leaves the database**. Selecting the
entity loads every column, puts each row in the persistence context, and makes it dirty-check on
flush. A constructor expression sends three scalars over the wire, allocates one record, and
enters no first-level cache at all — the right tool for a read that will never be written back.
`@Transactional(readOnly = true)` on the caller is the other half of that statement.

#### 4. Sealed interface — where the set of cases is closed

Records make the data exhaustive; **sealed** makes the *hierarchy* exhaustive. The use is a
result type whose variants are decided by the system and not extensible by a caller:

```java
public sealed interface SendOutcome permits SendOutcome.Sent, SendOutcome.Throttled, SendOutcome.Rejected {

    record Sent(UUID quotationVersionId, Instant sentAt) implements SendOutcome {}
    record Throttled(Duration retryAfter) implements SendOutcome {}
    record Rejected(String code, String message) implements SendOutcome {}
}
```

```java
return switch (outcome) {
    case SendOutcome.Sent s        -> ResponseEntity.ok(ShareResponse.of(s));
    case SendOutcome.Throttled t   -> ResponseEntity.status(429).header("Retry-After", ...).build();
    case SendOutcome.Rejected r    -> ResponseEntity.unprocessableEntity().body(error(r.code(), r.message()));
};
```

*(Shape as designed — not committed to the tree today.)*

**Why it earns its place:** no `default` branch, and no `else` that silently swallows a case. Add
a fourth variant and every `switch` over `SendOutcome` **stops compiling** until it is handled.
That converts a class of runtime bug — a new state falling into a catch-all and being reported as
success — into a build failure. It is the same instinct as the rest of this codebase: ArchUnit
fails the build on a missing `@TenantId`, the sealed hierarchy fails the build on an unhandled
case. Conventions that are not enforced are not conventions.

**Where I deliberately did *not* seal, which is the better half of the answer:** `AssignedWorkload`
(`FollowUpWorkload`, `EnquiryWorkload`, `CustomerWorkload`) is an *open* extension point —
`MemberService` injects `List<AssignedWorkload>` and Spring supplies every implementation on the
classpath, so a fourth kind of open work is a new `@Component` and nothing else. Sealing that
interface would mean editing a `permits` clause in a different module every time a module grows a
new assignable aggregate, which is exactly the coupling the plugin shape exists to avoid. Sealed
is for closed sets; `AssignedWorkload` is an open one. Choosing correctly between them is the
point — a sealed interface used everywhere is as wrong as one used nowhere.

#### 5. Streams — used where they clarify, avoided where they don't

Streams are ~13 sites, all of them the same two shapes, and that consistency is deliberate.

**Map a collection to its wire shape** — the most common one, and the reason
`ItemResponse::of` exists as a static factory:

```java
items.stream().map(ItemResponse::of).toList();                       // QuotationVersionResponse.of
users.findAll(Sort.by("email")).stream().map(MemberService::toResponse).toList();
```

**Reduce to a single value, with an explicit tie-break** — `ShareLinkService.primaryContact`,
where the "primary" contact is not guaranteed unique by any constraint, so the ordering has to be
written down rather than assumed:

```java
return contacts.findByCustomerId(customerId).stream()
        .min(Comparator.comparing((Contact c) -> !c.isPrimary()).thenComparing(Contact::getId));
```

Primaries first (`false` sorts before `true`), then the oldest contact — and ids are UUIDv7, so id
order *is* creation order. The stream returns `Optional<Contact>`, which is the honest return type:
a customer may have no contacts at all.

Two more worth knowing, because they answer "where else":

- `EnquiryWorkload` derives its active-stage list at class-init from the enum itself —
  `Arrays.stream(EnquiryStage.values()).filter(EnquiryStage::isActive).toList()` — so a new stage
  joins the correct side automatically instead of silently defaulting to "does not block a disable."
- `MemberService.requireNoOpenWork` streams the injected workloads **sorted by label**, because
  Spring's injection order is bean-definition order and that is not a contract; the 409 message and
  its `fields` map have to be deterministic.

**Where I did not use a stream:** `GstCalculator.totals` sums with a plain `for` loop over
`BigDecimal`. `reduce(BigDecimal.ZERO, BigDecimal::add)` reads well but starts from a scale-0 zero
and gives no place to hang the per-line reasoning; money accumulation is the last code I want to
make clever. That is the honest version of "I use streams" — knowing the one place they were the
wrong call is more convincing than claiming they're everywhere.

#### 6. Specification — a filter you can compose, because the filter is the security model

Four `*Specifications` classes (`Enquiry`, `Quotation`, `Order`, `FollowUp`, plus
`CustomerSpecifications`). The plain-JPA alternative to this is a combinatorial explosion of
finder methods, or string-concatenated JPQL:

```java
public static Specification<Enquiry> filter(EnquiryStage stage, UUID assignedTo, EnquirySource source) {
    return (root, query, cb) -> {
        List<Predicate> ps = new ArrayList<>();
        if (stage != null) ps.add(cb.equal(root.get("stage"), stage));
        if (assignedTo != null) ps.add(cb.equal(root.get("assignedTo"), assignedTo));
        if (source != null) ps.add(cb.equal(root.get("source"), source));
        return cb.and(ps.toArray(new Predicate[0]));   // empty -> always-true conjunction
    };
}
```

Three optional query parameters would otherwise be eight finder methods (2³). But the filter list
is only *half* the reason, and the second half is the one that matters:

**Record visibility is a `Specification`, and it is AND-ed onto every read.** `VisibilityPolicy`
returns a `Specification` per aggregate — a `SALES_EXEC` sees rows assigned to them or unassigned;
every other role is unrestricted — and `VisibleFinder` is the **only** class allowed to call a read
method on the five visibility-scoped repositories, a rule `VisibilityScopingArchTest` enforces at
build time:

```java
public Page<Enquiry> pageEnquiries(Specification<Enquiry> filter, Pageable pageable) {
    return enquiries.findAll(and(policy.enquiries(), filter), pageable);
}
```

Because a `Specification` composes, the *policy* and the *user's filter* are written independently
and combined in one place. Hand-written JPQL cannot do that — you would be string-splicing a
security predicate into a user-supplied query, which is the bug this design exists to make
impossible. Quotations and orders carry no `assigned_to` of their own, so their policy is an
`EXISTS` subquery over `Customer` — expressible in Criteria, and still `@TenantId`-scoped and under
RLS inside the subquery, so it cannot reach another tenant's customers.

Two sharp edges I hit and can name:

- **`Specification.and(null)` throws** in this Spring Data version — it is not the null-safe no-op
  the docs read like. A caller-supplied filter is routinely null (an unfiltered list view), so
  `VisibleFinder` guards once instead of pushing a null check onto every call site.
- **The always-true idiom is `cb.and()` with no arguments** — an empty conjunction. It is what lets
  "unrestricted" and "no filters supplied" be ordinary specifications rather than a null the rest
  of the code has to special-case.

To be explicit about the boundary: this is a **product** rule, not the tenant wall. The tenant wall
is `@TenantId` plus Postgres RLS and is never expressed as a `Specification` — which is precisely
why `VisibilityPolicy.unrestricted()` can safely fail *open*.

#### 7. Optional — at the boundary, never in a field

One convention, applied consistently: **`Optional` is a return type for a lookup that legitimately
finds nothing.** It is never a field, never a parameter, and never an entity attribute.

The highest-leverage use is `VisibleFinder`, where every single-row read returns one:

```java
public Optional<Quotation> findQuotation(UUID id) {
    return quotations.findOne(policy.quotations().and(hasId(id)));
}
```

Every service then funnels through the same one-line conversion, and that is what makes the house
404 rule real: **a row in another tenant, a row that does not exist, and a row you are not allowed
to see are all indistinguishable to the client.** Returning `null` would have made "forgot the null
check" a 500 and "handled it differently here" a tenant-enumeration oracle. The `Optional` forces
the decision at every call site, and the decision is always the same:

```java
.orElseThrow(() -> new NotFoundException("quotation " + id + " was not found"));
```

The other uses, each with a different shape:

- **A fail-open default.** `TenantContext.get()` returns `Optional<TenantPrincipal>`, so
  `VisibilityPolicy.unrestricted()` is `TenantContext.get().map(p -> !"SALES_EXEC".equals(p.role())).orElse(true)` —
  the absent-principal case (async listeners, tenant provisioning) is handled in the same
  expression as the present one, not in a null branch someone can forget.
- **A chain that degrades gracefully.** `ShareLinkService.waMeUrl` builds the WhatsApp deep link
  through `primary.map(...).filter(...).map(...).orElse("")`: no contact, a blank phone, or a
  missing name each fall back to a sensible default rather than failing the share, because
  `wa.me` with only text opens the contact picker — one extra tap instead of a blocked action.
- **A lock read that must exist.** `tenants.findForUpdate(...).orElseThrow(() -> new IllegalStateException(...))` —
  `Optional` here documents that the repository *can* return empty while the caller asserts it
  cannot, and the exception type says it is a bug rather than a client error.

The line I'd hold if pushed: `Optional` is a *vocabulary type for results*, not a null-wrapper to
sprinkle on fields. Adding it to an entity attribute costs an allocation per access, breaks
serialization, and Hibernate cannot populate it anyway.

### The migration question — three, at three different sizes

1. **Spring Boot 3 → Boot 4.1 on Java 25, deliberately, ahead of the ecosystem.** Boot 4 split
   auto-configuration into per-integration modules, so `flyway-core` on the classpath no longer
   brought `FlywayAutoConfiguration` — migrations silently did not run (challenge #4). Boot 4 also
   ships Jackson 3 under a new package (#10), splits MockMvc test auto-config into its own module,
   and needs springdoc 3.x — every tutorial online names 2.x, the Boot 3 line. **Driver:** starting
   a greenfield product on the version it will live on, paying the cost in "no Stack Overflow
   answer exists yet" rather than in a migration two years later.
2. **Package-convention modules → declared module boundaries.** `platform` had grown import edges
   into `crm`, `sales` and `tenant` — three dependency cycles (H4) that block ever extracting a
   service. Spring Modulith makes the boundary a verified property instead of a naming convention.
   **Driver:** the cycles were found by trying to plan the service split, not by a lint rule.
3. **A migration reversed before it was built — Kinesis + DMS CDC → SNS/SQS FIFO.** The original
   design streamed the outbox through DMS change-data-capture into Kinesis. Then it was sized: at
   100 tenants the event rate is ~**0.17 events/sec** against a Kinesis shard's 1,000/sec minimum.
   Kinesis caps consumer parallelism at shard count; SQS does not, and gives DLQ, redrive and
   visibility timeouts natively. Dropping DMS also removed the worst failure mode in the design
   (F6). **Driver: arithmetic.** This is the strongest stack-choice story available, because it is
   a decision made *against* the more impressive-sounding option.

**Don't say:** "I'm technology-agnostic, I can pick up anything." True, and worthless — every
candidate says it. Depth in one stack is what is being bought.

---

## Q2 — The hardest problem: tenant isolation

> *Describe the most complex backend or frontend problem you've solved. What was the business
> context and why did it matter?*

Use this one. Real business stake, a design with genuine trade-offs, two bugs only a real database
surfaces, and — the part that separates senior from mid — **two occasions where the test was
worthless and I found out.**

### The business context (30 seconds, don't skip it)

EasyCRM is multi-tenant on a shared schema: every distributor's customers, quotations and orders
sit in the same tables, separated only by a `tenant_id` column. One query that forgets its filter
shows one distributor their competitor's customer list and pricing. For a CRM sold to competing
traders in the same market, that is not a bug — it ends the business. So the requirement was not
"filter by tenant." It was: **make it structurally impossible for a developer, present or future,
to write a query that leaks.**

### Why one mechanism isn't enough

Every single layer has a bypass. Hand-written `WHERE tenant_id = ?` can be forgotten. ORM-level
filtering does not cover native SQL in a report or script. And any single check is a single point
of failure for code that does not exist yet — the table someone adds next year.

| Layer | Mechanism | Bypass it closes |
|---|---|---|
| 1 · Identity | Tenant read from the signed JWT only, into a `ThreadLocal` cleared in a `finally` | A client setting a header, query param or subdomain; pooled threads leaking context |
| 2 · ORM | Hibernate `@TenantId` — auto-appends the predicate, auto-populates on insert | A developer forgetting the filter. They never write it |
| 3 · Database | Postgres RLS `USING (tenant_id = current_setting('app.current_tenant')::uuid)`; app role has no `BYPASSRLS` | Native SQL, scripts, anything that goes around Hibernate |
| 4 · Build | ArchUnit: every `@Entity` declares `@TenantId` or is explicitly allowlisted | **Future** code. A new unscoped entity fails the build |

### The hard sub-problem — RLS meets a connection pool

RLS needs the connection to know which tenant it is serving, but connections are pooled and reused
across tenants, so it cannot be set once at checkout. A custom `JpaTransactionManager` issues
`set_config('app.current_tenant', ?, true)` in `doBegin`. The `true` makes it **transaction-local**:
it auto-clears on commit or rollback, so it can never leak back into the pool.

### The two things only a real Postgres told me

1. **A custom GUC resets to empty string, not NULL** (challenge #6). The policy assumed
   `current_setting(…, true)` returns NULL when unset, so `NULL::uuid` would match no rows. It
   returns `''` — a referenced custom GUC becomes a registered placeholder defaulting to the empty
   string — and `''::uuid` throws. Worse, an RLS `USING` clause silently doubles as the `WITH CHECK`
   for writes, so the failure was on *insert*, upstream of the assertion under test. Fix:
   `NULLIF(current_setting(…, true), '')::uuid`.
2. **A derived repository finder returned zero rows, and failed *safe* — which is why it was
   dangerous** (challenge #8). `findAll()` saw the row; `findByEmail()` did not, with correct SQL
   and correctly bound parameters. Spring Data wraps the CRUD methods it *declares* in a
   transaction, but not the query methods it *derives*. No transaction → `doBegin` never runs → the
   GUC stays `''` → RLS matches nothing. It returns empty rather than erroring, so nothing is ever
   logged. **RLS turns a missing tenant into "no rows," not into an error** — a class of bug that is
   invisible by construction.

### The part that makes this a senior story: the tests were wrong twice

1. **An isolation test that could never have failed** (challenge #30). The cross-tenant test on the
   one public route asserted that tenant B's data never appears in a PDF rendered from tenant A's
   share token. It passed — and would have passed with `@TenantId` **and** the RLS policy both
   deleted, because nothing in the app can ever mint a token pointing across tenants. A negative
   assertion with no path by which the positive case could arise. Fix: **forge the adversarial row
   directly** through the repository — a share link with tenant B's `tenant_id` pointing at tenant
   A's quotation version, a row no production code path can create — and assert 404.
2. **The harness structurally could not reproduce the failure it was guarding** (challenge #37).
   RLS was `ENABLE`d on every tenant table but `FORCE`d on none — and Postgres exempts a table's
   *owner* from its own policies unless forced, so isolation was quietly resting on the app
   happening to connect as a non-owner role. The `ALTER TABLE … FORCE` is one line; the guard was
   the hard part, because **Testcontainers creates its user as a superuser, and superusers ignore
   `FORCE` unconditionally** — a behavioural test fails identically before and after the fix. So
   the *catalog* is asserted instead: `pg_class.relrowsecurity`, `relforcerowsecurity`, and a row in
   `pg_policy`, for every base table carrying a live `tenant_id` **column** (not annotation — that
   is what makes it a genuine third layer rather than a second reading of layer 2). And because a
   catalog assertion fails open, it is paired with a named witness table that must appear and a
   throwaway probe table with no RLS that must trip all three checks.

> "The thing I'd carry forward: before you write a security test, ask what privilege the test
> harness runs with. Convenience defaults in test infrastructure are chosen to make setup easy, and
> 'easy' usually means 'privileged' — which silently exempts the code from the exact mechanism
> you're verifying."

### Two backups, if they've heard enough about tenancy

- **Money, end to end** (challenges #2, #17, #32). Not "use BigDecimal" — correctness for money is
  an end-to-end property. `NUMERIC` in Postgres, `BigDecimal` in Java (ArchUnit fails the build on
  money-as-`double`), and **a JSON string on the wire**, because every JavaScript number is an
  IEEE-754 double, so serializing as a JSON number re-introduces the exact error just removed. Plus
  rounding *per line then summing*, because that is what Tally does and the total has to reconcile
  with the customer's accounting software to the paise.
- **Write skew on the last owner** (challenge #65). Two owners demote each other simultaneously;
  each runs `count(active owners) > 1`, both see 2, both commit, the tenant is left with zero
  owners and no route to recover — every admin endpoint requires an owner. Nothing already in the
  codebase catches it: `@Version` guards two writers of the *same* row and these write different
  rows; a unique index expresses "at most one" and there is no declarative "at least one";
  `REPEATABLE READ` aborts write-write conflicts and these writes are disjoint. Textbook write
  skew; only `SERIALIZABLE` detects it. Fix: a `PESSIMISTIC_WRITE` lock on the tenant row — a row
  *neither* transaction otherwise touches — purely to manufacture the point of contention the
  invariant needs. The anomaly was proved first by removing the lock and watching the test fail with
  `expected: <1> but was: <2>`.

### Likely follow-ups

- **"Why not schema-per-tenant or database-per-tenant?"** Shared schema is the only shape that stays
  operable at hundreds of small tenants: one migration run, one backup story, one connection pool.
  Schema-per-tenant turns every Flyway migration into an N-way loop and blows out connection and
  catalog overhead. The trade is that isolation stops being physical — which is exactly why it needs
  four layers instead of a directory boundary.
- **"Doesn't RLS cost performance?"** The policy is an extra predicate on an already-indexed column,
  and it is the same predicate Hibernate adds anyway. The real cost is operational: every query path
  must run inside a tenant-bound transaction, which is what the derived-finder bug was about.
- **"What does a cross-tenant request return?"** 404, never 403 — a 403 confirms the record exists,
  which is an enumeration oracle.

---

## Q3 — Scale

> *Tell me about the largest scale system you've worked on — actual numbers. Peak RPS, DAUs, data
> volume, throughput. Did you design it, inherit it, or scale it up? Where did it start breaking,
> and what were the early warning signs? v1 vs. now? What surprised you?*

**This one is not in this repo.** Six slots to fill from real work, and to say as flat facts:

- **`[FILL: system]`** — what it did, in one sentence, and who used it
- **`[FILL: peak load]`** — RPS/QPS, DAU/MAU, rows or GB in the largest table, messages/day
- **`[FILL: role]`** — designed / inherited / scaled. All three are respectable; only vagueness isn't
- **`[FILL: first thing to break]`** — and the warning sign that preceded it (p99 creeping before
  p50, connection-pool wait time, replication lag, queue age)
- **`[FILL: v1 → now]`** — one architectural change and what forced it
- **`[FILL: the surprise]`** — the failure nobody predicted

If a number is genuinely approximate, say "order of a few hundred RPS at peak" rather than
inventing 847. A range is trusted far more than a suspiciously precise figure.

### The honest bridge

> "The biggest system I've run and the system I've most carefully designed for scale aren't the
> same one."

Then give capacity work where the arithmetic **changed a decision**:

- **Sizing killed the impressive option.** 100 tenants → ~0.17 events/sec against Kinesis' 1,000/sec
  minimum shard; the whole streaming tier was deleted from the design. The AWS target architecture
  costs roughly **8× the monolith**, and the case against it is written into the same document as
  the design.
- **Where the design scales horizontally, and why.** Channel polling claims work with
  `SELECT … FOR UPDATE SKIP LOCKED`, so N instances get provably disjoint tenant sets from the
  Postgres lock manager — adding a pod is the entire scaling story. The rejected alternative,
  ShedLock, locks the whole *job*: one instance polls every tenant serially while the others idle.
- **Autoscaling on the wrong signal.** Scheduled batch work inside a serving container makes CPU a
  lying metric — a 90-second sweep triggers a scale-out that serves no traffic (D8). So the design
  scales on request rate and queue backlog age, never CPU.

### "Where would it break first?" — answered about my own system

| Breaks at | What happens | Warning sign |
|---|---|---|
| Instance #2 | The rate limiter is an in-process Caffeine cache. N instances silently multiply every configured limit by N (H2) | None. It fails quietly in the permissive direction — which is why it is known from reasoning, not from an alert |
| Instance #2 | The 00:30 IST expiry sweep takes no distributed lock; every instance sweeps every tenant. Fails safe (`@Version` blocks the double-write) but does N× the work (H3) | Sweep duration scaling with pod count |
| Table growth | No index behind `assigned_to = :me OR assigned_to IS NULL`; no index supports a status-only order-list filter (H5) | List endpoints degrading first for the largest tenant while everyone else stays fast — a per-tenant p99, not a global one |

### Hard line

`aws-production-issues/` and `startup-production-issues/` (us-east-1 October 2025, Canva November
2024, retry storms, metastable failure, thundering herds) are **excellent** material for "how do you
think about failure" and **off-limits** as an answer to "what have you operated." If reaching for
them, say the frame out loud first: *"Not mine — but the failure mode I'd watch for here is…"*
Borrowed incidents told in the first person is the fastest way to fail a pre-screen.

---

## Q4 — Performance work

> *Walk me through the most impactful performance optimisation you've shipped. How did you find it?
> What tools diagnosed it? What didn't work? Measurable before/after?*

They stated the shape of the answer they want, in order. Follow it literally.

1. **`[FILL: how it surfaced]`** — user-reported, alerting, or profiling. "A customer complained" is
   a perfectly good origin story; it just obliges saying what alerting was added afterwards so it
   would not surface that way twice.
2. **`[FILL: diagnosis]`** — name the tool actually used: `EXPLAIN (ANALYZE, BUFFERS)`,
   `pg_stat_statements`, `auto_explain`, a JFR or async-profiler flamegraph, a distributed trace,
   thread dumps. One tool genuinely used beats three that can be named.
3. **`[FILL: the dead end]`** — **do not skip this; it was asked explicitly, so it is scored.**
   "I added an index and it wasn't used because the predicate wasn't sargable" / "I cached it and
   the invalidation cost more than the query" / "I raised the pool size and made it worse because
   the bottleneck was the database, not the pool."
4. **`[FILL: numbers]`** — p50 and p99 before/after, throughput, infra cost. p99 is the one they
   care about.
5. **`[FILL: what stopped it recurring]`** — the regression test, the alert, the budget.

### Backing material from EasyCRM, framed correctly

Design-stage optimisations, not shipped-under-load ones. Say that, then use them:

- **Caching driven by a throttle, not a latency graph.** Third-party channel credentials are
  envelope-encrypted: KMS wraps a per-credential data key, AES-256-GCM encrypts the secret. Direct
  KMS `Encrypt` would work — a 40-character key fits inside the 4 KB limit — but the *decrypt* path
  runs every poll cycle for every tenant, and KMS throttles per account. Envelope encryption exists
  here so the unwrapped key can be cached in memory with a short TTL. **The optimisation was chosen
  from the failure mode, before there was a graph to look at.**
- **Getting slow work out of transactions.** PDF rendering runs outside its transaction; the
  poll-claim transaction commits in ~2ms and never holds a row lock across a 10-second third-party
  HTTP call. Exclusion for the long operation comes from a *lease* (`next_poll_at` stamped forward
  inside the claim transaction), not from holding the lock. **"Lock for the claim, lease for the
  work."**
- **Knowing what the diagnosis pipeline can't tell you** (challenge #31). The observability design
  originally said "head-based sampling at 10%, with errors always sampled" — the sentence everyone
  writes. It is self-contradictory: head-based sampling decides at `startSpan`, before the handler
  has run a line, so it cannot know the request will error; a literal 10% head sampler drops 90% of
  exactly the traces anyone would open during an incident. The obvious fix, tail sampling, was
  structurally impossible there, because ADOT ran as a per-task sidecar and a sidecar only sees its
  own task's spans. And underneath: once errors are kept at 100% and successes at 10%, the surviving
  traces are no longer a representative sample, so counting them inflates the error rate by roughly
  the inverse of the sample rate.

> "The optimisation I'm proudest of is one I didn't ship — I sized the event volume, found it was
> 0.17 per second, and deleted the streaming tier from my own design."

---

## Q5 — What I own, and how it talks

> *What services or components do you personally own? What does each do, what are its SLAs, who are
> its consumers? How do your services communicate — REST, gRPC, event-driven, queues? Why?*

**The ownership half is `[FILL]`.** Per service owned: **`[FILL: what it does]`** ·
**`[FILL: who calls it]`** · **`[FILL: SLA/SLO, and whether one is actually written down]`** ·
**`[FILL: who gets paged]`**. If the team owns them jointly, say so — "I'm primary for X and
secondary for Y" is a real and credible answer. Note honestly whether an SLA exists formally or is
an informal expectation; claiming "99.9%" invites "measured how, over what window, and what's the
error-budget policy?"

### The design half — a decomposition defensible line by line

| Service | Owns | Consumers |
|---|---|---|
| identity | Tenants, users, invitations, tokens. RS256 + JWKS | Every service (verify only) |
| master-data | Customers, products, tax config | sales, document |
| sales | Enquiry → versioned quotation → order. The core wedge | The SPA; document via events |
| document | PDF rendering, share links, the one unauthenticated route | Buyers (public), sales |
| notification | WhatsApp, email, follow-up nudges | Event consumers only — no inbound ALB |

**Why not six.** The obvious next cut is splitting quotation from order. It was refused, because
`QuotationService.accept` is today one local transaction that creates the order and flips the
quotation. Splitting it turns a structural invariant into a saga with compensation — **procedural
correctness where structural correctness exists.** That is the trade to be argued out of.

**Why `document` *is* split.** CPU-bound bursty PDF rendering is a genuinely different scaling
profile from CRUD, and it owns the only unauthenticated route — a different blast radius too.

### How they communicate

- **Synchronous REST over ECS Service Connect** for reads a user is waiting on (D6). Service Connect
  makes retries, timeouts and outlier ejection task-definition config instead of Resilience4j code
  repeated in five services, and per-service RPS/latency/5xx land in CloudWatch for free.
- **Transactional outbox → relay → SNS FIFO → SQS FIFO** for everything a user is not waiting on
  (D3). The rule: *if a downstream failure should not fail the user's request, it must not share the
  user's transaction — but it also must not be a second write outside it.* Publishing to SNS
  directly from the transaction is the dual-write problem: the row commits and the publish fails, or
  vice versa. The outbox row commits *with* the business data, atomically, and a relay ships it
  afterwards.
- **At-least-once, so consumers are idempotent.** A `processed_event` table dedupes consumer-side;
  the whole consume — bind tenant context, dedupe, handle — is one transaction. DLQ and redrive come
  from SQS rather than being built.
- **No Kafka, no gRPC.** Kafka is right for replayable log semantics, high fan-out, or stream
  processing; here it is operational burden for 0.17 events/sec. gRPC buys a strict schema and
  streaming across a boundary that is currently one browser talking to one API — and that API is
  already contract-tested through a committed OpenAPI document that fails the build on drift.

**Worth saying about the relay:** it is the one component that must *not* bind a tenant, and it
deliberately does not have `BYPASSRLS` either — that is a database-wide role attribute that would
override `FORCE` on every table. It gets a role-scoped policy on the outbox table alone (OF6). Least
privilege applied to one's own infrastructure, not just to users.

### Likely follow-ups

- **"How do you handle a poison message?"** SQS redrive policy with a `maxReceiveCount` onto a DLQ,
  plus an alarm on DLQ depth — a DLQ nobody is paged about is just a slower way to lose data.
- **"What if the relay double-publishes?"** It will — at-least-once is the guarantee, and a crash
  between publish and mark-sent is the window. That is what the consumer-side dedupe table is for.
  Making delivery exactly-once is the wrong place to spend effort; making the consumer idempotent is
  the cheap place.
- **"Why FIFO?"** Per-tenant ordering for state transitions on the same aggregate. FIFO caps
  throughput, which at this event rate costs nothing — and that is the honest reason it is
  affordable.

---

## Three rules for the whole call

1. **Label the provenance of every claim, once, early.** *"Some of this is production work, some is
   a system I've designed and built end to end but haven't run at scale — I'll flag which as I go."*
   Said once at the top it reads as calibration and buys credit for everything after. Discovered by
   the interviewer at minute forty, it reads as having been caught.
2. **Lead with the trade-off rejected.** Every strong answer here has the same shape: *the obvious
   approach, why it's subtly wrong, what I did instead, and what it cost.* Sequences for gapless
   numbering (they burn a value on rollback — the opposite of what the invariant needs, #16).
   Kinesis for events (0.17/sec). A saga for quotation-accept (procedural where structural exists).
   The rejected option is what proves the chosen one was a decision.
3. **Volunteer one thing that's wrong with the system.** The in-process rate limiter that multiplies
   by pod count (H2). The sweep with no distributed lock (H3). The quotation that renders the
   buyer's address live, so editing a customer changes a PDF a buyer already holds through a share
   link (H1). Naming a live defect in one's own code, unprompted, with the fix and why it isn't done
   yet, does more for a senior read than any amount of architecture.

---

**Baseline at time of writing:** `main` at `13c01c4` — 586 tests, 0 failures, 33 migrations,
17 controllers, 67 challenge-log entries.
