# F1a — controller ruling record (2026-09-24)

**What this is.** The decision trail from executing
[`plans/2026-09-24-f1a-backend-master-data-prep.md`](plans/2026-09-24-f1a-backend-master-data-prep.md)
with `superpowers:subagent-driven-development`: a pre-flight conflict scan, then **26 rulings** made
while the slice ran, each with its reasoning and what it costs if the ruling was wrong.

**Why it is committed.** The working directory it came from
(`.superpowers/sdd/2026-09-24-f1a-backend-master-data-prep/`) is git-ignored and does not survive the
slice. F0b learned this the expensive way — its 107 rulings had to be copied out before its worktree was
deleted, and its handoff says so. This file is that copy, made before deletion rather than after.

**How to read it.** Each `**Rn —**` block is a decision the controller took on the owner's behalf,
without stopping to ask, because a running plan does not wait on a human for conflicts, plan defects, or
ambiguities. Several are corrections to defects in the plan and spec I wrote; a few are corrections the
implementers made to my rulings. The per-task lines between them record what was measured, which
mutations were proven red, and which findings were deferred.

**The single most useful thing in here**, if you read only one: **R14**. My plan asserted three times
that flattening the search `cb.or(...)` would produce `(active AND name) OR gstin` and leak deactivated
rows. That is false — the Criteria API builds an explicit `cb.and(...)` tree, so flattening degrades OR
into AND and, with a usually-NULL `gstin`, a name search matches nothing. It fails **closed**. Commit
`d7c5fe3`'s message still carries the wrong version; challenge #113 is the canonical correction.

---

# SDD ledger — plan: docs/superpowers/plans/2026-09-24-f1a-backend-master-data-prep.md

Spec: docs/superpowers/specs/2026-09-23-f1-master-data-design.md (read; binding authority)
Worktree: .claude/worktrees/f1a-backend-prep, branch worktree-f1a-backend-prep, base e8a1f76
Baseline: MEASURED at setup — `./gradlew clean check` BUILD SUCCESSFUL, **688 tests, 0 failures, 0
errors**, summed from `tests="…"` across 165 result XMLs in both Gradle modules. Matches the plan.

## Pre-flight scan — cross-task pairs (shared file or interface)

| A → B | A produces | B consumes | Finding |
|---|---|---|---|
| 1 → 3 | `idx_customer_business_name_trgm` on `lower(business_name)` | `cb.like(cb.lower(...))` predicate | OK — index expression matches predicate shape |
| 1 → 4 | `idx_product_*_trgm`, `idx_price_list_name_trgm` | same shape | OK |
| 1 → 10 | `MasterDataSearchIndexTest` | Step 4 confirms it ran | OK |
| 2 → 3 | `SortAllowlist.require(Pageable, Set<String>)` | called in `CustomerService.list` | OK — signature identical |
| 2 → 4 | same | `ProductService.list`, `PriceListService.list` | OK |
| 3 → 5 | `createCustomer(auth, businessName, gstin)` helper in `CustomerControllerTest` | Task 5's three tests call it | **ORDER DEPENDENCY** — 5 must follow 3. Plan order is correct; carried into Task 5's dispatch |
| 3 ↔ 5 | both edit `CustomerService.java` | different methods (`list` vs `resolveGstinAndState`/`create`) | OK — no textual overlap |
| 4 → 6 | `createProduct(auth, sku, name)` helper in `ProductControllerTest` | Task 6's tests call it | **ORDER DEPENDENCY** — 6 must follow 4. Plan order correct; carried into dispatch |
| 6 → 8 | `validateXor`/`validateRange` bodies gain codes | Task 8 refactors their **signatures** to take two `BigDecimal`s | **ORDER DEPENDENCY** — 8 must follow 6, else 6 edits a signature that no longer exists. Plan self-review already names this; carried into Task 8's dispatch |
| 6 ↔ 8 | both append to `PriceListItemControllerTest` and both say "read the file for existing helpers" | neither defines `priceListId`/`productId`/`itemId`/`auth` concretely | **CONFLICT — ruled below (R1)** |
| 5,6 → 7 | twelve registered codes | guard asserts ≥9 throw sites and every code registered | OK — order correct |
| 7 ↔ 8 | guard regex matches `new ValidationException(` regardless of arity | Task 8 changes arity of callers, not throw sites | OK, but see R2 |
| 9 ↔ 3 | Task 9 uses a 2-arg `createCustomer` | in `ContactControllerTest`, a **different file** from Task 3's | OK — Task 9 writes its own helper, and its step says so |
| 3,4,8,9 → 10 | controller signature + DTO changes | contract regeneration | OK |

## Pre-flight scan — per-task self-consistency

| Task | Tests vs code it specifies | Verdict |
|---|---|---|
| 1 | test lists 8 index names; migration creates exactly those 8 | consistent |
| 2 | asserts `SORT_INVALID`; impl throws it; registry row added | consistent |
| 3 | `filter(active,q)` → service → controller `q`; `@Size` needs `@Validated`, which the step adds | consistent |
| 4 | test rejects `sort=baseRate`; `baseRate` absent from `SORTABLE` | consistent |
| 5 | three codes, three registry rows | consistent |
| 6 | nine codes; multi-field path uses the two-map constructor that exists | consistent |
| 7 | 5 tests, 3 of them non-vacuity; 3 mutations specified | consistent, but see R2 |
| 8 | DTO/service/controller signatures agree; `updateRates` sets both fields | consistent |
| 9 | `@Size` values 255/20/20/255/128 match the `contact` table columns exactly (verified) | consistent |
| 10 | verification only | consistent |

## Rulings made before execution

