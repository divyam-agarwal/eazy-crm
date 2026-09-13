# Spring Modulith — evaluation and decision

**Date:** 2026-09-03
**Status:** Decision. No code changed. **Amended 2026-09-13 — see Part 7. A spike ran `verify()`
for real and two of this document's conclusions did not survive it: MA1's violation count was 446
rather than "higher than three", and M4 (declare `platform` OPEN) turns out to suppress the very
cycles M2 adopts `verify()` to catch. Read Part 7 before acting on M2, M4 or Part 6.**
**Code baseline:** `28f9ac8` — `main`, 586 tests, Spring Boot 4.1.0, Java 25.
**Parent:** [`2026-08-24-service-scope-and-shared-modules.md`](2026-08-24-service-scope-and-shared-modules.md)
(the five services and their boundaries)
**Siblings:** [`2026-08-19-aws-target-architecture-design.md`](2026-08-19-aws-target-architecture-design.md) ·
[`2026-08-19-outbox-lld.md`](2026-08-19-outbox-lld.md) ·
[`2026-08-26-identity-provider-evaluation.md`](2026-08-26-identity-provider-evaluation.md)
(the shape this document copies: evaluate, decide, record the trigger to revisit)

---

# Part 1 — Decision

## 1.1 What was decided

| # | Decision | Why |
|---|---|---|
| **M1** | **Adopt Spring Modulith, structure-verification half only**, as build-hygiene Wave 1.6 | Nothing in this repo has ever checked a module boundary, and two dependency inversions landed in three days without any gate noticing (§3) |
| **M2** | **Take `ApplicationModules.verify()` as a build gate**, in the same `check` task as the four existing ArchUnit tests | It is the only automated statement of the five-service boundaries. Today those boundaries live in prose that has already drifted from the code |
| **M3** | **Take `Documenter`** — C4 component diagrams and module canvases — and guard the output with the `OpenApiSnapshotTest` snapshot pattern | Generated from bytecode, so it cannot drift the way the parent doc did. The guard pattern already exists and is proven |
| **M4** | **Declare `platform` an OPEN module** on adoption, and carve named interfaces later | 12 subpackages and 77 inbound imports from `sales` alone. OPEN buys encapsulation relief on day one; it does **not** excuse the cycles, which must be fixed regardless |
| **M5** | **Decline the event publication registry and event externalisation** | Three collisions with `2026-08-19-outbox-lld.md`, which is the more specified design: tenancy, the relay's role boundary, and ordering (§5) |
| **M6** | **Defer `@ApplicationModuleTest`, the actuator endpoint, and module observability** to Wave 2 | Real but small value; the observability piece must be decided once, together with Micrometer Tracing, not stood up twice |
| **M7** | **Modulith models modules as packages; the six platform LLDs model them as Gradle modules. The package model is the source of truth for boundaries** | The two coexist — `com.easycrm.platform.error` is already a split package across `backend/` and `platform-primitives`, and Modulith reads the classpath. But only one may own the rules, or both will grow them |

## 1.2 The trigger to revisit M5

**If the AWS split is abandoned or deferred indefinitely and events stay in-process.** The registry
then becomes a cheap way to make the existing `@EventListener` seams crash-safe. Today those
listeners are synchronous and run inside the publisher's transaction *by design* (challenge #3
atomicity), so there is nothing to recover and nothing for the registry to do.

---

# Part 2 — Compatibility, verified

| Fact | Value | How it was checked |
|---|---|---|
| Latest release | **2.1.1**, deployed 2026-08-25 | `repo1.maven.org/.../spring-modulith-bom/maven-metadata.xml` |
| Targets | `spring-boot-autoconfigure:4.1.1`, `spring-context:7.0.9` | `spring-modulith-core-2.1.1.pom` |
| Repo is on | Boot 4.1.0, Framework 7.x, Java 25 | `gradle/libs.versions.toml` |
| Transitive ArchUnit | **1.4.2, compile scope** | `spring-modulith-core-2.1.1.pom` |
| Repo pins ArchUnit | **1.4.1**, deliberately | `libs.versions.toml` — "1.3.0 silently skips Java 25 bytecode" |
| SNS / SQS support | **None.** The BOM ships amqp, jms, kafka, jobrunr, messaging, mongodb, namastack | Artifact list in `spring-modulith-bom-2.1.1.pom` |

