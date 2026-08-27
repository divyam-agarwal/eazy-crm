# `platform-primitives` — Low-Level Design

**Date:** 2026-08-26 (implementation status updated 2026-08-27)
**Status:** **IMPLEMENTED AND MERGED** to `main` as merge commit `210545e`. Built on branch
`platform-primitives-module` as commits `4d43d75`..`6c255d4` — eleven commits: eight task commits,
two task-review fix commits, and one final whole-branch-review fix commit — verified at **262 tests,
0 failures, 0 errors** across both Gradle projects, re-run on the merged result. Design-time status
was "Design only; zero code changed" at baseline `80e74a3`; the branch was cut from `main` at
`ac4eaca`.
**Code baseline:** `80e74a3` (design); `ac4eaca` (implementation branch point)
**Parent:** [`../superpowers/specs/2026-08-26-shared-platform-modules-design.md`](../superpowers/specs/2026-08-26-shared-platform-modules-design.md)
**Sibling:** [`2026-08-19-outbox-lld.md`](2026-08-19-outbox-lld.md) — the module that consumes this one
**LLD #1 of 6.** Written first because it is the only module with no dependencies.

---

# Part 0 — What changed from the parent spec

The parent named this module **`platform-money`** and asserted it depended on nothing (P4). The
first read of the source falsified that: `Gstin` and `StateCode` both
`import com.easycrm.platform.error.ValidationException`, which the parent placed in `platform-web`.
Since all five services import money, `notification-svc` would have inherited the
`@RestControllerAdvice` and the whole servlet stack — contradicting the parent's own claim that
notification takes three of six modules and no servlet.

The root cause is that `platform/error` is two different things in one package:

| Class | Imports | Nature |
|---|---|---|
| `NotFoundException`, `UnauthorizedException`, `ForbiddenException`, `ConflictException` | **none at all** | vocabulary |
| `ValidationException` | `java.util.Map` | vocabulary |
| `ApiExceptionHandler` | Spring MVC, Spring DAO, `HttpStatus` | HTTP mapping |

**Resolution (supersedes P4).** The five exception *types* sink to the bottom of the graph and ship
here; `ApiExceptionHandler` and `PageResponse` stay in `platform-web`. The module is renamed
`platform-primitives`, because it now holds the error vocabulary, the money wire format and the
fiscal value types — every zero-dependency primitive in the system, and nothing else.

Still six modules. `platform-web` gains a dependency on this one; nothing else in the parent's graph
moves.

---

# Part 1 — Where the code lives

```
platform/
└── platform-primitives/
    ├── build.gradle.kts
    └── src/main/java/com/easycrm/platform/
        ├── error/
        │   ├── NotFoundException.java          moved from platform-web
        │   ├── UnauthorizedException.java      moved
        │   ├── ForbiddenException.java         moved
        │   ├── ConflictException.java          moved
        │   └── ValidationException.java        moved
        ├── money/
        │   ├── BigDecimalStringModule.java     unchanged
        │   ├── MoneyJacksonConfig.java         → MoneyAutoConfiguration
        │   └── EventJson.java                  NEW
        └── gst/
            ├── Gstin.java                      one behaviour change, see 2.4
            └── StateCode.java                  unchanged
```

**Package names do not change.** Every class keeps `com.easycrm.platform.{error,money,gst}`, so no
service changes a single `import`. The split is a build-file change plus file moves — which is the
cheapest possible way to introduce a module boundary, and the reason to do it before there are five
services rather than after.

## 1.1 Build file

```kotlin
// platform/platform-primitives/build.gradle.kts
dependencies {
    api("tools.jackson.core:jackson-databind")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
}
```

`api`, not `implementation`, for Jackson: `platform-outbox` receives a `JsonMapper` from
`EventJson.mapper()` and must see the type.

`compileOnly` for Boot: the value types and the module must be usable without Spring, and P8's
`@ConditionalOnClass` pattern means the auto-configuration simply does not activate where Boot is
absent. This module has **no runtime Spring dependency**.

## 1.2 Auto-configuration

`MoneyJacksonConfig` is a `@Configuration` today, discovered by component scan from `com.easycrm`.
Once this is a separate jar that no longer works — a service's `@SpringBootApplication` sits at
`com.easycrm.sales`, which does not scan `com.easycrm.platform`. It becomes an auto-configuration,
registered the same way `platform-outbox` registers its own:

