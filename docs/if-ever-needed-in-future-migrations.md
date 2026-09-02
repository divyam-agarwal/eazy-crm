# If ever needed in future: migrations

**Status:** reference, not a plan. Nothing here is scheduled.
**Written:** 2026-09-02, against `main` at `aa3fd55` (Flyway `V33`, Postgres 16, Boot 4.1, Java 25).

"Migration" means two unrelated things and they get confused constantly:

1. **Schema migration** — changing the database under a running system (Flyway, Postgres).
2. **Code migration** — moving the codebase across a framework, library or language version
   (Spring Boot 3→4, Java 21→25, JUnit 4→5). This is where OpenRewrite lives.

They share one idea — *make the change in reversible steps that each work on their own* — and
almost nothing else. Part 1 and Part 2 are independent; read whichever you need.

---

## 0. What is true of this repo today, and why it changes the answer

Before importing zero-downtime advice from the internet, note the actual deployment shape:

- **There is one app instance and no `Dockerfile`.** No rolling deploy, no blue/green, no second
  replica reading the old schema while the new one writes. The rate limiter's in-process store
  and the unlocked expiry sweep (HANDOFF §8, "Before any second app instance") both assume this.
- **So today, a schema change can take a short maintenance window**, and most of Part 1's
  ceremony is optional. That is the honest position and it should be said out loud rather than
  cargo-culting expand/contract into a single-instance app.
- **The moment a second instance exists, that stops being true**, because two app versions will
  be live at once and the schema must satisfy both. Everything in §1.3 becomes mandatory then,
  not before.
- **Flyway connects as `easycrm_owner`; the app connects as `easycrm_app`.** That split is
  load-bearing and shows up again in backfills (§1.5).

---

## 1. Schema migrations (Flyway + Postgres)

### 1.1 The house rules already in force

These are not suggestions; two of them are enforced by failing tests.

| Rule | Why | Enforced by |
|---|---|---|
| `V<n>__snake_case.sql`, forward-only | Flyway checksums every applied migration | Flyway itself, at deploy |
| **Never edit an applied migration** | See §1.2 — the failure is invisible in CI | Nothing. Discipline only. |
| A new tenant table ships `ENABLE` + `FORCE` RLS + a `tenant_isolation` policy, in its own migration | Layer 3 of tenant isolation | `RlsCoverageIntegrationTest` |
| A new global (RLS-exempt) table is allowlisted in **both** guards | The two lists differ on purpose and must not drift | `TenantScopingArchTest`, `RlsCoverageIntegrationTest` |
| Indexes ship in the creating migration | One line now vs. a migration against a live table later | Nothing. Convention. |

The RLS pattern to copy is `V30__rls_follow_up.sql`, in full:

```sql
ALTER TABLE follow_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE follow_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON follow_up
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
```

`FORCE` matters: without it the table owner bypasses the policy, so a migration or a job running
as `easycrm_owner` would silently see every tenant's rows.

### 1.2 The trap that CI cannot catch

**Editing an already-applied migration passes every check here and breaks a real deployment.**
Testcontainers builds each test database from scratch, so it recomputes the checksum from the new
text and agrees with itself. `flyway validate` against a database that already ran the old text
does not. This is exactly why `db/migration/*.sql` is excluded from Spotless — a reformatted byte
is a changed checksum. See challenge #60.

The corollary is that **the test suite proves the forward path from empty, and nothing else.** It
does not prove the upgrade path from the previous release's schema *with data in it*. If a
migration ever does anything more interesting than `CREATE TABLE`, that gap is worth closing with
a test that restores a prior-release dump and migrates it.

### 1.3 Expand / contract — the shape of every serious change

Never change a column in place while code reads it. Split it into steps that are each
independently deployable and revertible:

1. **Expand.** Add the new column/table, nullable, with no constraint. Deploy. Old code ignores
   it; new code can start writing it.
2. **Backfill.** Populate the new shape from the old, in batches (§1.5). Deploy nothing.
3. **Dual-write, then move reads.** Write both, read old. Then read new, still writing both.
   Each is its own deploy, and either can be reverted without data loss.
4. **Contract.** Stop writing the old shape. Deploy. Only then drop the column and add
   `NOT NULL`.

A rename is not a rename — it is expand/contract with a copy in the middle. `ALTER TABLE ...
RENAME COLUMN` is instant and free at the database, and catastrophic for any running instance
still selecting the old name.

### 1.4 Postgres locking hazards, concretely

