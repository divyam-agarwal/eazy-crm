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
| `@Configuration` | `org.springframework.context.annotation` | Class that declares `@Bean` methods. | Meta-annotated with `@Component` |
| `@Bean` | `org.springframework.context.annotation` | Method whose return value becomes a container-managed bean. | — (method-level marker; not a stereotype) |
| `@Component` | `org.springframework.stereotype` | Generic bean; discovered by component scanning. | — (the root stereotype) |
| `@EnableJpaAuditing` | `org.springframework.data.jpa.repository.config` | Turns on Spring Data auditing so `@CreatedDate`/`@LastModifiedDate` get populated. | Imports `JpaAuditingRegistrar` |
| `@EnableAsync` | `org.springframework.scheduling.annotation` | Enables `@Async` method execution; on `AsyncConfig`. | Imports async proxy config |
| `@Profile` | `org.springframework.context.annotation` | Bean only active under a given profile (`DemoSeeder` runs only in `dev`). | — |
| `@Primary` | `org.springframework.context.annotation` | When multiple beans of a type exist, prefer this one (our `TenantAwareTransactionManager`). | — |
| `@ConfigurationPropertiesScan` | `org.springframework.boot.context.properties` | On the main class; scans for `@ConfigurationProperties` records/classes to bind. | — |
| `@ConfigurationProperties` | `org.springframework.boot.context.properties` | Binds a group of `easycrm.*` YAML keys to a typed record (`JwtProperties`). | — |

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
| `@Enumerated` | `jakarta.persistence` | Maps an enum field to a column; we always use `EnumType.STRING` (`Role`, `UserStatus`, `TenantStatus`) so the DB stores the name, not a fragile ordinal. | — |
| `@UniqueConstraint` | `jakarta.persistence` | Table-level composite unique constraint declared inside `@Table` (`(tenant_id, email)` on `app_user` — email is unique *per tenant*, not globally). | — |
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

## 5. DI / stereotypes in use

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Component` | `org.springframework.stereotype` | Our `TenantIdentifierResolver` is a scanned bean. | Root stereotype |
| `@Service` | `org.springframework.stereotype` | Business-logic bean (`JwtService`). | Meta-annotated with `@Component` |
| `@RestController` | `org.springframework.web.bind.annotation` | REST endpoint class (`DemoRecordController`); returns bodies as JSON. | `@Controller` (→ `@Component`) + `@ResponseBody` |
| `@RestControllerAdvice` | `org.springframework.web.bind.annotation` | Global exception handling (`ApiExceptionHandler`) → JSON error bodies. | `@ControllerAdvice` (→ `@Component`) + `@ResponseBody` |
| `@RequestMapping` | `org.springframework.web.bind.annotation` | Base path for a controller (`/api/v1/demo-records`). | — |
| `@GetMapping` | `org.springframework.web.bind.annotation` | Maps HTTP GET to a handler method. | Meta-annotated `@RequestMapping(method = GET)` |
| `@PostMapping` | `org.springframework.web.bind.annotation` | Maps HTTP POST to a handler (`/auth/signup`, `/login`, `/refresh`, `/logout`). | Meta-annotated `@RequestMapping(method = POST)` |
| `@RequestBody` | `org.springframework.web.bind.annotation` | Binds/deserializes the JSON request body into a method parameter (the auth DTOs). | — |
| `@PathVariable` | `org.springframework.web.bind.annotation` | Binds a URI template segment (`{id}`) to a method parameter. | — |
| `@ExceptionHandler` | `org.springframework.web.bind.annotation` | Marks a method that handles a given exception type. | — |

## 6. Bean Validation & transactions

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Valid` | `jakarta.validation` | On a `@RequestBody` parameter, triggers Bean Validation of the DTO; a violation raises `MethodArgumentNotValidException` → mapped to 400. | — |
| `@NotBlank` | `jakarta.validation.constraints` | String must be non-null and contain non-whitespace (slug, email, password, etc.). | Meta-annotated `@Constraint` |
| `@Email` | `jakarta.validation.constraints` | String must look like an email address (`SignupRequest.email`). | Meta-annotated `@Constraint` |
| `@Size` | `jakarta.validation.constraints` | Length/size bounds (`password` min 8). | Meta-annotated `@Constraint` |
| `@Pattern` | `jakarta.validation.constraints` | String must match a regex (`slug` charset, 2-digit `stateCode`). | Meta-annotated `@Constraint` |
| `@Transactional` | `org.springframework.transaction.annotation` | Method/class transaction boundary (`AuditService.record`, `RefreshTokenService`, the RLS-scoped derived finders, `AuthService.me`). `readOnly = true` for reads. Runs through the `@Primary` `TenantAwareTransactionManager`, which sets the tenant GUC at `doBegin`. See challenges #8/#9. | — |

## 7. Testing (JUnit 5, Spring Test, Testcontainers)

| Annotation | Origin | Purpose | Composed of / inherits |
|---|---|---|---|
| `@Test` | `org.junit.jupiter.api` | Marks a test method. | — |
| `@AfterEach` | `org.junit.jupiter.api` | Runs after each test (we clear `TenantContext`). | — |
| `@SpringBootTest` | `org.springframework.boot.test.context` | Boots the full application context for integration tests. | Meta-annotated with `@ExtendWith(SpringExtension.class)` etc. |
| `@Testcontainers` | `org.testcontainers.junit.jupiter` | JUnit 5 extension that manages container lifecycle. | `@ExtendWith(TestcontainersExtension.class)` |
| `@Container` | `org.testcontainers.junit.jupiter` | Marks a container field for the extension to start/stop. | — |
| `@DynamicPropertySource` | `org.springframework.test.context` | Injects runtime values (container JDBC URL) into the Spring `Environment` before context start. | — |
| `@Autowired` | `org.springframework.beans.factory.annotation` | Injects a bean into a field/constructor. | — |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure` (Boot 4 module `spring-boot-webmvc-test`) | Wires a `MockMvc` for controller tests without a live server. | — |

---

## Notes on things that look like annotations but aren't Spring's

- `@Override` — a **Java** annotation (`java.lang`), compiler check only. Not DI.
- Records (`record TenantPrincipal(...)`) — a Java language feature, not an annotation.

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