```
src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

This is the single most likely thing to break silently during the split: component scan stops
finding the bean, the module is never registered, and money starts crossing the HTTP wire as a
number with no error anywhere. See MB1 and the test in 5.2 that exists to catch exactly this.

---

# Part 2 — Class model

## 2.1 The error vocabulary

Five unchecked exceptions, moved verbatim. `ValidationException` carries a `Map<String, String>` of
field errors — `ApiExceptionHandler` widens it to `Map<String, Object>` when building the response
envelope, which is a rendering concern and stays on that side of the boundary. The rest carry a
message.

They ship here rather than with the handler because **an exception type is a statement about the
domain, and mapping it to a status code is a statement about HTTP.** The first is meaningful to an
SQS consumer; the second is not. `notification-svc` has no HTTP surface and still throws
`NotFoundException` when an event references a row that has since been archived.

`platform-web` keeps `ApiExceptionHandler`, and with it the two behaviours that are load-bearing
rather than cosmetic: 404-not-403 for cross-tenant reads, and the 409 backstops for constraint and
optimistic-lock violations.

## 2.2 `BigDecimalStringModule`

Unchanged. Serialises every `BigDecimal` as a JSON string in plain notation.

**It is a numeric-precision serialiser, not a money serialiser**, and the distinction decides every
alternative below. `QuotationItem` carries nine `BigDecimal` fields: six are money, and three are
not — `qty` `NUMERIC(18,3)`, `gstRate` `NUMERIC(18,4)`, `discountPct` `NUMERIC(18,4)`. All nine are
covered today, because the module keys off the Java type rather than the field's meaning. A quantity
of `2.5` KG and a rate of `18.0000`% have exactly the IEEE-754 problem money has.

```java
gen.writeString(value.toPlainString());
```

Three properties worth stating because a reader will assume otherwise:

- **It does not round.** A `BigDecimal` with scale 6 serialises with six decimal places. Rounding is
  `GstCalculator`'s job (per line, `HALF_UP`, then sum) and the column's job
  (`NUMERIC(18,2)` / `(18,4)`). The wire shows exactly what was computed — a wire that silently
  rounded would hide precisely the disagreement with Tally the design exists to prevent.
- **It does not touch deserialisation.** Jackson already coerces a JSON string to `BigDecimal`. It
  also accepts a JSON *number*, so a client can still send `0.30000000000000004`. That is contained,
  not prevented: the server recomputes every total and is authoritative, and the client preview is
  never trusted.
- **`toPlainString()` is the point.** `toString()` would emit scientific notation for large or
  small scales, and `"1.25E+3"` is not a number any Indian accountant or Tally import will accept.

### Rejected alternatives

Recorded so this is not re-litigated. All three were evaluated against the code, not in the
abstract.

**`jackson-datatype-javax-money` (JSR-354).** Available and current — Zalando's module was archived
in November 2025 and consolidated into FasterXML as `tools.jackson.datatype:jackson-datatype-javax-money`,
which supports Jackson 3. Compatibility is not the objection; scope is.

`QuotationItem` carries nine `BigDecimal` fields. Six are money (`rate`, `taxableValue`, `cgst`,
`sgst`, `igst`, `lineTotal`) and **three are not**: `qty` `NUMERIC(18,3)`, `discountPct`
`NUMERIC(18,4)` and `gstRate` `NUMERIC(18,4)` — the last being the single most frequent
`BigDecimal` in the codebase. A quantity of `2.5` KG has exactly the IEEE-754 problem money has, so
adopting `MonetaryAmount` would **not** let us delete this serialiser. We would run both mechanisms.

Three further costs, none of which the benefit covers for a single-currency product:

- Every money field becomes `{"amount":"12.50","currency":"INR"}` — six repetitions of `INR` per
  line item, carrying no information. Amount serialises as a JSON *number* unless
  `withQuotedDecimalNumbers()` is configured, which is where we already are.
- Persistence needs a currency column on every money table, or an `AttributeConverter` that
  discards the currency — using the type for nothing. Twenty-five migrations currently use plain
  `NUMERIC`.
- JavaMoney brings `MonetaryRounding`, a second rounding authority that must be configured to agree
  with `GstCalculator`'s per-line `HALF_UP` and independently-rounded CGST/SGST, or the two disagree
  silently. The correctness criterion here is "round exactly the way Tally does"; a rounding
  framework is risk added, not removed.

**Per-field `@JsonFormat(shape = Shape.STRING)` or `ToStringSerializer`.** Zero custom code, which is
the right default. Rejected because it is procedural: someone must remember the annotation on every
new money field, and the failure is silent — the field ships as a JSON number and nothing complains.
A global serialiser is structural, which is the same argument this codebase makes for tenant
scoping. Twenty-eight lines is a fair price for "nobody has to remember."

**A datatype module for the non-money fields.** Searched; there is nothing to adopt.
`jackson-datatypes-misc` contains exactly six modules — `javax-money`, `moneta`, `joda-money`,
`jsr353`, `jakarta-jsonp`, `json-org` — three money, three JSON-P interop, none for quantities or
percentages. JSR-385 (units of measurement) has **no official Jackson module**; Indriya issue #203,
"How to serialize Quantities and Units?", is still an open community question, so that route means
hand-rolling a serialiser anyway *plus* adopting a units framework.

The one maintained option is `com.raynigon.unit-api:spring-boot-jackson-starter` (JSR-385 with
Jackson `@JsonUnit`, JPA and springdoc starters). It does not fit: `PCS` and `BOX` are not physical
units and would need custom dimensionless definitions; `uomSnapshot` is a deliberately frozen
*string* because a sent quotation must render identically forever, whereas a `Quantity` binds value
and unit as a live type; one `NUMERIC(18,3)` column becomes value plus unit; `qty × rate` in
`BigDecimal` becomes `Quantity` arithmetic needing unwrapping at every step; and it still emits
`{"value": 2.5, "unit": "kg"}`, a JSON number, so the wire problem survives intact.

For `gstRate` and `discountPct` there is nothing at all — no Jackson module and no standard Java
type for a percentage.

**`JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS`.** The only zero-custom-code option that would cover
all nine fields: one builder line, moved in Jackson 3 from `JsonGenerator.Feature` into
`JsonWriteFeature`. Rejected on scope, not capability — it stringifies *every* number, so
`totalElements`, `versionNo`, page sizes and counts all become strings, the API turns
stringly-typed, and existing assertions like `jsonPath("$.totalElements").value(1)` break.

**`SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN`.** Looks like the fix; is not. It controls
notation, not type — the value stays a JSON number and JavaScript still parses it as a double.
Worth naming because it is the first thing a reviewer will suggest.

## 2.3 The two wires — `EventJson`

**This is the module's one real design decision.**

Money now crosses two wires, not one:

| Wire | Mapper | Owned by | Contract lifetime |
|---|---|---|---|
| HTTP responses | the application `ObjectMapper` | Spring Boot | versioned by the API; may change |
| Outbox `payload` JSONB → SNS → SQS | ??? | — | **additive-only, readable for years** |

The second is new with the split, and TB3 is the bug where it goes wrong: an `ObjectMapper`
constructed inside the outbox writer does not carry `BigDecimalStringModule`, so money reaches SNS
as an IEEE-754 double — silently undoing challenges #2 and #17 after the entire stack avoided
exactly that.

**Rejected: inject the application `ObjectMapper` into the outbox writer.** It is the obvious fix
and it couples an at-rest contract to a presentation concern. Concretely: someone sets
`spring.jackson.default-property-inclusion=non_null` to slim an API response, and every subsequent
outbox payload silently drops its null fields. For an additive-only event contract that is worse
than a crash — a consumer can no longer distinguish "field absent because the producer is older"
from "field present and null". The two wires have different owners and different change cadences, so
they get different mappers.

**Rejected: `applicationMapper.rebuild()`.** Jackson 3 mappers are immutable and `rebuild()` derives
a new one from an existing configuration — which inherits the same coupling, just one step later.

**Chosen: an explicitly-built mapper owned by this module.**

```java
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new BigDecimalStringModule())
            // every other setting stated explicitly: this wire inherits nothing
            .build();

    private EventJson() {}

    /** The mapper for anything persisted or published. Immutable and thread-safe (Jackson 3). */
    public static JsonMapper mapper() { return MAPPER; }
}
```

Jackson 3 makes `ObjectMapper` immutable for full thread-safety and moves construction behind
`MapperBuilder`, so a single `static final` instance is correct — no synchronisation, no per-call
construction, no `ThreadLocal` pooling of the sort Jackson 2 code sometimes carried.

Being *able* to build a correct mapper is not the same as nobody building an incorrect one. Part 4
closes that with a rule rather than a convention.

## 2.4 `Gstin`

A validated 15-character GSTIN. Characters 1–2 are the GST state code; character 15 is a base-36
check digit computed from the first 14 by Luhn-mod-36.

**One behaviour change: `parse` validates the state prefix.**

```java
public static Gstin parse(String raw) {
    // … length, charset, checksum as today …
    StateCode.requireValid(g.substring(0, 2));   // NEW
    return new Gstin(g);
}
```

Today `parse` validates length, charset and checksum but **not** the state prefix, so a GSTIN
beginning `00` or `39` passes as long as its check digit is consistent. `CustomerService` compensates
by calling `StateCode.requireValid(derived)` on the very next line — a two-step every caller must
remember, and the second caller did not. See MF1.

Folding the check in makes the type's name true: a `Gstin` instance is a GSTIN that could exist.

## 2.5 `StateCode`

Unchanged. Valid codes are `01`–`38` plus `97` (Other Territory) and `99` (Centre Jurisdiction).
`isValid` for a boolean, `requireValid` to throw.

It stays a separate class rather than folding into `Gstin` because a state code is also collected on
its own, without a GSTIN — an unregistered buyer below the GST threshold has a state but no GSTIN,
and `SignupRequest` collects one for the seller.

---

# Part 3 — How a service adopts it

One dependency, and nothing else:

```kotlin
dependencies { implementation(project(":platform:platform-primitives")) }
```

Everything else arrives automatically:

- `BigDecimalStringModule` is registered on the application `ObjectMapper` by
  `MoneyAutoConfiguration`, if the service has an HTTP stack.
- `EventJson.mapper()` is a static call, available with or without Spring.
- `Gstin.parse` and `StateCode.requireValid` are static calls that throw `ValidationException`,
  which `platform-web` maps to 422 in the four services that have it.

`notification-svc` takes this module with no Spring web on the classpath. The auto-configuration
does not activate; `EventJson` and the exception types work regardless. That is the whole reason the
module has no runtime Spring dependency.

---

# Part 4 — What keeps it honest

Three ArchUnit rules. The first is the important one.

**R1 — nothing outside this module may construct a JSON mapper.**

```java
noClasses().that().resideOutsideOfPackage("com.easycrm.platform.money..")
    .should().callMethod(JsonMapper.class, "builder")
    .orShould().callConstructor(ObjectMapper.class)
    .because("a mapper built elsewhere loses BigDecimalStringModule and sends money "
           + "as a JSON number — see TB3. Use EventJson.mapper() or inject Boot's.");
