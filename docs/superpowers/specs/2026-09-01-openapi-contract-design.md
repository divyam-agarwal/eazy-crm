# OpenAPI contract — design

**Date:** 2026-09-01
**Status:** Designed, not yet planned
**Backlog item:** `HANDOFF.md` §8, "Wave 3, OpenAPI — the strongest claim, and it is coupled to
the frontend"; triaged in `specs/2026-09-01-build-hygiene-design.md` §3 as Wave 3 of 3.
**Baseline:** `main` at `e9d694e` (519 tests, 0 failures, 0 errors)
**Wave:** 3 of 3 (build hygiene → observability → OpenAPI). Wave 2 is **not** a prerequisite;
this wave is taken out of dependency order deliberately — see §1.

---

## 1. What this builds, and why the contract is the point

There are **16 controllers and 74 request mappings** in `backend/src/main/java`, and no API
document of any kind. Nothing has ever been built against this API — there is no frontend, no
second service, no consumer of any sort. That is the whole argument for doing this now, and it
cuts in a direction that is easy to get backwards.

**With no consumer, the contract is not "what the controllers promise" — it is "whatever the
controllers happen to do."** Every response shape, every status code, every query parameter is
currently an accident of implementation that nothing has ratified and nothing would notice
changing. The moment a frontend is written, that accident becomes the contract retroactively, and
every quirk in it becomes a thing someone depends on. Writing the document *first* is the only
moment at which the contract is chosen rather than discovered.

This is also why Wave 3 is taken before Wave 2. Dependency order says observability first; value
order says the artefact the frontend is built from is worth more than instrumentation for a system
with no production traffic. Wave 2 loses nothing by waiting — nothing here touches logging,
metrics or tracing.

Three things make this a spec rather than a config task.

**A generated spec is only a contract if something forces it to stay true.** springdoc alone
produces a document that is correct at the instant it is served and unversioned thereafter. The
guard that matters is not the generator, it is the committed snapshot plus a test that fails when
the two disagree — that is what converts "we have a Swagger page" into "changing an endpoint
without saying so breaks the build." §5.

**The error half of this API is currently undocumentable.** `ApiExceptionHandler` returns
`ResponseEntity<Map<String, Object>>` from all seven handlers. springdoc reads that type and emits
a property-less `object`. Left alone, the generated spec would describe every success response
precisely and every error response as "some object" — on an API whose error envelope is uniform,
deliberate, and the first thing a frontend has to handle. §4 fixes that, and it is the only change this slice
makes to *existing* application logic — everything else it adds is new configuration.

**A breaking-change gate that fires at nobody teaches people to ignore gates.** oasdiff is named in
Wave 3's brief and is worth having, but this repo has zero consumers and an actively moving API —
members management, cursor pagination and the frontend's own needs will all legitimately change
endpoints. §6 takes the reporting half now and defers the blocking half to a trigger.

---

## 2. Decisions taken

- **D1 — springdoc 3.1.0, not the 2.x line.** 2.x is the Spring Boot 3 line. §3.
- **D2 — the generator ships; the UI does not.** `springdoc-openapi-starter-webmvc-api` as
  `implementation`, `springdoc-openapi-starter-webmvc-ui` as `developmentOnly`. §7.
- **D3 — the error envelope becomes typed.** Two records replace `Map<String, Object>`, and the
  wire bytes must be identical. §4.
- **D4 — the snapshot is YAML, committed at `docs/api/openapi.yaml`.** Readable diffs; oasdiff
  reads either format.
- **D5 — one generation path, used by both the guard and the regeneration task.** The drift test
  in write mode *is* the regeneration task, so the guard cannot disagree with the generator. §5.
- **D6 — oasdiff reports, it does not block.** `continue-on-error: true`, changelog into the job
  summary. Trigger for flipping it: the frontend exists and consumes this spec. §6.
