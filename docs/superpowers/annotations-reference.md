# EasyCRM — Spring Boot Annotations Reference

A living glossary of every annotation used in the backend, what it does, where it
comes from, and its **meta-annotation composition** ("inheritance" — what other
annotations it is itself built from). Append new entries as they appear in code.

> **How to read the "Composed of / inherits" column:** many Spring annotations are
> *meta-annotated* — annotated with other annotations. `@RestController` is
> annotated with `@Controller`, which is annotated with `@Component`. So a
> `@RestController` **is a** `@Component` and gets component-scanned. This
> composition is Spring's form of annotation inheritance.

---

## The stereotype hierarchy (the backbone)

Spring's dependency-injection stereotypes all trace back to `@Component`. Anything
that **is a** `@Component` (directly or transitively) is discovered by component
scanning and registered as a bean.

```
@Component  (org.springframework.stereotype)
 ├── @Configuration   → class defines @Bean methods (a "full" config; proxied)
 ├── @Controller      → web MVC controller
 │     └── @RestController = @Controller + @ResponseBody
 ├── @Service         → business-logic bean (semantic marker; behaves like @Component)
 └── @Repository      → persistence bean; adds DataAccessException translation
```

Spring Data repositories (`interface X extends JpaRepository<…>`) are the exception:
they carry **no** annotation — Spring Data generates the implementation and registers
it as a bean automatically. `@Repository` is implied semantically but not written.

---

