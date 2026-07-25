# EasyCRM — Handoff

**Last updated:** 2026-07-25
**Purpose:** Everything a fresh agent needs to pick up this project and continue. Read this first, then the linked docs.

---

## 1. What this project is

**EasyCRM** — a multi-tenant SaaS CRM for Indian tier-2/3 **distributors, traders, and small manufacturers**. React (frontend, not started) + Spring Boot + PostgreSQL. It is a real product intended to be sold.

- **Wedge:** enquiry → GST quotation → order. Stops hard at the Order (no invoicing/stock/ledger — that's Tally's job, and every target customer already runs Tally).
- **Positioning:** vertical-first (distributors/traders), not a horizontal Zoho clone.
- Full rationale is in the design spec (below). Read it before making product decisions.

## 2. Read these, in order

All under `docs/superpowers/`:

1. **`../../CLAUDE.md`** (repo root) — working agreements loaded every session. **Non-negotiable rules live here.**
2. **`specs/2026-07-22-easycrm-design.md`** — the full design spec (architecture, domain model, 4-layer isolation, import module, frontend plan, release plan). The source of truth for *what* to build.
3. **`plans/2026-07-24-p0-tenant-isolation-foundation.md`** — P0 isolation plan (DONE, merged).
4. **`plans/2026-07-25-p0-auth-core.md`** — P0-auth plan (**NOT started — this is the next work**).
5. **`engineering-challenges.md`** — running log of non-obvious problems + solutions (7 entries). Great context on the stack's quirks.
6. **`annotations-reference.md`** — living glossary of every Spring/JPA annotation used.

## 3. Current state

- **Branch:** `main`. Working tree clean. Repo is local-only (no git remote).
- **Merged & done:** the design docs + **P0 tenant-isolation foundation** (merge commit on `main`). 21 tests passing from a clean build.
- **What P0 delivered:** the 4-layer multi-tenant isolation, all provably enforced by tests:
  1. **JWT resolution** (`platform/security` — `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`)
  2. **Hibernate `@TenantId`** (`platform/tenancy` — `TenantIdentifierResolver`, `HibernateTenancyConfig`; `TenantScopedEntity`)
  3. **Postgres RLS** (`TenantAwareTransactionManager` sets `app.current_tenant` per transaction; policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`)
  4. **ArchUnit** (`arch/TenantScopingArchTest` — every `@Entity` must extend `TenantScopedEntity` unless allowlisted in `GLOBAL_TABLES`)
  - Plus: `BaseEntity` (UUIDv7 ids, auditing, `@Version`), `TenantContext` (ThreadLocal + `runAs`), `TenantAwareTaskDecorator` (async propagation), `Tenant` (global entity), `DemoRecord` (isolation test subject — throwaway, replaced by real entities later), 404-not-403 error mapping, `DemoSeeder` + `backend/DEMO.md`.

## 4. THE NEXT TASK

**Execute `plans/2026-07-25-p0-auth-core.md`** — 16 TDD tasks adding self-serve auth: signup (atomic tenant + first OWNER provisioning), bcrypt login, rotating JWT refresh tokens, tenant-scoped audit log. Ends with a working `signup → login → call protected API → refresh` loop.

- Use the **superpowers:executing-plans** skill (inline) — this matched the project well for P0-isolation.
- The plan is self-contained: each task has the failing test, the code, the exact `./gradlew` command, and a commit. Follow it task-by-task.
- **Deferred to a later plan (do NOT build now):** invitations, record-level visibility layer (SALES_MANAGER/SALES_EXEC), rate limiting, real email provider.
- **Key design decisions already locked** (don't relitigate): bcrypt (not Argon2), HS256 (not RS256), opaque refresh tokens hashed at rest, `refresh_token` is a *global* table (allowlisted) while `user`/`audit_log` are tenant-scoped, generic 401 (no enumeration). The intricate part is the `TenantBinder` (Task 7): signup inserts the first user in the same transaction that creates its tenant, so the tenant context is rebound mid-transaction.

## 5. Environment (macOS, already set up)

- **JDK 25** installed (`~/Library/Java/JavaVirtualMachines/openjdk-25.0.1`). Shell default is JDK 21, but the **Gradle toolchain uses 25** — do NOT change the shell default.
- **Gradle 9.6.1** (via Homebrew) — but always use the wrapper: `cd backend && ./gradlew ...`.
- **Docker** must be running (Testcontainers needs it). Start Docker Desktop: `open -a Docker`, then wait for `docker info` to succeed. Note: a user Postgres container (`langfuse-postgres-1`) runs on `localhost:5432` — leave it alone; Testcontainers uses its own random-port container.
- **Run tests:** `cd backend && ./gradlew test` (or `clean test` for a full run). Integration tests spin up one shared Postgres container (singleton pattern) — the suite runs in ~4s once the image is cached.
- **Sandbox note:** in this harness, network + Docker operations may need the Bash tool's sandbox disabled (`dangerouslyDisableSandbox: true`). SDKMAN's reachability check is blocked by the sandbox even when network works.

## 6. Stack quirks already discovered (see challenges log for detail)

This is **Spring Boot 4.1 + Java 25 + Hibernate 7** — all recent. Watch for:
- **Spring Boot 4 split auto-config into per-integration modules.** `flyway-core` alone doesn't bring `FlywayAutoConfiguration` → use `spring-boot-starter-flyway`. `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure` (module `spring-boot-webmvc-test`). `HibernatePropertiesCustomizer` moved to `org.springframework.boot.hibernate.autoconfigure`. **If an import "does not exist," search the resolved jars for the class's new package** rather than assuming the plan is wrong.
- **ArchUnit 1.4.1** (not 1.3.0) — 1.3.0 silently skips Java 25 bytecode.
- **Testcontainers BOM pinned to 1.21.3** (Boot 4 BOM doesn't manage those versions).
- **RLS + custom GUC:** a referenced custom GUC resets to `''` not NULL, so policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid`. An RLS `USING` clause also acts as `WITH CHECK` for inserts.
- **Two DB roles:** Flyway runs as the **owner** (Testcontainers superuser); the app connects as **`easycrm_app`** (non-owner, no BYPASSRLS) — this is what makes RLS real. `IntegrationTest` wires both datasources.
- **`ddl-auto: validate`** is on — migration column types must match entity mappings exactly (e.g. `VARCHAR` not `CHAR` for a `String`).

## 7. Working agreements (also in CLAUDE.md — enforced)

- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already set). Plain `git commit`. **Never** add a `Co-Authored-By: Claude` trailer or mention Claude/AI in commit messages.
- **Log engineering challenges:** when a task surfaces a non-obvious problem, append to `engineering-challenges.md` (Problem → why hard → Solution → Lesson) in the same change.
- **Keep the annotations reference current:** add a row when a new annotation appears.
- **TDD:** failing test → run-to-confirm-fail → minimal code → run-to-pass → commit. One task per commit.
- **Money is never `double`** (BigDecimal / NUMERIC / JSON string). Not exercised until P1.
- **Tenant isolation is structural:** never hand-write `WHERE tenant_id`; rely on `@TenantId` + RLS; new entities extend `TenantScopedEntity` or get allowlisted (ArchUnit enforces).

## 8. After P0-auth

Options (confirm with the user): the **P0-auth follow-up** (invitations + visibility layer + rate limiting), or **P1 sales core** (customers, catalog, price lists, quotation engine, import module — the actual product features). Both are scoped in the spec. Each new chunk goes: (already-approved spec →) writing-plans → executing-plans → finishing-a-development-branch.