```

This converts TB3 from a bug someone must notice in review into a build failure. It is the same move
the codebase already makes for tenant scoping: **nobody hand-writes the dangerous thing, so nobody
can forget the safe thing.**

**R2 — this module may not depend on any other platform module or any service package.** It is the
bottom of the DAG; a dependency edge out of it means something has been placed wrong. This is what
would have caught the P4 error at build time rather than on first read.

**R3 — the existing D12 rule** (`platform` may not reference a service package) applies unchanged.

Note the rule that is **not** here. P5's principle — a mechanism used by exactly one service is not a
platform mechanism — has no automated form; an ArchUnit rule counting consumers would need the whole
service graph at test time. It stays a review rule, and PF6 records that honestly.

---

# Part 5 — Test plan

## 5.1 What exists today

| Test | Kind | Covers |
|---|---|---|
| `MoneyWireFormatTest` | **integration** (`@SpringBootTest` + MockMvc) | Creates a product and asserts the raw JSON contains `"baseRate":"12.50"` and `"gstRate":"18.0000"` — money as quoted strings, at scale, through the real HTTP stack |
| `GstinTest` | unit, 5 cases | valid parse + state extraction, trim/uppercase, bad checksum, wrong length, state-code validation |

`MoneyWireFormatTest` is doing real work and its raw-string assertion is deliberate — a JsonPath
assertion would pass against a JSON number, because JsonPath coerces. Keep the raw assertion.

## 5.2 What this design adds

**Unit, no Spring:**

| Test | Asserts |
|---|---|
| `BigDecimalStringModule` serialises `12.50` as `"12.50"` | scale is preserved, not normalised |
| … `new BigDecimal("1250")` with scale 0 | `"1250"`, not `"1250.00"` — the module does not round |
| … a large value | plain notation, never `1.25E+3` |
| … a negative and a zero | `"-1.00"`, `"0.00"` |
| **`EventJson.mapper()` serialises `BigDecimal` as a string** | **the TB3 regression test — the single most important test in this module** |
| `EventJson.mapper()` is the same instance across calls | no per-call construction |
| `Gstin.parse` rejects a checksum-valid GSTIN with state prefix `00` | the 2.4 behaviour change |
| `Gstin.parse` rejects `null` and a 15-char string with invalid characters | gaps in the current five cases |
| `StateCode` accepts `01`, `38`, `97`, `99`; rejects `00`, `39`, `null`, `"1"` | boundaries |

**Integration:**

| Test | Asserts |
|---|---|
| `MoneyWireFormatTest`, unchanged | the auto-configuration actually registered on the app mapper |

That last one is the reason not to delete it when the unit tests land. The unit test proves the
module works; only the integration test proves it was *wired* — and 1.2 is precisely where the
wiring silently disappears.

## 5.3 Not testable here

Whether the outbox writer actually uses `EventJson` rather than its own mapper. That is R1's job at
build time, and `platform-outbox`'s test plan at runtime (TB3).

---

# Part 6 — Bugs you will hit

| # | Bug | Why it happens | Fix |
|---|---|---|---|
| **MB1** | Money serialises as a JSON number on the HTTP wire after the split, with no error | `MoneyJacksonConfig` is a `@Configuration` found by component scan from `com.easycrm`; a service scanning from `com.easycrm.sales` never sees it | Auto-configuration + `AutoConfiguration.imports` (1.2). `MoneyWireFormatTest` is the tripwire |
| **MB2** | Money reaches SNS as a double | A mapper built inside the outbox writer (TB3) | `EventJson` + rule R1 |
| **MB3** | A reviewer "fixes" the serialiser to always emit two decimals | It looks like a formatting bug when a rate shows `18.0000` | It is not. `gstRate` is `NUMERIC(18,4)`; the wire must show what is stored. Rounding is `GstCalculator`'s job |
| **MB4** | `EventJson` drifts from the app mapper and someone "unifies" them | The duplication looks accidental | It is deliberate and 2.3 says why. Leave the comment in the source, not only in this doc |
| **MB5** | Circular dependency at build time between `platform-primitives` and `platform-web` | Someone adds a convenience method on an exception type that returns an `HttpStatus` | R2 fails the build. The mapping belongs in the handler |
| **MB6** | `Gstin.parse` starts rejecting GSTINs that used to be accepted | 2.4 is a genuine behaviour change | Intended. Any stored GSTIN with an invalid state prefix was already wrong; see MF2 for the backfill question |

---

# Appendix A — Findings

**Updated 2026-08-27, after implementation.** MF1–MF6 were written at design time; the third column
now carries what actually happened to each. MF7–MF10 were found while building the module and did
not exist when this document was written.

| # | Finding | Severity / status after implementation |
|---|---|---|
| **MF1** | **The seller's GSTIN is never validated, and the seller's state code is validated only as "two digits."** `SignupRequest` declares `@Pattern("\\d{2}")` on `stateCode` and a bare `String gstin` — no `Gstin.parse`, no `StateCode.requireValid`. A buyer's GSTIN goes through both in `CustomerService`. The asymmetry matters because `QuotationService.isInterState` compares `tenant.getStateCode()` against the customer's to choose **CGST+SGST vs IGST**, so an invalid seller state code silently decides the tax split of every quotation the tenant ever issues, and the unvalidated seller GSTIN prints on every PDF | **Live bug.** **FIXED, in two parts.** Task 7 (`0ffc68c`) made *validation* symmetric: `AuthService.signup` runs `Gstin.parse` on a supplied seller GSTIN, requires the derived state to equal the declared `stateCode`, and runs `StateCode.requireValid(stateCode)` unconditionally — four new tests. The whole-branch review then found *normalisation* still asymmetric — `CustomerService` persists `g.value()` while signup persisted the raw request string, so `27aapfu0939f1zv` was stored lowercase and printed that way on every PDF letterhead, and a copy-paste-padded 17-char GSTIN surfaced as a spurious 409 from the `VARCHAR(15)` constraint rather than a 422 naming the field. Closed in `6c255d4` by persisting `parsed.value()`, with two more tests. Logged as challenge #34. **The lesson is the two-part shape itself: making two paths agree on whether a value is *valid* does not make them agree on what they *store*.** |
| **MF2** | Folding `StateCode.requireValid` into `Gstin.parse` (2.4) is correct going forward but says nothing about rows already stored. Whether any existing `customer.gstin` or `tenant.gstin` has an invalid state prefix is unknown | **CLOSED for now (Task 7).** The audit was run and there was nothing to audit: **no `easycrm` database exists anywhere in this development environment** (nothing publishes 5432 but an unrelated `langfuse-postgres-1` container; every integration test uses a throwaway Testcontainers instance). No migration was written. This closes the question *for this environment only* — it is not evidence that no such row exists anywhere. The audit query is preserved in the plan and in Task 7's brief so nobody re-derives it: **re-run it against any environment that has persisted data, and before the first production deployment.** "There is no data yet" is a property of today, not a resolution |
| **MF3** | `platform-web` must now depend on `platform-primitives`, which the parent spec's graph does not show. The parent's Part 1 diagram and per-service matrix need the edge added | **DONE.** Verified 2026-08-27 against the parent spec: the Part 1 diagram already draws `platform-entitlement → platform-web → platform-primitives`, and the module table's `platform-primitives` row reads "all 5 services + `platform-outbox` + `platform-web`". No further edit needed |
| **MF4** | `MoneyWireFormatTest` is the only proof the Jackson module is wired, and it asserts through the product endpoint — so deleting or refactoring `ProductController` would silently remove the tripwire for MB1 | **RECORDED IN THE TEST (Task 2).** The dependency is now stated in `MoneyWireFormatTest` itself rather than only here — the second option, taken deliberately: a dedicated endpoint would be a *new* surface asserting the wiring, whereas the value of this test is that it goes through a real controller. `MoneyModuleWiringTest` (Task 2) is now a second, endpoint-independent proof of the same wiring, so deleting `ProductController` no longer removes the only tripwire |
| **MF5** | There is no unit test of `BigDecimalStringModule` at all today — only the integration test. A change to the serialiser fails one heavyweight Spring test with an opaque message | **CLOSED (Task 3, commit `bbc136b`).** Six unit tests in `BigDecimalStringModuleTest`, mutation-checked: replacing `gen.writeString(value.toPlainString())` with `gen.writeNumber(value)` killed 5 of the 6. The sixth (`deserialisationStillAcceptsBothStringAndNumber`) correctly survived, because it exercises the read path the module does not touch |
| **MF6** | `Gstin.parse` throws `ValidationException("gstin", …)` with a hard-coded field name, so a request carrying two GSTINs (buyer and consignee, once that exists) cannot report which one failed | **STILL ACCEPTED AS-IS.** Unchanged by this branch. Task 7 added a *second* GSTIN entry point (`SignupRequest`), but it carries one GSTIN, so the ambiguity MF6 describes still has no way to arise. Revisit when one request body carries two GSTINs (buyer + consignee) |
| **MF7** | **R1 as sketched in Part 4 above is not what shipped, and could not have.** The fluent form `should().callMethod(JsonMapper.class, "builder")` matches one *signature*, and `JsonMapper.builder()` is overloaded — so the rule would have covered the no-arg overload only and silently ignored the others: a vacuous pass with a different cause. It was replaced by a hand-written `ArchCondition` matching **any** call named `builder` on `ObjectMapper`, `JsonMapper` or `JsonMapper$Builder`, plus any constructor call on those types. The Part 4 snippet is kept as written because it is what the design said; read it as intent, not as the shipped rule. See `PlatformPrimitivesArchTest` | Discovered in Task 5 |
| **MF8** | **A hand-written `ArchCondition` under `noClasses()` has its polarity inverted**, so the natural `SimpleConditionEvent.violated(...)` for the offending class becomes a *passing* event and the rule can never fail. The plan's R1 code had exactly this bug and passed on its first run with a deliberate violation sitting in `CustomerService`. Fixed by emitting `.satisfied(...)`; proven in both directions. **This is the most valuable thing the implementation of this LLD learned** | Challenge #33. The only reason it was caught is that the plan mandated a prove-it-can-fail step; treat that step as mandatory for every future ArchUnit rule |
| **MF9** | **After the split, `importPackages("com.easycrm")` in the root project also imports the `platform-primitives` jar**, whose ten classes share the prefix. R1's non-vacuity guard was `assertThat(classes).isNotEmpty()`, which would still pass on the jar's classes alone if the root project's own bytecode stopped being imported — the exact ArchUnit 1.3.0/Java 25 failure this codebase already suffered | Fixed in Task 5: the guard now asserts `classes.contain("com.easycrm.EasyCrmApplication")`, a class that can only come from the root project. Challenge #36 |
| **MF10** | **`EventJsonDivergenceTest` found no divergence.** All three of its assertions passed unmodified on first run: today the application mapper and `EventJson` agree on money-as-string, null inclusion and ISO-8601 timestamps. Its value is entirely as a **future tripwire**, not as evidence of a present problem — do not cite it as having caught anything | Accepted and documented in the test. Its most likely trigger is someone setting `spring.jackson.default-property-inclusion=non_null`; that was verified by temporarily setting the property, at which point the application-mapper half of the test goes red and the `EventJson` half holds independently (Task 4 fix round 1) |

---

# Appendix B — Verified during implementation

**Updated 2026-08-27.** Every item below was written before any code existed. Each now carries the
answer this branch actually produced, with how it was determined. Nothing here has been quietly
dropped: an item that was **not** settled says so.

### 1. The Boot 4 artifact and package for Jackson auto-configuration — **ANSWERED**

The artifact is **`org.springframework.boot:spring-boot-jackson`**; the class is
**`org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration`**, and it is the sole
line in that jar's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
It arrives transitively with `spring-boot-starter-web` (→ `spring-boot-starter-jackson` →
`spring-boot-jackson`), so no service that serves HTTP has to name it.

**`spring-boot-autoconfigure` *is* still the correct `compileOnly` coordinate** — it is what carries
`@AutoConfiguration` and `@ConditionalOnClass`, and the module compiles against it and nothing else
from Boot. One Gradle wrinkle that cost a build: `compileOnly` is **non-transitive**, so
`org.springframework:spring-context` must be named separately even though `spring-boot-autoconfigure`
depends on it. Both are `compileOnly`, which is the point of the module — `notification-svc` takes
this jar with no Spring on its runtime classpath at all.

### 2. That an auto-configured `JacksonModule` bean is discovered like a component-scanned one — **ANSWERED: yes**

`JacksonAutoConfiguration$AbstractMapperBuilderCustomizer`'s constructor takes a
`(JacksonProperties, Collection<JacksonModule>, AutowireCapableBeanFactory)` — verified by `javap`
against `spring-boot-jackson-4.1.0.jar`. The modules are collected **by type**, so a `JacksonModule`
bean is registered on the application mapper regardless of which configuration class declared it,
and **no `@AutoConfigureBefore`/`@AutoConfigureAfter` ordering was needed**. `MoneyWireFormatTest`
(money as a quoted string through the real HTTP stack) is the end-to-end proof.

The related trap is worth reading before touching this: `MoneyAutoConfiguration` is named in the
`.imports` file **and** sits inside the `com.easycrm` range `EasyCrmApplication` component-scans, so
it is reachable twice. No `BeanDefinitionOverrideException`, no duplicate bean — Spring's
`ConfigurationClassParser` de-duplicates by **class identity**, structurally. But that means "one
bean exists" is *not* evidence the `.imports` file was read: component scan alone would produce the
same bean. The discriminator is the bean-definition **name**, because Spring uses two different
generators — `AnnotationBeanNameGenerator` (decapitalised short name) for scanned classes, and a
hardcoded `FullyQualifiedAnnotationBeanNameGenerator` (the FQCN) for classes reached via
`@Import`/`AutoConfiguration.imports`. `MoneyModuleWiringTest.theAutoConfigurationIsWhatRegisteredIt`
asserts on the FQCN for exactly that reason. See challenge #35.

### 3. `JsonMapper.builder()` defaults in Jackson 3 versus Boot's configured mapper — **ANSWERED, and it is the item with the most substance**

Determined empirically by building a bare `JsonMapper.builder().build()` against the same
`jackson-databind-3.1.4` jars with none of `EventJson`'s calls present, and re-checking each
feature's `enabledByDefault`.

**Only the `BigDecimalStringModule` line changes `EventJson`'s behaviour today.** Jackson 3 already:

| Setting `EventJson` pins | Jackson 3 default | Load-bearing today? |
|---|---|---|
| `addModule(new BigDecimalStringModule())` | absent | **Yes** — remove it and `serialisesBigDecimalAsAString` fails |
| `disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)` | already disabled (ISO-8601) | No |
| `disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)` | already disabled | No |
| `disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` | `enabledByDefault = false` | No |
| default property inclusion `ALWAYS` | `ConfigOverrides` default is `JsonInclude.Value.of(USE_DEFAULTS, USE_DEFAULTS)`, which resolves to `ALWAYS` for a plain field at write time | No |

**They are pinned explicitly anyway, deliberately.** §2.3's "inherits nothing" is the module's whole
thesis: an event contract read for years must not rest on a library default, and "matches today's
default" is not a statement about tomorrow's. The risk this creates is that a later reader deletes
them as dead config, so the reasoning is written into `EventJson`'s Javadoc at the call sites, not
only here. Do not read "currently redundant" as "safe to delete."

**Separately — an API break the design did not anticipate.** `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`
and `SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS` **do not exist in Jackson 3**. They
moved to `tools.jackson.databind.cfg.DateTimeFeature`, a `DatatypeFeature`, reached through
`disable(DatatypeFeature...)`. Confirmed by `javap` against `jackson-databind-3.1.4`. Everything else
in the design's builder chain compiled as written.

### 4. ArchUnit's ability to express R1 — **ANSWERED: yes, but the first two attempts were both vacuous**

It can be expressed, and it is (`PlatformPrimitivesArchTest`). Getting there found the two things
worth carrying forward, both recorded as MF7/MF8 above:

- The fluent sketch in Part 4 (`should().callMethod(JsonMapper.class, "builder")`) matches a single
  *signature*, and `JsonMapper.builder()` is overloaded — so it would have covered one overload and
  silently ignored the rest. Replaced by a hand-written `ArchCondition` matching any call named
  `builder` on `ObjectMapper`/`JsonMapper`/`JsonMapper$Builder`, plus any constructor call on those.
- **`noClasses().should(customCondition)` inverts every event the condition emits.** The natural
  `SimpleConditionEvent.violated(...)` for an offending class is flipped to *satisfied*, and the rule
  passes unconditionally. The plan's R1 code had this bug and went green with a deliberate
  `JsonMapper.builder()` sitting in `CustomerService`. The condition must emit `.satisfied(...)` for
  the case it forbids. `noClasses()` inverts the built-in `onlyDependOnClassesThat` too, which is why
  R2's allowlist form uses `classes()`, not `noClasses()`. **Challenge #33 — the most valuable thing
  this branch learned, and it was caught only because a prove-it-can-fail step was mandatory.**

On the second half of the original question — false-positives against Boot's own auto-configuration —
it does not arise: R1 imports `com.easycrm` only, and Boot's classes are not in that package. What
*did* arise instead is MF9: after the split, `com.easycrm` spans two build outputs, so the rule's
non-vacuity guard had to be re-scoped to a witness class the primitives jar cannot contain.

### 5. Whether `JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS` is on by default in Jackson 3 — **ANSWERED: no**

Settled directly against `jackson-core-3.1.4`, which is the only evidence that actually settles it:

```
JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.enabledByDefault() = false
JsonMapper.builder().build().writeValueAsString(Map.of("n", 1))  ->  {"n":1}
```

The secondary source that claimed otherwise was wrong. `BigDecimalStringModule` is therefore
genuinely doing the work for money, and nothing else on the wire is being stringified behind its
back.

The suite corroborates this — `PageResponse.totalElements` and `QuotationVersion.versionNo` are
asserted as JSON numbers (`jsonPath("$.totalElements").value(1)`) across seven test classes and all 262
tests pass — but **that corroboration is weaker than it looks and should not be relied on alone.**
Spring's `JsonPathExpectationsHelper` re-evaluates the path using the *expected* value's type when
the actual type differs, so `.value(1)` against a JSON `"1"` can coerce and pass. This is the same
coercion §5.1 already cites as the reason `MoneyWireFormatTest` asserts on the raw response body
rather than through JsonPath. Two independent checks were needed because neither the money tests nor
the JsonPath number assertions distinguish a stringify-everything default on their own.

### 6. Whether `api(...)` on jackson-databind leaks more than intended — **ANSWERED: it adds an edge, not an artifact**

Kept as `api`, and here is what was actually observed. `EventJson.mapper()` returns a
`tools.jackson.databind.json.JsonMapper`, which `platform-outbox` must be able to name, so the type
has to be on consumers' compile classpath — `implementation` would force every consumer to re-declare
jackson-databind or force `EventJson` behind a narrower return type that does not exist.

`./gradlew dependencyInsight --configuration compileClasspath --dependency jackson-databind` on the
root project resolves **`tools.jackson.core:jackson-databind:3.1.4`, one node**, reached by three
independent paths: `spring-boot-starter-web` → `spring-boot-starter-jackson` → `spring-boot-jackson`;
`org.flywaydb:flyway-core`; and this module's `api`. Same version from all three, no conflict, no
version forced by the module (the Boot BOM governs). So the `api` edge widens nobody's classpath in
practice — it only removes the possibility that a consumer *without* the web starter (the
`notification-svc` case the module exists for) cannot see the return type.

**One caveat that was not tested and cannot be, yet:** the "leaks nothing" claim rests on every
consumer already pulling jackson-databind. That is true of the single application today and of the
four servlet services on paper, and it is *why* `api` is safe rather than merely convenient. If a
future consumer takes this jar with no Jackson at all, the `api` edge is what puts Jackson on its
classpath — which is intended, since `EventJson` does not work without it, but it should be a
decision rather than a surprise.

---

# Appendix C — What the implementation added that this design did not specify

Recorded so the gap between the document and the code is visible rather than inferred.

- **`MoneyModuleWiringTest`** (Task 2) — two tests proving the module bean exists exactly once *and*
  that auto-configuration, not component scan, registered it. Appendix B item 2 explains why the
  second assertion is not redundant.
- **`EventJsonDivergenceTest`** (Task 4) — three tests comparing the injected application mapper
  against `EventJson`. It found **no** divergence; see MF10 before citing it.
- **`PrimitivesModuleArchTest`** (Task 5) — R2, reworked from the design's enumeration of forbidden
  packages into an **allowlist** (`classes().should().onlyDependOnClassesThat(...)`), which is a
  closure rather than a list and so cannot be escaped by a package nobody thought of. The allowlist
  is `java..`, `tools.jackson..`, `com.fasterxml.jackson.annotation..`, and
  `com.easycrm.platform.{error,money,gst}..`; each entry was confirmed load-bearing by removing it,
  except `gst..` (the module's own package, not yet cross-referenced internally).
- **The two-project build changes how you run a filtered test.** An unqualified
  `./gradlew clean test` still spans both projects and is what every verification step relies on. A
  **filtered** run must be project-qualified — `./gradlew :test --tests '…'` or
  `./gradlew :platform:platform-primitives:test --tests '…'` — because an unqualified `--tests`
  applies the filter to every project and then fails on whichever project has no match.
