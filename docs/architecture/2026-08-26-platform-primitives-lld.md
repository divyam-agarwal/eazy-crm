# `platform-primitives` — Low-Level Design

**Date:** 2026-08-26
**Status:** Design only. Zero code changed.
**Code baseline:** `80e74a3`
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

Five unchecked exceptions, moved verbatim. `ValidationException` carries a `Map<String, Object>` of
field errors; the rest carry a message.

They ship here rather than with the handler because **an exception type is a statement about the
domain, and mapping it to a status code is a statement about HTTP.** The first is meaningful to an
SQS consumer; the second is not. `notification-svc` has no HTTP surface and still throws
`NotFoundException` when an event references a row that has since been archived.

`platform-web` keeps `ApiExceptionHandler`, and with it the two behaviours that are load-bearing
rather than cosmetic: 404-not-403 for cross-tenant reads, and the 409 backstops for constraint and
optimistic-lock violations.

## 2.2 `BigDecimalStringModule`

Unchanged. Serialises every `BigDecimal` as a JSON string in plain notation.

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

| # | Finding | Severity |
|---|---|---|
| **MF1** | **The seller's GSTIN is never validated, and the seller's state code is validated only as "two digits."** `SignupRequest` declares `@Pattern("\\d{2}")` on `stateCode` and a bare `String gstin` — no `Gstin.parse`, no `StateCode.requireValid`. A buyer's GSTIN goes through both in `CustomerService`. The asymmetry matters because `QuotationService.isInterState` compares `tenant.getStateCode()` against the customer's to choose **CGST+SGST vs IGST**, so an invalid seller state code silently decides the tax split of every quotation the tenant ever issues, and the unvalidated seller GSTIN prints on every PDF | **Live bug.** Independent of all module work |
| **MF2** | Folding `StateCode.requireValid` into `Gstin.parse` (2.4) is correct going forward but says nothing about rows already stored. Whether any existing `customer.gstin` or `tenant.gstin` has an invalid state prefix is unknown | Decide before implementing: a read-only audit query first, then a migration only if it finds anything |
| **MF3** | `platform-web` must now depend on `platform-primitives`, which the parent spec's graph does not show. The parent's Part 1 diagram and per-service matrix need the edge added | Documentation; fixed when this LLD is accepted |
| **MF4** | `MoneyWireFormatTest` is the only proof the Jackson module is wired, and it asserts through the product endpoint — so deleting or refactoring `ProductController` would silently remove the tripwire for MB1 | Move the assertion to a dedicated endpoint-agnostic test, or note the dependency in the test itself |
| **MF5** | There is no unit test of `BigDecimalStringModule` at all today — only the integration test. A change to the serialiser fails one heavyweight Spring test with an opaque message | 5.2 adds them |
| **MF6** | `Gstin.parse` throws `ValidationException("gstin", …)` with a hard-coded field name, so a request carrying two GSTINs (buyer and consignee, once that exists) cannot report which one failed | Accept for now; revisit if a second GSTIN field appears |

---

# Appendix B — To verify before implementation

1. **The Boot 4 artifact and package for Jackson auto-configuration.** Boot 4 split auto-config into
   per-integration modules — `HibernatePropertiesCustomizer` now lives in
   `org.springframework.boot.hibernate.autoconfigure`, and `flyway-core` alone no longer brings
   `FlywayAutoConfiguration`. Confirm which artifact carries the Jackson auto-configuration and
   whether `spring-boot-autoconfigure` is still the right `compileOnly` coordinate.
2. **That a `JacksonModule` bean contributed by an auto-configuration is still discovered** the way
   one contributed by a component-scanned `@Configuration` is, and that ordering against Boot's own
   Jackson auto-configuration is correct.
3. **`JsonMapper.builder()` defaults in Jackson 3** — specifically whether any default differs from
   the application mapper Boot configures, since 2.3 deliberately inherits nothing. Enumerate the
   differences and state them in `EventJson`, or the "inherits nothing" claim is aspirational.
4. **ArchUnit's ability to express R1** against a static method on a Jackson 3 type, and that it does
   not false-positive on Boot's own auto-configuration if that is ever on the scanned classpath.
5. **Whether `api(...)` on jackson-databind leaks more than intended** to the five services, given
   they already receive Jackson through `spring-boot-starter-web`. If it changes nothing, prefer
   `implementation` and expose `EventJson` behind a narrower return type.