**Two traps worth recording, both of a kind this repo has already been bitten by.**

1. **The 1.x line is the Boot 3 line.** Every pre-2026 tutorial names it. This is the same trap
   `libs.versions.toml` already documents for springdoc 2.x vs 3.x — and the same fix: read the
   POM's declared Boot version, not the tutorial.
2. **Maven Central's search API is stale for this coordinate.** `search.maven.org` returns 1.4.1
   (2025-06) as the newest; `repo1.maven.org`'s `maven-metadata.xml` returns the 2.x line. The
   catalog's existing comments already prefer repo1 for exactly this reason. **Never version-check
   this dependency through the search API.**

**MF3 — the ArchUnit pin is a real interaction, not a formality.** Gradle resolves highest-wins, so
adding Modulith silently moves ArchUnit from the 1.4.1 you chose to 1.4.2. 1.4.2 is newer than the
documented floor and is expected to be fine, but the bump must be **made in the catalog with a
comment**, not absorbed. A version chosen because an older one *passed rules vacuously* is exactly
the version you do not let a transitive dependency pick.

---

# Part 3 — What `verify()` finds today

Computed from the `import` graph of `backend/src/main/java/com/easycrm` at `28f9ac8`:

```
catalog   -> platform(15)
crm       -> platform(12) iam(2)
demo      -> platform(3) tenant(2)
iam       -> platform(24) tenant(7)
platform  -> sales(12) tenant(3) crm(3)     <-- the wrong direction
sales     -> platform(77) iam(8) tenant(6) crm(5) catalog(4)
tenant    -> platform(4)
```

**Three cycles, all through `platform`, all from two files.**

| # | Finding | Severity |
|---|---|---|
| **MF1** | **`platform.visibility` imports every domain aggregate.** `VisibleFinder` imports `crm.Customer`/`CustomerRepository` and `sales.{Enquiry,Quotation,Order,FollowUp}` + their repositories (10 imports); `VisibilityPolicy` imports the same four aggregates (5). With `sales → platform` at 77 imports and `crm → platform` at 12, this is a hard cycle both ways. **Under the five-service split `platform` is shared by all five, so `master-data-svc` would compile-depend on `sales.Quotation` and `sales.Order`.** Strictly worse than S6 (`PdfEngine` in platform), which the parent doc already logs | **Blocking** for sub-project 8 |
| **MF2** | **`platform.job.TenantJobRunner` imports `tenant.{Tenant,TenantRepository,TenantStatus}`**, and `tenant → platform` (4). Same shape as MF1, one third the size | **Minor**, same fix |
| **MF3** | The transitive ArchUnit bump (Part 2) | Minor, decide in the catalog |
| **MF4** | **`demo` becomes a module with no owner.** `verify()` will surface it as a first-class module of 4 classes belonging to no service. S9 already says to delete it; this makes ignoring S9 cost something | Cosmetic, but forces the S9 decision |

## 3.1 Why the existing gates could not see this

The four ArchUnit tests guard **tenant scoping** (`TenantScopingArchTest`), **visibility scoping**
(`VisibilityScopingArchTest`), **activity-repository scoping** (`ActivityRepositoryScopingArchTest`)
and **the primitives `JsonMapper`** (`PlatformPrimitivesArchTest`). Not one of them asserts anything
about which package may depend on which. There has never been a module-boundary gate in this repo.

## 3.2 Why the design docs could not see it either

`VisibleFinder` was created 2026-08-29 (`873436c`) and `TenantJobRunner` 2026-08-31 (`a855056`).
The parent doc was last touched **2026-08-24**. Its findings S1–S10 predate both files, so the
prose that defines the service boundaries was already stale when it was written down — and nothing
tells you that except reading the imports by hand, which is what produced this document.

**This is the argument for M1 in one paragraph.** Two inversions that break the split went in over
three days, past four ArchUnit tests, a full `clean check`, SpotBugs, and a task-by-task review,
into a repo whose defining discipline is structural enforcement over procedural care.