- **D7 — Swagger UI and `/v3/api-docs` are dev-profile only, guarded twice.** springdoc's own
  `enabled` flags plus a `@Profile("dev")` filter chain. `SecurityConfig` is not modified. §7.
- **D8 — `DemoRecordController` is `@Hidden`.** It is the P0 tenant-isolation demonstration
  fixture, not product surface, and it is also the only controller returning a raw `Map`. Hiding
  it from the spec does **not** remove the route; §7 records that honestly.
- **D9 — no `@Operation` prose in this slice.** §10.
- **D10 — `info.version` is the Gradle project version** (`0.0.1-SNAPSHOT` today), not a date and
  not a hand-maintained string. A date churns the snapshot on every regeneration and makes the
  drift guard fire on the calendar rather than on a real change. `-SNAPSHOT` in a published
  contract is honest: nothing has been released and no consumer has pinned anything.

---

## 3. Compatibility: the version hunt, already resolved

§6 of `HANDOFF.md` records that most third-party tooling has needed a version hunt on this stack
(Boot 4.1 / Java 25 / Hibernate 7). It is the first thing to check for springdoc, because a
Boot-3-only OpenAPI generator would sink this slice outright.

It does not apply. Verified against `repo1.maven.org/maven2` on 2026-09-01: the newest release of
`springdoc-openapi` is **3.1.0** (published 2026-08-01), and its POM declares

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
</parent>
```

— the exact Boot version this repo pins in `libs.versions.toml`. The 2.x line (latest 2.9.0) is the
Boot 3 line and must not be used; a stale copy-paste from any pre-2026 tutorial will land on it and
fail in a way that looks like a springdoc bug rather than a version mismatch.

Catalog entries, following the file's existing "verified on `<date>`" comment convention:

```toml
# Verified against repo1.maven.org/maven2 on 2026-09-01 (latest/release: 3.1.0, published
# 2026-08-01). The 3.x line is the Spring Boot 4 line -- its POM parent is
# spring-boot-starter-parent 4.1.0, matching springBoot above. 2.x is the Boot 3 line and
# will not work here.
springdoc = "3.1.0"

springdoc-webmvc-api = { module = "org.springdoc:springdoc-openapi-starter-webmvc-api", version.ref = "springdoc" }
springdoc-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
```

---

## 4. The typed error envelope — the only change to existing application logic

Today, all seven handlers in `ApiExceptionHandler` build a `HashMap` and return
`ResponseEntity<Map<String, Object>>`. The wire shape is uniform:

```json
{"error": {"code": "NOT_FOUND", "message": "quotation not found"}}
{"error": {"code": "VALIDATION_FAILED", "message": "request is invalid", "fields": {"qty": "must be positive"}}}
```

`fields` is present only when non-null. The replacement:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> fields) {}

public record ApiErrorResponse(ApiError error) {}
```

`fields` stays `Map<String, Object>` and renders in the spec as a free-form object. That is
correct rather than lazy: its keys are dynamic field names taken from
`MethodArgumentNotValidException`'s binding result and from `ValidationException.getFields()`,
so there is no closed schema to write.

`@JsonInclude` sits on the **type**, not the component. Both work — a record component propagates
its annotations to the backing field and accessor — but `code` and `message` are never null, so
type-level `NON_NULL` is equivalent, is one annotation rather than one per component, and does not
rest on record-component propagation behaving as assumed.

**The acceptance bar is byte-identical JSON, not "equivalent" JSON.** This is a representation
change to code that seven exception paths and an unknown number of existing MockMvc assertions run
through; if the bytes move, this slice has changed application behaviour, which it is not
permitted to do. Build hygiene set the precedent — it proved its 311-file reformat byte-identical
to `spotlessApply` output rather than asserting it — and the same standard applies here:

- The existing MockMvc tests already assert `$.error.code`, `$.error.message` and `$.error.fields`
  across the handler set; those cover the present-`fields` case as a side effect.
- The **absent-`fields`** case gets an explicit assertion that the serialized body contains no
  `fields` key at all. This is the case `@JsonInclude` exists to preserve and the one a naive
  record conversion silently breaks (a record component serializes as `"fields":null` by default).