**R1 — `PriceListItemControllerTest` fixture ownership.** Tasks 6 and 8 both append tests referencing
`priceListId`/`productId`/`itemId`/`auth` and both defer to "existing helpers" that may not exist.
Ruling: **Task 6, as the earlier task, owns creating whatever fixture helper that class needs** (a
`@BeforeEach` or a private factory, matching the class's existing style); Task 8 reuses it and adds
only what it additionally needs (a second price list, an existing item id). Carried into both
dispatches. Cost if wrong: Task 8 duplicates a little fixture setup — cheap, visible in review.

**R2 — Task 7's `carriesACode` heuristic is loose.** `args.contains("_")` would false-pass a throw
whose *message* contains an underscore. Ruling: keep the approach (the three mutation runs are the
real proof), but instruct the implementer to tighten the check to "the final argument is a quoted
SCREAMING_SNAKE literal" if it can be done without fighting the regex, and to say in the report which
form it used. Cost if wrong: the guard admits a message-with-underscore throw that carries no code —
narrower than the gap it closes, and the mutation runs bound the risk.

**R3 — the plan's predicted test counts are indicative, not gates.** Task 2 Step 7 says "688 + 6 =
694", which silently omits Task 1's 3 new tests (actual after Task 2: 697). Ruling: standing
instruction A5 governs — **a predicted count never overrides the actual count**. Implementers report
the measured number and must not "fix" a discrepancy by deleting or adding tests. Cost if wrong: none;
this only prevents a false alarm.

**R4 — three near-identical `*Specifications` classes are accepted duplication.** A reviewer may flag
`CustomerSpecifications` / `ProductSpecifications` / `PriceListSpecifications` as verbatim repetition.
Ruling: accept. These are per-entity typed `Specification<T>` builders referencing different
attributes; the repo already established `CustomerSpecifications` as the pattern, and a generic
reflective helper would trade compile-time attribute safety for brevity. Pre-ruled so the review loop
does not relitigate it. Cost if wrong: ~15 lines of similar code that a later refactor could unify.

**R5 — Task 8 must run the full `./gradlew check` before committing.** As written its verification runs
only `PriceListItemControllerTest`, but Step 4 refactors `validateXor`/`validateRange` signatures used
by the `add` path, and Task 7's guard reads the same file. Ruling: add full `check` to Task 8's
verification. Carried into the dispatch. Cost if wrong: none — strictly more verification.

## Progress

# SDD dispatch log
Task 1: dispatched (sonnet), BASE e8a1f76, brief task-1-brief.md
Task 1: implemented, commit f18d4ab, 691 tests / 0 failures (688 + 3), DONE_WITH_CONCERNS
Task 1: plan defects found by implementer — `./gradlew check --tests X` is an invalid flag combo, and
  the brief's `sbdchd/squawk` docker image does not exist (used `npx squawk-cli@2.65.0`). Brief's
  verbatim test code failed Spotless; `spotlessApply` run, whitespace only. pg_trgm privileges were a
  non-issue (stopping point did not occur).

**R6 — V36 keeps plain `CREATE INDEX`, exempted per statement with inline `-- squawk-ignore`
comments.** squawk flagged all 8 indexes for `require-concurrent-index-creation`. The implementer
rightly refused both routes the brief offered: `squawk.toml` keeps that rule ON deliberately, and it
explicitly bars adding an unshipped migration to `excluded_paths`. I verified a third mechanism exists
and works: `-- squawk-ignore require-concurrent-index-creation` above a statement suppresses it, and a
control run without the comment still reports the warning — so the exemption is real and scoped.

Ruling: use inline ignores, carrying the reason, because CONCURRENTLY is the wrong trade for V36:
  1. There is no live database anywhere (roadmap §1.4 — no dev/staging/prod, zero AWS resources). All
     36 migrations run for the first time at the SP2 cutover against an EMPTY database, where an
     ACCESS EXCLUSIVE lock is instantaneous. CONCURRENTLY would protect nothing that exists.
  2. CONCURRENTLY requires `-- flyway:executeInTransaction=false`, trading whole-migration atomicity.
     V36 has 9 statements; if the 6th fails, the first 5 are committed and Flyway records a failed
     migration over a partially-applied schema, needing manual repair. A permanent safety downgrade.
  3. A failed CREATE INDEX CONCURRENTLY leaves an INVALID index needing a manual DROP — a worse
     failure mode in an automated cutover than a clean transactional rollback.
  4. `squawk.toml` demands this be "a decision to make per migration, not a rule to switch off".
     Inline ignores ARE that mechanism: per statement, reasoned, visible in review, and they weaken
     the rule for no other migration.
Also amending `squawk.toml`'s prose, which currently names executeInTransaction=false as "the honest
fix" and so would send the next reader the wrong way.
Cost if wrong: someone reusing V36 as a template after SP2, against a live loaded table, would copy a
write-blocking pattern. Mitigated by stating the empty-table precondition in the inline comment.

Task 1: fix round 1/5 (R6 applied: inline squawk-ignore + squawk.toml prose; squawk 0 issues; 691
  tests / 0 failures; commit f18d4ab amended to 0ffb7dd)
Task 1: task review dispatched (sonnet), package review-e8a1f76..0ffb7dd.diff
Task 1: review verdict — spec ✅ clean; quality: 0 Critical, 2 Important, 2 Minor. Both Important are
  real. Important-2 is a defect in MY plan text (the test came verbatim from the brief), so it needs a
  ruling against the plan rather than a straight fix.

**R7 — strengthen Task 1's test beyond what the plan's text specifies.** The reviewer showed
`MasterDataSearchIndexTest` would stay green if all five trigram indexes had been created on the RAW
column instead of `lower(col)` — indexes the `LOWER(col) LIKE LOWER('%needle%')` predicate can never
use. It also only checks GIN-ness for 2 of the 5. So the plan's verbatim test passes while the entire
point of the migration is defeated, and Tasks 3-4 build on it. Ruling: the plan's test is necessary but
not sufficient; the SPEC is the binding authority and §1.1 requires the index expression to match the
predicate shape, so strengthen the test to assert `indexdef` contains both `lower(` and `gin_trgm_ops`
for all five. Cost if wrong: none — strictly more assertion on the same objects.

**R8 — do NOT add `btree_gin` to make the trigram indexes composite with `tenant_id`.** The reviewer
correctly noted the GIN trigram indexes cannot lead with `tenant_id` (they'd need the `btree_gin`
extension), while the migration's own comment states the schema-wide "tenant_id leads every index"
rule — so half the file silently departs from a rule the file itself asserts. Ruling: document the
exception rather than adding a second extension. RLS remains correct either way (the tenant predicate
is applied as a filter/recheck after the bitmap scan, so no cross-tenant row is ever returned); what
the omission costs is scan efficiency at volume, which is exactly what the load baseline (roadmap item
12) exists to decide on evidence. Cost if wrong: searches scan tenant-blind trigram matches before
filtering, which at large multi-tenant volumes would want revisiting — named in the migration comment
so whoever reads the load baseline finds it.
Task 1: fix round 2/5 (R7+R8 applied: test now asserts lower( AND gin_trgm_ops for all 5 trigram
  indexes and was proven RED by pointing one at the raw column; GIN/tenant_id exception documented;
  alignment tidied; commit 0ffb7dd..0c2213d). Scoped re-review dispatched.
Task 1: re-review — all 3 findings ADDRESSED, new breakage none, commit hygiene verified.
Task 1: complete (commits e8a1f76..0c2213d, review clean)
Task 2: dispatched (sonnet), BASE 0c2213d, brief task-2-brief.md

**R9 — Task 3's visibility+search test moves to `CustomerVisibilityTest`, not `CustomerControllerTest`.**
My plan's Task 3 Step 7 calls `tokens.salesExec(tenant)`, which DOES NOT EXIST — `TestTokens` offers
`owner(tenantId)`, `as(tenantId, userId, role)` and `provisionOwner(stateCode)`. It also needs an
`assignedTo` that `AssignableUsers.require` accepts, i.e. a real ACTIVE `app_user` row, not a random
UUID. `CustomerVisibilityTest` already has exactly this apparatus: a `seedUser(UserStatus)` helper
using `TenantContext.runAs` + `TransactionTemplate`, a bound `tenantId`, and a
`customerJsonAssignedTo(UUID)` builder. Ruling: put the search-respects-visibility test there and use
`tokens.as(tenantId, userId, "SALES_EXEC")`; do not re-create the fixture in `CustomerControllerTest`.
Cost if wrong: the search assertion sits one file away from the other search tests — a navigability
cost, against duplicating a transactional user-seeding fixture.

**R10 — `AssignableUsers.require` also needs a code; spec §1.3 under-enumerated.**
`iam/AssignableUsers.require` throws `new ValidationException("assignedTo", "must be an active user")`
with NO code, and the customer form has an `assignedTo` field, so F1b would render raw English there —
exactly what F1-4 exists to prevent. The spec's §1.3 table lists only `crm`/`catalog` services and
missed this one because it lives in `iam` and is shared by `CustomerService`, `EnquiryService` and
`FollowUpService`. Ruling: in scope for F1a under F1-4's intent. Add code `ASSIGNEE_INVALID`, register
it in `docs/api/error-codes.md`, and add `AssignableUsers.java` to Task 7's guarded file list so it
cannot regress. Assign to Task 5, whose consumer (the customer form) is the reason it matters.
Cost if wrong: touches a component three services share — but adding a code is purely additive, changes
no status or behaviour, and only enriches the error envelope.
Task 2: implemented, commit 70cda97, 697 tests / 0 failures (691 + 6 — confirms R3: the brief's "694"
  was bad arithmetic, not a lost test). DONE. Deviations: committed from worktree (correct; brief's cd
  path predates it); no existing spring.data block so a fresh `data:` key was added; no challenge-log
  entry (plan collects those in Task 10). Task review dispatched.
Task 2: review — spec ✅, quality approved. 0 Critical, 0 Important, 3 Minor. No fix loop.
Task 2: minor (deferred): SORT_INVALID's message echoes the caller's field name — reviewer and I both
  judge it acceptable (422 JSON body, not HTML; property-name space bounded by the entity).
Task 2: minor (deferred): acceptsAnAllowedField and acceptsEveryFieldOfAMultiFieldSort partly overlap,
  but the latter uniquely guards early-exit-on-first-valid. Keep both.
Task 2: minor (deferred → ACTED ON, see R11): no test proves the page-size cap is enforced.
Task 2: complete (commits 0c2213d..70cda97, review clean)

**R11 — fold a page-size-cap test into Task 3.** Task 2's cap is config-only: its correctness rests
entirely on the YAML being nested right, and a typo would silently no-op it with every test still
green. Task 2's brief never asked for a test and the reviewer rated it Minor, noting it would be cheap
once a test stands up a real paged endpoint. Task 3 does exactly that. Ruling: add one assertion to
Task 3 — request a paged list with `size=1000` and assert the response's `size` is 100 — rather than
deferring it to the final review. Cost if wrong: one extra assertion in a file that already exercises
that endpoint.
Task 3: dispatched (sonnet), BASE 70cda97, brief task-3-brief.md, carrying overrides R9 (tokens.as +
  visibility test lives in CustomerVisibilityTest with its seedUser fixture) and R11 (page-size cap
  assertion), plus the mandatory Step 8 scoping-bypass RED run.
Task 3: implemented, commit d7c5fe3, DONE_WITH_CONCERNS.
Task 3: COUNT MISMATCH RESOLVED — no tests lost. The reported 671 is root-module-only; the
  platform-primitives results directory does not exist because that module did not run after the root
  test task failed. Root was 662 before Task 3 and is 671 now (+9), and 671 + 35 = 706 = 697 + 9.
  Arithmetic is exact. The implementer was right to flag it rather than force a number.
Task 3: two MORE defects in my brief's test code, both fixed by the implementer: "source":"WALK_IN"
  is not a valid CustomerSource value (used MANUAL), and ?q=%20%20 does not decode through
  MockMvc as intended (used .param("q", "  ")). A missing JsonPath import too.
Task 3: brief predicted 5 red tests at Step 2; only 3 went red — 2 passed trivially pre-feature, which
  the implementer attributes to weak per-test isolation. Flagged to the reviewer for assessment.

**R12 — every contract-changing task regenerates openapi.yaml in its own commit; the suite must never
sit red.** Task 3 changed a controller signature, so OpenApiSnapshotTest (a byte-for-byte guard) is
now failing, and my plan defers all regeneration to Task 10 — which would leave the suite red through
Tasks 4-9. That is unacceptable: with one expected-red test, a NEW breakage in any later task hides in
the noise, and every reviewer has to reason about which reds are allowed. Ruling: run
`./gradlew updateOpenApiSnapshot` (the task exists and is self-describing) at the end of each task that
changes the contract — 3, 4, 8, 9 — so the suite stays green and every red is real. Task 10 keeps its
job of verifying the CUMULATIVE contract diff is additive-only, which works just as well over four
commits as over one. Cost if wrong: the openapi.yaml diff is spread across four commits rather than
concentrated in one, making the final additive-only check read a range instead of a single file version.
Task 3: fix round 1/5 (R12 applied — commit 349acd4 regenerates openapi.yaml, additive-only: 7 lines,
  the `q` param on the customers list; `clean check` fully GREEN, 706 tests = 671 root + 35 pp).

**R13 — two of Task 3's tests do not test their feature; fix both and prove each red.** The implementer
diagnosed both non-red tests as category (b), assertions satisfiable without the feature, NOT leaked
state (each uses a fresh random tenant). Specifically:
  * `searchMatchesGstin` — its tenant holds exactly one customer, so `totalElements == 1` holds whether
    or not `q` matches `gstin`. Spec §1.1 requires `q` to match businessName OR gstin, and right now
    NOTHING proves the gstin half exists. Drop gstin from the predicate and every test stays green.
  * `searchComposesWithTheActiveFilter` — the pre-existing `active` filter alone produces both expected
    counts, so the test cannot catch the predicate-flattening bug it was written to guard: `active=true
    AND (name OR gstin)` degrading to `(active AND name) OR gstin`, which leaks deactivated rows. This
    is the exact subtlety my own plan flagged in prose while shipping a test blind to it.
Ruling: fix both by adding non-matching fixture rows so each assertion depends on the feature, then
prove each bites — `searchMatchesGstin` by removing the gstin predicate, and
`searchComposesWithTheActiveFilter` by actually FLATTENING the OR into the predicate list (the specific
bug, not a generic break). The `active=false` branch needs its own non-matching inactive row too.
Cost if wrong: two extra fixture rows per test and a slightly longer test class — against two
requirements currently guarded by nothing.
Task 3: fix round 2/5 (R13 applied, commit d5f2e55 — both tests fixture-hardened; both mutations proved
  RED then reverted GREEN; `check` fully green, 706 tests). All findings addressed.

**R14 — my plan's stated failure mechanism for flattening the OR is FALSE, and must not be logged as a
lesson.** The implementer honestly reported that the flattening mutation failed via
`active AND name AND gstin` rather than the `(active AND name) OR gstin` reassociation my prose
described. I verified: `CustomerSpecifications` ends `return cb.and(ps.toArray(...))`, so every element
of `ps` is AND-ed. This is the JPA Criteria API building an explicit predicate tree — there is no SQL
operator-precedence question in play at all. So flattening does NOT leak deactivated rows; it degrades
OR into AND, and because most rows have a NULL gstin (`NULL LIKE pattern` is not true), a name search
returns NOTHING. It fails CLOSED, not open.
The test is still worth exactly what it costs — it pins OR semantics — but the REASONING is wrong in
three places that would outlive this session:
  * Task 4's brief repeats it in a code comment the implementer would write verbatim into
    `ProductSpecifications` ("a matching sku would resurrect a deactivated product" — equally false).
  * Task 10 Step 5's challenge candidate #1 would write the false mechanism permanently into
    `docs/superpowers/engineering-challenges.md`.
  * Task 3's commit message d7c5fe3 already contains it.
Ruling: correct it in Task 4's dispatch and in Task 10's challenge entry, stating the real mechanism
(OR→AND, fails closed via NULL gstin). Do NOT rewrite d7c5fe3 — rebasing three commits to reword prose
risks more than a stale rationale in one commit body is worth; the challenge log is the canonical home
for the lesson and will carry the correction. Record the bad commit body in the handoff.
Cost if wrong: a future reader trusts d7c5fe3's body over the challenge log and believes a
precedence hazard exists where none does.
Task 3: review — spec ✅ compliant, no scope creep. 0 Critical, 2 Important, 2 Minor.

**R15 — the false rationale gets corrected in CODE, not by rewriting history.** The reviewer confirms
`CustomerSpecifications.java` does NOT carry the false claim — only d7c5fe3's commit body does. Rewording
a commit three back needs an interactive rebase, which is unavailable in this environment, and
`filter-branch` is not worth it for prose. Ruling: put the CORRECT rationale as a comment in
`CustomerSpecifications.java` beside the `cb.or(...)`, which is where `git blame` actually sends a
reader, and record the stale commit body in the handoff. Cost if wrong: d7c5fe3's body stays wrong in
history, mitigated by a correct comment on the very line it describes.

**R16 — `@Size(max=100)` on `q` must fail in this app's error envelope, and F1a owns that.** The reviewer
found this is the FIRST `@Size` on a `@RequestParam` anywhere in the codebase, and
`ApiExceptionHandler` has no branch for `HandlerMethodValidationException` — so an over-long `q` falls
through to Spring's built-in handling and returns a `ProblemDetail`-shaped body, NOT this API's
`{error:{code,message,fields,fieldCodes}}`. That is a silent hole aimed straight at F1b: `applyApiError`
parses the app envelope and would not understand a ProblemDetail. F0a deliberately made every JSON
error one shape; introducing the first constraint of a new kind without extending the handler undoes
that locally. Ruling: in scope — F1a introduced the constraint, so F1a owns its failure path. Add the
handler branch mapping it to the standard envelope with a `SIZE` fieldCode, and a test asserting both
the 400 and the envelope shape. Cost if wrong: a platform exception handler gains a branch used by one
endpoint today and every future validated request param after.

Task 3: minor (deferred): no LIKE wildcard escaping — a `%` or `_` typed into the search box acts as a
  wildcard rather than a literal. Real but genuinely minor, and whether a user SHOULD be able to type
  wildcards is a product decision, not a bug to fix silently; doing it properly needs a `cb.like(expr,
  pattern, escapeChar)` ESCAPE clause applied uniformly across all three Specifications. Deferred to the
  final review to triage. Task 4 will inherit the same shape, which is consistent rather than worse.
Task 3: fix round 3/5 (R15+R16+Locale applied, commit a315c73; 707 tests = 672 root + 35 pp, green).
  Exception empirically confirmed as `jakarta.validation.ConstraintViolationException` (AOP method
  validation via `@Validated`), NOT `HandlerMethodValidationException` — which is exactly why I told it
  to determine this by experiment rather than from the class name.
Task 3: CHALLENGE-WORTHY, carry to Task 10 — the implementer found that an `@ApiResponse` on the new
  handler would have been merged by springdoc onto ALL 48 operations (unlike the existing
  `MethodArgumentNotValidException` handler), corrupting ~30 unrelated endpoints' 400 descriptions. It
  documented the response through the existing scoped `ErrorResponsesCustomizer` instead. That is a
  non-obvious, interview-worthy trap and belongs in the challenge log.
Task 3: WATCH ITEM — it also modified a PRE-EXISTING test (`OpenApiMediaTypesTest`) whose "customers has
  no 400" assumption F1a invalidated. Legitimate on its face, but editing an existing test to make a
  failure go away is precisely where regressions hide, so the re-review was told to verify explicitly
  whether it is an update or a weakening.
Task 3: re-review — all 3 findings ADDRESSED. WATCH ITEM CLEARED: the OpenApiMediaTypesTest edit is
  net-STRENGTHENING — the negative example moved to /products (verified structurally identical: no body,
  only an unconstrained Boolean param) and a NEW positive assertion was added proving customers now
  documents a 400 while 429 stays absent. Nothing deleted or loosened. openapi.yaml additive (one new
  400 block; ~16 existing ones only had descriptions reworded). ConstraintViolationException has no
  other throw site in main, so the new handler cannot swallow a correct status from elsewhere.
Task 3: complete (commits 70cda97..a315c73, review clean)
Task 4: dispatched (sonnet), BASE a315c73, brief task-4-brief.md, carrying R14 (CORRECTED comment — the
  brief's false "resurrect a deactivated product" rationale must NOT be copied), R12 (regenerate the
  contract), R4 (three Specifications is accepted duplication), and the Locale.ROOT pattern.
Task 4: implemented, commits 96d9b57 (feature), 508e5d1 (witness route), 8eaab00 (contract regen).
  715 tests = 680 root + 35 pp (was 707), `check` green. Both mutations (drop sku term, flatten OR)
  proved RED then green. Corrected three brief fixtures that would have passed vacuously in single-row
  tenants — the Task 3 trap, caught proactively this time because the dispatch warned about it.
  Challenge 109 logged; numbering verified — no duplicates, 109 is genuinely the next free number.

**R17 — the OpenAPI 400-scoping test must DERIVE its witness, not name one.** For the second task
running, `OpenApiMediaTypesTest.errorResponsesAreScopedToWhereTheyCanOccur` broke because it hardcodes
a route as the "no constrained parameters" witness: Task 3 moved it from `/customers` to `/products`,
and Task 4's own `q` parameter then invalidated `/products`, moving it to `/orders`. This is
whack-a-mole with a finite supply — every future constrained parameter consumes another witness, in
Tasks 8-9 and again throughout F1b and F2, and each repoint silently changes what the test is about.
Ruling: replace the three sampled witnesses with a universal derived assertion — for EVERY operation in
the spec, `documents400 == (hasRequestBody || hasConstrainedParameter)`. That is immune to route churn
and strictly stronger: 48 operations checked instead of 3 samples. Update Challenge 109's Solution and
Lesson to match, since the implementer wrote it up around the weaker fix — the lesson becomes "derive
the witness, never name it".
IMPORTANT GUARD on this ruling: if the universal form surfaces PRE-EXISTING violations elsewhere in the
spec, report and baseline them explicitly. Do not mass-fix unrelated endpoints, and do not weaken the
assertion to make them disappear. Cost if wrong: a ~25-line rewrite of a pre-existing test my plan never
scoped, and a risk it surfaces pre-existing inconsistencies that need their own triage.
Task 4: fix round 1/5 (R17 applied, commit d233198). Universal assertion, renamed
  `everyOperationDocuments400IffItCanProduceOne`, mirrors ErrorResponsesCustomizer's own "constrained"
  definition. **Zero pre-existing violations across all ~48 operations, empty grandfather allowlist** —
  a meaningful negative result: the customizer and the spec have been consistent all along, so nothing
  was papered over. Mutation (narrowing isConstrained to pattern-only) went RED naming all three
  q-bearing operations, reverted clean. 715 tests green. Task review dispatched.
Task 4: review — spec ✅ PASS, quality PASS with 2 Important. False rationale confirmed NOT propagated
  (implementer corrected both the comment AND the commit body itself). Universal assertion independently
  re-verified by the reviewer parsing the spec itself: 78 operations over 60 paths (not the "~48" the
  report and Challenge 109 claim), 33 expecting 400 and 45 not, and `hasConstrainedParameter` is a
  byte-for-byte match of `ErrorResponsesCustomizer.isConstrained`. Zero violations reproduced independently.

**R18 — delete the now-dead `findByActive` from both catalog repositories.** My brief's Step 3 told the
implementer to leave the derived finders alone because "findBySku, findByName and findByActive are still
used elsewhere". That is false for `findByActive` specifically: the Specification rewrite removed its only
callers, and grep finds zero. `findBySku`/`findByName` genuinely are still used. Task 3's
`CustomerRepository` has no such leftover, so keeping these two is also inconsistent with the precedent
this slice just set. Ruling: delete both. Cost if wrong: a derived finder someone wanted later is one line
to restore, and its absence is compiler-enforced rather than silent.

**R19 — add a RUNTIME test that `@Size` on `q` actually rejects, for products and price lists.** The
reviewer made the sharp observation here: the new universal OpenAPI assertion does NOT cover this,
because springdoc emits `maxLength` into the schema from `@Size` REGARDLESS of whether the controller
carries `@Validated` — so the OpenAPI test stays green even if `@Validated` were deleted and the
constraint became inert at runtime. A gate that looks like it covers the case and does not is exactly
the failure mode this project treats as primary. `CustomerControllerTest` already has the runtime
equivalent; products and price lists have only an inspection. Ruling: add the runtime test to both, and
PROVE it by deleting `@Validated` and watching it go red — that mutation is the whole point, since it
demonstrates the runtime test catches what the schema test cannot. Cost if wrong: two small tests
duplicating a pattern already reviewed on customers.

Task 4: minor (deferred): `PriceListControllerTest` has no `searchComposesWithActive` equivalent. Brief
  scoped it to three tests, and the AND-composition path is shared with ProductSpecifications, which was
  mutation-tested. Low risk; noted for the final review.
Task 4: fix round 2/5 (R18+R19 applied, commit cd17e84; 717 tests = 682 root + 35 pp, green).
  **R19's mutation confirmed the reasoning exactly, and turned up something better:** with `@Validated`
  deleted, the OpenAPI test stayed GREEN (as predicted) and the runtime test went RED — but the STATUS
  ALONE WAS STILL 400, because Spring's built-in `HandlerMethodValidationException` fires independently,
  returning an EMPTY body. So a status-only assertion would have PASSED while the constraint was inert.
  That is a sharper lesson than the one I set out to prove: for this class of check, asserting the
  envelope is not thoroughness, it is the only thing that measures anything. CARRY TO TASK 10 as a
  challenge candidate.
Task 4: re-review — all 3 findings ADDRESSED, no new breakage. Both new tests assert the full envelope,
  not status only. `findBySku`/`findByName` correctly retained; unused Page/Pageable imports removed too.
  Challenge 109's operation count corrected in both places it appeared.
Task 4: complete (commits a315c73..cd17e84, review clean)
Task 5: dispatched (sonnet), BASE cd17e84, brief task-5-brief.md, carrying R10 (add ASSIGNEE_INVALID to
  iam/AssignableUsers, which spec §1.3 under-enumerated) and the STATE_CODE_GSTIN_MISMATCH reuse.
Task 5: implemented, commit f66e0eb, 721 tests = 686 root + 35 pp (was 717), green, OpenApiSnapshotTest
  green (no contract change, as expected). All 4 new tests failed pre-implementation on GENUINE
  assertions (PathNotFoundException on the fieldCodes path), never at compilation — the distinction I
  keep demanding, cleanly demonstrated here.
Task 5: R10 CORRECTED BY THE IMPLEMENTER — `AssignableUsers.require` has FOUR callers, not the three I
  named: `ActivityService` is a fourth. All four now emit ASSIGNEE_INVALID; purely additive, no status or
  behaviour change. The registry row names all four.
Task 5: my brief's `"source":"WALK_IN"` bug recurred for the THIRD time (not a valid CustomerSource;
  valid are INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT). It made all four tests fail on a
  spurious Jackson 400 rather than the intended path. Warn Task 9's dispatch — it also creates customers.
Task 5: review — spec ✅ PASS, quality PASS. 0 Critical, 0 Important, 7 Minor (all confirmations, no
  action). Reviewer traced `ApiError`'s compact constructor: fieldCodes is nulled when the map is EMPTY,
  not merely null, so all four assertions were structurally incapable of passing pre-implementation.
  Confirmed no other uncoded fielded error remains reachable from the customer form. Map.of is safe at
  every site touched (all single-entry, no ordering exposure).
Task 5: complete (commits cd17e84..f66e0eb, review clean)
Task 6: dispatched (sonnet), BASE f66e0eb, brief task-6-brief.md, carrying R1 (Task 6 OWNS creating the
  PriceListItemControllerTest fixture that Task 8 will reuse) and the LinkedHashMap requirement for
  ProductService.validate's multi-field path.
Task 6: implemented, commit 0936ada, 729 tests = 694 root + 35 pp (was 721), green. All 10 new/extended
  assertions failed pre-implementation on the INTENDED PathNotFoundException — not compilation, not a
  spurious deserialization 400. R1's premise was partly wrong: `PriceListItemControllerTest` already had
  a `seed()`/`Fixture(tenant, priceListId, productId)` helper, so nothing new was needed. The ruling was
  harmless — it reused the helper as-is and documented for Task 8 how to get a second price list (the
  inline TenantContext+saveAndFlush pattern already used in
  `deleteItemUnderWrongPriceListReturns404`) and an existing item id (POST one first). Task review
  dispatched.
Task 6: review — spec ✅ met, quality good. 0 Critical, 0 Important, 2 Minor. LinkedHashMap confirmed on
  the multi-field path; BOTH PriceListService throw sites covered; all nine codes registered with correct
  thrower attribution; fixture enum values independently verified real (uom PCS, gstRate 7/18, hsn
  12/7318); no validation behaviour altered — every hunk only adds arguments to existing throws.
  Implementer added an unasked-for rename duplicate-name test; reviewer says keep it, and I agree — the
  brief itself flagged that site as easy to miss.
Task 6: minor (deferred → F1b INPUT): the XOR code is keyed to `overrideRate`, per my brief. When BOTH
  fields are set the error is really about the pair, so a frontend rendering per-field inline errors will
  show nothing beside `discountPct` even though it is equally implicated. Not a defect here (built as
  specified) but a real F1b consideration — carry it into the F1b notes at Task 10.
Task 6: complete (commits f66e0eb..0936ada, review clean)
Task 7: dispatched (sonnet), BASE 0936ada, brief task-7-brief.md, carrying R2 (tighten the carriesACode
  heuristic), R10 (add iam/AssignableUsers to the guarded file list), and a recalibrated throw-site floor.
Task 7: implemented, commit a0dd8e6, 734 tests = 699 root + 35 pp (was 729), clean check green,
  openapi.yaml untouched. All three mandatory mutations confirmed RED and reverted green: (a) dropped
  third arg → red naming the line; (b) GSTIN_DUPLICATE→GSTIN_DUPE → red on the registry assertion;
  (c) regex pointed at a nonexistent exception → red on NON-VACUITY ("found 0"), not a silent pass.
  Guarded 5 files (AssignableUsers added per R10), floor set to the measured 12 throw sites.
Task 7: **MY BRIEF'S HEURISTIC WAS A FALSE POSITIVE, not merely loose.** It failed red on the UNMUTATED
  codebase: `ProductService.validate` passes its codes via a VARIABLE (`new ValidationException(errors,
  codes)`, both locally-built LinkedHashMaps), so no SCREAMING_SNAKE literal appears inside the
  constructor-argument span the regex captures. The guard would therefore have failed the build on
  correct code — unusable. R2 anticipated the false-NEGATIVE risk (a message containing an underscore
  slipping through); the real hazard was the opposite direction. Fixed with a second detection shape plus
  tightening code-detection to the LAST quoted literal. Logged as challenge #110.
  The review is dispatched on OPUS rather than sonnet, because a guard patched to accommodate one call
  shape is exactly where a hole gets introduced — in particular whether the new shape would still catch
  `new ValidationException(errors)` with no codes at all, which is a real regression it must catch.
Task 7: review (opus) — spec ✅ PASS, and the guard DOES guard. 0 Critical, 2 Important, 3 Minor.
  The critical question answered concretely: `new ValidationException(errors)` IS caught, because the
  accumulator branch keys on the identifier actually passed and requires the `.put` VALUE to be entirely
  SCREAMING_SNAKE — the real messages have spaces and lowercase, so no match → offender → red. Floor of
  12 independently recounted and confirmed. All three mutations judged structurally capable.

**R20 — make the mutations PERMANENT: `codesFor` needs its own non-vacuity test.** The reviewer's best
finding. Every other rule in this guard is protected (file existence, throw-site floor, registry
control), but nothing proves `codesFor` can EVER return empty. The three mutations demonstrated it once,
by hand, unrepeatably — so a future "simplification" of that helper that always returns a code would
leave all five tests green forever. That is precisely the silent-death mode this repo's non-vacuity
convention exists to prevent, reappearing one level up: we built a guard against hollow checks and left
the guard's own core unguarded. Ruling: add a direct test of the helper over synthetic inputs, covering
both supported shapes in both directions. Cost if wrong: four cheap assertions.

**R21 — add `SortAllowlist.java` to the guarded set.** `SORT_INVALID` is registered in error-codes.md
and thrown from `platform/web/SortAllowlist.java`, but that file is not in GUARDED — so dropping its
code argument would break nothing. My R10 extended the set for a code Task 5 added; this one predates it
(commit 70cda97) and was simply missed. Ruling: guard it too. A package-wide walk would be stronger
still, but would red on `sales/*`, which is deliberately uncoded until F2 — so the explicit allowlist is
the right shape, and its exclusions belong in the javadoc. Cost if wrong: one more path in a list.
Task 7: fix round 1/5 (R20+R21 and both minors applied, commit 0155777; 738 tests = 703 root + 35 pp,
  clean check green). Floor 12 → 13 (SortAllowlist). All 13 real codes re-verified against the tightened
  SCREAMING_SNAKE pattern (now requires an underscore group) and the table-anchored registry check — none
  failed. `codesFor` now has 4 direct non-vacuity tests, so the hand-run mutations are permanent. Both
  false-positive call shapes documented; allowlist-vs-walk rationale recorded. Mutation (a) re-run
  post-fix went RED identically. Scoped re-review dispatched.
Task 7: re-review — all 4 findings ADDRESSED, no new breakage. The decisive check: rewriting `codesFor`
  to `return List.of("ANY_CODE")` unconditionally now goes RED on three named tests, so R20 achieved what
  it set out to — the guard's core is machine-checked, not proven once by hand. Throw-site count
  independently recounted as 13. Registry anchoring verified against real table rows (prose lines do not
  start with `|`). All 20 registered codes contain underscores, so the tightened pattern excludes none.
  `codesFor` stayed private (same-class tests) — no needless visibility widening.
Task 7: minor (deferred): one javadoc sentence describes the constant-reference false positive as the
  accumulator check "also failing", when the direct-shape branch always returns first — dead-code
  narration. Outcome documented is correct; wording only.
Task 7: complete (commits 0936ada..0155777, review clean)
Task 8: dispatched (sonnet), BASE 0155777, brief task-8-brief.md. Carries R5 (full check before commit),
  R12 (regenerate the contract — this task ADDS an endpoint), and Task 6's fixture handover notes.
Task 8: implemented, commit aa4cca5, 744 tests = 709 root + 35 pp (was 738), clean check green, OpenAPI
  regenerated with an additive diff. Wrong-parent mutation confirmed RED (`Status expected:<404> but
  was:<200>`) then restored green. Worth noting for the record: Task 7's guard now watches
  PriceListItemService, so Task 8's refactor of the two validator signatures was protected by a gate
  built two tasks earlier — the ordering paid off exactly as the pre-flight scan predicted. Task review
  dispatched.
Task 8: review — spec ✅ full match, quality clean. **0 findings at every severity.** Reviewer verified
  independently rather than from the report: all three codes preserved on the same fields (read from the
  diff, not inferred from the guard passing); `updateRates` sets both fields, with the counterfactual
  traced (a "set only what's provided" mutator would leave overrideRate=10 rendering, failing the test);
  one shared copy of each validator, both callers using it; wrong-parent gate placed before any mutation;
  no @Version/If-Match scope creep; pre-implementation failures were all genuine 405s (no PUT mapping),
  not compile errors or spurious 400s.
Task 8: minor (deferred): springdoc renumbered `operationId`s as cosmetic churn in the regenerated spec.
  Non-blocking — the generated frontend client keys on path+method, not operationId — and the implementer
  self-flagged it rather than hiding it. Noted for the final review.
Task 8: complete (commits 0155777..aa4cca5, review clean)
Task 9: dispatched (sonnet), BASE aa4cca5, brief task-9-brief.md. Carries the WALK_IN warning (this task
  creates customers, and that value is invalid — third recurrence), R12 (regenerate the contract: DTO
  constraints change the schema), and the challenge-#8 @Transactional requirement on the new finder.
Task 9: implemented, commit b6da224, 751 tests = 716 root + 35 pp (was 744), clean check green.
  Demotion-scoping mutation confirmed RED — pointing `demoteOtherPrimaries` at `findAll()` made
  `demotionDoesNotReachAnotherCustomersContacts` fail with "Untouched" having lost its primary flag —
  then reverted green. TWO MORE BRIEF DEFECTS found and fixed by the implementer: (1) a JsonPath
  filter+function quirk in my literal assertions (`$[?(@.isPrimary == true)].length()` does not evaluate
  as intended), and (2) a derived-finder name that did not match the entity's actual property — a boolean
  field `isPrimary` exposes the JavaBean property `primary`, so the Spring Data method name differs from
  what I wrote. Both are exactly the class of error only running the code reveals. Task review dispatched
  with specific scrutiny on whether the JsonPath replacement still proves "exactly one primary, and it is
  the expected one", since a weaker replacement would pass with two primaries.
Task 9: review — spec ✅ PASS, quality PASS (high). 0 Critical, 1 Important, 0 Minor. The challenge-#8
  trap was AVOIDED: `findByCustomerIdAndPrimaryTrue` carries `@Transactional(readOnly = true)` with a
  javadoc citing the challenge. @Size values match V11__contact.sql exactly in both directions. Demotion
  has no transaction boundary of its own; both write paths demote; `add` passing null for
  exceptContactId is safe (unsaved row cannot appear in results, and equals(null) is a safe false). Both
  JsonPath replacements verified to still prove "exactly one primary, and it is the expected one" —
  `hasSize(1)` counts matched elements directly. The renamed finder parses correctly against the entity
  property. No pre-existing test asserted multiple primaries, so none was weakened.

**R22 — restore the contract's `minLength: 1` on `ContactRequest.name`.** Combining `@NotBlank` with
`@Size` on one field — a first for this codebase — made springdoc emit `minLength: 0` where the schema
previously carried `1`. Runtime enforcement is unaffected (`@NotBlank` still rejects blanks), so this is
not a behaviour bug; it is information loss in a generated contract this slice is explicitly delivering,
and the brief required the diff to be additive. An external consumer generating validation from the
schema would now under-constrain `name`. The implementer disclosed it accurately and correctly judged it
did not meet the STOP condition. Ruling: fix it — try stating the bound explicitly as
`@Size(min = 1, max = 255)` so springdoc emits `minLength: 1` without weakening anything at runtime
(`@NotBlank` remains, and is stricter since it also rejects whitespace-only). If that does not restore
it, accept and document as a known gap rather than dropping the `@Size` that mirrors the column.
Cost if wrong: one annotation parameter, and a regenerated snapshot.
Task 9: fix round 1/5 (R22 applied, commit 50ef3cf). `minLength: 1` RESTORED via explicit
  `@Size(min = 1, max = 255)`; `@NotBlank` retained and is stricter. Entire regeneration diff was that
  one line; all five annotated fields verified unshifted. Counts unchanged (annotation-only).
Task 9: re-review (haiku, cheapest tier for a one-line mechanical fix) — ADDRESSED, no new breakage.
Task 9: complete (commits aa4cca5..50ef3cf, review clean)
Task 10: dispatched (sonnet), BASE 50ef3cf. Verification + durable record only; no feature code.
Task 10: complete (commit 0c0125e). Measured 751 tests (716 root + 35 pp), `clean check` BUILD
  SUCCESSFUL. Cumulative contract diff e8a1f76..HEAD confirmed ADDITIVE-ONLY by full read. Challenges
  logged: #113 (the CORRECTED OR-flattening mechanism — fails closed via NULL gstin, and noting
  d7c5fe3's message is wrong), #114 (springdoc merges an advice-level @ApiResponse onto every
  operation), #115 (a status-code-only assertion measures nothing). Rejected candidate (c) as already
  covered by #110's follow-up — correct call, quality over volume. ROADMAP H5 corrected, counts updated,
  F1a recorded as built-not-merged. New HANDOFF top section written.