## 3.3 What is already clean, and is the template for the fix

**`iam` imports nothing from `crm`, `sales`, or `catalog`.** Challenge #66's `AssignedWorkload`
port — declared in `iam`, implemented by `crm.CustomerWorkload`, `sales.EnquiryWorkload`,
`sales.FollowUpWorkload` — inverts correctly, and `verify()` would pass it unchanged.

**`VisibleFinder` is the same problem with the opposite outcome, and takes the same remedy:** a port
in `platform`, implementations in the packages that own the aggregates. `VisibilityScopingArchTest`
must keep passing across the change — it is the rule that made `VisibleFinder` a single chokepoint
in the first place, and the fix must preserve the chokepoint while reversing the arrow.

---

# Part 4 — Feature-by-feature

| Feature | Artifact | Decision | Reasoning |
|---|---|---|---|
| Structure verification — `ApplicationModules.of(EasyCrmApplication.class).verify()` | `spring-modulith-core` (test) | **Take** (M2) | The whole reason to adopt. Makes the split's boundaries executable *before* extraction |
| Documentation — C4 PlantUML + module canvases | `spring-modulith-docs` (test) | **Take** (M3) | Cannot drift; commit the output, guard it like `docs/api/openapi.yaml` |
| `@ApplicationModule` / `@NamedInterface` / `type = OPEN` | annotations | **Take** (M4) | The migration lever for `platform` |
| `@ApplicationModuleTest` | `spring-modulith-starter-test` | **Defer** | Real value, but 586 tests pass at acceptable time on Testcontainers-shaped integration tests |
| Actuator `/actuator/modules` | `spring-modulith-actuator` | **Defer to Wave 2** | Near-zero value before observability exists |
| Module observability (per-module spans) | `spring-modulith-observability-core` | **Defer to Wave 2** | Proxies module beans to emit spans. Decide once, with Micrometer Tracing |
| Event publication registry | `starter-jdbc` / `starter-jpa` | **Decline** (M5) | Part 5 |
| Event externalisation | `events-kafka` / `-amqp` / `-jms` / … | **Decline** (M5) | **No SNS or SQS artifact exists.** The target transport is not supported first-party |
| `spring-modulith-moments` | — | **Decline** | Domain-time events; nothing needs them |

---

# Part 5 — Why the events half is declined

`2026-08-19-outbox-lld.md` is more specified than what Modulith offers, and the two disagree exactly
where this product cannot afford disagreement.

| # | Collision |
|---|---|
| **1. Tenancy** | `JpaEventPublication` maps nine columns — `id, publicationDate, listenerId, serializedEvent, eventType, completionDate, lastResubmissionDate, completionAttempts, status`. **There is no `tenant_id`.** `event_publication` would become a global table holding every tenant's serialised event bodies with no RLS policy, in a codebase whose first principle is that tenant isolation is structural. It would need an explicit-review exemption in `TenantScopingArchTest.GLOBAL_TABLES` and the `RlsCoverageIntegrationTest` allowlist — for the one table carrying all tenants' payloads |
| **2. The relay's role boundary** | The LLD runs the relay as a separate `relay_app` Postgres role with per-table policy exemptions and an asserted `rolbypassrls = false` (OF6, test 7a). Modulith's registry runs on the application's own DataSource. Adopting it discards that boundary |
| **3. Ordering** | The LLD guarantees per-aggregate order via UUIDv7 and `ORDER BY occurred_at, id`, with ShedLock on the relay and SNS FIFO `MessageGroupId = quotation_id`. Modulith's registry tracks per-**listener** completion (`listenerId`). That is a different guarantee, not a weaker version of the same one |

Point 1 is the decisive one. The others could be engineered around; a global, unpoliced table of
cross-tenant payloads is the precise failure mode challenge #1 exists to prevent.

---

# Part 6 — Cost, and the shape of the slice

Adding the dependency is minutes. **The work is fixing the cycles, which is owed regardless of
whether Modulith is adopted** — MF1 blocks sub-project 8 on its own.