With the type in place, the handler methods return `ResponseEntity<ApiErrorResponse>` and each
`@ExceptionHandler` carries an `@ApiResponse` naming the status and the schema, so the generated
document describes 400/401/403/404/409/422 with a real shape instead of an empty object.

Two smaller structural annotations complete this section:

- **`@ParameterObject` on the 8 `Pageable` parameters** — `EnquiryController`,
  `QuotationController`, `ActivityController`, `FollowUpController`, `OrderController`,
  `CustomerController`, `PriceListController`, `ProductController`. Without it springdoc renders
  `Pageable` as a single nested object rather than the `page` / `size` / `sort` query parameters
  the API actually accepts, which is worse than omitting it — it documents a request the server
  will not honour.
- **`@Hidden` on `DemoRecordController`** (D8), with a comment saying why, so the next person does
  not read it as an oversight and remove it.

No other **existing** file under `src/main/java` changes. The two new classes this slice adds
there — `OpenApiConfig` (§3) and the dev-profile filter chain (§7) — are additive configuration
and carry no request-path logic.

---

## 5. Generation and the drift guard

**One test, two modes.** `OpenApiSnapshotTest` runs on the existing Testcontainers Postgres
singleton, obtains the generated document from springdoc, and either compares it to
`docs/api/openapi.yaml` (default) or writes it (`-Dopenapi.write=true`, wrapped by a friendlier
`./gradlew updateOpenApiSnapshot`). D5's point is that there is exactly one code path that
produces the document, so the guard physically cannot check something different from what the
regeneration task emits. On mismatch the failure message names the regeneration command and the
test writes its actual output under `build/` so the difference can be diffed rather than guessed.

The test lives in the root project, so a filtered run must be **project-qualified** —
`./gradlew :test --tests '*OpenApiSnapshotTest'`. An unqualified `--tests` applies the filter to
`:platform:platform-primitives` as well and fails there for having no match; `HANDOFF.md` §0
records this tripping an implementer already.

Three implementation details that are traps rather than choices:

**The document must serialize deterministically or the snapshot churns.** springdoc's internal
maps have no guaranteed iteration order, so two runs over identical code can emit identical
content in different orders and fail the guard for no reason. `springdoc.writer-with-order-by-keys`
is the property that fixes this; **confirm it exists and takes effect on 3.1.0 before relying on
it** rather than copying it from this spec — an ordering flag that silently does nothing produces
an intermittently failing build, which is the worst available outcome and exactly the
untested-assumption shape of challenge #33.

**The snapshot lives outside the Gradle project.** The Gradle root is `backend/`; the file is at
`docs/api/openapi.yaml`, one directory up. Do not resolve it from the test's working directory —
inject the absolute path as a system property from the `test` task configuration, so the test has
no opinion about where it was launched from.

**`springdoc.api-docs.enabled` is `false` by default under D7**, which means the springdoc
resource bean may not be present in a default-profile test context. The test enables it explicitly
via `@TestPropertySource` and obtains the document from the springdoc bean directly rather than
over HTTP — no MockMvc, no filter chain, no interaction with `SecurityConfig`'s `denyAll`.

**What this guard buys.** A new endpoint, a renamed field, a changed status code or a new query
parameter all make `clean check` fail until the snapshot is regenerated and committed alongside
the change. The spec cannot silently fall behind the code, and every API change becomes visible as
a diff in review rather than as a surprise to whoever is building the client.

---

## 6. oasdiff in CI — report, don't block

A step in `.github/workflows/ci.yml`, on `push` only, comparing `git show
HEAD~1:docs/api/openapi.yaml` against the working copy and writing the changelog to
`$GITHUB_STEP_SUMMARY`. `continue-on-error: true`.