FINAL whole-branch review dispatched on OPUS over e8a1f76..0c0125e (19 commits, 41 files), pointed at
the six things per-task reviews structurally could not see, and given the full deferred/parked list to
triage for merge-readiness.

FINAL REVIEW VERDICT: 0 Critical, 3 Important, 4 Minor. Merge-ready once I1 is decided. Confirmed clean:
Specifications cohere across the two tasks that wrote them; search composes INSIDE CustomerVisibility
(`findAll(and(spec(), filter))`) so the fail-open case is closed; V36's index expressions match the
predicates Hibernate emits; challenge #113 states the corrected mechanism accurately.

**R23 — fix I1 (missing default sort) on this branch, not in F1b.** The spec requires an explicit
default sort, V36's header justifies three btree indexes by "the default ORDER BY", and the plan's
traceability row records it as done — but no controller carries `@PageableDefault` and no service
applies a fallback `Sort`. An unsorted Pageable emits no ORDER BY, so rows can repeat or be skipped
across pages. This is the one finding that changes behaviour a user sees, AND leaving it unfixed leaves
two committed documents asserting something untrue. Nine task reviews missed it because each saw only
its own diff and no task's test list named it — a plan gap, not an implementer error. Cost if wrong:
one annotation per controller plus paging-stability tests, and another contract regeneration.