1. **Invert `VisibleFinder` / `VisibilityPolicy`** — port in `platform`, implementations in `crm`
   and `sales`, following `AssignedWorkload`. `VisibilityScopingArchTest` must stay green. This is
   the bulk of the slice.
2. **Invert `TenantJobRunner`'s tenant enumeration** behind a port. Three imports.
3. **Settle `demo`** (MF4) — S9 says delete it.
4. **Declare `platform` OPEN**, add the `ModularityTest`, wire `Documenter` output plus its snapshot
   guard, and pin ArchUnit explicitly in the catalog (MF3).

**Two facts that make this cheaper than it looks.** `com.easycrm.platform.error` is already a split
package across `backend/src/main/java` and `platform-primitives`, and Modulith reads the classpath —
so it sees both jars as one `platform` module and the existing Gradle extraction does not fight the
package model (this is the concrete basis for M7). And `sales`, at 88 classes, is the only module
large enough for a boundary fix to be non-trivial; the rest are 4–38.

---

# Appendix A — What was not verified

| # | Not verified | Consequence |
|---|---|---|
| **MA1** | `verify()` was not actually run. The cycles are computed from `import` statements, which is what Modulith reads, but it also counts field/parameter/return types, annotations and constructor injection — **the real violation count will be higher than three, not lower** | Plan for a longer first-run list. The three named cycles are certain; they are a floor |
| **MA2** | Whether Modulith's default module detection treats each of `catalog, crm, demo, iam, platform, sales, tenant` as one module, given `platform`'s 12 subpackages | Expected yes (direct subpackages of the app package). Confirm before writing the plan |
| **MA3** | Whether `Documenter` output is byte-stable across runs, as `updateOpenApiSnapshot` was proven to be (challenge #63) | If it is not, the snapshot guard in M3 is not viable and documentation generation becomes report-only |
| **MA4** | Interaction between `spring-modulith-observability-core`'s bean proxying and the existing filter chain / `TenantContext` | Deferred with M6; must be settled inside Wave 2, not assumed |
| **MA5** | Whether ArchUnit 1.4.2 parses Java 25 bytecode as completely as 1.4.1 does | Non-vacuity is already asserted by `PlatformPrimitivesArchTest.theImportIsNotVacuous`. Watch that test on the bump |

# Appendix B — Sources

Checked 2026-09-03.

- Spring Modulith reference — fundamentals, documentation, production-ready, appendix
  (`github.com/spring-projects/spring-modulith`, via context7)
- `repo1.maven.org/maven2/org/springframework/modulith/` — `maven-metadata.xml`,
  `spring-modulith-bom-2.1.1.pom`, `spring-modulith-core-2.1.1.pom`
- This repository at `28f9ac8`: the `com.easycrm` import graph, `gradle/libs.versions.toml`,
  the four tests in `src/test/java/com/easycrm/arch/`, and `git log --diff-filter=A` for
  `VisibleFinder` and `TenantJobRunner`


---

# Part 7 — Spike findings, 2026-09-13

Appendix A said `verify()` had not been run and that MA2 should be confirmed "before writing the
plan." It was, on throwaway branch `spike-modulith-verify` (since deleted), at `b858429` with
Modulith 2.1.1. Full design consequences:
[`../superpowers/specs/2026-09-13-wave-1.6-module-boundaries-design.md`](../superpowers/specs/2026-09-13-wave-1.6-module-boundaries-design.md).

| Appendix A item | Outcome |
|---|---|
| **MA1** — real violation count is a floor of 3 | **446.** 12 cycles + 434 "depends on non-exposed type", 0 other. The 434 concentrate in `platform.persistence.TenantScopedEntity`, `platform.error.*` and `platform.web.PageResponse` |
| **MA2** — does detection see 7 modules? | **Confirmed. Exactly 7**, `platform`'s 12 subpackages collapsing into one. `demo` is detected as a module, so **MF4 is real** |
| **MA3** — is `Documenter` output byte-stable? | **Recorded "Confirmed. 16 files, 0 differ" across two consecutive runs — this was a false positive.** Both runs executed inside the same test method in the same JVM, exactly the context that hides the instability: it is a function of execution context, not of merely re-running the generator. Wave 1.6 Task 9 found the bytes differ reproducibly between `updateModulithDocs` (sole test in its JVM) and `clean check` (same test, ~550th of 598, forked JVM) — same machine, same JDK (challenge #79). The M3 snapshot guard is viable only because it now canonicalizes the unordered output before comparing; the risk this predicted was real, just on a different axis than expected — cross-machine and cross-JDK-patch drift is *still* unverified |

## 7.1 M2 and M4 conflict — the finding that reshaped Wave 1.6

**Declaring `platform` OPEN makes `verify()` pass with zero violations — all 12 cycles included**,
because every one of the 12 routes through `platform`. Isolated rather than inferred: with
`platform` still OPEN, a planted `catalog → sales → catalog` cycle **is** caught. So OPEN suppresses
cycles *through the open module*, not cycle detection in general.

M4 says OPEN "does not excuse the cycles, which must be fixed regardless." True, and insufficient:
after OPEN, nothing in the build reports them. **Adopt Modulith, declare `platform` OPEN, fix
nothing, and `verify()` certifies the graph that blocks SP8.** Logged as challenge #75.

**M2 is therefore amended:** `verify()` is kept for module detection, the C4 documentation and
cycles **not** involving `platform`. The H4 gate becomes a hand-written ArchUnit rule — no class in
`com.easycrm.platform..` may depend on `crm`, `sales`, `catalog`, `tenant` or `iam` — which OPEN does
not affect. **M4 stands:** `platform` is one shared library every service consumes, so the 434
encapsulation findings are noise and named interfaces buy little.

## 7.2 Part 6's remedy for MF1 is not available

Part 6 says to invert `VisibleFinder` with "a port in `platform`, implementations in `crm` and
`sales`, following `AssignedWorkload`." **The two are not the same shape.** `AssignedWorkload`
returns `String` and `long`, so nothing of `crm`/`sales` crosses. `VisibleFinder` returns
`Optional<Customer>`, `Page<Quotation>`, `List<Quotation>` — the domain aggregates **are** the
return values, so a port declared in `platform` still imports them. `VisibilityPolicy.viaCustomer`
has the same problem, building a criteria subquery on `Root<Customer>`.

Wave 1.6 therefore **deletes `platform.visibility`** rather than inverting it: the rule is derived
per-module from the JWT role claim, and the specs plus repository reads move to `crm` and `sales`.
MF1 closes by deletion. See §3.1 of the Wave 1.6 spec.

## 7.3 API drift — Part 2's compatibility table is right, its call sites are 1.x

| Assumed | Actual in 2.1.1 |
|---|---|
| `ApplicationModule.getName()` | `getIdentifier()` / `getDisplayName()` |
| `new Documenter(modules, "dir")` | `new Documenter(modules, Documenter.Options.defaults().withOutputFolder(dir))` |
| Modulith is test-scope only | `package-info.java` carrying `@ApplicationModule` needs `spring-modulith-api` at **compile** scope |

And a third version trap for Part 2's list: **`repo1.maven.org`'s `maven-metadata.xml` now reports
`<release>2.2.0-M1</release>` — a milestone in the `release` field.** Part 2's rule "read repo1, not
the search API" is necessary but not sufficient; the release field itself cannot be read naively.
Latest stable remains **2.1.1**.

## 7.4 What the spike additionally found, outside this document's scope

**Acyclicity is necessary and nowhere near sufficient for extraction.** Four direct cross-domain
repository reads exist (`sales → crm.ContactRepository`, `sales → catalog.{Product,PriceListItem}Repository`,
`sales`/`iam` → `tenant.TenantRepository`). None is a cycle, so `verify()` reports them only as
"non-exposed type" — silenced by OPEN. Wave 1.6 freezes them behind a second ArchUnit rule with an
exit plan per edge; the resolution design is a new roadmap item. The schema, by contrast, is already
clean: **zero FK constraints in all 34 migrations.**

Auditing those reads also surfaced **H7**: `QuotationPdfService` freezes the buyer but reads the
seller live, and computes the tax presentation from the frozen `placeOfSupply` against the live
tenant `stateCode`. See §5.3 of the Wave 1.6 spec.