## 1. Application bootstrap & configuration

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@SpringBootApplication` | `org.springframework.boot.autoconfigure` | Marks the main class; enables the app. | `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` (so it **is a** `@Component`) |
| `@AutoConfiguration` | `org.springframework.boot.autoconfigure` (artifact `spring-boot-autoconfigure`) | Marks a class as an auto-configuration, applied only when it is named in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Unlike `@Configuration`, it is applied *after* user beans, so `@ConditionalOnMissingBean` can back off. Our `MoneyAutoConfiguration` uses this so the money Jackson module reaches any service's ObjectMapper regardless of where that service's `@SpringBootApplication` scans from. | Meta-annotated `@Configuration(proxyBeanMethods = false)` plus `@AutoConfigureBefore`/`@AutoConfigureAfter` aliases — which is why a class carrying it is still a candidate for component scan if it happens to sit under a scanned package. |
| `@ConditionalOnClass` | `org.springframework.boot.autoconfigure.condition` (artifact `spring-boot-autoconfigure`) | Applies an auto-configuration only when a named class is on the runtime classpath. Boot reads the value from the class file's annotation metadata (ASM) rather than by loading the annotation, so naming a class that turns out to be absent does not throw. On `MoneyAutoConfiguration` it names `tools.jackson.databind.JacksonModule`, so the config backs off silently in any service that has Spring but no Jackson databind — the value types, the exception vocabulary and `EventJson` keep working with no Spring on the runtime classpath at all, which is the point of `platform-primitives`. | Meta-annotated `@Conditional(OnClassCondition.class)` |
| `@Configuration` | `org.springframework.context.annotation` | Class that declares `@Bean` methods. | Meta-annotated with `@Component` |
| `@Bean` | `org.springframework.context.annotation` | Method whose return value becomes a container-managed bean. | — (method-level marker; not a stereotype) |
| `@Component` | `org.springframework.stereotype` | Generic bean; discovered by component scanning. | — (the root stereotype) |
| `@EnableJpaAuditing` | `org.springframework.data.jpa.repository.config` | Turns on Spring Data auditing so `@CreatedDate`/`@LastModifiedDate` get populated. | Imports `JpaAuditingRegistrar` |
| `@EnableAsync` | `org.springframework.scheduling.annotation` | Enables `@Async` method execution; on `AsyncConfig`. | Imports async proxy config |
| `@EnableScheduling` | `org.springframework.scheduling.annotation` | Registers `ScheduledAnnotationBeanPostProcessor`, which scans beans for `@Scheduled` and registers their tasks with a `TaskScheduler`. Without it `@Scheduled` is inert and silently does nothing — no error, no log line, the method just never runs. On `platform/job/SchedulingConfig`, kept as its own tiny `@Configuration` rather than folded into an existing one, so "does this app run scheduled work at all" is one grep away. | — |
| `@Scheduled` | `org.springframework.scheduling.annotation` | Marks a no-arg method for periodic invocation. `cron` accepts a property placeholder rather than a literal, so `QuotationExpiryJob.run`'s schedule lives in `application.yml` (`easycrm.jobs.quotation-expiry.cron`); the literal `"-"` (`Scheduled.CRON_DISABLED`) skips registration entirely, which is how the test suite turns the job off rather than letting it fire mid-test-run. `zone` pins the cron's timezone independent of the server's default — this project uses `Asia/Kolkata` so the fire time is IST correct regardless of where it's deployed (challenge #53). Requires `@EnableScheduling` somewhere in the context or the annotation is never even inspected. | — |
| `@Profile` | `org.springframework.context.annotation` | Bean only active under a given profile (`DemoSeeder` runs only in `dev`). | — |
| `@Primary` | `org.springframework.context.annotation` | When multiple beans of a type exist, prefer this one (our `TenantAwareTransactionManager`). | — |
| `@ConfigurationPropertiesScan` | `org.springframework.boot.context.properties` | On the main class; scans for `@ConfigurationProperties` records/classes to bind. | — |
| `@ConfigurationProperties` | `org.springframework.boot.context.properties` | Binds a group of `easycrm.*` YAML keys to a typed record (`JwtProperties`, `RateLimitProperties`). | — |
| `@EnableConfigurationProperties` | `org.springframework.boot.context.properties` | On a `@Configuration` class, explicitly registers a named `@ConfigurationProperties` type as a bean. `RateLimitConfig` declares it for `RateLimitProperties` so the config class is self-contained and correct on its own even though `EasyCrmApplication`'s package-wide `@ConfigurationPropertiesScan` would also pick the type up — Spring de-dupes the resulting bean definition, so declaring both is harmless. | Imports `EnableConfigurationPropertiesRegistrar` |
| `@DefaultValue` | `org.springframework.boot.context.properties.bind` | On a `@ConfigurationProperties` record component, supplies the value used when the YAML key is absent, since records have no field initializers for the binder to fall back on. `RateLimitProperties.enabled` uses `@DefaultValue("true")` so a missing key still ships rate limiting on; `policies` uses the no-argument form (empty list) so an unmatched path is simply unlimited rather than a `NullPointerException`. | — |
| `@Value` | `org.springframework.beans.factory.annotation` | Injects a single resolved property/SpEL expression into a constructor or field parameter; `ShareLinkService`'s constructor takes `@Value("${easycrm.public-base-url}") String publicBaseUrl` — a lighter-weight alternative to `@ConfigurationProperties` for a single scalar. | — |

## 2. Persistence — JPA (jakarta.persistence)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Entity` | `jakarta.persistence` | Maps a class to a table row; managed by JPA. | — |
| `@Table` | `jakarta.persistence` | Overrides table name / schema for an `@Entity`. | — |
| `@MappedSuperclass` | `jakarta.persistence` | Shares mapped fields with subclasses **without** being a table itself (our `BaseEntity`, `TenantScopedEntity`). | — |
| `@Id` | `jakarta.persistence` | Marks the primary-key field. | — |
| `@Column` | `jakarta.persistence` | Column mapping details (name, nullable, length, updatable). | — |
| `@Version` | `jakarta.persistence` | Optimistic-locking counter; auto-incremented on update, throws on stale write. Also used by Hibernate/Spring Data to detect a *new* entity (version at seed = 0). | — |
| `@EntityListeners` | `jakarta.persistence` | Registers a callback listener for lifecycle events (we use `AuditingEntityListener`). | — |
| `@Enumerated` | `jakarta.persistence` | Maps an enum field to a column; we always use `EnumType.STRING` (`Role`, `UserStatus`, `TenantStatus`, and P1a's `Product.uom`, `Customer.source`) so the DB stores the name, not a fragile ordinal. | — |
| `@UniqueConstraint` | `jakarta.persistence` | Table-level composite unique constraint declared inside `@Table` (`(tenant_id, email)` on `app_user` — email is unique *per tenant*, not globally; P1a reuses the pattern for `(tenant_id, sku)` on `product` and `(tenant_id, price_list_id, product_id)` on `price_list_item`). | — |
| `@Transient` | `jakarta.persistence` | Field is **not** persisted (backs `Tenant`'s `isNew` flag for `Persistable`). | — |
| `@PostPersist` | `jakarta.persistence` | Lifecycle callback run after INSERT; clears `Tenant.isNew` so a re-save updates. | — |
| `@PostLoad` | `jakarta.persistence` | Lifecycle callback run after a row is loaded; clears `Tenant.isNew` for managed entities. | — |

## 3. Persistence — Hibernate (org.hibernate.annotations)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@UuidGenerator` | `org.hibernate.annotations` | Generates the id as a UUID; `style = TIME` = time-sortable (UUIDv7-style). | — |
| `@TenantId` | `org.hibernate.annotations` | Marks the tenant-discriminator column; Hibernate auto-fills it and auto-filters every query (Layer 2). Resolved **once, at session-open** — see engineering-challenges #9. | — |
| `@JdbcTypeCode` | `org.hibernate.annotations` | Overrides the JDBC type Hibernate uses for a column; with `SqlTypes.JSON` (`org.hibernate.type`) it maps a `Map<String,Object>` to a Postgres `jsonb` column (`AuditLog.detail`). | — |

## 4. Spring Data auditing (org.springframework.data)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@CreatedDate` | `org.springframework.data.annotation` | Field auto-set to creation timestamp (needs `@EnableJpaAuditing`). | — |
| `@LastModifiedDate` | `org.springframework.data.annotation` | Field auto-updated on every save. | — |

## 4a. Spring Data JPA repositories (query & locking)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Query` | `org.springframework.data.jpa.repository` | Supplies an explicit JPQL query for a repository method, overriding derived-query-by-method-name (`DocumentCounterRepository.findForUpdate`, keyed on `docType`+`fy` — the tenant filter still applies via `@TenantId`, see challenge #8). | — |
| `@Param` | `org.springframework.data.repository.query` | Binds a method parameter to a named `:param` placeholder in an `@Query` string. | — |
| `@Lock` | `org.springframework.data.jpa.repository` | Forces a `LockModeType` on the query's execution; `PESSIMISTIC_WRITE` compiles to `SELECT … FOR UPDATE`, serializing concurrent readers of the same row (`DocumentCounterRepository.findForUpdate`, so concurrent quote sends within a tenant/FY get gapless numbers — must run inside the caller's tenant-bound `@Transactional`, or the lock is never acquired inside a real transaction). Second use site: `TenantRepository.findForUpdate`, which locks the tenant row at the top of every member-admin write so two concurrent role changes can't both pass the last-active-owner count check — the row being locked is not the row being written, it exists purely to serialize the invariant check (challenge #62). | — |

## 5. DI / stereotypes in use

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Component` | `org.springframework.stereotype` | Our `TenantIdentifierResolver` is a scanned bean. | Root stereotype |
| `@Service` | `org.springframework.stereotype` | Business-logic bean (`JwtService`). | Meta-annotated with `@Component` |
| `@EventListener` | `org.springframework.context.event` | Marks a method as a subscriber to `ApplicationEventPublisher.publishEvent(...)`; runs **synchronously, on the caller's thread, inside the caller's transaction** by default (no `@Async`/`@TransactionalEventListener`) — `OrderAcceptedAuditListener.on(QuotationAcceptedEvent)` writes the `QUOTATION_ACCEPTED` audit row so it commits/rolls back atomically with the order (challenge #3); same pattern for `OrderStatusChangedAuditListener.on(OrderStatusChangedEvent)`, which writes the `ORDER_*` transition rows. | — |
| `@RestController` | `org.springframework.web.bind.annotation` | REST endpoint class (`DemoRecordController`); returns bodies as JSON. | `@Controller` (→ `@Component`) + `@ResponseBody` |
| `@RestControllerAdvice` | `org.springframework.web.bind.annotation` | Global exception handling (`ApiExceptionHandler`) → JSON error bodies. | `@ControllerAdvice` (→ `@Component`) + `@ResponseBody` |
| `@RequestMapping` | `org.springframework.web.bind.annotation` | Base path for a controller (`/api/v1/demo-records`). | — |
| `@GetMapping` | `org.springframework.web.bind.annotation` | Maps HTTP GET to a handler method. | Meta-annotated `@RequestMapping(method = GET)` |
| `@PostMapping` | `org.springframework.web.bind.annotation` | Maps HTTP POST to a handler (`/auth/signup`, `/login`, `/refresh`, `/logout`, `ProductController` create/activate/deactivate). | Meta-annotated `@RequestMapping(method = POST)` |
| `@PutMapping` | `org.springframework.web.bind.annotation` | Maps HTTP PUT to a handler (`ProductController.update`, full-resource replace semantics; `QuotationController.replaceItems` — full replace of a DRAFT version's line items). | Meta-annotated `@RequestMapping(method = PUT)` |
| `@PatchMapping` | `org.springframework.web.bind.annotation` | Maps HTTP PATCH to a handler (`QuotationController.patch` — partial header edit, distinct from PUT's full-resource-replace semantics). | Meta-annotated `@RequestMapping(method = PATCH)` |
| `@DeleteMapping` | `org.springframework.web.bind.annotation` | Maps HTTP DELETE to a handler (`ContactController.delete`, `PriceListItemController.delete`). | Meta-annotated `@RequestMapping(method = DELETE)` |
| `@RequestBody` | `org.springframework.web.bind.annotation` | Binds/deserializes the JSON request body into a method parameter (the auth DTOs). `required = false` makes the body itself optional, not just its fields — `QuotationController.accept`'s `AcceptRequest req` may be `null` when the client sends no body (accept has no mandatory fields, only optional `poReference`/`poDate`), and the controller substitutes an empty `AcceptRequest(null, null)` before calling the service. | — |
| `@PathVariable` | `org.springframework.web.bind.annotation` | Binds a URI template segment (`{id}`) to a method parameter. | — |
| `@RequestParam` | `org.springframework.web.bind.annotation` | Binds a query-string parameter; `required = false` makes it optional (`ProductController.list`'s `active` filter — `Boolean` wrapper stays `null` when absent, meaning "no filter"). | — |
| `@ExceptionHandler` | `org.springframework.web.bind.annotation` | Marks a method that handles a given exception type. | — |

## 6. Bean Validation & transactions

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Valid` | `jakarta.validation` | On a `@RequestBody` parameter, triggers Bean Validation of the DTO; a violation raises `MethodArgumentNotValidException` → mapped to 400. | — |
| `@NotBlank` | `jakarta.validation.constraints` | String must be non-null and contain non-whitespace (slug, email, password, etc.). | Meta-annotated `@Constraint` |
| `@NotNull` | `jakarta.validation.constraints` | Field must be non-null (structural presence only, no content check — `ProductCreateRequest.uom/gstRate/baseRate`, where `@NotBlank` doesn't apply because the type isn't `String`). | Meta-annotated `@Constraint` |
| `@NotEmpty` | `jakarta.validation.constraints` | Collection/string must be non-null **and** non-empty (`QuotationCreateRequest.items` — a quotation with zero lines is rejected with 400 before the service layer runs). | Meta-annotated `@Constraint`; composes `@NotNull` + `@Size(min = 1)` semantics |
| `@Email` | `jakarta.validation.constraints` | String must look like an email address (`SignupRequest.email`). | Meta-annotated `@Constraint` |
| `@Size` | `jakarta.validation.constraints` | Length/size bounds (`password` min 8). | Meta-annotated `@Constraint` |
| `@Pattern` | `jakarta.validation.constraints` | String must match a regex (`slug` charset, 2-digit `stateCode`). | Meta-annotated `@Constraint` |
| `@Positive` | `jakarta.validation.constraints` | Numeric value must be > 0 (`RateLimitPolicy.capacity` — `capacity: 0` would otherwise bind happily and then deny every request on that policy's route, a startup-time catch for what would else be a production outage). | Meta-annotated `@Constraint` |
| `@Validated` | `org.springframework.validation.annotation` | On a `@ConfigurationProperties` class, tells `ConfigurationPropertiesBindingPostProcessor` to run Bean Validation on the bound instance so a bad value (e.g. `RateLimitProperties`' `capacity: 0`) fails application startup instead of binding silently. Paired with `@Valid` on the `List<RateLimitPolicy> policies` field to cascade validation into each element. | Meta-annotated with Spring's `@Validated` machinery (not JSR-303 `@Constraint`) |
| `@Transactional` | `org.springframework.transaction.annotation` | Method/class transaction boundary (`AuditService.record`, `RefreshTokenService`, the RLS-scoped derived finders, `AuthService.me`). `readOnly = true` for reads. Runs through the `@Primary` `TenantAwareTransactionManager`, which sets the tenant GUC at `doBegin`. See challenges #8/#9. | — |

## 6a. JSON serialization (Jackson)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@JsonInclude` | `com.fasterxml.jackson.annotation` (the annotations module did not move to `tools.jackson` in the Jackson 3 split — only `jackson-core`/`jackson-databind` did) | Drops fields from the serialized JSON under a given inclusion rule. `ActivityResponse` carries `@JsonInclude(Include.NON_NULL)` at the record level so an activity with no linked follow-up omits `followUpId` from the response rather than serializing it as `null`. Applied class-wide, so it also suppresses any other null field on the same record — today that includes a null `outcome`, which is harmless (the client already treats a missing key and an explicit `null` the same way) but is a blast radius wider than the one field it was added for. | — |

## 7. Testing (JUnit 5, Spring Test, Testcontainers)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Test` | `org.junit.jupiter.api` | Marks a test method. | — |
| `@Disabled` | `org.junit.jupiter.api` | Skips a test with a required reason string (`QuotationEditTest.editingItemsOnSentVersionReturns422`, pending the `/send` endpoint that lands in Task 9). | — |
| `@AfterEach` | `org.junit.jupiter.api` | Runs after each test (we clear `TenantContext`). | — |
| `@SpringBootTest` | `org.springframework.boot.test.context` | Boots the full application context for integration tests. | Meta-annotated with `@ExtendWith(SpringExtension.class)` etc. |
| `@Testcontainers` | `org.testcontainers.junit.jupiter` | JUnit 5 extension that manages container lifecycle. | `@ExtendWith(TestcontainersExtension.class)` |
| `@Container` | `org.testcontainers.junit.jupiter` | Marks a container field for the extension to start/stop. | — |
| `@DynamicPropertySource` | `org.springframework.test.context` | Injects runtime values (container JDBC URL) into the Spring `Environment` before context start. Always outranks `@TestPropertySource`, but among several `@DynamicPropertySource` methods in one class hierarchy the superclass's runs *last* and wins on a shared key — see challenge log #40. | — |
| `@TestPropertySource` | `org.springframework.test.context` | Adds inline test properties (`properties = "..."`) to the `Environment`, lower precedence than `@DynamicPropertySource` and than any same-key `@DynamicPropertySource` registration from any class in the hierarchy. Used on `IntegrationTest` to default `easycrm.rate-limit.enabled=false` so a subclass's `@DynamicPropertySource` can reliably override it (challenge log #40). | — |
| `@Autowired` | `org.springframework.beans.factory.annotation` | Injects a bean into a field/constructor. | — |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure` (Boot 4 module `spring-boot-webmvc-test`) | Wires a `MockMvc` for controller tests without a live server. | — |

---

## Notes on things that look like annotations but aren't Spring's

- `@Override` — a **Java** annotation (`java.lang`), compiler check only. Not DI.
- Records (`record TenantPrincipal(...)`) — a Java language feature, not an annotation.
- `JpaSpecificationExecutor<T>` / `Specification<T>` (`org.springframework.data.jpa.repository` / `.domain`) — **interfaces, not annotations**. A repository that also extends `JpaSpecificationExecutor<T>` gains `findAll(Specification, Pageable)`; a `Specification` is a lambda `(root, query, cb) -> Predicate` used to build dynamic criteria queries. Introduced in the enquiry slice (`EnquiryRepository`, `EnquirySpecifications`) to AND-compose optional list filters without an `if/else` chain (challenge #24). No annotation involved — listed here so the pattern is discoverable.

---

## Concepts worth remembering

- **Meta-annotation = inheritance.** If annotation `A` is annotated with `@Component`,
  then a class marked `@A` is a component. This is why `@RestController`,
  `@Service`, `@Repository`, `@Configuration` are all auto-discovered — each **is a**
  `@Component` transitively.
- **`@MappedSuperclass` vs `@Entity`.** `@MappedSuperclass` contributes columns to
  subclasses but is not itself a table. Our inheritance chain:
  `BaseEntity` (`@MappedSuperclass`) → `TenantScopedEntity` (`@MappedSuperclass` + `@TenantId`) → real `@Entity` tables.
- **JPA vs Hibernate annotations.** `jakarta.persistence.*` is the portable JPA spec;
  `org.hibernate.annotations.*` is Hibernate-specific (e.g. `@TenantId`,
  `@UuidGenerator`). We use Hibernate's where the JPA spec has no equivalent.