**R24 — fix I2 and I3 here too.** I2: the replaced OpenAPI test lost ALL negative 429 coverage (only
positive assertions on /auth/** remain), so a regression making ErrorResponsesCustomizer add 429
unconditionally would ship silently — a coverage loss this branch caused, so this branch repairs it.
I3: `CustomerService.update` has no duplicate-GSTIN pre-check, so editing a GSTIN to a colliding value
falls to the DataIntegrityViolation backstop and returns a 409 with NO field attribution — the create
form gets an inline error on `gstin`, the edit form gets a bare banner, for the identical mistake. That
directly undercuts the slice's purpose, and `PriceListService.rename` already does the symmetric check,
so it is an inconsistency rather than a policy. Cost if wrong: two small changes in areas already
reviewed.

**R25 — M2 (no DB-level one-primary constraint) is RECORDED, not fixed.** Two concurrent POSTs with
`isPrimary: true` can both commit, leaving two primaries with nothing detecting it. Adding a partial
unique index needs care over demote/promote flush ordering, and the blast radius is small (a UI picks
one). But the reviewer is right that spec Part 5.1's accepted risk covers field OVERWRITES, which is a
different thing from a broken invariant — so it must be written down as accepted rather than left
unnoticed. Ruling: add it to spec Part 5, do not add the index in this slice.
Deferred as minors, ledgered for F1b: M1 (Gstin/StateCode outside GUARDED — needs cross-module relative
paths, and both carry codes today), M4 (validate-before-resolve ordering in PriceListItemService.update).

FINAL FIX WAVE applied in ONE dispatch, commit 3b69bc0. 759 tests = 724 root + 35 pp (was 751),
`clean check` green. Fix 1 via a service-side `SortAllowlist.withDefault` applied before allowlist
validation in all three services; Fix 2 derives expected 429 from application.yml's real policies via
PathPatternParser; Fix 3 pre-checks GSTIN on update, with both the conflict and the keep-own-GSTIN
cases; Fixes 4 and 5 documentation only. `updateOpenApiSnapshot` produced NO diff, which is consistent
with a service-side default rather than @PageableDefault.

**The honesty note that matters most in this whole slice:** the paging-stability test did NOT go red
under the naive mutation, because a small freshly-seeded table replays insertion order regardless of
ORDER BY. Rather than claim a red run it did not get, the implementer escalated to a `VACUUM FULL`
between the two page fetches — a physical rewrite — which reproduced the duplicate-row-across-pages bug
reliably, and passed once the fix was restored. Logged as challenge #116. This is precisely what the
mutation discipline exists to produce, and faking it would have been trivially easy.
OPEN QUESTION put to the re-review: is `VACUUM FULL` in the COMMITTED test, or was it used only during
verification? If committed, it takes an ACCESS EXCLUSIVE lock in a Testcontainers database shared by
many test classes; if not committed, the stability test is not itself proven able to fail.

FINAL RE-REVIEW: all 5 findings ADDRESSED, no new breakage, reviewer calls the branch merge-ready.
Verified independently: `require()` still runs on the RAW pageable so the default is not a bypass;
`withDefault` returns unchanged when already sorted, so an explicit sort wins; the stability tests assert
`overlap.isEmpty()` AND `union == all 25 seeded ids`; Fix 2's 429 derivation uses the SAME
`PathPatternParser` and the SAME policy source as production; Fix 3 mirrors `rename` exactly and both
required cases are covered; no existing test body was modified, only appended.

**R26 — PARK the `VACUUM FULL` concern with a tripwire, rather than opening a second fix wave.**
Answer to the open question: it IS in the committed tests (three controller tests, via an owner
connection). The reviewer's assessment, which I accept: safe TODAY only because nothing in this build
enables parallel test execution (no `maxParallelForks`, no JUnit parallel config), the suite runs
sequentially against one static singleton container, the tables hold 25 rows, and the exclusive lock is
held for milliseconds. It is nonetheless an IMPLICIT, undocumented dependency: if this repo ever turns on
parallel tests — a routine CI speedup — any other class touching `customer`/`product`/`price_list`
concurrently would contend on ACCESS EXCLUSIVE and hang for reasons unrelated to what it was asserting.
Ruling: park it, and write a TRIPWIRE into HANDOFF.md so whoever parallelises this suite finds it before
it bites. The reviewer's preferred alternative — perturbing physical order with ordinary row-level DML,
or asserting the generated SQL carries an ORDER BY via a statement listener — is genuinely better, but
buying it now costs another fix-and-review cycle for a hazard that is currently inert, and the process
allows no second fix wave. Cost if wrong: someone enables parallel tests and spends time diagnosing a
lock contention whose cause is one line in three tests — which is exactly what the tripwire exists to
prevent.