The danger is rarely the statement's duration — it is the lock it takes and the queue behind it.
**A blocked `ALTER TABLE` blocks every subsequent query on that table**, so a statement that
waits 30 seconds for a lock can stall the whole application for 30 seconds even though its own
work is instant.

Always bound the wait rather than joining the queue indefinitely:

```sql
SET lock_timeout = '3s';
```

Then retry. Failing fast and retrying is strictly better than an unbounded wait.

| Operation | Safe? | Notes |
|---|---|---|
| `ADD COLUMN`, nullable, no default | Yes | Metadata only |
| `ADD COLUMN ... DEFAULT <constant>` | Yes since PG 11 | No table rewrite for a non-volatile default. A **volatile** default (e.g. `random()`) still rewrites. |
| `ADD COLUMN ... NOT NULL` with no default | No | Rewrites, or fails on existing rows |
| Setting `NOT NULL` on a populated column | Not directly | Use the two-step below |
| `CREATE INDEX` | No | Takes `SHARE`, blocks writes for the whole build |
| `CREATE INDEX CONCURRENTLY` | Yes | **Cannot run inside a transaction** — see below |
| `DROP COLUMN` | Yes | Metadata only; space reclaimed later |
| `RENAME COLUMN` | Instant, but unsafe | Breaks running code. Expand/contract instead. |
| Adding a foreign key | No | Scans both tables — use `NOT VALID` then `VALIDATE` |

**Setting `NOT NULL` without a long exclusive scan** (PG 12+):

```sql
ALTER TABLE t ADD CONSTRAINT t_col_not_null CHECK (col IS NOT NULL) NOT VALID;
ALTER TABLE t VALIDATE CONSTRAINT t_col_not_null;   -- scans, but takes only SHARE UPDATE EXCLUSIVE
ALTER TABLE t ALTER COLUMN col SET NOT NULL;        -- uses the validated constraint, no re-scan
ALTER TABLE t DROP CONSTRAINT t_col_not_null;       -- optional
```

**`CREATE INDEX CONCURRENTLY` and Flyway fight each other.** Flyway wraps each migration in a
transaction on Postgres by default, and `CONCURRENTLY` cannot run inside one. Flyway supports
disabling that per migration through a script-config file sitting beside the `.sql`
(`V34__x.sql.conf` with `executeInTransaction=false`) — **verify the exact mechanism against the
Flyway version Spring Boot pulls in before relying on it**, because this has changed across
Flyway major versions. Also note a concurrent index build can fail and leave an `INVALID` index
behind, which must be dropped and rebuilt; that is not an error the migration will report for you.

**`squawk` lints most of this automatically** and is already on the Wave 1.5 backlog (HANDOFF §8)
precisely for unsafe Postgres DDL in Flyway migrations. If a serious schema change is coming,
pull Wave 1.5 forward first — it is cheaper than learning these by outage.

### 1.5 Backfills, and the RLS trap specific to this codebase

A backfill is not a migration. Put it in application code or a one-off job, not in a `V<n>.sql`
that holds a transaction open across millions of rows.

- **Batch by primary key range**, commit per batch, and make it **resumable** — record progress
  so a killed job restarts where it stopped rather than from zero.
- **Make it idempotent.** It will be run twice.
- **Throttle.** A backfill that saturates I/O is an outage with extra steps.

**The trap:** `easycrm_app` is subject to RLS with `FORCE`, so a naive cross-tenant backfill run
as the app role **silently sees zero rows** — not an error, just nothing. Two correct options:

1. Run it per tenant through `platform/job/TenantJobRunner`, which binds `TenantContext` *before*
   opening each transaction. Getting that order backwards is silent, not loud — challenges #9,
   #52 and the members-management slice all hit it.
2. Run it as `easycrm_owner`, which bypasses RLS. Faster and far more dangerous: nothing then
   scopes the write to one tenant, so a wrong `WHERE` corrupts every tenant at once.

Prefer (1) unless there is a measured reason not to.

---

## 2. Code migrations (OpenRewrite)

### 2.1 What it is, and when it is worth it

OpenRewrite performs **type-aware AST transformations**, not regex. It parses the project with
full type attribution, so it can tell `com.foo.List` from `java.util.List` and rewrite call sites,
imports and build files together. The unit of work is a **recipe**; recipes compose into larger
recipes.

It earns its keep on changes that are **mechanical, wide, and boring** — the exact changes a
human does badly across 300 files. It does no design work. If the change needs judgment per call
site, OpenRewrite is the wrong tool and a careful human diff is the right one.

Relevant recipe modules:

| Module | Covers |
|---|---|
| `rewrite-migrate-java` | Java version upgrades, deprecated API removal, Jakarta EE renames |
| `rewrite-spring` | Spring Boot and Spring Framework version migrations, property renames |
| `rewrite-testing-frameworks` | JUnit 4→5, assertion library swaps, Mockito |
| `rewrite-static-analysis` | The mechanical half of what SpotBugs reports |

### 2.2 Run it without committing the plugin

This repo has deliberate build hygiene — a version catalog, a `buildSrc` convention plugin, every
pin justified. A one-off migration should **not** leave a permanent plugin in `build.gradle.kts`.
Use a Gradle init script instead, which applies the plugin for one invocation and touches nothing:

```groovy
// init.gradle — not committed, or committed under tools/ and clearly marked one-off
initscript {
    repositories { maven { url "https://plugins.gradle.org/m2" } }
    dependencies { classpath("org.openrewrite:plugin:latest.release") }
}

rootProject {
    plugins.apply(org.openrewrite.gradle.RewritePlugin)
    dependencies {
        rewrite("org.openrewrite.recipe:rewrite-spring:latest.release")
    }
    afterEvaluate {
        if (repositories.isEmpty()) { repositories { mavenCentral() } }
    }
}
```

```bash
# Look before you leap — writes a patch, changes nothing
./gradlew rewriteDryRun --init-script init.gradle \
    -Drewrite.activeRecipe=org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_2

# Apply
./gradlew rewriteRun --init-script init.gradle \
    -Drewrite.activeRecipe=<same recipe>
```

The init script and a `rewrite { }` block in the build file are **mutually exclusive** — if one
is ever added to `build.gradle.kts`, the init-script route stops working.

### 2.3 The workflow that fits this repo

1. **`rewriteDryRun` first, always.** Read the patch. A recipe that touches 300 files deserves
   ten minutes of reading before it touches one.
2. **Apply on a branch of its own**, off a green `main`.
3. **Commit the mechanical change alone**, with nothing hand-written mixed in. Precedent: the
   whole-tree Spotless reformat landed as its own commit (`2616049`) and was recorded in
   `.git-blame-ignore-revs`. Do the same for any wide rewrite, or every future `git blame` on
   those lines points at the migration instead of the author.
4. **Then `./gradlew spotlessApply`.** OpenRewrite's output will not match palantir-java-format,
   and `clean check` will fail on formatting if you skip this.
5. **Then `./gradlew clean check` in full.** Not `:test`. Two tasks in the members-management
   slice shipped invisible Spotless and SpotBugs violations precisely by running targeted tests
   between gate runs — challenge #67.
6. **Re-run the guards deliberately and read their output.** OpenRewrite knows nothing about this
   codebase's invariants: `@TenantId`, the RLS policies, `VisibilityScopingArchTest`'s allowlist,
   money as `BigDecimal`. A recipe that "simplifies" a defensive copy or a repository call can be
   locally correct and break a structural rule. The ArchUnit and RLS tests are the backstop —
   make sure they actually ran.
7. **Regenerate what is generated, in the same change.** `docs/api/openapi.yaml` is guarded; if
   the rewrite changes a controller signature, run `./gradlew updateOpenApiSnapshot` and commit
   the result alongside. The members-management merge is the worked example.

### 2.4 Honest caveats

- **This stack is ahead of most recipe coverage.** Boot 4.1 / Java 25 / Hibernate 7 is bleeding
  edge, and HANDOFF §6 records that most third-party tooling here has needed a version hunt.
  Check that a Boot 3→4 recipe actually exists and is maintained before planning around it;
  do not assume parity with the well-trodden Boot 2→3 path.
- **`latest.release` is fine for a one-off, wrong for anything repeated.** If a recipe ever gets
  committed into the build, pin it in `gradle/libs.versions.toml` with a justifying comment, like
  every other dependency here.
- **A green build is not a correct migration.** Recipes preserve compilation, not behaviour.
  The 586-test suite is what tells you the semantics survived.

---

## 3. The one rule both halves share

**Every step must be independently deployable and independently revertible.** A schema change
that requires the new code, and new code that requires the schema change, is a single step that
cannot be rolled back — which means the rollback plan is "roll forward under pressure at 2am."
Expand/contract exists to turn that one irreversible step into four reversible ones.

And the local check matters more here than in most repos: **CI is a post-merge smoke alarm, not
a pre-merge gate** (HANDOFF §0). It will tell you `main` is broken; it cannot stop you breaking
it. Run `./gradlew clean check` before you merge.