**Why not blocking.** §0 of `HANDOFF.md` establishes that CI here is a post-merge smoke alarm: the
workflow fires on `push: [main]`, this repo has never opened a pull request, and required-status
checks are PR-shaped. A blocking oasdiff would therefore fail *after* the breaking change had
already landed on `main`, which is a notification with extra steps. Combine that with zero
consumers and an API that is still growing, and a blocking gate would fire regularly, at nobody, on
work that is entirely correct — the reliable way to train someone to ignore a red build. The
reporting form gives the thing that is actually valuable today: a durable, per-commit record of how
the contract changed. **Trigger for flipping it to blocking: the frontend exists and consumes this
spec.** At that point there is a real party to break, and the decision changes with it.

**`fetch-depth: 2` is required and is currently absent.** `actions/checkout@v7` clones shallow at
depth 1 by default, so `HEAD~1` does not exist in the CI working copy and `git show HEAD~1:...`
fails. This is the same category as the dormant `pull_request` trigger §0 flags: configuration that
reads as correct and does nothing. The step also needs a guard for the case where the base file is
absent — the commit that introduces the snapshot has no predecessor version of it — and must skip
cleanly rather than fail there.

Verification is by observation, not assertion: the run that lands this slice will exercise the
absent-base path, and a subsequent commit that deliberately changes an endpoint must be seen to
produce a non-empty changelog in the job summary. Until both have been seen on real runs, this step
is unproven config.

---

## 7. Exposure

**Two independent layers, both off outside `dev`.**

1. `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false` in
   `application.yml`; both `true` in `application-dev.yml`. Outside dev the routes are never
   registered, so there is nothing for a security rule to have to deny.
2. A new `@Profile("dev")` configuration contributing an `@Order(0)` `SecurityFilterChain` with
   `securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")` and `permitAll`.

`SecurityConfig` is **not modified**. Its `.anyRequest().denyAll()` remains the production answer
for these paths, and the dev chain is additive and profile-scoped, so deleting it restores today's
behaviour exactly. This is the same defence-in-depth shape the tenant isolation work uses: the
second layer is not redundant, it is what makes a mistake in the first layer fail closed.

**D2 makes the UI's absence structural rather than configured.** `springdoc-openapi-starter-webmvc-ui`
goes in Spring Boot's `developmentOnly` configuration — present for `bootRun`, excluded from
`bootJar`. The swagger-ui webjar therefore never reaches a production artefact even if someone
later flips a property by mistake. Only `...-webmvc-api`, the generator, ships. Confirm during
implementation that springdoc's UI auto-configuration degrades cleanly when the UI starter is
absent from the runtime classpath; if it does not, fall back to shipping the `-ui` starter and
relying on the two layers above, and record the reason.

**One honest limitation.** D8 hides `DemoRecordController` from the document; it does not remove
`GET /api/v1/demo-records/{id}`, which remains a live authenticated route in every profile. The
spec is a description of the intended contract, not an enumeration of every path the server will
answer. If that endpoint should not exist in production, that is a separate decision and a separate
slice.

---

## 8. Testing: prove each guard can fail

Challenge #33 is the standing reason this section exists: a guard that has never failed is
indistinguishable from one that checks nothing, and this repo has already shipped an ArchUnit rule
that passed vacuously. Each new guard gets a deliberate, reverted violation:

- **Drift guard** — add a query parameter to any controller, run `clean check`, see it fail
  naming the regeneration command; regenerate, see it pass; revert.
- **Determinism** — run the generation twice from clean and confirm byte-identical output. If
  `writer-with-order-by-keys` is not doing what §5 assumes, this is where it shows up, before the
  build starts failing intermittently for someone else.
- **Byte-identical errors** — §4's absent-`fields` assertion, plus the existing handler tests
  passing unchanged. Any edit to an existing error assertion is evidence the change was not
  behaviour-preserving and needs explaining, not accommodating.
- **oasdiff** — the absent-base path on the landing commit, then a real non-empty changelog on a
  later commit (§6). Both by observation of an actual run.

`./gradlew clean check` remains the single baseline command. Expected test count: **519 + the new
tests**; the exact number goes in `HANDOFF.md` §0 when the slice merges, per the standing practice.

---

## 9. Risks

- **springdoc ordering is not deterministic and the flag does not fix it.** Mitigation: §8's
  double-generation check catches it during implementation rather than after merge. Fallback is a
  normalising serializer in the test (sort keys before comparison) at the cost of the snapshot no
  longer being byte-for-byte what springdoc emits.
- **The error-record conversion changes the wire bytes.** Highest-consequence risk in the slice,
  because it is the one thing here that can alter application behaviour. Mitigation: §4's explicit
  assertion plus the existing handler tests; the standard is that no existing assertion may be
  edited to make the change pass.
- **JaCoCo coverage floors dip.** New configuration classes and records add instructions that
  existing tests may not touch. Mitigation: the records are exercised by the existing error-path
  tests; if a floor still fails, the fix is a test, not a lowered floor.
- **`developmentOnly` breaks springdoc's UI auto-configuration.** §7 names the fallback and
  requires recording the reason if taken.
- **The snapshot becomes a merge-conflict magnet.** A generated YAML file touched by every API
  change will conflict on concurrent branches. Nothing is in flight today, which is the cheapest
  moment to introduce it — the same argument build-hygiene made for the whole-tree reformat. The
  resolution is always "regenerate", never "hand-merge the YAML", and that must be written down
  where someone hitting the conflict will find it.

---

## 10. Out of scope

- **`@Operation` / `@Schema` prose on 74 endpoints** (D9). It is a large mechanical diff whose
  value decays without an owner, and the structural spec is useful to a client without it. Revisit
  when the frontend is being built and specific endpoints prove ambiguous — that is a real trigger,
  not a vague later.
- **Client code generation.** No consumer to generate for yet.
- **Blocking breaking-change detection** — deferred with the trigger in D6/§6.
- **Pact** — unchanged from the build-hygiene spec's deferral: when the React frontend exists.
- **API versioning changes.** Every route already sits under `/api/v1`; nothing here revisits that.
- **Any change to endpoint behaviour, routing, status codes or payloads.** The error envelope is a
  representation change and must be byte-identical (§4). If this slice changes what the server
  does, it has failed.

---

## 11. Task sketch

Ordering is by risk: the compatibility question and the behaviour-preserving change come first,
because everything else is worthless if either fails.

1. **Dependencies and the OpenAPI bean.** Catalog entries (§3), `implementation` /
   `developmentOnly` split (D2), `OpenApiConfig` with title, `info.version`, servers, bearer-JWT
   security scheme and tags. Confirm `writer-with-order-by-keys` exists on 3.1.0.
2. **Typed error envelope.** `ApiError` / `ApiErrorResponse`, all seven handlers converted,
   `@ApiResponse` per handler, absent-`fields` assertion. Byte-identity is the acceptance bar.
3. **Structural annotations.** `@ParameterObject` × 8, `@Hidden` on `DemoRecordController`.
4. **Snapshot and drift guard.** `OpenApiSnapshotTest` both modes, `updateOpenApiSnapshot` task,
   injected path, first committed `docs/api/openapi.yaml`. Prove it fails (§8).
5. **Dev-profile exposure.** springdoc `enabled` flags per profile, `@Profile("dev")` filter chain,
   confirm `SecurityConfig` untouched and production behaviour unchanged.
6. **oasdiff in CI.** `fetch-depth: 2`, absent-base guard, job summary, `continue-on-error`.
7. **Docs wrap-up.** `HANDOFF.md` §0/§3/§8 (including correcting the "18 controllers" figure to
   16), `annotations-reference.md` rows for `@ParameterObject`, `@Hidden`, `@ApiResponse`,
   `@JsonInclude` and `@TestPropertySource` if absent, and the challenges-log entry — the
   single-generation-path/determinism problem is the strongest candidate, with the post-merge
   `fetch-depth` trap second.
