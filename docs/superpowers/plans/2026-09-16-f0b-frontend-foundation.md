# F0b — Frontend Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `frontend/`: a React + TypeScript SPA with a generated, drift-guarded API client, a browser session that survives tabs and flaky 4G, login, signup, `/invite/{token}`, an empty authenticated shell, and blocking frontend + E2E CI jobs.

**Architecture:** A Vite SPA in `frontend/` at the repository root, proxied to the unchanged Spring Boot backend in dev and E2E. Layers are enforced by ESLint: `api` (generated schema + the one HTTP client) → `lib`/`components` → `features/auth` → `app`. The session lives in `features/auth/session` and reaches the HTTP layer only through an injected `AuthBridge`, so `api` never imports a feature. Every session start and end goes through `establishSession` / `endSession`; every refresh goes through one Web-Locks-serialized coordinator.

**Tech Stack:** Node 24 LTS, pnpm 10, Vite, React 19, TypeScript (strict), React Router 7, TanStack Query 5, Zustand 5, React Hook Form 7 + `zod/mini`, Tailwind v4 + shadcn/ui primitives, i18next, openapi-typescript + openapi-fetch, Vitest + Testing Library + MSW (+ openapi-msw), Playwright + `@axe-core/playwright`, ESLint 9 flat config. One small backend change (Java 25, springdoc) and one backend test class.

**Spec:** `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md` — this plan implements **Parts 4–6 (F0b)** and the F0b items of Part 7. Part 3 (F0a) is already merged. Read the spec's Parts 4–6 before starting any task; this plan argues from them.

---

## Plan decisions (read first — each resolves a gap the handoff named or a spec detail that does not survive contact)

| # | Decision | Why |
|---|---|---|
| **P1** | **`AuthResponse`, `MeResponse`, `InvitationPreviewResponse`, `SignupStatusResponse`, `ApiErrorResponse` and `ApiError` declare `required` in the contract** (Task 1, backend), via type-level `@Schema(requiredProperties = …)`, guarded by a new `OpenApiRequiredFieldsTest` | Handoff gap 2. Fixing the contract once beats narrowing types in every consumer; spec §4.4 forbids scattered non-null assertions. Type-level rather than per-component because `ApiError`'s own Javadoc records that this codebase does not rest on record-component annotation propagation |
| **P2** | **Boot retry: a 429 honours `Retry-After` (clamped to 1–60 s); every other retryable failure uses 2 s → 5 s → 15 s → 30 s** | Handoff gap 4. `RateLimitFilter` runs before any token is read, so a 429'd refresh consumed nothing — the grace window is untouched, and retrying before `Retry-After` is guaranteed another 429. The first non-429 retry (2 s) lands inside the 30 s grace window as §4.4 requires |
| **P3** | **The bare refresh/logout client sends `X-EasyCRM-Client: web` twice over:** at runtime in `createBareFetch`, and at the type level because the contract marks the header parameter `required`, so `bareApi.POST('/api/v1/auth/refresh')` does not compile without `params.header` | Handoff gap 1. A unit test and an MSW handler that answers 403 without the header prove it |
| **P4** | **The HTTP middleware is openapi-fetch's `fetch` option (a wrapping `(Request) => Promise<Response>`), not `client.use()` middleware.** It snapshots the request body to an `ArrayBuffer` once and builds a fresh `Request` per attempt | openapi-fetch middleware cannot re-dispatch a request, and a `Request` body stream is single-use. Building each attempt from a byte snapshot makes "retry sends the identical body" true by construction and unit-testable without openapi-fetch |
| **P5** | **A fifth session status, `signing-out`**, alongside §4.4's four | §4.4's logout-failure state ("local state cleared, cookie still live, login page not shown") is a state the router must render. Modelling it as a status makes `RootLayout` render one blocking screen everywhere instead of every page checking a side flag |
| **P6** | **Boot results are discarded once the status has left `booting`/`unreachable`** | A user can sign in on `/login` while a slow boot refresh is still in flight; its later 401 must not flip an authenticated tab to anonymous |
| **P7** | **The splash sits outside `#root` and is hidden by `#root:not(:empty) + #splash` in an external stylesheet.** `RequireSession` renders `null` while booting | §4.9's CSP forbids inline `<style>`; the external sheet paints before JS parses. One splash, zero duplicate React markup |
| **P8** | **No toast container in F0.** The first background failure (F1) adds it | F0 has no background, non-blocking failure — every F0 error is a form-level `role="alert"`. shadcn's current toast primitive (Sonner) injects a runtime `<style>` element, which `style-src 'self'` refuses; deciding how to load its CSS belongs with its first real use |
| **P9** | **Forms use small hand-written `TextField` / `PasswordField` / `SelectField` components over shadcn `Label` + `Input`**, not shadcn's `Form` wrapper; the state picker is a **native `<select>`** | The a11y wiring §5.1 requires (`aria-describedby`, `aria-invalid`) is ten lines and tested directly. Radix `Select` pulls `react-remove-scroll`, which injects a `<style>` tag the CSP refuses; a native select is also the right control on low-end Android |
| **P10** | **E2E starts each backend with `java -jar` on one `bootJar` build, not two concurrent `bootRun`s** | Two Gradle invocations on one project directory contend for the same build lock; `bootRun` twice would serialize or fail |
| **P11** | **E2E test 6 proves serialization by asserting at most one refresh is ever in flight across both tabs** (plus both tabs still signed in), not by trying to control which `Set-Cookie` lands last | Playwright's `route.fetch()` runs through the context's request API, which shares — and may update — the cookie jar at fetch time, so cookie landing order cannot be forced reliably. In-flight concurrency is exactly what Web Locks guarantee and exactly what a no-op lock breaks, so the red run is deterministic |
| **P12** | **Boundary lint uses `eslint-plugin-import-x`'s `no-restricted-paths` with zones generated from `src/features/*`**, not `eslint-plugin-boundaries` | `boundaries` changed its configuration syntax in each of its last two majors; `no-restricted-paths` zones are stable. Each zone is proven red by a fixture test |
| **P13** | **The dependency ledger is `frontend/DEPENDENCIES.md`, measured from the production build** (Task 13's `deps:sizes` script over the visualizer's raw data), not at `pnpm add` time | A size measured before tree-shaking is not the size a user downloads |

---

## Global Constraints

- Commit as the repo identity (`divyam <divyam.0444@gmail.com>`, plain `git commit`). **No `Co-Authored-By` trailer and no mention of Claude/AI in commit messages** (CLAUDE.md).
- `docs/api/openapi.yaml` is generated output — regenerate with `./gradlew updateOpenApiSnapshot` from `backend/`, never hand-edit. `frontend/src/api/schema.d.ts` is generated output — regenerate with `pnpm gen:api`, never hand-edit.
- **Every new gate must be seen failing once** (challenges #75–79). Where a step says "Expected: FAIL", confirm the failure *reason* matches before continuing.
- **Node 24 LTS** (`.nvmrc` = `24`, `engines.node` = `>=24 <25`, `engine-strict=true`). **pnpm** via `packageManager` = the version `pnpm -v` prints at Task 2 (10.33.0 on the owner's Mac on 2026-09-16).
- **Expected majors** — if `pnpm add <pkg>` resolves a *different* major than listed, re-run as `pnpm add <pkg>@^<major>` so this plan's code stays valid, and note it in the ledger: react 19, react-dom 19, react-router 7, @tanstack/react-query 5, zustand 5, react-hook-form 7, @hookform/resolvers 5, zod 4, i18next 25, react-i18next 16, i18next-resources-to-backend 1, openapi-fetch 0.x, openapi-typescript 7, vite 7, @vitejs/plugin-react 5, tailwindcss 4, vitest 3, msw 2, openapi-msw 1, @playwright/test 1, eslint 9, typescript-eslint 8, eslint-plugin-import-x 4.
- TypeScript `strict` + `noUncheckedIndexedAccess`. No `any` in `src/` outside generated files. No non-null assertions (`!`) on API data.
- CSP (exact, spec §4.9): `default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self'`. No inline `<script>` or `<style>` in `index.html`.
- Client header: `X-EasyCRM-Client: web` on every request (refresh and logout require it). Request timeout **15 s**.
- Refresh-exempt routes (a 401 never triggers refresh): `/api/v1/auth/login`, `/api/v1/auth/signup`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/invitations/**`.
- Web Lock name: `easycrm-refresh`. BroadcastChannel name: `easycrm-auth`. Remembered workspace key: `localStorage['easycrm.lastWorkspace']`.
- Boot retry schedule: 2 000, 5 000, 15 000, 30 000 ms (last repeats); 429 → `Retry-After` clamped to [1, 60] s (P2).
- Query defaults: queries retry network/5xx at most 2 times, never 4xx; mutations `retry: 0`; `refetchOnWindowFocus: false`; `staleTime: 30_000`.
- Per-route JS budget: **200 KB gzipped** for `/login`, `/signup`, `/invite/:token`.
- Every user-visible string in `src/features/**` and `src/app/**` comes from `t()`; locale files are `src/locales/en/{common,auth}.json`.
- Frontend commands run from `frontend/`; Gradle commands run from `backend/`.
- Backend baseline before this plan: **677 tests (642 root + 35 `platform-primitives`), 0 failures** at `2d9a2aa`.
- Engineering challenges log: template at the top of `docs/superpowers/engineering-challenges.md`; **next number is 84**.

---

## File Structure

**Backend (Task 1, Task 16)**
- Modify: `backend/src/main/java/com/easycrm/iam/web/dto/{AuthResponse,MeResponse,InvitationPreviewResponse,SignupStatusResponse}.java`, `backend/src/main/java/com/easycrm/platform/error/{ApiError,ApiErrorResponse}.java` — `requiredProperties`.
- Create: `backend/src/test/java/com/easycrm/platform/openapi/OpenApiRequiredFieldsTest.java`
- Create: `backend/src/test/java/com/easycrm/platform/frontend/FrontendWorkflowTest.java`
- Modify: `backend/src/test/java/com/easycrm/platform/openapi/OasdiffWorkflowTest.java` (message only)
- Regenerate: `docs/api/openapi.yaml`

**Frontend — tooling** (`frontend/`)
- `package.json`, `pnpm-lock.yaml`, `.nvmrc`, `.npmrc`, `.gitignore`, `tsconfig.json`, `vite.config.ts`, `eslint.config.js`, `components.json`, `index.html`, `public/splash.css`, `DEPENDENCIES.md`
- `scripts/check-budget.mjs` (+ `.test.ts`), `scripts/dependency-sizes.mjs`

**Frontend — `src/`** (one responsibility per file)
```
src/
  main.tsx                     entry: startApp() + render
  index.css                    Tailwind + shadcn theme tokens
  api/
    schema.d.ts                GENERATED
    types.ts                   named aliases of generated schemas
    authBridge.ts              AuthBridge interface + setter (api never imports features)
    authFetch.ts               createAuthFetch / createBareFetch: headers, timeout, 401→refresh→retry
    client.ts                  api, bareApi, createApiClient, createBareClient
    errors.ts                  ApiHttpError, unwrap, ensureOk, toApiFailure, parseRetryAfter
  app/
    bootstrap.ts               startApp(): query client + session runtime + router
    queryClient.ts             createQueryClient, shouldRetryQuery
    router.tsx                 appRoutes, createAppRouter
    providers.tsx              QueryClientProvider + RouterProvider (+ DEV devtools)
    RootLayout.tsx             signing-out gate, session-expired → /login?next=
    RequireSession.tsx         booting / unreachable / anonymous / children
    RouteErrorBoundary.tsx
    NotFoundPage.tsx
    shell/AppShell.tsx         header: slug, email, role, logout
    shell/HomePage.tsx         placeholder
    shell/UnreachableScreen.tsx
    shell/SignOutPendingScreen.tsx
  features/auth/
    api/authKeys.ts
    api/useLogin.ts, useSignup.ts, useSignupStatus.ts, useInvitationPreview.ts, useAcceptInvitation.ts
    session/sessionStore.ts    Zustand {status, me}
    session/accessToken.ts     module-scoped token
    session/toMe.ts            AuthResponse → Me, isRole
    session/lockProvider.ts    LockProvider, webLocks, createInMemoryLocks, noopLocks
    session/authChannel.ts     BroadcastChannel wrapper
    session/sessionEvents.ts   session-expired event
    session/runtime.ts         SessionRuntime injection
    session/session.ts         establishSession, endSession, subscribeToAuthChannel
    session/refreshCall.ts     callRefresh (bare client)
    session/refreshCoordinator.ts
    session/bridge.ts          AuthBridge implementation
    session/boot.ts            createBoot, nextBootDelayMs
    session/logout.ts          callLogout, createLogout
    session/start.ts           startSession, sessionControls
    roleLabel.ts
    workspaceState.ts          router state → pre-filled workspace on /login
    pages/LoginPage.tsx, SignupPage.tsx, InvitePage.tsx
  components/
    ui/                        shadcn: button, input, label, card, alert, skeleton
    form/TextField.tsx, PasswordField.tsx, SelectField.tsx, FormAlert.tsx
    PageHeading.tsx
  lib/
    utils.ts                   shadcn cn()
    storage.ts, safeNext.ts, slug.ts, apiError.ts, useDocumentTitle.ts
    gst/states.ts
    i18n/index.ts, i18n/i18next.d.ts, i18n/translator.ts, i18n/keys.typecheck.ts
  locales/en/common.json, auth.json
  test/setup.ts, msw.ts, openapiHttp.ts, openapiHttp.typecheck.ts, fixtures.ts, i18n.ts, renderApp.tsx
```

**Frontend — E2E** (`frontend/e2e/`)
- `playwright.config.ts`, `fixtures.ts`, `support/api.ts`, `support/ui.ts`, `scripts/run-backend.sh`, `scripts/flaky-summary.mjs`
- `main/{signup-reload-logout,invite-accept,invite-signed-in,cross-tab-logout,invite-invalid,guards}.spec.ts`
- `refresh/{multi-tab-refresh,lost-refresh-response}.spec.ts`

**CI and docs**
- Modify: `.github/workflows/ci.yml` (jobs `frontend`, `e2e`; oasdiff comment), `.github/dependabot.yml` (npm)
- Modify: `docs/superpowers/engineering-challenges.md`, `docs/superpowers/annotations-reference.md`, `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md`, `docs/superpowers/specs/2026-07-22-easycrm-design.md` (§5 note), `docs/superpowers/HANDOFF.md`, `docs/ROADMAP.md`

---

### Task 1: Worktree, and the contract declares required fields

**Files:**
- Create: `backend/src/test/java/com/easycrm/platform/openapi/OpenApiRequiredFieldsTest.java`
- Modify: `backend/src/main/java/com/easycrm/iam/web/dto/AuthResponse.java`, `MeResponse.java`, `InvitationPreviewResponse.java`, `SignupStatusResponse.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiError.java`, `ApiErrorResponse.java`
- Modify: `docs/superpowers/annotations-reference.md` (§6b table, after the existing `@Schema` row)
- Regenerate: `docs/api/openapi.yaml`

**Interfaces:**
- Consumes: the `openapi.snapshot` system property (`backend/build.gradle.kts:108`).
- Produces: a contract in which the six schemas above list `required`, so `openapi-typescript` (Task 3) emits non-optional fields. Later tasks rely on `AuthResponse` = `{ accessToken: string; userId: string; tenantId: string; tenantSlug: string; email: string; role: string }` with every field required.

- [ ] **Step 1: Create the worktree and confirm the baseline**

Use `superpowers:using-git-worktrees` to create branch `f0b-frontend` in a worktree off `main`. Then, from the worktree root:
```bash
git rev-parse --short HEAD
git status --short
cd backend && ./gradlew clean check --console=plain 2>&1 | tail -3 && cd ..
find backend -path '*build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
```
Expected: HEAD `2d9a2aa` or later docs-only commits; `BUILD SUCCESSFUL`; count **677**. If the count differs, stop and reconcile.

- [ ] **Step 2: Write the failing guard test**

```java
package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi-typescript makes every property optional unless the schema lists it under
 * {@code required}. The browser session is built from these responses, so an optional
 * {@code accessToken} would push non-null assertions into every consumer (spec 2026-09-14-f0 §4.4).
 * Reads the COMMITTED snapshot, which OpenApiSnapshotTest holds equal to what springdoc generates.
 */
class OpenApiRequiredFieldsTest {

    private static final Map<String, List<String>> REQUIRED = Map.of(
            "AuthResponse", List.of("accessToken", "userId", "tenantId", "tenantSlug", "email", "role"),
            "MeResponse", List.of("userId", "tenantId", "tenantSlug", "email", "role"),
            "InvitationPreviewResponse", List.of("businessName", "email", "role"),
            "SignupStatusResponse", List.of("open"),
            "ApiErrorResponse", List.of("error"),
            "ApiError", List.of("code", "message"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() throws Exception {
        String yaml = Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8);
        Map<String, Object> doc = new Yaml().load(yaml);
        return (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionAndErrorSchemasDeclareTheirRequiredFields() throws Exception {
        var schemas = schemas();
        for (var entry : REQUIRED.entrySet()) {
            var schema = (Map<String, Object>) schemas.get(entry.getKey());
            assertNotNull(schema, "no schema named " + entry.getKey() + " in the snapshot");
            var required = (List<String>) schema.getOrDefault("required", List.of());
            assertEquals(
                    new TreeSet<>(entry.getValue()),
                    new TreeSet<>(required),
                    entry.getKey() + " must list exactly these as required");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRequiredNameIsARealProperty() throws Exception {
        // requiredProperties is a list of strings: a typo there would emit a required name that no
        // property has, and the generated client would demand a field the server never sends.
        var schemas = schemas();
        for (var name : REQUIRED.keySet()) {
            var schema = (Map<String, Object>) schemas.get(name);
            var properties = ((Map<String, Object>) schema.get("properties")).keySet();
            var required = (List<String>) schema.getOrDefault("required", List.of());
            assertTrue(properties.containsAll(required), name + " requires " + required + " but has " + properties);
            assertFalse(required.isEmpty(), name + " declares no required fields (non-vacuity)");
        }
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.openapi.OpenApiRequiredFieldsTest' --console=plain`
Expected: FAIL — `AuthResponse must list exactly these as required ==> expected: <[accessToken, email, role, tenantId, tenantSlug, userId]> but was: <[]>`.

- [ ] **Step 4: Annotate the six records**

`AuthResponse.java` — add the import and the annotation; the Javadoc stays:
```java
package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Every session-issuing response (signup, login, invitation accept, refresh). Carries the same
 * identity as MeResponse so a browser needs no follow-up /me call and can detect a principal change
 * on refresh. The refresh token is NOT here: it travels only in the easycrm_rt httpOnly cookie
 * (spec 2026-09-14-f0 §3.1).
 *
 * <p>{@code requiredProperties} is type-level on purpose (see ApiError's Javadoc on record-component
 * annotation propagation); OpenApiRequiredFieldsTest catches a typo in the names.
 */
@Schema(requiredProperties = {"accessToken", "userId", "tenantId", "tenantSlug", "email", "role"})
public record AuthResponse(
        String accessToken, UUID userId, UUID tenantId, String tenantSlug, String email, String role) {}
```

`MeResponse.java`:
```java
package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"userId", "tenantId", "tenantSlug", "email", "role"})
public record MeResponse(UUID userId, UUID tenantId, String email, String role, String tenantSlug) {}
```

`InvitationPreviewResponse.java` — keep the Javadoc, add above the record:
```java
import io.swagger.v3.oas.annotations.media.Schema;
// ...
@Schema(requiredProperties = {"businessName", "email", "role"})
public record InvitationPreviewResponse(String businessName, String email, String role) {}
```

`SignupStatusResponse.java`:
```java
package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"open"})
public record SignupStatusResponse(boolean open) {}
```

`ApiErrorResponse.java` — add `import io.swagger.v3.oas.annotations.media.Schema;` and `@Schema(requiredProperties = {"error"})` directly above `public record ApiErrorResponse`.

`ApiError.java` — add `import io.swagger.v3.oas.annotations.media.Schema;` and, directly above the existing `@JsonInclude(JsonInclude.Include.NON_NULL)`, `@Schema(requiredProperties = {"code", "message"})`. Add one sentence to the end of its Javadoc: `<p>{@code code} and {@code message} are marked required in the contract; {@code fields} and {@code fieldCodes} stay optional because NON_NULL omits them.`

- [ ] **Step 5: Regenerate the snapshot and inspect the diff**

```bash
cd backend && ./gradlew updateOpenApiSnapshot --console=plain && cd ..
git diff --stat docs/api/openapi.yaml
git diff docs/api/openapi.yaml | grep '^[+-]' | grep -v '^+++\|^---'
```
Expected: only added `required:` blocks (six of them) with their list items; no removed lines. **If `required` did not appear for a schema**, springdoc ignored `requiredProperties` on that type: replace the type-level annotation on that record with `@Schema(requiredMode = Schema.RequiredMode.REQUIRED)` on each listed component (e.g. `@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken`), regenerate, and record which form worked in the Step 7 annotations row.

- [ ] **Step 6: Run the guards and the full check**

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.openapi.*' --console=plain && ./gradlew clean check --console=plain 2>&1 | tail -3`
Expected: PASS, `BUILD SUCCESSFUL`. Test count **679** (677 + 2).

- [ ] **Step 7: Record the annotation use**

In `docs/superpowers/annotations-reference.md`, directly below the existing `@Schema` row, add:
```markdown
| `@Schema(requiredProperties = …)` | `io.swagger.v3.oas.annotations.media` | Type-level on a response record: lists the properties the OpenAPI schema marks `required`. On `AuthResponse`, `MeResponse`, `InvitationPreviewResponse`, `SignupStatusResponse`, `ApiErrorResponse` and `ApiError` so openapi-typescript emits non-optional fields for the browser session (F0b Task 1). Type-level rather than per-component because this codebase does not rest on record-component annotation propagation (see `ApiError`); the names are strings, so `OpenApiRequiredFieldsTest` asserts each is a real property. | — |
```

- [ ] **Step 8: Commit**

```bash
git add backend/src docs/api/openapi.yaml docs/superpowers/annotations-reference.md
git commit -m "feat(api): mark session, invitation-preview and error fields required in the contract"
```

---

### Task 2: Scaffold `frontend/`

**Files:**
- Create: `frontend/package.json`, `frontend/.nvmrc`, `frontend/.npmrc`, `frontend/.gitignore`, `frontend/tsconfig.json`, `frontend/vite.config.ts`, `frontend/index.html`, `frontend/public/splash.css`, `frontend/src/main.tsx`, `frontend/src/index.css`, `frontend/src/test/setup.ts`, `frontend/src/test/msw.ts`, `frontend/src/test/environment.test.ts`, `frontend/DEPENDENCIES.md`

**Interfaces:**
- Produces: `pnpm dev|build|preview|typecheck|test` working; the `@/` alias → `src/`; `CSP` exported from `vite.config.ts`; a global MSW `server` in `src/test/msw.ts` started by `src/test/setup.ts` with `onUnhandledRequest: 'error'`.

- [ ] **Step 1: Confirm the toolchain**

```bash
node -v
pnpm -v
```
Expected: `v24.x`. **If Node is not 24** (the owner's Mac had v25.2.1 and no version manager on 2026-09-16), stop and ask the owner to install Node 24 — suggested: `brew install fnm && fnm install 24 && fnm use 24` — do not change their global Node unasked. Record the pnpm version for `packageManager`.

- [ ] **Step 2: Write the tooling files**

`frontend/.nvmrc`:
```
24
```

`frontend/.npmrc`:
```
engine-strict=true
```

`frontend/.gitignore`:
```
node_modules/
dist/
reports/
coverage/
test-results/
playwright-report/
*.local
```

`frontend/package.json` (replace `10.33.0` with Step 1's pnpm version):
```json
{
  "name": "easycrm-frontend",
  "private": true,
  "type": "module",
  "packageManager": "pnpm@10.33.0",
  "engines": { "node": ">=24 <25" },
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "typecheck": "tsc --noEmit",
    "lint": "eslint --max-warnings=0 .",
    "test": "vitest run",
    "test:coverage": "vitest run --coverage",
    "gen:api": "openapi-typescript ../docs/api/openapi.yaml -o src/api/schema.d.ts",
    "budget": "node scripts/check-budget.mjs",
    "deps:sizes": "node scripts/dependency-sizes.mjs",
    "e2e": "playwright test -c e2e/playwright.config.ts"
  }
}
```

- [ ] **Step 3: Install the base dependencies**

```bash
cd frontend
pnpm add react react-dom
pnpm add -D typescript vite @vitejs/plugin-react @types/react @types/react-dom @types/node \
  tailwindcss @tailwindcss/vite \
  vitest jsdom @testing-library/react @testing-library/dom @testing-library/user-event @testing-library/jest-dom \
  msw
```
Expected: installs with no engine error. Check majors against Global Constraints.

- [ ] **Step 4: Write `tsconfig.json`, `vite.config.ts`, `index.html`, splash and entry**

`frontend/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "noFallthroughCasesInSwitch": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "verbatimModuleSyntax": true,
    "allowJs": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client", "node"],
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["src", "scripts", "e2e", "vite.config.ts"]
}
```

`frontend/vite.config.ts`:
```ts
import { fileURLToPath, URL } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Spec 2026-09-14-f0 §4.9. The deployed header belongs to the hosting layer (item 5 / SP2); vite
// preview sends it so E2E surfaces any violation during F0.
export const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "frame-ancestors 'none'",
  "connect-src 'self'",
].join('; ');

// E2E runs two backends; each vite preview instance proxies to one of them (Task 14).
const apiTarget = process.env.E2E_API_TARGET ?? 'http://localhost:8080';
const proxy = { '/api': { target: apiTarget, changeOrigin: false } };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { proxy },
  preview: { proxy, headers: { 'Content-Security-Policy': CSP, 'Referrer-Policy': 'no-referrer' } },
  build: { manifest: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.ts'],
    restoreMocks: true,
    testTimeout: 10_000,
  },
});
```

`frontend/index.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="referrer" content="no-referrer" />
    <title>EasyCRM</title>
    <link rel="stylesheet" href="/splash.css" />
  </head>
  <body>
    <div id="root"></div>
    <div id="splash" class="splash" role="status" aria-busy="true" aria-label="Loading EasyCRM">
      <div class="splash-mark" aria-hidden="true"></div>
    </div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/public/splash.css`:
```css
/* Plan decision P7: the splash paints before any JS parses, and disappears the moment React renders
   anything into #root. External file, because the CSP forbids inline <style>. */
#root:not(:empty) + #splash {
  display: none;
}
.splash {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
  background: #ffffff;
}
.splash-mark {
  width: 3rem;
  height: 3rem;
  border-radius: 9999px;
  border: 4px solid #e5e7eb;
  border-top-color: #2563eb;
  animation: splash-spin 0.9s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .splash-mark {
    animation: none;
  }
}
@keyframes splash-spin {
  to {
    transform: rotate(360deg);
  }
}
```

`frontend/src/index.css`:
```css
@import 'tailwindcss';
```

`frontend/src/main.tsx` (Task 7 replaces the body):
```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';

const root = document.getElementById('root');
if (!root) throw new Error('#root is missing from index.html');
createRoot(root).render(<StrictMode>{null}</StrictMode>);
```

- [ ] **Step 5: Write the test harness and the environment probe**

`frontend/src/test/msw.ts`:
```ts
import { setupServer } from 'msw/node';

/** The one MSW server for every component test. Handlers are added per test with server.use(). */
export const server = setupServer();
```

`frontend/src/test/setup.ts` (Tasks 4 and 5 extend it):
```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './msw';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
```

`frontend/src/test/environment.test.ts`:
```ts
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from './msw';

describe('test environment', () => {
  // authFetch (Task 3) builds every Request with AbortSignal.timeout. Under jsdom, a DOM AbortSignal
  // handed to Node's Request can be rejected ("Expected signal to be an instance of AbortSignal").
  // This probe fails loudly here instead of inside thirty component tests later.
  it('lets a Request carry an AbortSignal through MSW', async () => {
    server.use(http.get('*/probe', () => HttpResponse.json({ ok: true })));
    const response = await fetch(
      new Request(`${location.origin}/probe`, { signal: AbortSignal.timeout(1_000) }),
    );
    expect(await response.json()).toEqual({ ok: true });
  });
});
```

- [ ] **Step 6: Run the probe, typecheck and build**

```bash
pnpm test
pnpm typecheck
pnpm build
```
Expected: 1 test PASS; typecheck clean; build writes `dist/index.html` and `dist/.vite/manifest.json`.
**If the probe fails with an AbortSignal instance error:** `pnpm add -D happy-dom`, set `test.environment: 'happy-dom'` in `vite.config.ts`, rerun. If it still fails, stop and report — do not remove the signal from authFetch.

- [ ] **Step 7: Start the dependency ledger**

`frontend/DEPENDENCIES.md`:
```markdown
# Frontend dependency ledger

Spec 2026-09-14-f0 §4.2: every runtime dependency states its gzipped size and the chunk it lands in.
Sizes are measured from the production build by `pnpm deps:sizes` (Task 13), not at install time —
a pre-tree-shaking size is not what a user downloads. **Every later F-slice that adds a runtime
dependency adds a row here in the same change.**

| Package | Version | Chunk | Gzipped (measured) | Why |
|---|---|---|---|---|
| react | | entry | | UI runtime |
| react-dom | | entry | | UI runtime |

Dev-only dependencies are not listed: they never reach a user.
```

- [ ] **Step 8: Commit**

```bash
cd .. && git add frontend
git commit -m "feat(frontend): scaffold Vite + React + TypeScript app with CSP-safe splash and test harness"
```

---

### Task 3: The generated client and the one HTTP layer

**Files:**
- Create: `frontend/src/api/schema.d.ts` (generated), `types.ts`, `authBridge.ts`, `authFetch.ts`, `client.ts`, `errors.ts`
- Create: `frontend/src/api/authFetch.test.ts`, `frontend/src/api/errors.test.ts`
- Create: `frontend/src/test/openapiHttp.ts`, `frontend/src/test/openapiHttp.typecheck.ts`, `frontend/src/test/fixtures.ts`

**Interfaces:**
- Consumes: Task 1's contract; Task 2's harness.
- Produces (exact names later tasks use):
  - `authBridge.ts`: `type RefreshOutcome = 'refreshed' | 'ended' | 'unavailable'`; `interface AuthBridge { getAccessToken(): string | null; refresh(tokenAtFailure: string | null): Promise<RefreshOutcome>; sessionExpired(): void }`; `setAuthBridge(b: AuthBridge): void`; `getAuthBridge(): AuthBridge`.
  - `authFetch.ts`: `CLIENT_HEADER = 'X-EasyCRM-Client'`; `REQUEST_TIMEOUT_MS = 15_000`; `type FetchLike = (request: Request) => Promise<Response>`; `class RetryableRequestError extends Error`; `isRefreshExempt(url: string): boolean`; `createAuthFetch(opts?: { bridge?: () => AuthBridge; fetchImpl?: FetchLike; timeoutMs?: number }): FetchLike`; `createBareFetch(opts?: { fetchImpl?: FetchLike; timeoutMs?: number }): FetchLike`.
  - `client.ts`: `createApiClient(fetchImpl?: FetchLike)`, `createBareClient(fetchImpl?: FetchLike)`, `api`, `bareApi`, `type ApiClient`.
  - `errors.ts`: `class ApiHttpError extends Error { status: number; body: unknown; headers: Headers }`; `unwrap<T>(r): T`; `ensureOk(r): void`; `type ApiFailure = { kind: 'http'; status: number; body: unknown; headers: Headers } | { kind: 'network' }`; `toApiFailure(e: unknown): ApiFailure`; `parseRetryAfter(v: string | null): number | undefined`.
  - `types.ts`: `AuthResponse`, `MeResponse`, `LoginRequest`, `SignupRequest`, `SignupStatusResponse`, `InvitationPreviewResponse`, `AcceptInvitationRequest`, `ApiErrorResponse`.
  - `test/openapiHttp.ts`: `http` (typed handlers, base `*`). `test/fixtures.ts`: `ownerSession`, `inviteeSession`, `errorBody(code, message, extra?)`.

- [ ] **Step 1: Install and generate**

```bash
cd frontend
pnpm add openapi-fetch
pnpm add -D openapi-typescript openapi-msw
pnpm gen:api
grep -n "AuthResponse: {" -A8 src/api/schema.d.ts
```
Expected: `accessToken: string;` (no `?`) — Task 1's required list reached the client. If it shows `accessToken?: string;`, Task 1 did not land; stop.

- [ ] **Step 2: Prove the drift guard fails on a hand edit**

CI (Task 16) runs `pnpm gen:api && git diff --exit-code -- src/api/schema.d.ts`: regenerate, then compare against what is committed. Simulate a committed hand edit by staging one (`git diff` compares the working tree with the index):
```bash
sed -i.bak 's/accessToken: string;/accessToken: number;/' src/api/schema.d.ts && rm src/api/schema.d.ts.bak
git add src/api/schema.d.ts
pnpm gen:api && git diff --exit-code -- src/api/schema.d.ts; echo "exit=$?"
```
Expected: `exit=1`, and the diff shows `-  accessToken: number;` / `+  accessToken: string;`. Restore with `git add src/api/schema.d.ts` (the regenerated file is now correct).

- [ ] **Step 3: Write `types.ts` and `authBridge.ts`**

`frontend/src/api/types.ts`:
```ts
import type { components } from './schema';

type Schemas = components['schemas'];

export type AuthResponse = Schemas['AuthResponse'];
export type MeResponse = Schemas['MeResponse'];
export type LoginRequest = Schemas['LoginRequest'];
export type SignupRequest = Schemas['SignupRequest'];
export type SignupStatusResponse = Schemas['SignupStatusResponse'];
export type InvitationPreviewResponse = Schemas['InvitationPreviewResponse'];
export type AcceptInvitationRequest = Schemas['AcceptInvitationRequest'];
export type ApiErrorResponse = Schemas['ApiErrorResponse'];
```

`frontend/src/api/authBridge.ts`:
```ts
/**
 * How the HTTP layer reaches the session without importing a feature (spec §4.3: api is the lowest
 * layer). features/auth/session installs the real bridge at startup (Task 5).
 */
export type RefreshOutcome = 'refreshed' | 'ended' | 'unavailable';

export interface AuthBridge {
  getAccessToken(): string | null;
  /** Refresh unless the token already changed since `tokenAtFailure` was sent. */
  refresh(tokenAtFailure: string | null): Promise<RefreshOutcome>;
  /** The retried request was also refused: end the session and tell the router. */
  sessionExpired(): void;
}

const inert: AuthBridge = {
  getAccessToken: () => null,
  refresh: async () => 'ended',
  sessionExpired: () => {},
};

let current: AuthBridge = inert;

export function setAuthBridge(bridge: AuthBridge): void {
  current = bridge;
}

export function getAuthBridge(): AuthBridge {
  return current;
}
```

- [ ] **Step 4: Write the failing `authFetch` tests**

`frontend/src/api/authFetch.test.ts`:
```ts
// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import type { AuthBridge, RefreshOutcome } from './authBridge';
import { CLIENT_HEADER, RetryableRequestError, createAuthFetch, createBareFetch } from './authFetch';

const ORIGIN = 'http://app.test';

function fakeBridge(token: string | null, outcome: RefreshOutcome) {
  let current = token;
  return {
    getAccessToken: () => current,
    refresh: vi.fn(async (_tokenAtFailure: string | null) => {
      if (outcome === 'refreshed') current = 'token-2';
      return outcome;
    }),
    sessionExpired: vi.fn(),
  } satisfies AuthBridge;
}

function recordingFetch(statuses: number[]) {
  const seen: { url: string; headers: Headers; body: string }[] = [];
  const impl = vi.fn(async (request: Request) => {
    seen.push({ url: request.url, headers: request.headers, body: await request.text() });
    const status = statuses.shift() ?? 200;
    return new Response(status === 204 ? null : '{}', { status });
  });
  return { impl, seen };
}

const post = (path: string, body: unknown) =>
  new Request(ORIGIN + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

describe('createAuthFetch', () => {
  it('attaches the bearer token and the client header', async () => {
    const { impl, seen } = recordingFetch([200]);
    const doFetch = createAuthFetch({ bridge: () => fakeBridge('token-1', 'refreshed'), fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(seen[0]?.headers.get('Authorization')).toBe('Bearer token-1');
    expect(seen[0]?.headers.get(CLIENT_HEADER)).toBe('web');
  });

  it('sends no Authorization header when there is no token', async () => {
    const { impl, seen } = recordingFetch([200]);
    const doFetch = createAuthFetch({ bridge: () => fakeBridge(null, 'ended'), fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/auth/signup/status`));

    expect(seen[0]?.headers.has('Authorization')).toBe(false);
  });

  it('refreshes on 401 and retries once with the new token and the identical body', async () => {
    const { impl, seen } = recordingFetch([401, 201]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(post('/api/v1/customers', { businessName: 'Ravi Traders' }));

    expect(response.status).toBe(201);
    expect(bridge.refresh).toHaveBeenCalledWith('token-1');
    expect(seen).toHaveLength(2);
    expect(seen[1]?.headers.get('Authorization')).toBe('Bearer token-2');
    expect(seen[1]?.body).toBe(seen[0]?.body);
    expect(seen[1]?.body).toBe('{"businessName":"Ravi Traders"}');
  });

  it('ends the session when the retry is also refused', async () => {
    const { impl } = recordingFetch([401, 401]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(response.status).toBe(401);
    expect(bridge.sessionExpired).toHaveBeenCalledTimes(1);
  });

  it('ends the session without retrying when the refresh itself was refused', async () => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'ended');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(new Request(`${ORIGIN}/api/v1/customers`));

    expect(response.status).toBe(401);
    expect(impl).toHaveBeenCalledTimes(1);
    expect(bridge.sessionExpired).toHaveBeenCalledTimes(1);
  });

  it('keeps the session and fails retryably when the refresh could not be completed', async () => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'unavailable');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    await expect(doFetch(new Request(`${ORIGIN}/api/v1/customers`))).rejects.toBeInstanceOf(
      RetryableRequestError,
    );
    expect(bridge.sessionExpired).not.toHaveBeenCalled();
  });

  it.each([
    '/api/v1/auth/login',
    '/api/v1/auth/signup',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
    '/api/v1/auth/invitations/abc',
    '/api/v1/auth/invitations/abc/accept',
  ])('never refreshes on a 401 from %s', async (path) => {
    const { impl } = recordingFetch([401]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    const response = await doFetch(post(path, {}));

    expect(response.status).toBe(401);
    expect(bridge.refresh).not.toHaveBeenCalled();
  });

  it('does refresh on a 401 from /api/v1/auth/me, which is bearer-authenticated', async () => {
    const { impl } = recordingFetch([401, 200]);
    const bridge = fakeBridge('token-1', 'refreshed');
    const doFetch = createAuthFetch({ bridge: () => bridge, fetchImpl: impl });

    await doFetch(new Request(`${ORIGIN}/api/v1/auth/me`));

    expect(bridge.refresh).toHaveBeenCalledTimes(1);
  });

  it('aborts a request that outlives the timeout', async () => {
    const hang = vi.fn(
      (request: Request) =>
        new Promise<Response>((_, reject) =>
          request.signal.addEventListener('abort', () => reject(request.signal.reason)),
        ),
    );
    const doFetch = createAuthFetch({ bridge: () => fakeBridge(null, 'ended'), fetchImpl: hang, timeoutMs: 20 });

    await expect(doFetch(new Request(`${ORIGIN}/api/v1/customers`))).rejects.toMatchObject({
      name: 'TimeoutError',
    });
  });
});

describe('createBareFetch', () => {
  it('sends the client header, no Authorization, and passes a 401 straight through', async () => {
    const { impl, seen } = recordingFetch([401]);
    const doFetch = createBareFetch({ fetchImpl: impl });

    const response = await doFetch(post('/api/v1/auth/refresh', {}));

    expect(response.status).toBe(401);
    expect(seen[0]?.headers.get(CLIENT_HEADER)).toBe('web');
    expect(seen[0]?.headers.has('Authorization')).toBe(false);
    expect(impl).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 5: Run to confirm failure**

Run: `pnpm test src/api/authFetch.test.ts`
Expected: FAIL — `Failed to resolve import "./authFetch"`.

- [ ] **Step 6: Implement `authFetch.ts`**

```ts
import { getAuthBridge, type AuthBridge } from './authBridge';

export const CLIENT_HEADER = 'X-EasyCRM-Client';
export const REQUEST_TIMEOUT_MS = 15_000;

export type FetchLike = (request: Request) => Promise<Response>;

/** The request could not be completed, but the session is intact: the caller may retry. */
export class RetryableRequestError extends Error {
  constructor(cause?: unknown) {
    super('request failed and may be retried', { cause });
    this.name = 'RetryableRequestError';
  }
}

// Routes whose 401 means "wrong credentials" or "no session", never "access token expired".
const REFRESH_EXEMPT = [
  /^\/api\/v1\/auth\/(login|signup|refresh|logout)$/,
  /^\/api\/v1\/auth\/invitations\//,
];

export function isRefreshExempt(url: string): boolean {
  const { pathname } = new URL(url);
  return REFRESH_EXEMPT.some((pattern) => pattern.test(pathname));
}

interface RequestSnapshot {
  url: string;
  method: string;
  headers: Headers;
  body: ArrayBuffer | undefined;
}

// Plan decision P4: read the body once, then build a fresh Request per attempt. A Request body is a
// single-use stream, so a retry built from the original would send nothing.
async function snapshot(request: Request): Promise<RequestSnapshot> {
  const body =
    request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.arrayBuffer();
  return { url: request.url, method: request.method, headers: new Headers(request.headers), body };
}

function build(s: RequestSnapshot, token: string | null, timeoutMs: number): Request {
  const headers = new Headers(s.headers);
  headers.set(CLIENT_HEADER, 'web');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  else headers.delete('Authorization');
  return new Request(s.url, {
    method: s.method,
    headers,
    body: s.body,
    signal: AbortSignal.timeout(timeoutMs),
  });
}

const globalFetch: FetchLike = (request) => globalThis.fetch(request);

export function createAuthFetch(
  opts: { bridge?: () => AuthBridge; fetchImpl?: FetchLike; timeoutMs?: number } = {},
): FetchLike {
  const bridgeOf = opts.bridge ?? getAuthBridge;
  const fetchImpl = opts.fetchImpl ?? globalFetch;
  const timeoutMs = opts.timeoutMs ?? REQUEST_TIMEOUT_MS;

  return async (request) => {
    const snap = await snapshot(request);
    const bridge = bridgeOf();
    const tokenAtSend = bridge.getAccessToken();
    const first = await fetchImpl(build(snap, tokenAtSend, timeoutMs));
    if (first.status !== 401 || isRefreshExempt(snap.url)) return first;

    const outcome = await bridge.refresh(tokenAtSend);
    if (outcome === 'unavailable') throw new RetryableRequestError();
    if (outcome === 'ended') {
      bridge.sessionExpired();
      return first;
    }
    const retried = await fetchImpl(build(snap, bridge.getAccessToken(), timeoutMs));
    if (retried.status === 401) bridge.sessionExpired();
    return retried;
  };
}

/**
 * For refresh and logout only. Skips Authorization and 401 handling — it must never recurse into a
 * refresh — but still sends X-EasyCRM-Client, without which both routes answer 403 (spec §4.4).
 */
export function createBareFetch(opts: { fetchImpl?: FetchLike; timeoutMs?: number } = {}): FetchLike {
  const fetchImpl = opts.fetchImpl ?? globalFetch;
  const timeoutMs = opts.timeoutMs ?? REQUEST_TIMEOUT_MS;
  return async (request) => fetchImpl(build(await snapshot(request), null, timeoutMs));
}
```

- [ ] **Step 7: Run the tests**

Run: `pnpm test src/api/authFetch.test.ts`
Expected: PASS (15 tests including the `it.each` rows).

- [ ] **Step 8: Write `errors.ts` test-first**

`frontend/src/api/errors.test.ts`:
```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { ApiHttpError, ensureOk, parseRetryAfter, toApiFailure, unwrap } from './errors';

describe('unwrap', () => {
  it('returns data from a successful response', () => {
    expect(unwrap({ data: { open: true }, response: new Response(null, { status: 200 }) })).toEqual({ open: true });
  });

  it('throws ApiHttpError carrying status, body and headers', () => {
    const response = new Response(null, { status: 429, headers: { 'Retry-After': '7' } });
    const body = { error: { code: 'RATE_LIMITED', message: 'slow down' } };
    try {
      unwrap({ error: body, response });
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ApiHttpError);
      expect(e).toMatchObject({ status: 429, body });
      expect((e as ApiHttpError).headers.get('Retry-After')).toBe('7');
    }
  });
});

describe('ensureOk', () => {
  it('accepts a 204 and rejects a 403', () => {
    expect(() => ensureOk({ response: new Response(null, { status: 204 }) })).not.toThrow();
    expect(() => ensureOk({ response: new Response(null, { status: 403 }) })).toThrow(ApiHttpError);
  });
});

describe('toApiFailure', () => {
  it('maps an ApiHttpError to an http failure and anything else to network', () => {
    const http = new ApiHttpError(422, { error: {} }, new Headers());
    expect(toApiFailure(http)).toMatchObject({ kind: 'http', status: 422 });
    expect(toApiFailure(new TypeError('Failed to fetch'))).toEqual({ kind: 'network' });
  });
});

describe('parseRetryAfter', () => {
  it.each([
    ['7', 7],
    [' 30 ', 30],
    ['0', 0],
    [null, undefined],
    ['soon', undefined],
    ['-1', undefined],
    ['1.5', undefined],
  ])('parses %s as %s', (input, expected) => {
    expect(parseRetryAfter(input)).toBe(expected);
  });
});
```

Run: `pnpm test src/api/errors.test.ts` — Expected: FAIL (missing module). Then `frontend/src/api/errors.ts`:
```ts
export class ApiHttpError extends Error {
  readonly status: number;
  readonly body: unknown;
  readonly headers: Headers;

  constructor(status: number, body: unknown, headers: Headers) {
    super(`API request failed with status ${status}`);
    this.name = 'ApiHttpError';
    this.status = status;
    this.body = body;
    this.headers = headers;
  }
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** For hooks: a non-2xx (or a 2xx without a body where one is expected) becomes a thrown ApiHttpError. */
export function unwrap<T>(result: FetchResult<T>): T {
  if (result.response.ok && result.data !== undefined) return result.data;
  throw new ApiHttpError(result.response.status, result.error, result.response.headers);
}

/** For body-less successes such as 204. */
export function ensureOk(result: { error?: unknown; response: Response }): void {
  if (!result.response.ok) {
    throw new ApiHttpError(result.response.status, result.error, result.response.headers);
  }
}

export type ApiFailure =
  | { kind: 'http'; status: number; body: unknown; headers: Headers }
  | { kind: 'network' };

export function toApiFailure(error: unknown): ApiFailure {
  if (error instanceof ApiHttpError) {
    return { kind: 'http', status: error.status, body: error.body, headers: error.headers };
  }
  return { kind: 'network' };
}

/** Whole seconds, as RateLimitFilter emits them. Anything else is ignored. */
export function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined;
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  return Number(trimmed);
}
```
Run: `pnpm test src/api` — Expected: PASS.

- [ ] **Step 9: Write `client.ts`**

```ts
import createClient from 'openapi-fetch';
import { createAuthFetch, createBareFetch, type FetchLike } from './authFetch';
import type { paths } from './schema';

// An absolute base: openapi-fetch builds `new Request(url)`, which needs one outside a browser.
const origin = (): string => globalThis.location?.origin ?? 'http://localhost';

/** THE client every feature uses (spec §4.3). Nothing outside src/api may call fetch directly. */
export function createApiClient(fetchImpl?: FetchLike) {
  return createClient<paths>({ baseUrl: origin(), fetch: createAuthFetch({ fetchImpl }) });
}

/** Refresh and logout only — see createBareFetch. */
export function createBareClient(fetchImpl?: FetchLike) {
  return createClient<paths>({ baseUrl: origin(), fetch: createBareFetch({ fetchImpl }) });
}

export type ApiClient = ReturnType<typeof createApiClient>;

export const api = createApiClient();
export const bareApi = createBareClient();
```

- [ ] **Step 10: Typed MSW helper, fixtures, and a compile-time drift proof**

`frontend/src/test/openapiHttp.ts`:
```ts
import { createOpenApiHttp } from 'openapi-msw';
import type { paths } from '@/api/schema';

/**
 * Handlers typed from the generated contract (spec §6.3): a mock whose path, status or body the
 * contract does not allow fails `tsc`. Base `*` matches any origin.
 */
export const http = createOpenApiHttp<paths>({ baseUrl: '*' });
```

`frontend/src/test/openapiHttp.typecheck.ts`:
```ts
// Compile-time proof that openapiHttp is typed. If typing silently degraded to `any`, these
// @ts-expect-error lines would become unused and `tsc` would fail. Never executed.
import { http } from './openapiHttp';

// @ts-expect-error — not a path in the contract
http.get('/api/v1/not-a-route', ({ response }) => response(200).empty());

http.post('/api/v1/auth/refresh', ({ response }) =>
  // @ts-expect-error — accessToken must be a string
  response(200).json({ accessToken: 42, userId: 'u', tenantId: 't', tenantSlug: 's', email: 'e', role: 'OWNER' }),
);
```

`frontend/src/test/fixtures.ts`:
```ts
import type { AuthResponse } from '@/api/types';

export const ownerSession: AuthResponse = {
  accessToken: 'owner-access-1',
  userId: '11111111-1111-4111-8111-111111111111',
  tenantId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  tenantSlug: 'ravi-traders',
  email: 'ravi@shop.in',
  role: 'OWNER',
};

export const inviteeSession: AuthResponse = {
  accessToken: 'invitee-access-1',
  userId: '22222222-2222-4222-8222-222222222222',
  tenantId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  tenantSlug: 'ravi-traders',
  email: 'asha@shop.in',
  role: 'SALES_EXEC',
};

export function errorBody(
  code: string,
  message: string,
  extra: { fields?: Record<string, string>; fieldCodes?: Record<string, string> } = {},
) {
  return { error: { code, message, ...extra } };
}
```

- [ ] **Step 11: Typecheck, test, commit**

```bash
pnpm typecheck && pnpm test
cd .. && git add frontend
git commit -m "feat(frontend): generated API client with body-safe retry, client header and timeout"
```
Expected: typecheck clean (both `@ts-expect-error` lines are consumed); all tests PASS.

---

### Task 4: i18n plumbing, enforced by types and tests

**Files:**
- Create: `frontend/src/lib/i18n/index.ts`, `frontend/src/lib/i18n/i18next.d.ts`, `frontend/src/lib/i18n/translator.ts`, `frontend/src/lib/i18n/keys.typecheck.ts`, `frontend/src/lib/i18n/i18n.test.ts`
- Create: `frontend/src/locales/en/common.json`, `frontend/src/locales/en/auth.json`
- Create: `frontend/src/test/i18n.ts`
- Modify: `frontend/src/test/setup.ts`

**Interfaces:**
- Produces: `initI18n(instance?: i18n): Promise<unknown>`; `interface Translator { t(key: string, options?: Record<string, unknown>): string; exists(key: string): boolean }`; `useTranslator(): Translator`. Every locale key used by Tasks 7–12 is defined in this task's two JSON files — **later tasks add no keys without editing these files**. Tests run with a synchronously-initialised instance whose missing-key handler throws.

- [ ] **Step 1: Install**

```bash
cd frontend
pnpm add i18next react-i18next i18next-resources-to-backend
```

- [ ] **Step 2: Write the locale files**

`frontend/src/locales/en/common.json`:
```json
{
  "app": { "name": "EasyCRM" },
  "actions": { "retry": "Try again", "reload": "Reload page", "signOut": "Sign out" },
  "roles": { "OWNER": "Owner", "SALES_MANAGER": "Sales manager", "SALES_EXEC": "Sales executive" },
  "shell": {
    "nav": "Main",
    "homeTitle": "Home",
    "homeHeading": "Welcome",
    "signedInAs": "Signed in to {{slug}} as {{email}} ({{role}})"
  },
  "unreachable": {
    "title": "Can't reach EasyCRM",
    "heading": "Can't reach EasyCRM",
    "body": "Check your internet connection. We'll keep trying."
  },
  "signOutPending": {
    "title": "Signing out",
    "heading": "Sign-out did not complete — retrying",
    "body": "Keep this page open. This device is not signed out until this finishes."
  },
  "notFound": { "title": "Page not found", "heading": "Page not found", "home": "Go to home" },
  "routeError": {
    "title": "Something went wrong",
    "heading": "Something went wrong",
    "body": "Reload the page to try again."
  },
  "errors": {
    "network": "Can't reach EasyCRM. Check your connection and try again.",
    "server": "Something went wrong on our side. Try again.",
    "unexpected": "Something went wrong. Try again.",
    "rateLimited": "Too many attempts. Try again shortly.",
    "rateLimitedIn_one": "Too many attempts. Try again in {{count}} second.",
    "rateLimitedIn_other": "Too many attempts. Try again in {{count}} seconds.",
    "codes": {
      "FORBIDDEN": "You don't have permission to do that."
    },
    "fields": {
      "NOT_BLANK": "This field is required.",
      "SIZE": "This value has the wrong length.",
      "PATTERN": "This value is not in the expected format.",
      "EMAIL": "Enter a valid email address.",
      "GSTIN_REQUIRED": "Enter a GSTIN.",
      "GSTIN_LENGTH": "A GSTIN has 15 characters.",
      "GSTIN_CHARSET": "A GSTIN uses only digits and capital letters.",
      "GSTIN_CHECKSUM": "This GSTIN is not valid. Check it for typos.",
      "STATE_CODE_INVALID": "Choose a valid state.",
      "STATE_CODE_GSTIN_MISMATCH": "The state doesn't match the state in the GSTIN.",
      "SLUG_TAKEN": "This workspace name is taken."
    }
  },
  "validation": {
    "required": "This field is required.",
    "email": "Enter a valid email address.",
    "passwordMin": "Use at least 8 characters.",
    "slugFormat": "Use 3–64 lowercase letters, digits or hyphens.",
    "gstinShape": "A GSTIN has 15 digits and capital letters.",
    "stateRequired": "Choose your state."
  },
  "password": { "show": "Show password" }
}
```

`frontend/src/locales/en/auth.json`:
```json
{
  "login": {
    "title": "Sign in",
    "heading": "Sign in to EasyCRM",
    "workspace": "Workspace",
    "workspaceHint": "The workspace name you chose when you signed up.",
    "email": "Email",
    "password": "Password",
    "submit": "Sign in",
    "submitting": "Signing in…",
    "invalidCredentials": "Workspace, email or password is incorrect.",
    "noAccount": "New to EasyCRM? <link>Create a workspace</link>"
  },
  "signup": {
    "title": "Create a workspace",
    "heading": "Create your EasyCRM workspace",
    "loading": "Checking whether signups are open",
    "closedHeading": "Signups are currently closed",
    "closedBody": "Ask your business owner for an invitation, or <link>sign in</link>.",
    "businessName": "Business name",
    "slug": "Workspace name",
    "slugHint": "You'll use this to sign in. Lowercase letters, digits and hyphens.",
    "state": "State",
    "statePlaceholder": "Choose your state",
    "gstin": "GSTIN (optional)",
    "gstinHint": "15 characters. You can add it later.",
    "email": "Email",
    "phone": "Phone (optional)",
    "password": "Password",
    "passwordHint": "At least 8 characters.",
    "submit": "Create workspace",
    "submitting": "Creating…",
    "maybeCreated": "This workspace may already have been created. <link>Sign in</link>",
    "haveAccount": "Already have a workspace? <link>Sign in</link>"
  },
  "invite": {
    "title": "Join a workspace",
    "heading": "Join a workspace",
    "loading": "Loading invitation",
    "invalid": "This invitation link is invalid or has expired.",
    "join": "Join <strong>{{businessName}}</strong> as <strong>{{role}}</strong>",
    "email": "Email",
    "password": "Choose a password",
    "passwordHint": "At least 8 characters.",
    "phone": "Phone (optional)",
    "submit": "Join workspace",
    "submitting": "Joining…",
    "signedInAs": "This invitation is for <strong>{{inviteEmail}}</strong> to join <strong>{{businessName}}</strong>. You're signed in as <strong>{{email}}</strong>.",
    "signOutAndAccept": "Sign out and accept",
    "goToWorkspace": "Go to my workspace",
    "maybeAccepted": "If you already set your password, <link>sign in</link>."
  }
}
```

- [ ] **Step 3: Typed keys and the compile-time proof**

`frontend/src/lib/i18n/i18next.d.ts`:
```ts
import 'i18next';
import type auth from '../../locales/en/auth.json';
import type common from '../../locales/en/common.json';

// Spec §4.7: an unknown key fails `tsc`. `en` is the source of truth for key shape.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: { common: typeof common; auth: typeof auth };
  }
}
```

`frontend/src/lib/i18n/keys.typecheck.ts`:
```ts
// Never executed. If key typing silently degraded to `string`, the @ts-expect-error below would be
// unused and `tsc` would fail — so this file is the red fixture for "unknown key fails tsc".
import i18next from 'i18next';

i18next.t('actions.retry');
i18next.t('login.heading', { ns: 'auth' });
// @ts-expect-error — not a key in common.json
i18next.t('actions.doesNotExist');
```

- [ ] **Step 4: Write the failing runtime tests**

`frontend/src/lib/i18n/i18n.test.ts`:
```ts
import i18next from 'i18next';
import { describe, expect, it } from 'vitest';
import { initI18n } from './index';

describe('initI18n', () => {
  it('lazy-loads English resources per namespace and sets <html lang>', async () => {
    const instance = i18next.createInstance();
    document.documentElement.lang = '';

    await initI18n(instance);

    expect(instance.t('app.name')).toBe('EasyCRM');
    expect(instance.t('login.heading', { ns: 'auth' })).toBe('Sign in to EasyCRM');
    expect(document.documentElement.lang).toBe('en');
  });
});

describe('test i18n instance', () => {
  it('throws on a missing key instead of rendering the key', () => {
    expect(() => i18next.t('actions.nope' as never)).toThrow(/Missing i18n key/);
  });
});
```

Run: `pnpm test src/lib/i18n` — Expected: FAIL (`./index` missing).

- [ ] **Step 5: Implement**

`frontend/src/lib/i18n/index.ts`:
```ts
import i18next, { type i18n } from 'i18next';
import resourcesToBackend from 'i18next-resources-to-backend';
import { initReactI18next } from 'react-i18next';

export const NAMESPACES = ['common', 'auth'] as const;

/**
 * Spec §4.7. Each language/namespace is its own dynamic import, so a future `hi` adds nothing to the
 * English user's bundle.
 */
export function initI18n(instance: i18n = i18next): Promise<unknown> {
  instance.on('languageChanged', (lng) => {
    document.documentElement.lang = lng;
  });
  return instance
    .use(initReactI18next)
    .use(resourcesToBackend((lng: string, ns: string) => import(`../../locales/${lng}/${ns}.json`)))
    .init({
      lng: 'en',
      supportedLngs: ['en'],
      fallbackLng: 'en',
      ns: [...NAMESPACES],
      defaultNS: 'common',
      interpolation: { escapeValue: false },
    });
}
```

`frontend/src/lib/i18n/translator.ts`:
```ts
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * A deliberately untyped view of i18next for keys built at runtime from server codes
 * (`errors.fields.${code}`). Static UI text uses the typed `t` from useTranslation instead.
 */
export interface Translator {
  t(key: string, options?: Record<string, unknown>): string;
  exists(key: string): boolean;
}

export function useTranslator(): Translator {
  const { i18n } = useTranslation();
  return useMemo(() => {
    const untypedT = i18n.t as unknown as (key: string, options?: Record<string, unknown>) => string;
    return { t: (key, options) => untypedT(key, options), exists: (key) => i18n.exists(key) };
  }, [i18n]);
}
```

`frontend/src/test/i18n.ts`:
```ts
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import auth from '@/locales/en/auth.json';
import common from '@/locales/en/common.json';

// Synchronous, bundled resources for tests; a missing key throws (spec §4.7) so a typo'd runtime
// key (e.g. from a server code) fails the test that renders it.
void i18next.use(initReactI18next).init({
  lng: 'en',
  fallbackLng: 'en',
  ns: ['common', 'auth'],
  defaultNS: 'common',
  resources: { en: { common, auth } },
  initAsync: false,
  interpolation: { escapeValue: false },
  saveMissing: true,
  missingKeyHandler: (_lngs, ns, key) => {
    throw new Error(`Missing i18n key: ${ns}:${key}`);
  },
});
```
(If `tsc` reports `initAsync` as unknown, the installed i18next predates v24 — use `initImmediate: false` instead and note the version in the ledger.)

Add as the **first** line of `frontend/src/test/setup.ts`:
```ts
import './i18n';
```

- [ ] **Step 6: Run and commit**

```bash
pnpm test && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): i18n with lazy per-namespace resources, typed keys and a throwing test instance"
```
Expected: PASS; typecheck clean (the `@ts-expect-error` is consumed).

---

### Task 5: Session core — state, tokens, locks, the coordinator

**Files:**
- Create in `frontend/src/features/auth/session/`: `sessionStore.ts`, `accessToken.ts`, `toMe.ts`, `lockProvider.ts`, `authChannel.ts`, `sessionEvents.ts`, `runtime.ts`, `session.ts`, `refreshCall.ts`, `refreshCoordinator.ts`, `bridge.ts`
- Create tests: `session.test.ts`, `refreshCall.test.ts`, `refreshCoordinator.test.ts`, `lockProvider.test.ts` (same folder)
- Create: `frontend/src/lib/storage.ts`, `frontend/src/lib/storage.test.ts`
- Modify: `frontend/src/test/setup.ts`

**Interfaces:**
- Consumes: `api/authBridge.ts`, `api/client.ts` (`bareApi`, `createBareClient`), `api/errors.ts` (`parseRetryAfter`), `api/types.ts` (`AuthResponse`).
- Produces:
  - `sessionStore.ts`: `ROLES`, `type Role`, `interface Me { userId; tenantId; tenantSlug; email; role: Role }`, `type SessionStatus = 'booting' | 'authenticated' | 'anonymous' | 'unreachable' | 'signing-out'`, `useSessionStore` (Zustand, state `{ status, me }`), `resetSessionStore()`.
  - `accessToken.ts`: `getAccessToken()`, `setAccessToken(t)`, `clearAccessToken()`.
  - `toMe.ts`: `isRole(v: string): v is Role`, `toMe(r: AuthResponse): Me`.
  - `lockProvider.ts`: `REFRESH_LOCK = 'easycrm-refresh'`, `interface LockProvider { withLock<T>(name: string, fn: () => Promise<T>): Promise<T> }`, `webLocks`, `createInMemoryLocks()`, `noopLocks`, `supportsWebLocks()`.
  - `authChannel.ts`: `type AuthMessage`, `interface AuthChannel { post(m); subscribe(l): () => void }`, `createAuthChannel()`, `createNoopChannel()`.
  - `sessionEvents.ts`: `emitSessionExpired()`, `onSessionExpired(l): () => void`.
  - `runtime.ts`: `interface SessionRuntime { clearQueryCache(): void; channel: AuthChannel; locks: LockProvider; reload(): void; log(message: string): void }`, `configureSession(r)`, `sessionRuntime()`.
  - `session.ts`: `type EndReason = 'logout' | 'expired' | 'principal-changed' | 'remote-logout'`, `type EstablishResult = 'established' | 'principal-changed'`, `establishSession(r: AuthResponse): EstablishResult`, `endSession(reason: EndReason): void`, `subscribeToAuthChannel(): () => void`.
  - `refreshCall.ts`: `type RefreshCallResult = { kind: 'ok'; body: AuthResponse } | { kind: 'unauthorized' } | { kind: 'forbidden' } | { kind: 'unavailable'; retryAfterSeconds?: number }`, `callRefresh(client?): Promise<RefreshCallResult>`, `REFRESH_FORBIDDEN_MESSAGE`.
  - `refreshCoordinator.ts`: `interface RefreshCoordinator { refresh(tokenAtFailure: string | null): Promise<RefreshOutcome> }`, `createRefreshCoordinator(deps)`.
  - `bridge.ts`: `createSessionAuthBridge(coordinator): AuthBridge`.
  - `lib/storage.ts`: `readLastWorkspace(): string | null`, `writeLastWorkspace(slug: string): void`.

- [ ] **Step 1: Install Zustand**

```bash
cd frontend && pnpm add zustand
```

- [ ] **Step 2: `lib/storage.ts`, test first**

`frontend/src/lib/storage.test.ts`:
```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readLastWorkspace, writeLastWorkspace } from './storage';

describe('remembered workspace', () => {
  afterEach(() => localStorage.clear());

  it('round-trips through localStorage under easycrm.lastWorkspace', () => {
    writeLastWorkspace('ravi-traders');
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
    expect(readLastWorkspace()).toBe('ravi-traders');
  });

  it('means "nothing remembered" when storage calls throw', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    expect(() => writeLastWorkspace('x')).not.toThrow();
    expect(readLastWorkspace()).toBeNull();
  });

  it('means "nothing remembered" when even reading window.localStorage throws', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
    });
    try {
      expect(readLastWorkspace()).toBeNull();
      expect(() => writeLastWorkspace('x')).not.toThrow();
    } finally {
      if (original) Object.defineProperty(window, 'localStorage', original);
    }
  });
});
```

Run: `pnpm test src/lib/storage.test.ts` — Expected: FAIL (missing module). Then `frontend/src/lib/storage.ts`:
```ts
const LAST_WORKSPACE = 'easycrm.lastWorkspace';

// Every access is wrapped (spec §4.8): private modes, blocked site data and full quotas all throw, and
// any of them means "nothing remembered", never a crash.
export function readLastWorkspace(): string | null {
  try {
    return globalThis.localStorage.getItem(LAST_WORKSPACE);
  } catch {
    return null;
  }
}

export function writeLastWorkspace(slug: string): void {
  try {
    globalThis.localStorage.setItem(LAST_WORKSPACE, slug);
  } catch {
    // nothing remembered
  }
}
```
Run again — Expected: PASS.

- [ ] **Step 3: Write the small session modules**

`sessionStore.ts`:
```ts
import { create } from 'zustand';

export const ROLES = ['OWNER', 'SALES_MANAGER', 'SALES_EXEC'] as const;
export type Role = (typeof ROLES)[number];

export interface Me {
  userId: string;
  tenantId: string;
  tenantSlug: string;
  email: string;
  role: Role;
}

/** Spec §4.4's four statuses plus `signing-out` (plan decision P5). */
export type SessionStatus = 'booting' | 'authenticated' | 'anonymous' | 'unreachable' | 'signing-out';

interface SessionState {
  status: SessionStatus;
  /** Changes ONLY through establishSession and endSession. */
  me: Me | null;
}

const initial: SessionState = { status: 'booting', me: null };

export const useSessionStore = create<SessionState>()(() => initial);

export function resetSessionStore(): void {
  useSessionStore.setState(initial, true);
}
```

`accessToken.ts`:
```ts
// Module-scoped, never persisted (spec §4.4, F0-13): nothing survives in storage for XSS to read later.
let token: string | null = null;

export function getAccessToken(): string | null {
  return token;
}

export function setAccessToken(value: string): void {
  token = value;
}

export function clearAccessToken(): void {
  token = null;
}
```

`toMe.ts`:
```ts
import type { AuthResponse } from '@/api/types';
import { ROLES, type Me, type Role } from './sessionStore';

export function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/** The one place a session response is narrowed (spec §4.4: no scattered assertions). */
export function toMe(response: AuthResponse): Me {
  if (!isRole(response.role)) throw new Error(`unknown role from server: ${response.role}`);
  return {
    userId: response.userId,
    tenantId: response.tenantId,
    tenantSlug: response.tenantSlug,
    email: response.email,
    role: response.role,
  };
}
```

`lockProvider.ts`:
```ts
export const REFRESH_LOCK = 'easycrm-refresh';

/** Exclusive, held until fn's promise settles — the semantics of navigator.locks.request. */
export interface LockProvider {
  withLock<T>(name: string, fn: () => Promise<T>): Promise<T>;
}

export const webLocks: LockProvider = {
  withLock: (name, fn) => navigator.locks.request(name, { mode: 'exclusive' }, fn),
};

export function supportsWebLocks(): boolean {
  return typeof navigator !== 'undefined' && 'locks' in navigator;
}

/** Same semantics within one JS realm. Tests, and the per-tab fallback when Web Locks are missing. */
export function createInMemoryLocks(): LockProvider {
  const tails = new Map<string, Promise<unknown>>();
  return {
    withLock<T>(name: string, fn: () => Promise<T>): Promise<T> {
      const previous = tails.get(name) ?? Promise.resolve();
      const run = previous.then(fn, fn);
      tails.set(
        name,
        run.catch(() => undefined),
      );
      return run;
    },
  };
}

/** Deliberately broken: used ONLY for Task 15's recorded red run. Never bind it in production. */
export const noopLocks: LockProvider = { withLock: (_name, fn) => fn() };
```

`authChannel.ts`:
```ts
export type AuthMessage = { type: 'login'; userId: string; tenantId: string } | { type: 'logout' };

export interface AuthChannel {
  post(message: AuthMessage): void;
  subscribe(listener: (message: AuthMessage) => void): () => void;
}

export const AUTH_CHANNEL = 'easycrm-auth';

export function createAuthChannel(): AuthChannel {
  if (typeof BroadcastChannel === 'undefined') return createNoopChannel();
  const channel = new BroadcastChannel(AUTH_CHANNEL);
  return {
    post: (message) => channel.postMessage(message),
    subscribe: (listener) => {
      const handler = (event: MessageEvent<AuthMessage>) => listener(event.data);
      channel.addEventListener('message', handler);
      return () => channel.removeEventListener('message', handler);
    },
  };
}

export function createNoopChannel(): AuthChannel {
  return { post: () => {}, subscribe: () => () => {} };
}
```

`sessionEvents.ts`:
```ts
// The HTTP layer never calls the router (spec §4.4): it raises this event, and the router listens, so
// F2 can swap navigation for a re-login dialog that preserves an in-progress quotation.
const target = new EventTarget();
const EXPIRED = 'session-expired';

export function emitSessionExpired(): void {
  target.dispatchEvent(new Event(EXPIRED));
}

export function onSessionExpired(listener: () => void): () => void {
  target.addEventListener(EXPIRED, listener);
  return () => target.removeEventListener(EXPIRED, listener);
}
```

`runtime.ts`:
```ts
import type { AuthChannel } from './authChannel';
import type { LockProvider } from './lockProvider';

/** Everything the session needs from outside the feature, injected by app/bootstrap (or a test). */
export interface SessionRuntime {
  clearQueryCache(): void;
  channel: AuthChannel;
  locks: LockProvider;
  reload(): void;
  log(message: string): void;
}

let runtime: SessionRuntime | null = null;

export function configureSession(value: SessionRuntime): void {
  runtime = value;
}

export function sessionRuntime(): SessionRuntime {
  if (!runtime) throw new Error('configureSession() has not run');
  return runtime;
}
```

- [ ] **Step 4: Write the failing `session.ts` tests**

`session.test.ts`:
```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { inviteeSession, ownerSession } from '@/test/fixtures';
import { getAccessToken } from './accessToken';
import type { AuthChannel, AuthMessage } from './authChannel';
import { createInMemoryLocks } from './lockProvider';
import { configureSession, type SessionRuntime } from './runtime';
import { endSession, establishSession, subscribeToAuthChannel } from './session';
import { useSessionStore } from './sessionStore';

function fakeRuntime() {
  let listener: ((m: AuthMessage) => void) | null = null;
  const channel = {
    post: vi.fn(),
    subscribe: vi.fn((l: (m: AuthMessage) => void) => {
      listener = l;
      return () => {
        listener = null;
      };
    }),
  } satisfies AuthChannel;
  const runtime = {
    clearQueryCache: vi.fn(),
    channel,
    locks: createInMemoryLocks(),
    reload: vi.fn(),
    log: vi.fn(),
  } satisfies SessionRuntime;
  configureSession(runtime);
  return { runtime, deliver: (m: AuthMessage) => listener?.(m) };
}

describe('establishSession', () => {
  beforeEach(() => void fakeRuntime());

  it('stores the token, sets me, remembers the workspace and broadcasts login', () => {
    const { runtime } = fakeRuntime();

    expect(establishSession(ownerSession)).toBe('established');

    expect(getAccessToken()).toBe(ownerSession.accessToken);
    expect(useSessionStore.getState()).toMatchObject({
      status: 'authenticated',
      me: { email: 'ravi@shop.in', role: 'OWNER', tenantSlug: 'ravi-traders' },
    });
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
    expect(runtime.channel.post).toHaveBeenCalledWith({
      type: 'login',
      userId: ownerSession.userId,
      tenantId: ownerSession.tenantId,
    });
  });

  it('ends the session and reloads when a refresh returns a different principal', () => {
    const { runtime } = fakeRuntime();
    establishSession(ownerSession);

    expect(establishSession(inviteeSession)).toBe('principal-changed');

    expect(getAccessToken()).toBeNull();
    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
    expect(runtime.clearQueryCache).toHaveBeenCalled();
    expect(runtime.reload).toHaveBeenCalledTimes(1);
  });

  it('rejects an unknown role rather than storing it', () => {
    expect(() => establishSession({ ...ownerSession, role: 'ADMIN' })).toThrow(/unknown role/);
    expect(getAccessToken()).toBeNull();
  });
});

describe('endSession', () => {
  it('clears the token, resets the store and clears the query cache', () => {
    const { runtime } = fakeRuntime();
    establishSession(ownerSession);

    endSession('logout');

    expect(getAccessToken()).toBeNull();
    expect(useSessionStore.getState()).toMatchObject({ status: 'anonymous', me: null });
    expect(runtime.clearQueryCache).toHaveBeenCalledTimes(1);
  });
});

describe('subscribeToAuthChannel', () => {
  it('ends this tab’s session when another tab logs out', () => {
    const { deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'logout' });

    expect(useSessionStore.getState().status).toBe('anonymous');
  });

  it('ends and reloads when another tab signs in as someone else', () => {
    const { runtime, deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'login', userId: inviteeSession.userId, tenantId: inviteeSession.tenantId });

    expect(useSessionStore.getState().me).toBeNull();
    expect(runtime.reload).toHaveBeenCalledTimes(1);
  });

  it('ignores a login broadcast for the same principal', () => {
    const { runtime, deliver } = fakeRuntime();
    subscribeToAuthChannel();
    establishSession(ownerSession);

    deliver({ type: 'login', userId: ownerSession.userId, tenantId: ownerSession.tenantId });

    expect(useSessionStore.getState().status).toBe('authenticated');
    expect(runtime.reload).not.toHaveBeenCalled();
  });
});
```

Add to `frontend/src/test/setup.ts` inside the existing `afterEach` (and the imports at the top):
```ts
import { clearAccessToken } from '@/features/auth/session/accessToken';
import { resetSessionStore } from '@/features/auth/session/sessionStore';
// ...in afterEach, after server.resetHandlers():
  resetSessionStore();
  clearAccessToken();
  localStorage.clear();
```

Run: `pnpm test src/features/auth/session/session.test.ts` — Expected: FAIL (`./session` missing).

- [ ] **Step 5: Implement `session.ts` and `bridge.ts`**

`session.ts`:
```ts
import type { AuthResponse } from '@/api/types';
import { writeLastWorkspace } from '@/lib/storage';
import { clearAccessToken, setAccessToken } from './accessToken';
import { sessionRuntime } from './runtime';
import { useSessionStore } from './sessionStore';
import { toMe } from './toMe';

export type EndReason = 'logout' | 'expired' | 'principal-changed' | 'remote-logout';
export type EstablishResult = 'established' | 'principal-changed';

/** Boot, login, signup, accept and every refresh go through here (spec §4.4). */
export function establishSession(response: AuthResponse): EstablishResult {
  const next = toMe(response);
  const { me } = useSessionStore.getState();
  if (me && (me.userId !== next.userId || me.tenantId !== next.tenantId)) {
    // The cookie jar is shared by every tab: another tab signed in as someone else, and this tab's
    // refresh picked up their cookie. Nothing cached for the previous principal may survive.
    endSession('principal-changed');
    sessionRuntime().reload();
    return 'principal-changed';
  }
  setAccessToken(response.accessToken);
  useSessionStore.setState({ status: 'authenticated', me: next });
  writeLastWorkspace(next.tenantSlug);
  sessionRuntime().channel.post({ type: 'login', userId: next.userId, tenantId: next.tenantId });
  return 'established';
}

/** EVERY way a session ends goes through here (spec §4.4). */
export function endSession(_reason: EndReason): void {
  clearAccessToken();
  useSessionStore.setState({ status: 'anonymous', me: null });
  sessionRuntime().clearQueryCache();
}

export function subscribeToAuthChannel(): () => void {
  return sessionRuntime().channel.subscribe((message) => {
    const { me } = useSessionStore.getState();
    if (!me) return;
    if (message.type === 'logout') {
      endSession('remote-logout');
      return;
    }
    if (me.userId !== message.userId || me.tenantId !== message.tenantId) {
      endSession('principal-changed');
      sessionRuntime().reload();
    }
  });
}
```

`bridge.ts`:
```ts
import type { AuthBridge } from '@/api/authBridge';
import { getAccessToken } from './accessToken';
import type { RefreshCoordinator } from './refreshCoordinator';
import { endSession } from './session';
import { emitSessionExpired } from './sessionEvents';

export function createSessionAuthBridge(coordinator: RefreshCoordinator): AuthBridge {
  return {
    getAccessToken,
    refresh: (tokenAtFailure) => coordinator.refresh(tokenAtFailure),
    sessionExpired: () => {
      endSession('expired');
      emitSessionExpired();
    },
  };
}
```

Run: `pnpm test src/features/auth/session/session.test.ts` — Expected: PASS. (`bridge.ts` compiles once `refreshCoordinator.ts` exists — Step 7.)

- [ ] **Step 6: `refreshCall.ts`, test first**

`refreshCall.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest';
import { createBareClient } from '@/api/client';
import { ownerSession } from '@/test/fixtures';
import { callRefresh, type RefreshCallResult } from './refreshCall';

function clientAnswering(response: () => Response) {
  const seen: Request[] = [];
  const client = createBareClient(async (request) => {
    seen.push(request);
    return response();
  });
  return { client, seen };
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

describe('callRefresh', () => {
  it('sends X-EasyCRM-Client: web from the bare client', async () => {
    const { client, seen } = clientAnswering(() => json(200, ownerSession));

    await callRefresh(client);

    expect(seen[0]?.method).toBe('POST');
    expect(new URL(seen[0]?.url ?? '').pathname).toBe('/api/v1/auth/refresh');
    expect(seen[0]?.headers.get('X-EasyCRM-Client')).toBe('web');
    expect(seen[0]?.headers.has('Authorization')).toBe(false);
  });

  it.each<[string, () => Response, RefreshCallResult]>([
    ['200', () => json(200, ownerSession), { kind: 'ok', body: ownerSession }],
    ['401', () => json(401, { error: { code: 'UNAUTHORIZED', message: 'x' } }), { kind: 'unauthorized' }],
    ['403', () => json(403, { error: { code: 'FORBIDDEN', message: 'x' } }), { kind: 'forbidden' }],
    [
      '429',
      () => json(429, { error: { code: 'RATE_LIMITED', message: 'x' } }, { 'Retry-After': '7' }),
      { kind: 'unavailable', retryAfterSeconds: 7 },
    ],
    ['503', () => new Response(null, { status: 503 }), { kind: 'unavailable', retryAfterSeconds: undefined }],
  ])('maps %s', async (_label, response, expected) => {
    const { client } = clientAnswering(response);
    expect(await callRefresh(client)).toEqual(expected);
  });

  it('maps a network failure to unavailable', async () => {
    const client = createBareClient(vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))));
    expect(await callRefresh(client)).toEqual({ kind: 'unavailable' });
  });
});
```

Run — Expected: FAIL (missing module). Then `refreshCall.ts`:
```ts
import { bareApi } from '@/api/client';
import type * as Client from '@/api/client';
import { parseRetryAfter } from '@/api/errors';
import type { AuthResponse } from '@/api/types';

export type RefreshCallResult =
  | { kind: 'ok'; body: AuthResponse }
  | { kind: 'unauthorized' }
  | { kind: 'forbidden' }
  | { kind: 'unavailable'; retryAfterSeconds?: number };

export const REFRESH_FORBIDDEN_MESSAGE =
  'POST /api/v1/auth/refresh answered 403: the X-EasyCRM-Client header was not sent. This is a client bug, not a signed-out user.';

export async function callRefresh(
  client: ReturnType<typeof Client.createBareClient> = bareApi,
): Promise<RefreshCallResult> {
  try {
    // The contract marks the header required, so this call does not compile without it (plan P3).
    const { data, response } = await client.POST('/api/v1/auth/refresh', {
      params: { header: { 'X-EasyCRM-Client': 'web' } },
    });
    if (response.status === 200 && data) return { kind: 'ok', body: data };
    if (response.status === 401) return { kind: 'unauthorized' };
    if (response.status === 403) return { kind: 'forbidden' };
    return { kind: 'unavailable', retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')) };
  } catch {
    return { kind: 'unavailable' };
  }
}
```
Run — Expected: PASS.

- [ ] **Step 7: The refresh coordinator, test first**

`refreshCoordinator.test.ts`:
```ts
// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { createInMemoryLocks, type LockProvider } from './lockProvider';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import { createRefreshCoordinator } from './refreshCoordinator';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

function coordinator(opts: {
  locks?: LockProvider;
  token?: () => string | null;
  call: () => Promise<RefreshCallResult>;
  established?: boolean;
}) {
  const deps = {
    locks: opts.locks ?? createInMemoryLocks(),
    callRefresh: vi.fn(opts.call),
    getToken: opts.token ?? (() => 'token-1'),
    onRefreshed: vi.fn(() => (opts.established === false ? 'principal-changed' : 'established') as const),
    onUnauthorized: vi.fn(),
    log: vi.fn(),
  };
  return { deps, coordinator: createRefreshCoordinator(deps) };
}

describe('refresh coordinator', () => {
  it('shares one in-flight refresh between concurrent callers in a tab', async () => {
    const gate = deferred<RefreshCallResult>();
    const { deps, coordinator: c } = coordinator({ call: () => gate.promise });

    const first = c.refresh('token-1');
    const second = c.refresh('token-1');
    gate.resolve({ kind: 'ok', body: ownerSession });

    expect(await first).toBe('refreshed');
    expect(await second).toBe('refreshed');
    expect(deps.callRefresh).toHaveBeenCalledTimes(1);
  });

  it('serializes refreshes across tabs that share a lock', async () => {
    const locks = createInMemoryLocks();
    let inFlight = 0;
    let maxInFlight = 0;
    const call = async (): Promise<RefreshCallResult> => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((r) => setTimeout(r, 10));
      inFlight -= 1;
      return { kind: 'ok', body: ownerSession };
    };
    const tabA = coordinator({ locks, call }).coordinator;
    const tabB = coordinator({ locks, call }).coordinator;

    await Promise.all([tabA.refresh('token-1'), tabB.refresh('token-1')]);

    expect(maxInFlight).toBe(1);
  });

  it('does not refresh when the token already changed since the failing request', async () => {
    const { deps, coordinator: c } = coordinator({
      token: () => 'token-2',
      call: async () => ({ kind: 'ok', body: ownerSession }),
    });

    expect(await c.refresh('token-1')).toBe('refreshed');
    expect(deps.callRefresh).not.toHaveBeenCalled();
  });

  it('ends the session on 401', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'unauthorized' }) });

    expect(await c.refresh('token-1')).toBe('ended');
    expect(deps.onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('keeps the session on a network, 5xx or 429 failure', async () => {
    const { deps, coordinator: c } = coordinator({
      call: async () => ({ kind: 'unavailable', retryAfterSeconds: 5 }),
    });

    expect(await c.refresh('token-1')).toBe('unavailable');
    expect(deps.onUnauthorized).not.toHaveBeenCalled();
  });

  it('treats 403 as a logged client bug, never as a signed-out user', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'forbidden' }) });

    expect(await c.refresh('token-1')).toBe('unavailable');
    expect(deps.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
    expect(deps.onUnauthorized).not.toHaveBeenCalled();
  });

  it('reports ended when the refreshed principal differs', async () => {
    const { coordinator: c } = coordinator({
      call: async () => ({ kind: 'ok', body: ownerSession }),
      established: false,
    });

    expect(await c.refresh('token-1')).toBe('ended');
  });

  it('refreshes again after the previous refresh settled', async () => {
    const { deps, coordinator: c } = coordinator({ call: async () => ({ kind: 'ok', body: ownerSession }) });

    await c.refresh('token-1');
    await c.refresh('token-1');

    expect(deps.callRefresh).toHaveBeenCalledTimes(2);
  });
});
```

Run — Expected: FAIL (missing module). Then `refreshCoordinator.ts`:
```ts
import type { RefreshOutcome } from '@/api/authBridge';
import type { AuthResponse } from '@/api/types';
import { REFRESH_LOCK, type LockProvider } from './lockProvider';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { EstablishResult } from './session';

export interface RefreshCoordinator {
  refresh(tokenAtFailure: string | null): Promise<RefreshOutcome>;
}

export interface RefreshCoordinatorDeps {
  locks: LockProvider;
  callRefresh(): Promise<RefreshCallResult>;
  getToken(): string | null;
  onRefreshed(body: AuthResponse): EstablishResult;
  onUnauthorized(): void;
  log(message: string): void;
}

/**
 * Spec §4.4. Web Locks serialization is load-bearing: two concurrent refreshes of one cookie both
 * succeed (the second through the grace window) and the later one revokes the earlier successor, so
 * whichever Set-Cookie lands last-but-not-latest leaves every tab holding a dead cookie.
 */
export function createRefreshCoordinator(deps: RefreshCoordinatorDeps): RefreshCoordinator {
  let inFlight: Promise<RefreshOutcome> | null = null;

  async function underLock(tokenAtFailure: string | null): Promise<RefreshOutcome> {
    const current = deps.getToken();
    if (current !== null && current !== tokenAtFailure) return 'refreshed';
    const result = await deps.callRefresh();
    switch (result.kind) {
      case 'ok':
        return deps.onRefreshed(result.body) === 'established' ? 'refreshed' : 'ended';
      case 'unauthorized':
        deps.onUnauthorized();
        return 'ended';
      case 'forbidden':
        deps.log(REFRESH_FORBIDDEN_MESSAGE);
        return 'unavailable';
      case 'unavailable':
        return 'unavailable';
    }
  }

  return {
    refresh(tokenAtFailure) {
      inFlight ??= deps.locks
        .withLock(REFRESH_LOCK, () => underLock(tokenAtFailure))
        .finally(() => {
          inFlight = null;
        });
      return inFlight;
    },
  };
}
```

- [ ] **Step 8: Prove the in-memory lock is exclusive**

`lockProvider.test.ts`:
```ts
// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { createInMemoryLocks, noopLocks } from './lockProvider';

describe('lock providers', () => {
  it('in-memory locks run holders one at a time, even when a holder throws', async () => {
    const locks = createInMemoryLocks();
    const order: string[] = [];
    const a = locks.withLock('x', async () => {
      order.push('a-start');
      await new Promise((r) => setTimeout(r, 5));
      order.push('a-end');
      throw new Error('a failed');
    });
    const b = locks.withLock('x', async () => {
      order.push('b');
      return 'b-result';
    });

    await expect(a).rejects.toThrow('a failed');
    expect(await b).toBe('b-result');
    expect(order).toEqual(['a-start', 'a-end', 'b']);
  });

  it('the no-op provider really is not exclusive (it exists only for the recorded red run)', async () => {
    const order: string[] = [];
    await Promise.all([
      noopLocks.withLock('x', async () => {
        order.push('a-start');
        await new Promise((r) => setTimeout(r, 5));
        order.push('a-end');
      }),
      noopLocks.withLock('x', async () => {
        order.push('b');
      }),
    ]);
    expect(order).toEqual(['a-start', 'b', 'a-end']);
  });
});
```

- [ ] **Step 9: Run everything and commit**

```bash
pnpm test && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): session store, establish/end, Web-Locks refresh coordinator and auth channel"
```
Expected: PASS; typecheck clean.

---

### Task 6: Boot and logout

**Files:**
- Create: `frontend/src/features/auth/session/boot.ts`, `boot.test.ts`, `logout.ts`, `logout.test.ts`, `start.ts`
- Modify: `frontend/src/test/setup.ts`

**Interfaces:**
- Consumes: Task 5's modules.
- Produces:
  - `boot.ts`: `RETRY_SCHEDULE_MS = [2000, 5000, 15000, 30000]`, `nextBootDelayMs(attempt: number, retryAfterSeconds?: number): number`, `interface Boot { start(): Promise<void>; retryNow(): Promise<void>; stop(): void }`, `createBoot(deps: BootDeps): Boot`.
  - `logout.ts`: `LOGOUT_RETRY_MS = 5000`, `callLogout(client?): Promise<'done' | 'failed'>`, `createLogout(deps): { logout(): Promise<void>; stop(): void }`.
  - `start.ts`: `interface SessionControls { boot: Boot; logout(): Promise<void>; stop(): void }`, `startSession(runtime: SessionRuntime, options?: { autoBoot?: boolean }): SessionControls`, `sessionControls(): SessionControls`, `stopSession(): void`.

- [ ] **Step 1: Write the failing boot tests**

`boot.test.ts`:
```ts
// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ownerSession } from '@/test/fixtures';
import { createBoot, nextBootDelayMs } from './boot';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { SessionStatus } from './sessionStore';

function harness(results: RefreshCallResult[]) {
  let status: SessionStatus = 'booting';
  const scheduled: { fn: () => void; ms: number; cancel: ReturnType<typeof vi.fn> }[] = [];
  const deps = {
    refresh: vi.fn(async () => results.shift() ?? { kind: 'unavailable' as const }),
    establish: vi.fn(() => {
      status = 'authenticated';
    }),
    getStatus: () => status,
    setStatus: vi.fn((s: SessionStatus) => {
      status = s;
    }),
    schedule: vi.fn((fn: () => void, ms: number) => {
      const cancel = vi.fn();
      scheduled.push({ fn, ms, cancel });
      return cancel;
    }),
    log: vi.fn(),
  };
  return { deps, boot: createBoot(deps), scheduled, status: () => status, setStatus: (s: SessionStatus) => (status = s) };
}

describe('nextBootDelayMs (plan decision P2)', () => {
  it('retries first within 5 s — inside the 30 s grace window — then backs off', () => {
    expect([0, 1, 2, 3, 4, 9].map((a) => nextBootDelayMs(a))).toEqual([2000, 5000, 15000, 30000, 30000, 30000]);
  });

  it('lets a 429 Retry-After win, clamped to 1–60 s', () => {
    expect(nextBootDelayMs(0, 20)).toBe(20_000);
    expect(nextBootDelayMs(0, 0)).toBe(1_000);
    expect(nextBootDelayMs(0, 600)).toBe(60_000);
  });
});

describe('boot', () => {
  it('establishes the session on 200', async () => {
    const h = harness([{ kind: 'ok', body: ownerSession }]);
    await h.boot.start();
    expect(h.deps.establish).toHaveBeenCalledWith(ownerSession);
  });

  it('is anonymous on 401', async () => {
    const h = harness([{ kind: 'unauthorized' }]);
    await h.boot.start();
    expect(h.status()).toBe('anonymous');
  });

  it('treats 403 as a client bug: logged, unreachable, never anonymous, no automatic retry', async () => {
    const h = harness([{ kind: 'forbidden' }]);
    await h.boot.start();
    expect(h.deps.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
    expect(h.status()).toBe('unreachable');
    expect(h.deps.schedule).not.toHaveBeenCalled();
  });

  it('is unreachable on a network failure and retries automatically, first after 2 s', async () => {
    const h = harness([{ kind: 'unavailable' }, { kind: 'unavailable' }, { kind: 'ok', body: ownerSession }]);

    await h.boot.start();
    expect(h.status()).toBe('unreachable');
    expect(h.scheduled[0]?.ms).toBe(2_000);

    h.scheduled[0]?.fn();
    await vi.waitFor(() => expect(h.scheduled[1]?.ms).toBe(5_000));

    h.scheduled[1]?.fn();
    await vi.waitFor(() => expect(h.deps.establish).toHaveBeenCalled());
  });

  it('waits for Retry-After on a 429', async () => {
    const h = harness([{ kind: 'unavailable', retryAfterSeconds: 20 }]);
    await h.boot.start();
    expect(h.scheduled[0]?.ms).toBe(20_000);
  });

  it('cancels the pending automatic retry when the user retries now', async () => {
    const h = harness([{ kind: 'unavailable' }, { kind: 'ok', body: ownerSession }]);
    await h.boot.start();

    await h.boot.retryNow();

    expect(h.scheduled[0]?.cancel).toHaveBeenCalled();
    expect(h.deps.establish).toHaveBeenCalled();
  });

  it('discards a boot result that arrives after the user signed in interactively (plan decision P6)', async () => {
    let finish!: (r: RefreshCallResult) => void;
    const h = harness([]);
    h.deps.refresh.mockImplementationOnce(() => new Promise((r) => (finish = r)));

    const started = h.boot.start();
    h.setStatus('authenticated'); // login succeeded while the boot refresh was in flight
    finish({ kind: 'unauthorized' });
    await started;

    expect(h.status()).toBe('authenticated');
  });

  it('stop cancels a scheduled retry', async () => {
    const h = harness([{ kind: 'unavailable' }]);
    await h.boot.start();
    h.boot.stop();
    expect(h.scheduled[0]?.cancel).toHaveBeenCalled();
  });
});
```

Run: `pnpm test src/features/auth/session/boot.test.ts` — Expected: FAIL (missing module).

- [ ] **Step 2: Implement `boot.ts`**

```ts
import type { AuthResponse } from '@/api/types';
import { REFRESH_FORBIDDEN_MESSAGE, type RefreshCallResult } from './refreshCall';
import type { SessionStatus } from './sessionStore';

export const RETRY_SCHEDULE_MS = [2_000, 5_000, 15_000, 30_000] as const;
const LAST_RETRY_MS = 30_000;

/**
 * Plan decision P2. A 429 honours Retry-After: RateLimitFilter runs before any token is read, so a
 * 429'd refresh consumed nothing and retrying earlier only earns another 429. Everything else retries
 * first after 2 s so a refresh whose response was lost lands inside the 30 s grace window (spec §3.3).
 */
export function nextBootDelayMs(attempt: number, retryAfterSeconds?: number): number {
  if (retryAfterSeconds !== undefined) return Math.min(Math.max(retryAfterSeconds, 1), 60) * 1_000;
  return RETRY_SCHEDULE_MS[Math.min(attempt, RETRY_SCHEDULE_MS.length - 1)] ?? LAST_RETRY_MS;
}

export interface BootDeps {
  refresh(): Promise<RefreshCallResult>;
  establish(body: AuthResponse): unknown;
  getStatus(): SessionStatus;
  setStatus(status: SessionStatus): void;
  schedule(fn: () => void, ms: number): () => void;
  log(message: string): void;
}

export interface Boot {
  start(): Promise<void>;
  retryNow(): Promise<void>;
  stop(): void;
}

export function createBoot(deps: BootDeps): Boot {
  let attempt = 0;
  let cancelRetry: (() => void) | null = null;

  const stop = () => {
    cancelRetry?.();
    cancelRetry = null;
  };
  const pending = () => {
    const status = deps.getStatus();
    return status === 'booting' || status === 'unreachable';
  };

  async function run(): Promise<void> {
    stop();
    if (!pending()) return;
    const result = await deps.refresh();
    if (!pending()) return; // plan decision P6
    switch (result.kind) {
      case 'ok':
        attempt = 0;
        deps.establish(result.body);
        return;
      case 'unauthorized':
        deps.setStatus('anonymous');
        return;
      case 'forbidden':
        deps.log(REFRESH_FORBIDDEN_MESSAGE);
        deps.setStatus('unreachable');
        return;
      case 'unavailable':
        deps.setStatus('unreachable');
        cancelRetry = deps.schedule(() => void run(), nextBootDelayMs(attempt++, result.retryAfterSeconds));
        return;
    }
  }

  return { start: run, retryNow: run, stop };
}
```

Run — Expected: PASS.

- [ ] **Step 3: Write the failing logout tests**

`logout.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest';
import { createBareClient } from '@/api/client';
import { callLogout, createLogout, LOGOUT_RETRY_MS } from './logout';
import type { SessionStatus } from './sessionStore';

function harness(outcomes: ('done' | 'failed')[]) {
  const calls: string[] = [];
  let onlineListener: (() => void) | null = null;
  const timers: { fn: () => void; ms: number; cancel: ReturnType<typeof vi.fn> }[] = [];
  const deps = {
    callLogout: vi.fn(async () => {
      calls.push('POST');
      return outcomes.shift() ?? 'failed';
    }),
    endSession: vi.fn(() => calls.push('endSession')),
    broadcastLogout: vi.fn(() => calls.push('broadcast')),
    setStatus: vi.fn((_s: SessionStatus) => {}),
    onOnline: vi.fn((fn: () => void) => {
      onlineListener = fn;
      return () => {
        onlineListener = null;
      };
    }),
    schedule: vi.fn((fn: () => void, ms: number) => {
      const cancel = vi.fn();
      timers.push({ fn, ms, cancel });
      return cancel;
    }),
  };
  return { deps, logout: createLogout(deps), calls, timers, goOnline: () => onlineListener?.() };
}

describe('logout', () => {
  it('on 204: POSTs first, then ends the session and broadcasts', async () => {
    const h = harness(['done']);
    await h.logout.logout();
    expect(h.calls).toEqual(['POST', 'endSession', 'broadcast']);
    expect(h.deps.endSession).toHaveBeenCalledWith('logout');
    expect(h.deps.setStatus).not.toHaveBeenCalledWith('signing-out');
  });

  it('on failure: still clears local state, but blocks on signing-out and retries', async () => {
    const h = harness(['failed']);
    await h.logout.logout();
    expect(h.deps.endSession).toHaveBeenCalled();
    expect(h.deps.broadcastLogout).toHaveBeenCalled();
    expect(h.deps.setStatus).toHaveBeenLastCalledWith('signing-out');
    expect(h.timers[0]?.ms).toBe(LOGOUT_RETRY_MS);
  });

  it('shows the login page only after a later 204, retried when the network returns', async () => {
    const h = harness(['failed', 'done']);
    await h.logout.logout();

    h.goOnline();

    await vi.waitFor(() => expect(h.deps.setStatus).toHaveBeenLastCalledWith('anonymous'));
    expect(h.timers[0]?.cancel).toHaveBeenCalled();
  });

  it('keeps retrying on a timer while the POST keeps failing', async () => {
    const h = harness(['failed', 'failed']);
    await h.logout.logout();

    h.timers[0]?.fn();

    await vi.waitFor(() => expect(h.timers).toHaveLength(2));
    expect(h.deps.setStatus).not.toHaveBeenCalledWith('anonymous');
  });
});

describe('callLogout', () => {
  const clientAnswering = (status: number) => {
    const seen: Request[] = [];
    const client = createBareClient(async (request) => {
      seen.push(request);
      return new Response(null, { status });
    });
    return { client, seen };
  };

  it('sends the client header and reports done on 204', async () => {
    const { client, seen } = clientAnswering(204);
    expect(await callLogout(client)).toBe('done');
    expect(seen[0]?.headers.get('X-EasyCRM-Client')).toBe('web');
  });

  it('reports failed on any other status and on a network error', async () => {
    expect(await callLogout(clientAnswering(500).client)).toBe('failed');
    const broken = createBareClient(async () => Promise.reject(new TypeError('offline')));
    expect(await callLogout(broken)).toBe('failed');
  });
});
```

Run — Expected: FAIL (missing module).

- [ ] **Step 4: Implement `logout.ts`**

```ts
import { bareApi } from '@/api/client';
import type * as Client from '@/api/client';
import type { EndReason } from './session';
import type { SessionStatus } from './sessionStore';

export const LOGOUT_RETRY_MS = 5_000;

export async function callLogout(
  client: ReturnType<typeof Client.createBareClient> = bareApi,
): Promise<'done' | 'failed'> {
  try {
    const { response } = await client.POST('/api/v1/auth/logout', {
      params: { header: { 'X-EasyCRM-Client': 'web' } },
    });
    return response.status === 204 ? 'done' : 'failed';
  } catch {
    return 'failed';
  }
}

export interface LogoutDeps {
  callLogout(): Promise<'done' | 'failed'>;
  endSession(reason: EndReason): void;
  broadcastLogout(): void;
  setStatus(status: SessionStatus): void;
  onOnline(fn: () => void): () => void;
  schedule(fn: () => void, ms: number): () => void;
}

/**
 * Spec §4.4. The cookie is httpOnly, so JS cannot delete it: until the server answers 204 the device
 * is still signed in, whatever local state says. On a shared counter phone that is the difference
 * between signed out and looking signed out.
 */
export function createLogout(deps: LogoutDeps) {
  let stopRetrying: (() => void) | null = null;

  function scheduleRetry() {
    const cleanup = () => {
      offOnline();
      cancelTimer();
      stopRetrying = null;
    };
    const again = () => {
      cleanup();
      void retry();
    };
    const offOnline = deps.onOnline(again);
    const cancelTimer = deps.schedule(again, LOGOUT_RETRY_MS);
    stopRetrying = cleanup;
  }

  async function retry() {
    if ((await deps.callLogout()) === 'done') deps.setStatus('anonymous');
    else scheduleRetry();
  }

  return {
    async logout(): Promise<void> {
      const outcome = await deps.callLogout();
      deps.endSession('logout');
      deps.broadcastLogout();
      if (outcome === 'done') return;
      deps.setStatus('signing-out');
      scheduleRetry();
    },
    stop(): void {
      stopRetrying?.();
    },
  };
}
```

Run — Expected: PASS.

- [ ] **Step 5: Wire it together in `start.ts`**

```ts
import { setAuthBridge } from '@/api/authBridge';
import { getAccessToken } from './accessToken';
import { createBoot, type Boot } from './boot';
import { createSessionAuthBridge } from './bridge';
import { REFRESH_LOCK } from './lockProvider';
import { callLogout, createLogout } from './logout';
import { callRefresh } from './refreshCall';
import { createRefreshCoordinator } from './refreshCoordinator';
import { configureSession, type SessionRuntime } from './runtime';
import { endSession, establishSession, subscribeToAuthChannel } from './session';
import { useSessionStore, type SessionStatus } from './sessionStore';

export interface SessionControls {
  boot: Boot;
  logout(): Promise<void>;
  stop(): void;
}

let controls: SessionControls | null = null;

export function sessionControls(): SessionControls {
  if (!controls) throw new Error('startSession() has not run');
  return controls;
}

export function stopSession(): void {
  controls?.stop();
  controls = null;
}

const schedule = (fn: () => void, ms: number) => {
  const id = setTimeout(fn, ms);
  return () => clearTimeout(id);
};

export function startSession(runtime: SessionRuntime, options: { autoBoot?: boolean } = {}): SessionControls {
  stopSession();
  configureSession(runtime);

  const coordinator = createRefreshCoordinator({
    locks: runtime.locks,
    callRefresh: () => callRefresh(),
    getToken: getAccessToken,
    onRefreshed: establishSession,
    onUnauthorized: () => endSession('expired'),
    log: runtime.log,
  });
  setAuthBridge(createSessionAuthBridge(coordinator));
  const unsubscribe = subscribeToAuthChannel();

  const setStatus = (status: SessionStatus) => useSessionStore.setState({ status });
  const boot = createBoot({
    // Boot takes the same lock as every other refresh: two tabs opening at once is the commonest race.
    refresh: () => runtime.locks.withLock(REFRESH_LOCK, () => callRefresh()),
    establish: establishSession,
    getStatus: () => useSessionStore.getState().status,
    setStatus,
    schedule,
    log: runtime.log,
  });
  const logout = createLogout({
    callLogout: () => callLogout(),
    endSession,
    broadcastLogout: () => runtime.channel.post({ type: 'logout' }),
    setStatus,
    onOnline: (fn) => {
      window.addEventListener('online', fn);
      return () => window.removeEventListener('online', fn);
    },
    schedule,
  });

  controls = {
    boot,
    logout: () => logout.logout(),
    stop: () => {
      boot.stop();
      logout.stop();
      unsubscribe();
    },
  };
  if (options.autoBoot ?? true) void boot.start();
  return controls;
}
```

In `frontend/src/test/setup.ts` add `import { stopSession } from '@/features/auth/session/start';` and call `stopSession();` first inside `afterEach`.

- [ ] **Step 6: Run and commit**

```bash
pnpm test && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): boot with grace-aware retry and fail-safe logout"
```
Expected: PASS; clean.

---

### Task 7: UI primitives and the form/error foundation

**Files:**
- Create: `frontend/components.json`; shadcn output in `frontend/src/components/ui/` and `frontend/src/lib/utils.ts` (generated by the CLI)
- Create: `frontend/src/components/form/fieldIds.ts`, `TextField.tsx`, `PasswordField.tsx`, `SelectField.tsx`, `FormAlert.tsx`, `fields.test.tsx`
- Create: `frontend/src/components/PageHeading.tsx`
- Create: `frontend/src/lib/useDocumentTitle.ts`, `frontend/src/lib/safeNext.ts`, `safeNext.test.ts`, `frontend/src/lib/apiError.ts`, `apiError.test.ts`
- Modify: `frontend/src/lib/i18n/translator.ts` (add `useFieldError`), `frontend/src/index.css` (shadcn theme)

**Interfaces:**
- Consumes: `api/errors.ts` (`ApiFailure`, `parseRetryAfter`), `lib/i18n/translator.ts` (`Translator`).
- Produces:
  - `TextField(props: ComponentProps<'input'> & { id: string; label: string; error?: string; description?: string })`
  - `PasswordField(props: same + { showLabel: string })`
  - `SelectField(props: ComponentProps<'select'> & { id; label; error?; description?; placeholder: string; options: readonly { value: string; label: string }[] })`
  - `FormAlert({ message: string | null })` — always rendered, `role="alert"`.
  - `PageHeading({ children })` — `<h1 tabIndex={-1}>`, focused on mount.
  - `useDocumentTitle(title: string): void` — sets `document.title = \`${title} · EasyCRM\``.
  - `safeNext(raw: string | null | undefined, origin?: string): string | null`.
  - `parseEnvelope(body: unknown): { code: string | null; message: string | null; fields: Record<string, string>; fieldCodes: Record<string, string> } | null`.
  - `applyApiError<T extends FieldValues>(failure: ApiFailure, form: { setError: UseFormSetError<T>; fields: readonly Path<T>[] }, tr: Translator): { formMessage: string | null; appliedFields: Path<T>[]; code: string | null }`.
  - `useFieldError(): (error: FieldError | undefined) => string | undefined` — translates a zod key, passes server text through.

- [ ] **Step 1: Install form and UI dependencies**

```bash
cd frontend
pnpm add react-hook-form @hookform/resolvers zod
```

Write `frontend/components.json`:
```json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "new-york",
  "rsc": false,
  "tsx": true,
  "tailwind": { "config": "", "css": "src/index.css", "baseColor": "neutral", "cssVariables": true, "prefix": "" },
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/utils",
    "ui": "@/components/ui",
    "lib": "@/lib",
    "hooks": "@/hooks"
  },
  "iconLibrary": "lucide"
}
```

Then:
```bash
pnpm dlx shadcn@latest add button input label card alert skeleton --yes
git status --short
```
Expected: new files under `src/components/ui/`, `src/lib/utils.ts`, theme tokens appended to `src/index.css`, and runtime deps added (`class-variance-authority`, `clsx`, `tailwind-merge`, a Radix package, `lucide-react`, `tw-animate-css`). If the CLI insists on `init` first, run `pnpm dlx shadcn@latest init` accepting the defaults (Neutral) — it reads `components.json` — then re-run `add`. **Do not add `select`, `form`, `toast` or `sonner`** (plan decisions P8, P9).

Spec §5.3 requires shimmer to respect `prefers-reduced-motion`: in the generated `src/components/ui/skeleton.tsx`, add `motion-reduce:animate-none` next to `animate-pulse` in its class list.

- [ ] **Step 2: `safeNext`, test first**

`frontend/src/lib/safeNext.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

const ORIGIN = 'https://app.easycustomerrelationship.site';

describe('safeNext', () => {
  it.each(['/quotes', '/quotes?status=SENT', '/', '/invite/abc#top'])('accepts %s', (path) => {
    expect(safeNext(path, ORIGIN)).toBe(path);
  });

  it.each(['//evil.com', 'https://evil.com', '/\\evil.com', '', 'quotes', '/\t/evil.com', 'javascript:alert(1)', null, undefined])(
    'rejects %s',
    (raw) => {
      expect(safeNext(raw, ORIGIN)).toBeNull();
    },
  );
});
```
Run — FAIL. Then `frontend/src/lib/safeNext.ts`:
```ts
/**
 * Spec §5.1: safe = starts with a single "/", not "//", no "\", and resolves same-origin. The URL
 * parse catches what the string checks cannot (a tab inside "/\t/evil.com" is stripped to "//").
 */
export function safeNext(raw: string | null | undefined, origin: string = globalThis.location.origin): string | null {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return null;
  try {
    const url = new URL(raw, origin);
    if (url.origin !== origin) return null;
    return url.pathname + url.search + url.hash;
  } catch {
    return null;
  }
}
```
Run — PASS.

- [ ] **Step 3: `applyApiError`, test first**

`frontend/src/lib/apiError.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest';
import type { ApiFailure } from '@/api/errors';
import { applyApiError, parseEnvelope } from './apiError';
import type { Translator } from './i18n/translator';

const KEYS: Record<string, string> = {
  'errors.network': 'NETWORK',
  'errors.server': 'SERVER',
  'errors.unexpected': 'UNEXPECTED',
  'errors.rateLimited': 'RATE',
  'errors.rateLimitedIn': 'RATE {{count}}',
  'errors.fields.GSTIN_CHECKSUM': 'GSTIN BAD',
  'errors.fields.SLUG_TAKEN': 'SLUG TAKEN',
  'errors.codes.FORBIDDEN': 'NOT ALLOWED',
};
const tr: Translator = {
  exists: (key) => key in KEYS,
  t: (key, options) => (KEYS[key] ?? `!!${key}`).replace('{{count}}', String(options?.count ?? '')),
};

const http = (status: number, body: unknown, headers: Record<string, string> = {}): ApiFailure => ({
  kind: 'http',
  status,
  body,
  headers: new Headers(headers),
});

type Form = { slug: string; gstin: string; email: string };
const FIELDS = ['slug', 'gstin', 'email'] as const;

describe('applyApiError', () => {
  it('translates fieldCodes and focuses only the first field', () => {
    const setError = vi.fn();
    const result = applyApiError<Form>(
      http(422, {
        error: {
          code: 'VALIDATION_FAILED',
          message: 'invalid',
          fields: { gstin: 'bad checksum', slug: 'taken' },
          fieldCodes: { gstin: 'GSTIN_CHECKSUM', slug: 'SLUG_TAKEN' },
        },
      }),
      { setError, fields: FIELDS },
      tr,
    );

    expect(result).toEqual({ formMessage: null, appliedFields: ['slug', 'gstin'], code: 'VALIDATION_FAILED' });
    expect(setError).toHaveBeenNthCalledWith(1, 'slug', { type: 'server', message: 'SLUG TAKEN' }, { shouldFocus: true });
    expect(setError).toHaveBeenNthCalledWith(2, 'gstin', { type: 'server', message: 'GSTIN BAD' }, { shouldFocus: false });
  });

  it('falls back from an unknown field code to the code key, then to the server text', () => {
    const setError = vi.fn();
    applyApiError<Form>(
      http(400, {
        error: { code: 'VALIDATION_FAILED', message: 'x', fields: { email: 'must be a well-formed email address' }, fieldCodes: { email: 'NEW_CODE' } },
      }),
      { setError, fields: FIELDS },
      tr,
    );
    expect(setError).toHaveBeenCalledWith('email', { type: 'server', message: 'must be a well-formed email address' }, { shouldFocus: true });
  });

  it.each([400, 409, 422])('maps field errors for %s', (status) => {
    const setError = vi.fn();
    applyApiError<Form>(
      http(status, { error: { code: 'X', message: 'x', fields: { slug: 'taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } } }),
      { setError, fields: FIELDS },
      tr,
    );
    expect(setError).toHaveBeenCalledTimes(1);
  });

  it('uses the envelope message when no field matches the form', () => {
    const result = applyApiError<Form>(
      http(409, { error: { code: 'CONFLICT', message: 'already exists', fields: { other: 'x' } } }),
      { setError: vi.fn(), fields: FIELDS },
      tr,
    );
    expect(result.formMessage).toBe('already exists');
  });

  it('prefers a translated code for the form message', () => {
    const result = applyApiError<Form>(
      http(403, { error: { code: 'FORBIDDEN', message: 'role not permitted' } }),
      { setError: vi.fn(), fields: FIELDS },
      tr,
    );
    expect(result.formMessage).toBe('NOT ALLOWED');
  });

  it.each([
    ['a body-less 401', http(401, undefined)],
    ['a non-JSON 403', http(403, '<html>forbidden</html>')],
    ['an envelope without error', http(400, { message: 'nope' })],
  ])('never throws on %s', (_label, failure) => {
    expect(applyApiError<Form>(failure, { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('UNEXPECTED');
  });

  it('uses Retry-After on 429', () => {
    const withHeader = applyApiError<Form>(http(429, undefined, { 'Retry-After': '12' }), { setError: vi.fn(), fields: FIELDS }, tr);
    const without = applyApiError<Form>(http(429, undefined), { setError: vi.fn(), fields: FIELDS }, tr);
    expect(withHeader.formMessage).toBe('RATE 12');
    expect(without.formMessage).toBe('RATE');
  });

  it('maps network failures and 5xx to form-level messages', () => {
    expect(applyApiError<Form>({ kind: 'network' }, { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('NETWORK');
    expect(applyApiError<Form>(http(503, undefined), { setError: vi.fn(), fields: FIELDS }, tr).formMessage).toBe('SERVER');
  });
});

describe('parseEnvelope', () => {
  it('keeps only string values from fields and fieldCodes', () => {
    expect(parseEnvelope({ error: { code: 'X', message: 'm', fields: { a: 'x', b: 3 }, fieldCodes: { a: 'A', b: null } } })).toEqual({
      code: 'X',
      message: 'm',
      fields: { a: 'x' },
      fieldCodes: { a: 'A' },
    });
  });
});
```
Run — FAIL. Then `frontend/src/lib/apiError.ts`:
```ts
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { parseRetryAfter, type ApiFailure } from '@/api/errors';
import type { Translator } from './i18n/translator';

export interface ParsedEnvelope {
  code: string | null;
  message: string | null;
  fields: Record<string, string>;
  fieldCodes: Record<string, string>;
}

function stringEntries(value: unknown): Record<string, string> {
  if (typeof value !== 'object' || value === null) return {};
  return Object.fromEntries(Object.entries(value).filter((e): e is [string, string] => typeof e[1] === 'string'));
}

/** Defensive by design (spec §4.6): a body-less 401/403 or a non-JSON body never throws. */
export function parseEnvelope(body: unknown): ParsedEnvelope | null {
  if (typeof body !== 'object' || body === null || !('error' in body)) return null;
  const error = body.error;
  if (typeof error !== 'object' || error === null) return null;
  const record = error as Record<string, unknown>;
  return {
    code: typeof record.code === 'string' ? record.code : null,
    message: typeof record.message === 'string' ? record.message : null,
    fields: stringEntries(record.fields),
    fieldCodes: stringEntries(record.fieldCodes),
  };
}

export interface ApplyResult<T extends FieldValues> {
  formMessage: string | null;
  appliedFields: Path<T>[];
  code: string | null;
}

const FIELD_STATUSES = new Set([400, 409, 422]);

/** The one mapping from an API failure to a form, used by every form in F0–F3 (spec §4.6). */
export function applyApiError<T extends FieldValues>(
  failure: ApiFailure,
  form: { setError: UseFormSetError<T>; fields: readonly Path<T>[] },
  tr: Translator,
): ApplyResult<T> {
  if (failure.kind === 'network') return { formMessage: tr.t('errors.network'), appliedFields: [], code: null };

  const envelope = parseEnvelope(failure.body);
  const code = envelope?.code ?? null;

  if (failure.status === 429) {
    const seconds = parseRetryAfter(failure.headers.get('Retry-After'));
    const formMessage = seconds === undefined ? tr.t('errors.rateLimited') : tr.t('errors.rateLimitedIn', { count: seconds });
    return { formMessage, appliedFields: [], code };
  }
  if (failure.status >= 500) return { formMessage: tr.t('errors.server'), appliedFields: [], code };

  if (envelope && FIELD_STATUSES.has(failure.status)) {
    const appliedFields: Path<T>[] = [];
    for (const field of form.fields) {
      const fieldCode = envelope.fieldCodes[field];
      const serverText = envelope.fields[field];
      if (fieldCode === undefined && serverText === undefined) continue;
      const message =
        fieldCode !== undefined && tr.exists(`errors.fields.${fieldCode}`)
          ? tr.t(`errors.fields.${fieldCode}`)
          : code !== null && tr.exists(`errors.codes.${code}`)
            ? tr.t(`errors.codes.${code}`)
            : (serverText ?? tr.t('errors.unexpected'));
      form.setError(field, { type: 'server', message }, { shouldFocus: appliedFields.length === 0 });
      appliedFields.push(field);
    }
    if (appliedFields.length > 0) return { formMessage: null, appliedFields, code };
  }

  const formMessage =
    code !== null && tr.exists(`errors.codes.${code}`)
      ? tr.t(`errors.codes.${code}`)
      : (envelope?.message ?? tr.t('errors.unexpected'));
  return { formMessage, appliedFields: [], code };
}
```
Run — PASS. (Note: `errors.rateLimitedIn` resolves through i18next's plural suffixes `_one`/`_other` at runtime; the test translator maps the base key directly.)

- [ ] **Step 4: `useFieldError`**

Append to `frontend/src/lib/i18n/translator.ts`:
```ts
import type { FieldError } from 'react-hook-form';

/**
 * Zod messages are i18n keys (spec §4.7); server field messages are already final text from
 * applyApiError. A message that is a known key is translated; anything else is shown as is.
 */
export function useFieldError(): (error: FieldError | undefined) => string | undefined {
  const tr = useTranslator();
  return (error) => {
    const message = error?.message;
    if (!message) return undefined;
    return tr.exists(message) ? tr.t(message) : message;
  };
}
```
(Move the `FieldError` import to the top of the file with the others.)

- [ ] **Step 5: Field components, test first**

`frontend/src/components/form/fields.test.tsx`:
```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { FormAlert } from './FormAlert';
import { PasswordField } from './PasswordField';
import { SelectField } from './SelectField';
import { TextField } from './TextField';

describe('TextField', () => {
  it('links label, description and error to the input', () => {
    render(<TextField id="email" label="Email" description="Work email" error="Enter a valid email address." />);
    const input = screen.getByRole('textbox', { name: 'Email' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Work email Enter a valid email address.');
  });

  it('is not marked invalid without an error', () => {
    render(<TextField id="email" label="Email" />);
    expect(screen.getByRole('textbox', { name: 'Email' })).not.toHaveAttribute('aria-invalid');
  });
});

describe('PasswordField', () => {
  it('toggles visibility with a pressed-state button and never blocks paste', async () => {
    const user = userEvent.setup();
    render(<PasswordField id="pw" label="Password" showLabel="Show password" />);
    const input = screen.getByLabelText('Password', { selector: 'input' });
    const toggle = screen.getByRole('button', { name: 'Show password' });

    expect(input).toHaveAttribute('type', 'password');
    await user.click(toggle);
    expect(input).toHaveAttribute('type', 'text');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');

    await user.click(input);
    await user.paste('pasted-secret');
    expect(input).toHaveValue('pasted-secret');
  });
});

describe('SelectField', () => {
  it('renders a native select with a disabled placeholder', () => {
    render(
      <SelectField id="state" label="State" placeholder="Choose your state" options={[{ value: '27', label: '27 – Maharashtra' }]} defaultValue="" />,
    );
    const select = screen.getByRole('combobox', { name: 'State' });
    expect(select.tagName).toBe('SELECT');
    expect(screen.getByRole('option', { name: 'Choose your state' })).toBeDisabled();
    expect(screen.getByRole('option', { name: '27 – Maharashtra' })).toBeInTheDocument();
  });
});

describe('FormAlert', () => {
  it('is always present as an alert region so screen readers announce changes', () => {
    const { rerender } = render(<FormAlert message={null} />);
    expect(screen.getByRole('alert')).toBeEmptyDOMElement();
    rerender(<FormAlert message="Workspace, email or password is incorrect." />);
    expect(screen.getByRole('alert')).toHaveTextContent('Workspace, email or password is incorrect.');
  });
});
```
Run — FAIL. Then:

`frontend/src/components/form/fieldIds.ts`:
```ts
export function describedBy(...ids: (string | undefined)[]): string | undefined {
  const joined = ids.filter(Boolean).join(' ');
  return joined || undefined;
}

export function fieldIds(id: string, description?: string, error?: string) {
  const descriptionId = description ? `${id}-description` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return { descriptionId, errorId, describedBy: describedBy(descriptionId, errorId) };
}
```

`frontend/src/components/form/TextField.tsx`:
```tsx
import type { ComponentProps } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { fieldIds } from './fieldIds';

export type TextFieldProps = ComponentProps<'input'> & {
  id: string;
  label: string;
  description?: string;
  error?: string;
};

export function TextField({ id, label, description, error, ...input }: TextFieldProps) {
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} aria-invalid={error ? true : undefined} aria-describedby={ids.describedBy} {...input} />
      {description && (
        <p id={ids.descriptionId} className="text-muted-foreground text-sm">
          {description}
        </p>
      )}
      {error && (
        <p id={ids.errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  );
}
```

`frontend/src/components/form/PasswordField.tsx`:
```tsx
import { Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { fieldIds } from './fieldIds';
import type { TextFieldProps } from './TextField';

export type PasswordFieldProps = Omit<TextFieldProps, 'type'> & { showLabel: string };

export function PasswordField({ id, label, description, error, showLabel, ...input }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <div className="relative">
        <Input
          id={id}
          type={visible ? 'text' : 'password'}
          className="pr-11"
          aria-invalid={error ? true : undefined}
          aria-describedby={ids.describedBy}
          {...input}
        />
        <button
          type="button"
          className="absolute inset-y-0 right-0 grid w-11 place-items-center"
          aria-label={showLabel}
          aria-pressed={visible}
          aria-controls={id}
          onClick={() => setVisible((v) => !v)}
        >
          {visible ? <EyeOff aria-hidden className="size-4" /> : <Eye aria-hidden className="size-4" />}
        </button>
      </div>
      {description && (
        <p id={ids.descriptionId} className="text-muted-foreground text-sm">
          {description}
        </p>
      )}
      {error && (
        <p id={ids.errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  );
}
```

`frontend/src/components/form/SelectField.tsx`:
```tsx
import type { ComponentProps } from 'react';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { fieldIds } from './fieldIds';

export type SelectFieldProps = ComponentProps<'select'> & {
  id: string;
  label: string;
  placeholder: string;
  options: readonly { value: string; label: string }[];
  description?: string;
  error?: string;
};

// Native on purpose (plan decision P9): Radix Select injects a <style> tag the CSP refuses, and a
// native picker is the right control on low-end Android.
export function SelectField({ id, label, placeholder, options, description, error, className, ...select }: SelectFieldProps) {
  const ids = fieldIds(id, description, error);
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <select
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={ids.describedBy}
        className={cn(
          'border-input bg-background h-9 w-full rounded-md border px-3 text-base md:text-sm',
          'aria-invalid:border-destructive',
          className,
        )}
        {...select}
      >
        <option value="" disabled>
          {placeholder}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {description && (
        <p id={ids.descriptionId} className="text-muted-foreground text-sm">
          {description}
        </p>
      )}
      {error && (
        <p id={ids.errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  );
}
```

`frontend/src/components/form/FormAlert.tsx`:
```tsx
import { cn } from '@/lib/utils';

/** Persistent role="alert" region (spec §4.6): form-level failures are never toasts. */
export function FormAlert({ message }: { message: string | null }) {
  return (
    <div
      role="alert"
      className={cn(message && 'border-destructive/50 bg-destructive/10 text-destructive rounded-md border p-3 text-sm')}
    >
      {message}
    </div>
  );
}
```

`frontend/src/components/PageHeading.tsx`:
```tsx
import { useEffect, useRef, type ReactNode } from 'react';

/** Spec §5.3: focus moves to the page <h1> after navigation, so screen readers start at the page. */
export function PageHeading({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1} className="text-2xl font-semibold outline-none">
      {children}
    </h1>
  );
}
```

`frontend/src/lib/useDocumentTitle.ts`:
```ts
import { useEffect } from 'react';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · EasyCRM`;
  }, [title]);
}
```

- [ ] **Step 6: Run and commit**

```bash
pnpm test && pnpm typecheck && pnpm build
cd .. && git add frontend
git commit -m "feat(frontend): accessible form fields, safeNext and the single API-error-to-form mapping"
```
Expected: PASS; clean; build succeeds.

---

### Task 8: App shell, routing and `RequireSession`

**Files:**
- Create in `frontend/src/app/`: `queryClient.ts`, `queryClient.test.ts`, `router.tsx`, `providers.tsx`, `bootstrap.ts`, `RootLayout.tsx`, `RequireSession.tsx`, `RouteErrorBoundary.tsx`, `NotFoundPage.tsx`, `shell/AppShell.tsx`, `shell/HomePage.tsx`, `shell/UnreachableScreen.tsx`, `shell/SignOutPendingScreen.tsx`, `app.test.tsx`
- Create: `frontend/src/test/renderApp.tsx`
- Modify: `frontend/src/main.tsx`

**Interfaces:**
- Consumes: Tasks 3–7.
- Produces:
  - `createQueryClient(): QueryClient`, `shouldRetryQuery(failureCount: number, error: unknown): boolean`.
  - `appRoutes: RouteObject[]` — one root route (`element: <RootLayout />`) whose `children` array Tasks 10–12 **insert public routes into, before the `RequireSession` entry**; `createAppRouter()`.
  - `Providers({ router, queryClient })`.
  - `startApp(): { queryClient; router }`.
  - `renderApp(path: string, options?: { session?: { status: SessionStatus; me?: Me | null; accessToken?: string }; boot?: boolean })` → `{ router, runtime, controls, queryClient, user, ...RenderResult }`.

- [ ] **Step 1: Install**

```bash
cd frontend
pnpm add react-router @tanstack/react-query
pnpm add -D @tanstack/react-query-devtools
```
(Devtools is a dev dependency and only dynamically imported under `import.meta.env.DEV`, so it never reaches the production build.)

- [ ] **Step 2: Query client, test first**

`frontend/src/app/queryClient.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { RetryableRequestError } from '@/api/authFetch';
import { ApiHttpError } from '@/api/errors';
import { createQueryClient, shouldRetryQuery } from './queryClient';

const http = (status: number) => new ApiHttpError(status, undefined, new Headers());

describe('query defaults (spec §4.5)', () => {
  it('retries network errors and 5xx at most twice, and never 4xx', () => {
    expect(shouldRetryQuery(0, http(503))).toBe(true);
    expect(shouldRetryQuery(1, http(500))).toBe(true);
    expect(shouldRetryQuery(2, http(500))).toBe(false);
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetryQuery(0, new RetryableRequestError())).toBe(true);
    for (const status of [400, 401, 403, 404, 409, 422, 429]) expect(shouldRetryQuery(0, http(status))).toBe(false);
  });

  it('sets mutation retry 0, no refetch on focus, and a 30 s stale time', () => {
    const { queries, mutations } = createQueryClient().getDefaultOptions();
    expect(mutations?.retry).toBe(0);
    expect(queries?.refetchOnWindowFocus).toBe(false);
    expect(queries?.staleTime).toBe(30_000);
  });
});
```
Run — FAIL. Then `frontend/src/app/queryClient.ts`:
```ts
import { QueryClient } from '@tanstack/react-query';
import { ApiHttpError } from '@/api/errors';

const MAX_QUERY_RETRIES = 2;

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) return false;
  if (error instanceof ApiHttpError) return error.status >= 500;
  return true; // network failures, timeouts, RetryableRequestError
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetryQuery, refetchOnWindowFocus: false, staleTime: 30_000 },
      mutations: { retry: 0 },
    },
  });
}
```
Run — PASS.

- [ ] **Step 3: Write the screens, layout and router**

`frontend/src/app/shell/UnreachableScreen.tsx`:
```tsx
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function UnreachableScreen({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  useDocumentTitle(t('unreachable.title'));
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('unreachable.heading')}</PageHeading>
      <p>{t('unreachable.body')}</p>
      <Button onClick={onRetry}>{t('actions.retry')}</Button>
    </main>
  );
}
```

`frontend/src/app/shell/SignOutPendingScreen.tsx`:
```tsx
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function SignOutPendingScreen() {
  const { t } = useTranslation();
  useDocumentTitle(t('signOutPending.title'));
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10" aria-busy="true">
      <PageHeading>{t('signOutPending.heading')}</PageHeading>
      <p>{t('signOutPending.body')}</p>
    </main>
  );
}
```

`frontend/src/app/shell/AppShell.tsx`:
```tsx
import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';
import { Button } from '@/components/ui/button';
import { sessionControls } from '@/features/auth/session/start';
import { useSessionStore } from '@/features/auth/session/sessionStore';

export function AppShell() {
  const { t } = useTranslation();
  const me = useSessionStore((s) => s.me);
  if (!me) return null;
  return (
    <div className="min-h-dvh">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
        <p className="font-semibold">{me.tenantSlug}</p>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span>{me.email}</span>
          <span>{t(`roles.${me.role}`)}</span>
          <Button variant="outline" size="sm" onClick={() => void sessionControls().logout()}>
            {t('actions.signOut')}
          </Button>
        </div>
      </header>
      <div className="flex">
        <nav aria-label={t('shell.nav')} className="hidden w-56 border-r md:block" />
        <main className="flex-1 px-4 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
```

`frontend/src/app/shell/HomePage.tsx`:
```tsx
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/components/PageHeading';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/** Placeholder; F3 replaces it with the role-aware dashboard. */
export function HomePage() {
  const { t } = useTranslation();
  const me = useSessionStore((s) => s.me);
  useDocumentTitle(t('shell.homeTitle'));
  if (!me) return null;
  return (
    <div className="grid gap-2">
      <PageHeading>{t('shell.homeHeading')}</PageHeading>
      <p>{t('shell.signedInAs', { slug: me.tenantSlug, email: me.email, role: t(`roles.${me.role}`) })}</p>
    </div>
  );
}
```

`frontend/src/app/NotFoundPage.tsx`:
```tsx
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { PageHeading } from '@/components/PageHeading';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function NotFoundPage() {
  const { t } = useTranslation();
  useDocumentTitle(t('notFound.title'));
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('notFound.heading')}</PageHeading>
      <Link to="/" className="underline">
        {t('notFound.home')}
      </Link>
    </main>
  );
}
```

`frontend/src/app/RouteErrorBoundary.tsx`:
```tsx
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useRouteError } from 'react-router';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

export function RouteErrorBoundary() {
  const error = useRouteError();
  const { t } = useTranslation();
  useDocumentTitle(t('routeError.title'));
  useEffect(() => {
    console.error(error);
  }, [error]);
  return (
    <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
      <PageHeading>{t('routeError.heading')}</PageHeading>
      <p>{t('routeError.body')}</p>
      <Button onClick={() => window.location.reload()}>{t('actions.reload')}</Button>
    </main>
  );
}
```

`frontend/src/app/RequireSession.tsx`:
```tsx
import { Navigate, Outlet, useLocation } from 'react-router';
import { sessionControls } from '@/features/auth/session/start';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { safeNext } from '@/lib/safeNext';
import { UnreachableScreen } from './shell/UnreachableScreen';

export function RequireSession() {
  const status = useSessionStore((s) => s.status);
  const location = useLocation();
  switch (status) {
    case 'booting':
    case 'signing-out':
      // booting: render nothing, so #root stays empty and the index.html splash shows (plan P7).
      // signing-out: RootLayout renders the blocking screen instead of this outlet.
      return null;
    case 'unreachable':
      return <UnreachableScreen onRetry={() => void sessionControls().boot.retryNow()} />;
    case 'anonymous': {
      const next = safeNext(location.pathname + location.search) ?? '/';
      return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
    }
    case 'authenticated':
      return <Outlet />;
  }
}
```

`frontend/src/app/RootLayout.tsx`:
```tsx
import { useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { onSessionExpired } from '@/features/auth/session/sessionEvents';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { safeNext } from '@/lib/safeNext';
import { SignOutPendingScreen } from './shell/SignOutPendingScreen';

export function RootLayout() {
  const status = useSessionStore((s) => s.status);
  const navigate = useNavigate();
  const location = useLocation();
  const locationRef = useRef(location);

  useEffect(() => {
    locationRef.current = location;
  }, [location]);

  // The HTTP layer raises session-expired; only the router decides what that means (spec §4.4).
  useEffect(
    () =>
      onSessionExpired(() => {
        const { pathname, search } = locationRef.current;
        const next = safeNext(pathname + search) ?? '/';
        void navigate(`/login?next=${encodeURIComponent(next)}`, { replace: true });
      }),
    [navigate],
  );

  if (status === 'signing-out') return <SignOutPendingScreen />;
  return <Outlet />;
}
```

`frontend/src/app/router.tsx`:
```tsx
import { createBrowserRouter, type RouteObject } from 'react-router';
import { RequireSession } from './RequireSession';
import { RootLayout } from './RootLayout';
import { RouteErrorBoundary } from './RouteErrorBoundary';

export const appRoutes: RouteObject[] = [
  {
    element: <RootLayout />,
    errorElement: <RouteErrorBoundary />,
    children: [
      // Public routes (Tasks 10–12) go HERE, before RequireSession. They render without waiting on boot.
      {
        element: <RequireSession />,
        children: [
          {
            lazy: () => import('./shell/AppShell').then((m) => ({ Component: m.AppShell })),
            errorElement: <RouteErrorBoundary />,
            children: [
              {
                index: true,
                lazy: () => import('./shell/HomePage').then((m) => ({ Component: m.HomePage })),
                errorElement: <RouteErrorBoundary />,
              },
            ],
          },
        ],
      },
      {
        path: '*',
        lazy: () => import('./NotFoundPage').then((m) => ({ Component: m.NotFoundPage })),
        errorElement: <RouteErrorBoundary />,
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
```

`frontend/src/app/providers.tsx`:
```tsx
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { lazy, Suspense } from 'react';
import { RouterProvider, type createBrowserRouter } from 'react-router';

type AppRouter = ReturnType<typeof createBrowserRouter>;

const Devtools = import.meta.env.DEV
  ? lazy(() => import('@tanstack/react-query-devtools').then((m) => ({ default: m.ReactQueryDevtools })))
  : () => null;

export function Providers({ router, queryClient }: { router: AppRouter; queryClient: QueryClient }) {
  return (
    <QueryClientProvider client={queryClient}>
      <Suspense fallback={null}>
        <RouterProvider router={router} />
      </Suspense>
      <Suspense fallback={null}>
        <Devtools />
      </Suspense>
    </QueryClientProvider>
  );
}
```
(`createMemoryRouter` returns the same router type, so tests pass one in.)

`frontend/src/app/bootstrap.ts`:
```ts
import { createAuthChannel } from '@/features/auth/session/authChannel';
import { createInMemoryLocks, supportsWebLocks, webLocks } from '@/features/auth/session/lockProvider';
import { startSession } from '@/features/auth/session/start';
import { initI18n } from '@/lib/i18n';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

export function startApp() {
  void initI18n();
  const queryClient = createQueryClient();
  startSession({
    clearQueryCache: () => queryClient.clear(),
    channel: createAuthChannel(),
    // Browsers without Web Locks serialize per tab only; every browser this product targets has them.
    locks: supportsWebLocks() ? webLocks : createInMemoryLocks(),
    reload: () => window.location.reload(),
    log: (message) => console.error(`[easycrm] ${message}`),
  });
  return { queryClient, router: createAppRouter() };
}
```

`frontend/src/main.tsx` (replace):
```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { startApp } from '@/app/bootstrap';
import { Providers } from '@/app/providers';
import './index.css';

const { queryClient, router } = startApp();
const root = document.getElementById('root');
if (!root) throw new Error('#root is missing from index.html');
createRoot(root).render(
  <StrictMode>
    <Providers router={router} queryClient={queryClient} />
  </StrictMode>,
);
```

- [ ] **Step 4: The test renderer**

`frontend/src/test/renderApp.tsx`:
```tsx
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { Providers } from '@/app/providers';
import { createQueryClient } from '@/app/queryClient';
import { appRoutes } from '@/app/router';
import { setAccessToken } from '@/features/auth/session/accessToken';
import { createNoopChannel } from '@/features/auth/session/authChannel';
import { createInMemoryLocks } from '@/features/auth/session/lockProvider';
import { startSession } from '@/features/auth/session/start';
import { useSessionStore, type Me, type SessionStatus } from '@/features/auth/session/sessionStore';

export interface RenderAppOptions {
  session?: { status: SessionStatus; me?: Me | null; accessToken?: string };
  boot?: boolean;
}

/** The real router, session and HTTP layer; only the network (MSW) and the runtime are fakes. */
export function renderApp(path: string, options: RenderAppOptions = {}) {
  const queryClient = createQueryClient();
  const runtime = {
    clearQueryCache: vi.fn(() => queryClient.clear()),
    channel: createNoopChannel(),
    locks: createInMemoryLocks(),
    reload: vi.fn(),
    log: vi.fn(),
  };
  const controls = startSession(runtime, { autoBoot: false });
  if (options.session) {
    useSessionStore.setState({ status: options.session.status, me: options.session.me ?? null });
    if (options.session.accessToken) setAccessToken(options.session.accessToken);
  }
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] });
  if (options.boot) void controls.boot.start();
  const user = userEvent.setup();
  const view = render(<Providers router={router} queryClient={queryClient} />);
  return { ...view, router, runtime, controls, queryClient, user };
}
```

Add to `frontend/src/test/fixtures.ts`:
```ts
import { toMe } from '@/features/auth/session/toMe';
// ...
export const ownerMe = toMe(ownerSession);
export const inviteeMe = toMe(inviteeSession);
```

- [ ] **Step 5: Write the app tests**

`frontend/src/app/app.test.tsx`:
```tsx
import { act, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { REFRESH_FORBIDDEN_MESSAGE } from '@/features/auth/session/refreshCall';
import { emitSessionExpired } from '@/features/auth/session/sessionEvents';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { errorBody, ownerMe, ownerSession } from '@/test/fixtures';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const signedInText = 'Signed in to ravi-traders as ravi@shop.in (Owner)';
const authenticated = { status: 'authenticated' as const, me: ownerMe, accessToken: ownerSession.accessToken };

// The contract requires the header; this handler refuses a refresh without it, exactly as the server does.
const refreshRequiringHeader = http.post('/api/v1/auth/refresh', ({ request, response }) =>
  request.headers.get('X-EasyCRM-Client') === 'web'
    ? response(200).json(ownerSession)
    : response(403).json(errorBody('FORBIDDEN', 'the X-EasyCRM-Client: web header is missing')),
);

describe('RequireSession', () => {
  it('renders nothing while booting, so the index.html splash stays visible', () => {
    const { container } = renderApp('/');
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the shell and home placeholder when authenticated, with title and focus', async () => {
    renderApp('/', { session: authenticated });
    expect(await screen.findByText(signedInText)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome' })).toHaveFocus();
    expect(document.title).toBe('Home · EasyCRM');
  });

  it('redirects an anonymous visitor to /login with a safe next', async () => {
    const { router } = renderApp('/?tab=1', { session: { status: 'anonymous' } });
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/?tab=1')}`);
  });

  it('shows the retry screen when unreachable, and retrying boots', async () => {
    server.use(refreshRequiringHeader);
    const { user } = renderApp('/', { session: { status: 'unreachable' } });

    await user.click(await screen.findByRole('button', { name: 'Try again' }));

    expect(await screen.findByText(signedInText)).toBeInTheDocument();
  });
});

describe('boot', () => {
  it('sends X-EasyCRM-Client on the boot refresh and signs in', async () => {
    server.use(refreshRequiringHeader);
    renderApp('/', { boot: true });
    expect(await screen.findByText(signedInText)).toBeInTheDocument();
  });

  it('treats a boot 403 as a client bug: retry screen, not the login page', async () => {
    server.use(
      http.post('/api/v1/auth/refresh', ({ response }) =>
        response(403).json(errorBody('FORBIDDEN', 'the X-EasyCRM-Client: web header is missing')),
      ),
    );
    const { router, runtime } = renderApp('/', { boot: true });

    expect(await screen.findByRole('heading', { name: "Can't reach EasyCRM" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(runtime.log).toHaveBeenCalledWith(REFRESH_FORBIDDEN_MESSAGE);
  });

  it('retries automatically within 5 s after a network failure', async () => {
    let calls = 0;
    server.use(
      mswHttp.post('*/api/v1/auth/refresh', () => {
        calls += 1;
        return calls === 1 ? HttpResponse.error() : HttpResponse.json(ownerSession);
      }),
    );
    const started = Date.now();
    renderApp('/', { boot: true });

    expect(await screen.findByRole('heading', { name: "Can't reach EasyCRM" })).toBeInTheDocument();
    expect(await screen.findByText(signedInText, undefined, { timeout: 5_000 })).toBeInTheDocument();
    expect(Date.now() - started).toBeLessThan(5_000);
  });
});

describe('RootLayout', () => {
  it('navigates to /login with a sanitized next when the session expires', async () => {
    // On a public route, so RequireSession's own redirect cannot be what moves the location.
    const { router } = renderApp('/nope?x=1', { session: { status: 'anonymous' } });
    await screen.findByRole('heading', { name: 'Page not found' });

    act(() => emitSessionExpired());

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/nope?x=1')}`);
  });

  it('blocks every route while sign-out is pending', async () => {
    renderApp('/anything', { session: { status: 'signing-out' } });
    expect(await screen.findByRole('heading', { name: 'Sign-out did not complete — retrying' })).toBeInTheDocument();
  });

  it('renders the not-found page for an unknown route', async () => {
    renderApp('/nope', { session: { status: 'anonymous' } });
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });
});

describe('AppShell', () => {
  it('signs out: POST logout with the client header, then leaves the protected route', async () => {
    let sawHeader: string | null = null;
    server.use(
      http.post('/api/v1/auth/logout', ({ request, response }) => {
        sawHeader = request.headers.get('X-EasyCRM-Client');
        return response(204).empty();
      }),
    );
    const { user, router } = renderApp('/', { session: authenticated });

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(sawHeader).toBe('web');
    expect(useSessionStore.getState().me).toBeNull();
  });
});
```

- [ ] **Step 6: Run to confirm, then fix only real failures**

Run: `pnpm test src/app`
Expected: PASS for all. If `redirects an anonymous visitor` fails on the search string, compare with `encodeURIComponent('/?tab=1')` = `%2F%3Ftab%3D1` — do not weaken the assertion to a `contains`.

- [ ] **Step 7: Prove the splash in a real browser, and commit**

```bash
pnpm build && pnpm preview --port 4173 &
sleep 2 && curl -sI http://localhost:4173/ | grep -i content-security-policy
kill %1
pnpm typecheck && pnpm test
cd .. && git add frontend
git commit -m "feat(frontend): router, RequireSession, app shell and session-expired navigation"
```
Expected: the CSP header line prints the exact Global Constraints value; typecheck and tests clean.

---

### Task 9: ESLint gates, each proven red by a fixture

**Files:**
- Create: `frontend/eslint.config.js`, `frontend/src/test/eslintGates.test.ts`
- Modify: any `src/` file the new rules flag (fix the code, never loosen a rule)

**Interfaces:**
- Consumes: the folder layout from Tasks 3–8 (`src/api`, `src/lib`, `src/components`, `src/features/auth`, `src/app`).
- Produces: `pnpm lint` (zero warnings allowed) enforcing: layer direction `api` → `lib`/`components` → `features` → `app`; features never import each other; no `fetch`/`XMLHttpRequest`/`openapi-fetch` outside `src/api`; jsx-a11y recommended; no literal JSX text in `src/features/**` and `src/app/**`; `react/no-danger`. Test files (`*.test.ts(x)`, `src/test/**`, `*.typecheck.ts`) are exempt from the layer and literal-string rules.

- [ ] **Step 1: Install**

```bash
cd frontend
pnpm add -D eslint @eslint/js typescript-eslint globals eslint-plugin-react eslint-plugin-react-hooks \
  eslint-plugin-jsx-a11y eslint-plugin-import-x eslint-import-resolver-typescript eslint-plugin-i18next
```

- [ ] **Step 2: Write the fixture test first**

`frontend/src/test/eslintGates.test.ts`:
```ts
// @vitest-environment node
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// Every gate in spec §4.3/§4.7/§4.9 is proven by linting a deliberately violating snippet at a real
// path. A second feature is created on disk so "features never import each other" has a target;
// eslint.config.js generates one zone per directory under src/features at load time.
const root = fileURLToPath(new URL('../..', import.meta.url));
const fixtureFeature = `${root}/src/features/zz-lint-fixture`;
let eslint: ESLint;

beforeAll(() => {
  mkdirSync(fixtureFeature, { recursive: true });
  writeFileSync(`${fixtureFeature}/thing.ts`, 'export const thing = 1;\n');
  eslint = new ESLint({ cwd: root });
});

afterAll(() => rmSync(fixtureFeature, { recursive: true, force: true }));

async function ruleIds(path: string, code: string): Promise<(string | null)[]> {
  const [result] = await eslint.lintText(code, { filePath: `${root}/${path}` });
  return (result?.messages ?? []).map((m) => m.ruleId);
}

describe('layer boundaries', () => {
  it.each([
    ['a feature importing app', 'src/features/auth/x.ts', "import { appRoutes } from '@/app/router';\nexport const r = appRoutes;\n"],
    [
      'one feature importing another',
      'src/features/zz-lint-fixture/y.ts',
      "import { getAccessToken } from '@/features/auth/session/accessToken';\nexport const g = getAccessToken;\n",
    ],
    ['auth importing the fixture feature', 'src/features/auth/z.ts', "import { thing } from '../zz-lint-fixture/thing';\nexport const t = thing;\n"],
    ['lib importing a feature', 'src/lib/x.ts', "import { getAccessToken } from '@/features/auth/session/accessToken';\nexport const g = getAccessToken;\n"],
    ['api importing lib', 'src/api/x.ts', "import { readLastWorkspace } from '@/lib/storage';\nexport const r = readLastWorkspace;\n"],
  ])('rejects %s', async (_label, path, code) => {
    expect(await ruleIds(path, code)).toContain('import-x/no-restricted-paths');
  });

  it('allows a feature importing lib and api (non-vacuity)', async () => {
    const ids = await ruleIds(
      'src/features/auth/ok.ts',
      "import { api } from '@/api/client';\nimport { safeNext } from '@/lib/safeNext';\nexport const both = [api, safeNext];\n",
    );
    expect(ids).not.toContain('import-x/no-restricted-paths');
  });
});

describe('no request bypasses src/api', () => {
  it('rejects a raw fetch in a feature', async () => {
    expect(await ruleIds('src/features/auth/x.ts', "export const go = () => fetch('/api/v1/customers');\n")).toContain(
      'no-restricted-globals',
    );
  });

  it('rejects globalThis.fetch in a feature', async () => {
    expect(await ruleIds('src/features/auth/x.ts', "export const go = () => globalThis.fetch('/api');\n")).toContain(
      'no-restricted-properties',
    );
  });

  it('rejects importing openapi-fetch outside src/api', async () => {
    expect(await ruleIds('src/app/x.ts', "import createClient from 'openapi-fetch';\nexport const c = createClient;\n")).toContain(
      'no-restricted-imports',
    );
  });

  it('allows fetch inside src/api (non-vacuity)', async () => {
    expect(await ruleIds('src/api/x.ts', "export const go = () => fetch('/x');\n")).not.toContain('no-restricted-globals');
  });
});

describe('JSX gates', () => {
  it('rejects an <img> without alt', async () => {
    expect(await ruleIds('src/features/auth/X.tsx', 'export const X = () => <img src="/a.png" />;\n')).toContain('jsx-a11y/alt-text');
  });

  it('rejects literal JSX text in features and app', async () => {
    expect(await ruleIds('src/features/auth/X.tsx', 'export const X = () => <p>Hello</p>;\n')).toContain('i18next/no-literal-string');
    expect(await ruleIds('src/app/X.tsx', 'export const X = () => <p>Hello</p>;\n')).toContain('i18next/no-literal-string');
  });

  it('allows expression text, and literal text in tests (non-vacuity)', async () => {
    expect(await ruleIds('src/features/auth/X.tsx', 'export const X = ({ label }: { label: string }) => <p>{label}</p>;\n')).not.toContain(
      'i18next/no-literal-string',
    );
    expect(await ruleIds('src/features/auth/X.test.tsx', 'export const X = () => <p>Hello</p>;\n')).not.toContain(
      'i18next/no-literal-string',
    );
  });

  it('rejects dangerouslySetInnerHTML', async () => {
    expect(
      await ruleIds('src/features/auth/X.tsx', 'export const X = ({ html }: { html: string }) => <div dangerouslySetInnerHTML={{ __html: html }} />;\n'),
    ).toContain('react/no-danger');
  });
});
```

Run: `pnpm test src/test/eslintGates.test.ts` — Expected: FAIL (no ESLint configuration found).

- [ ] **Step 3: Write `eslint.config.js`**

```js
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import js from '@eslint/js';
import { createTypeScriptImportResolver } from 'eslint-import-resolver-typescript';
import i18next from 'eslint-plugin-i18next';
import importX from 'eslint-plugin-import-x';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// One zone per feature directory, so a new feature is isolated from the others the moment it exists
// (plan decision P12). Read at config load.
const features = readdirSync(fileURLToPath(new URL('./src/features', import.meta.url)), { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name);

const TESTS = ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.typecheck.ts'];
const BYPASS = 'Requests go through src/api/client.ts, which adds auth, the CSRF header, the timeout and refresh (spec §4.3).';

export default tseslint.config(
  { ignores: ['dist', 'reports', 'coverage', 'test-results', 'playwright-report', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { react, 'react-hooks': reactHooks, 'import-x': importX },
    settings: {
      react: { version: 'detect' },
      'import-x/resolver-next': [createTypeScriptImportResolver({ project: './tsconfig.json' })],
    },
    rules: {
      ...react.configs.flat.recommended.rules,
      ...react.configs.flat['jsx-runtime'].rules,
      'react/prop-types': 'off',
      'react/no-danger': 'error',
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  { ...jsxA11y.flatConfigs.recommended, files: ['**/*.{ts,tsx}'] },
  // Build and CI scripts (scripts/*.mjs, e2e/scripts/*.mjs) run in Node.
  { files: ['**/*.{js,mjs}'], languageOptions: { globals: globals.node } },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [...TESTS, 'src/api/**'],
    rules: {
      'no-restricted-globals': ['error', { name: 'fetch', message: BYPASS }, { name: 'XMLHttpRequest', message: BYPASS }],
      'no-restricted-properties': [
        'error',
        { object: 'window', property: 'fetch', message: BYPASS },
        { object: 'globalThis', property: 'fetch', message: BYPASS },
      ],
      'no-restricted-imports': ['error', { paths: [{ name: 'openapi-fetch', message: BYPASS }] }],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: TESTS,
    rules: {
      'import-x/no-restricted-paths': [
        'error',
        {
          zones: [
            {
              target: './src/api',
              from: ['./src/lib', './src/components', './src/features', './src/app'],
              message: 'api is the lowest layer: it imports nothing else from src.',
            },
            {
              target: ['./src/lib', './src/components'],
              from: ['./src/features', './src/app'],
              message: 'lib and components never import features or app.',
            },
            { target: './src/features', from: './src/app', message: 'features never import app.' },
            ...features.map((name) => ({
              target: `./src/features/${name}`,
              from: './src/features',
              except: [`./${name}`],
              message: 'features never import each other (spec §4.3).',
            })),
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/**/*.tsx', 'src/app/**/*.tsx'],
    ignores: TESTS,
    plugins: { i18next },
    rules: { 'i18next/no-literal-string': ['error', { mode: 'jsx-text-only' }] },
  },
);
```

- [ ] **Step 4: Run the gates, then the whole tree**

```bash
pnpm test src/test/eslintGates.test.ts
pnpm lint
```
Expected: the gate tests PASS. `pnpm lint` exits 0. **If `pnpm lint` flags existing code**, fix the code (e.g. add a missing dependency to a hook array); if a shadcn-generated file in `src/components/ui/` trips a jsx-a11y rule, fix that file. Never disable a rule to get green. If a gate test fails because a rule id differs in the installed plugin version (e.g. `import-x/no-restricted-paths` renamed), update the expected id **and** confirm by hand that the violating snippet is still reported.

- [ ] **Step 5: Commit**

```bash
pnpm typecheck && pnpm test
cd .. && git add frontend
git commit -m "build(frontend): ESLint layer, no-bypass, a11y, i18n and no-danger gates with red fixtures"
```

---

### Task 10: `/login`

**Files:**
- Create: `frontend/src/features/auth/api/authKeys.ts`, `frontend/src/features/auth/api/useLogin.ts`, `frontend/src/features/auth/workspaceState.ts`, `frontend/src/features/auth/pages/LoginPage.tsx`, `frontend/src/features/auth/pages/LoginPage.test.tsx`
- Modify: `frontend/src/app/router.tsx` (add the route)

**Interfaces:**
- Consumes: `api`, `unwrap`, `toApiFailure`, `establishSession`, `useSessionStore`, `readLastWorkspace`, `safeNext`, `applyApiError`, `useTranslator`, `useFieldError`, `TextField`, `PasswordField`, `FormAlert`, `PageHeading`, `useDocumentTitle`, `Button`.
- Produces: `authKeys = { all, signupStatus(), invitation(token) }`; `useLogin()`; `readWorkspaceState(state: unknown): string | null` (Task 11 links to `/login` with `state={{ workspace }}`); route `/login` → `LoginPage`.

- [ ] **Step 1: Write the failing page tests**

`frontend/src/features/auth/pages/LoginPage.test.tsx`:
```tsx
import { screen, waitFor } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { errorBody, ownerMe, ownerSession } from '@/test/fixtures';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const anonymous = { status: 'anonymous' as const };

async function fillAndSubmit(user: ReturnType<typeof renderApp>['user'], values = { slug: 'ravi-traders', email: 'ravi@shop.in', password: 'correct-horse-9' }) {
  const workspace = await screen.findByLabelText('Workspace');
  await user.clear(workspace);
  await user.type(workspace, values.slug);
  await user.type(screen.getByLabelText('Email'), values.email);
  await user.type(screen.getByLabelText('Password'), values.password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  it('shows one generic message on 401 and does not redirect', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(401).json(errorBody('UNAUTHORIZED', 'invalid credentials'))));
    const { user, router } = renderApp('/login', { session: anonymous });

    await fillAndSubmit(user);

    expect(await screen.findByText('Workspace, email or password is incorrect.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(document.title).toBe('Sign in · EasyCRM');
  });

  it('pre-fills the workspace remembered on this device', async () => {
    localStorage.setItem('easycrm.lastWorkspace', 'ravi-traders');
    renderApp('/login', { session: anonymous });
    expect(await screen.findByLabelText('Workspace')).toHaveValue('ravi-traders');
  });

  it('lowercases the workspace as it is typed', async () => {
    const { user } = renderApp('/login', { session: anonymous });
    await user.type(await screen.findByLabelText('Workspace'), 'Ravi-Traders');
    expect(screen.getByLabelText('Workspace')).toHaveValue('ravi-traders');
  });

  it('signs in, remembers the workspace and goes to a safe next', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(200).json(ownerSession)));
    const { user, router } = renderApp(`/login?next=${encodeURIComponent('/nope?x=1')}`, { session: anonymous });

    await fillAndSubmit(user);

    await waitFor(() => expect(router.state.location.pathname).toBe('/nope'));
    expect(router.state.location.search).toBe('?x=1');
    expect(useSessionStore.getState().me?.email).toBe('ravi@shop.in');
    expect(localStorage.getItem('easycrm.lastWorkspace')).toBe('ravi-traders');
  });

  it('ignores an unsafe next and goes home', async () => {
    server.use(http.post('/api/v1/auth/login', ({ response }) => response(200).json(ownerSession)));
    const { user } = renderApp(`/login?next=${encodeURIComponent('//evil.com')}`, { session: anonymous });

    await fillAndSubmit(user);

    expect(await screen.findByText('Signed in to ravi-traders as ravi@shop.in (Owner)')).toBeInTheDocument();
  });

  it('redirects a signed-in user away from /login', async () => {
    const { router } = renderApp('/login', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('explains a 429 with Retry-After', async () => {
    server.use(
      mswHttp.post('*/api/v1/auth/login', () =>
        HttpResponse.json(errorBody('RATE_LIMITED', 'too many requests'), { status: 429, headers: { 'Retry-After': '12' } }),
      ),
    );
    const { user } = renderApp('/login', { session: anonymous });
    await fillAndSubmit(user);
    expect(await screen.findByText('Too many attempts. Try again in 12 seconds.')).toBeInTheDocument();
  });

  it('explains a network failure', async () => {
    server.use(mswHttp.post('*/api/v1/auth/login', () => HttpResponse.error()));
    const { user } = renderApp('/login', { session: anonymous });
    await fillAndSubmit(user);
    expect(await screen.findByText("Can't reach EasyCRM. Check your connection and try again.")).toBeInTheDocument();
  });

  it('validates on the client, marks fields invalid and focuses the first', async () => {
    const { user } = renderApp('/login', { session: anonymous });
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findAllByText('This field is required.')).toHaveLength(3);
    expect(screen.getByLabelText('Workspace')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Workspace')).toHaveFocus();
  });
});
```

Add the route to `appRoutes[0].children` in `frontend/src/app/router.tsx`, directly above the `RequireSession` entry:
```tsx
      {
        path: 'login',
        lazy: () => import('@/features/auth/pages/LoginPage').then((m) => ({ Component: m.LoginPage })),
        errorElement: <RouteErrorBoundary />,
      },
```

Run: `pnpm test src/features/auth/pages/LoginPage.test.tsx` — Expected: FAIL (module not found).

- [ ] **Step 2: Implement**

`frontend/src/features/auth/api/authKeys.ts`:
```ts
export const authKeys = {
  all: ['auth'] as const,
  signupStatus: () => [...authKeys.all, 'signup-status'] as const,
  invitation: (token: string) => [...authKeys.all, 'invitation', token] as const,
};
```

`frontend/src/features/auth/api/useLogin.ts`:
```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { LoginRequest } from '@/api/types';

export function useLogin() {
  return useMutation({
    mutationFn: async (body: LoginRequest) => unwrap(await api.POST('/api/v1/auth/login', { body })),
  });
}
```

`frontend/src/features/auth/workspaceState.ts`:
```ts
/** Router state a page may pass to /login to pre-fill the workspace (signup's lost-response hint). */
export function readWorkspaceState(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('workspace' in state)) return null;
  return typeof state.workspace === 'string' ? state.workspace : null;
}
```

`frontend/src/features/auth/pages/LoginPage.tsx`:
```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router';
import * as z from 'zod/mini';
import { toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PasswordField } from '@/components/form/PasswordField';
import { TextField } from '@/components/form/TextField';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { applyApiError } from '@/lib/apiError';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { safeNext } from '@/lib/safeNext';
import { readLastWorkspace } from '@/lib/storage';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useLogin } from '../api/useLogin';
import { establishSession } from '../session/session';
import { useSessionStore } from '../session/sessionStore';
import { readWorkspaceState } from '../workspaceState';

const loginSchema = z.object({
  slug: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  email: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  password: z.string().check(z.minLength(1, { error: 'validation.required' })),
});
type LoginValues = z.infer<typeof loginSchema>;
const LOGIN_FIELDS = ['slug', 'email', 'password'] as const;

export function LoginPage() {
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const status = useSessionStore((s) => s.status);
  const location = useLocation();
  const [params] = useSearchParams();
  const login = useLogin();
  const [formMessage, setFormMessage] = useState<string | null>(null);
  useDocumentTitle(t('login.title'));

  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { slug: readWorkspaceState(location.state) ?? readLastWorkspace() ?? '', email: '', password: '' },
  });

  // Also the post-login navigation: establishSession flips status, and this sends the user to `next`.
  if (status === 'authenticated') return <Navigate to={safeNext(params.get('next')) ?? '/'} replace />;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormMessage(null);
    try {
      establishSession(await login.mutateAsync(values));
    } catch (error) {
      const failure = toApiFailure(error);
      if (failure.kind === 'http' && failure.status === 401) {
        setFormMessage(t('login.invalidCredentials'));
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: LOGIN_FIELDS }, translator).formMessage);
    }
  });

  const slugField = form.register('slug');
  const { errors, isSubmitting } = form.formState;

  return (
    <main className="mx-auto grid w-full max-w-sm gap-6 px-4 py-10">
      <PageHeading>{t('login.heading')}</PageHeading>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        <FormAlert message={formMessage} />
        <TextField
          id="login-slug"
          label={t('login.workspace')}
          description={t('login.workspaceHint')}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          error={fieldError(errors.slug)}
          {...slugField}
          onChange={(event) => {
            const lower = event.target.value.toLowerCase();
            if (lower !== event.target.value) event.target.value = lower;
            void slugField.onChange(event);
          }}
        />
        <TextField
          id="login-email"
          type="email"
          autoComplete="username"
          label={t('login.email')}
          error={fieldError(errors.email)}
          {...form.register('email')}
        />
        <PasswordField
          id="login-password"
          autoComplete="current-password"
          label={t('login.password')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? t('login.submitting') : t('login.submit')}
        </Button>
      </form>
      <p className="text-sm">
        <Trans t={t} i18nKey="login.noAccount" components={{ link: <Link to="/signup" className="underline" /> }} />
      </p>
    </main>
  );
}
```

- [ ] **Step 3: Run, lint, commit**

```bash
pnpm test src/features/auth/pages/LoginPage.test.tsx && pnpm lint && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): login page with remembered workspace, safe next and mapped errors"
```
Expected: 9 tests PASS; lint and typecheck clean.

---

### Task 11: `/signup`

**Files:**
- Create: `frontend/src/lib/gst/states.ts`, `frontend/src/lib/gst/states.test.ts`, `frontend/src/lib/slug.ts`, `frontend/src/lib/slug.test.ts`
- Create: `frontend/src/features/auth/api/useSignup.ts`, `useSignupStatus.ts`, `frontend/src/features/auth/pages/SignupPage.tsx`, `SignupPage.test.tsx`
- Modify: `frontend/src/app/router.tsx`

**Interfaces:**
- Consumes: Task 10's `authKeys`, `readWorkspaceState` contract (`state={{ workspace }}`), and everything Task 10 consumed plus `SelectField`, `Skeleton`, `parseEnvelope`.
- Produces: `GST_STATES: readonly { code: string; name: string }[]`; `suggestSlug(businessName: string): string`; `useSignup()`; `useSignupStatus()`; route `/signup`.

- [ ] **Step 1: States and slug helpers, test first**

`frontend/src/lib/gst/states.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { GST_STATES } from './states';

describe('GST_STATES', () => {
  it('matches the backend exactly: 01–38, 97 and 99 (platform-primitives StateCode)', () => {
    const expected = [...Array.from({ length: 38 }, (_, i) => String(i + 1).padStart(2, '0')), '97', '99'];
    expect(GST_STATES.map((s) => s.code)).toEqual(expected);
    expect(GST_STATES.every((s) => s.name.length > 0)).toBe(true);
  });
});
```

`frontend/src/lib/gst/states.ts`:
```ts
/**
 * GST state codes, in code order. Must equal StateCode.VALID in platform-primitives (01–38, 97, 99);
 * states.test.ts pins it. Names are proper nouns shown after the code ("27 – Maharashtra"); per spec
 * §4.7 the Hindi pass keeps the code first.
 */
export const GST_STATES = [
  { code: '01', name: 'Jammu and Kashmir' },
  { code: '02', name: 'Himachal Pradesh' },
  { code: '03', name: 'Punjab' },
  { code: '04', name: 'Chandigarh' },
  { code: '05', name: 'Uttarakhand' },
  { code: '06', name: 'Haryana' },
  { code: '07', name: 'Delhi' },
  { code: '08', name: 'Rajasthan' },
  { code: '09', name: 'Uttar Pradesh' },
  { code: '10', name: 'Bihar' },
  { code: '11', name: 'Sikkim' },
  { code: '12', name: 'Arunachal Pradesh' },
  { code: '13', name: 'Nagaland' },
  { code: '14', name: 'Manipur' },
  { code: '15', name: 'Mizoram' },
  { code: '16', name: 'Tripura' },
  { code: '17', name: 'Meghalaya' },
  { code: '18', name: 'Assam' },
  { code: '19', name: 'West Bengal' },
  { code: '20', name: 'Jharkhand' },
  { code: '21', name: 'Odisha' },
  { code: '22', name: 'Chhattisgarh' },
  { code: '23', name: 'Madhya Pradesh' },
  { code: '24', name: 'Gujarat' },
  { code: '25', name: 'Daman and Diu' },
  { code: '26', name: 'Dadra and Nagar Haveli and Daman and Diu' },
  { code: '27', name: 'Maharashtra' },
  { code: '28', name: 'Andhra Pradesh (before division)' },
  { code: '29', name: 'Karnataka' },
  { code: '30', name: 'Goa' },
  { code: '31', name: 'Lakshadweep' },
  { code: '32', name: 'Kerala' },
  { code: '33', name: 'Tamil Nadu' },
  { code: '34', name: 'Puducherry' },
  { code: '35', name: 'Andaman and Nicobar Islands' },
  { code: '36', name: 'Telangana' },
  { code: '37', name: 'Andhra Pradesh' },
  { code: '38', name: 'Ladakh' },
  { code: '97', name: 'Other Territory' },
  { code: '99', name: 'Centre Jurisdiction' },
] as const;
```

`frontend/src/lib/slug.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { suggestSlug } from './slug';

describe('suggestSlug', () => {
  it.each([
    ['Ravi Traders & Sons', 'ravi-traders-sons'],
    ['  A.B.C.  ', 'a-b-c'],
    ['Café Müller', 'cafe-muller'],
    ['Sharma शर्मा Traders', 'sharma-traders'],
    ['शर्मा ट्रेडर्स', ''],
    ['AB', ''],
    ['', ''],
  ])('%s → %s', (input, expected) => {
    expect(suggestSlug(input)).toBe(expected);
  });

  it('caps at 64 characters without a trailing hyphen, matching [a-z0-9-]{3,64}', () => {
    const slug = suggestSlug(`${'a'.repeat(63)} b ${'c'.repeat(10)}`);
    expect(slug.length).toBeLessThanOrEqual(64);
    expect(slug).toMatch(/^[a-z0-9-]{3,64}$/);
    expect(slug.endsWith('-')).toBe(false);
  });
});
```

`frontend/src/lib/slug.ts`:
```ts
/**
 * A starting suggestion for the workspace slug; the user can edit it. Non-Latin scripts have no ASCII
 * form, so a Devanagari-only name suggests nothing rather than something wrong.
 */
export function suggestSlug(businessName: string): string {
  const ascii = businessName.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const slug = ascii
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
    .replace(/-+$/, '');
  return slug.length >= 3 ? slug : '';
}
```

Run: `pnpm test src/lib/gst src/lib/slug.test.ts` — Expected: FAIL before the implementations exist, PASS after.

- [ ] **Step 2: Write the failing page tests**

`frontend/src/features/auth/pages/SignupPage.test.tsx`:
```tsx
import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { useSessionStore } from '@/features/auth/session/sessionStore';
import { errorBody, ownerSession } from '@/test/fixtures';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const anonymous = { status: 'anonymous' as const };
const open = http.get('/api/v1/auth/signup/status', ({ response }) => response(200).json({ open: true }));
const slugTaken = http.post('/api/v1/auth/signup', ({ response }) =>
  response(409).json(errorBody('CONFLICT', 'slug already taken', { fields: { slug: 'slug already taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } })),
);

async function fillValidForm(user: ReturnType<typeof renderApp>['user']) {
  await user.type(await screen.findByLabelText('Business name'), 'Ravi Traders');
  await user.selectOptions(screen.getByLabelText('State'), '27');
  await user.type(screen.getByLabelText('Email'), 'ravi@shop.in');
  await user.type(screen.getByLabelText('Password'), 'correct-horse-9');
}
const submit = (user: ReturnType<typeof renderApp>['user']) => user.click(screen.getByRole('button', { name: 'Create workspace' }));

describe('SignupPage', () => {
  it('shows the closed state with a sign-in link and no form', async () => {
    server.use(http.get('/api/v1/auth/signup/status', ({ response }) => response(200).json({ open: false })));
    renderApp('/signup', { session: anonymous });

    expect(await screen.findByRole('heading', { name: 'Signups are currently closed' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'sign in' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('button', { name: 'Create workspace' })).not.toBeInTheDocument();
  });

  it('suggests a slug from the business name until the slug is edited', async () => {
    server.use(open);
    const { user } = renderApp('/signup', { session: anonymous });

    await user.type(await screen.findByLabelText('Business name'), 'Ravi Traders');
    expect(screen.getByLabelText('Workspace name')).toHaveValue('ravi-traders');

    await user.clear(screen.getByLabelText('Workspace name'));
    await user.type(screen.getByLabelText('Workspace name'), 'Ravi-HQ');
    await user.type(screen.getByLabelText('Business name'), ' and Sons');
    expect(screen.getByLabelText('Workspace name')).toHaveValue('ravi-hq');
  });

  it('creates the workspace, omits blank optional fields, and lands on home', async () => {
    let sent: unknown;
    server.use(
      open,
      http.post('/api/v1/auth/signup', async ({ request, response }) => {
        sent = await request.json();
        return response(201).json(ownerSession);
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    expect(await screen.findByText('Signed in to ravi-traders as ravi@shop.in (Owner)')).toBeInTheDocument();
    expect(sent).toEqual({ businessName: 'Ravi Traders', slug: 'ravi-traders', stateCode: '27', email: 'ravi@shop.in', password: 'correct-horse-9' });
    expect(useSessionStore.getState().status).toBe('authenticated');
  });

  it('puts SLUG_TAKEN on the slug field', async () => {
    server.use(open, slugTaken);
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);

    expect(await screen.findByText('This workspace name is taken.')).toBeInTheDocument();
    expect(screen.getByLabelText('Workspace name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Workspace name')).toHaveFocus();
  });

  it('focuses the GSTIN on a 422 GSTIN_CHECKSUM, uppercasing what was typed', async () => {
    let sentGstin: unknown;
    server.use(
      open,
      http.post('/api/v1/auth/signup', async ({ request, response }) => {
        sentGstin = (await request.json()).gstin;
        return response(422).json(
          errorBody('VALIDATION_FAILED', 'invalid GSTIN', { fields: { gstin: 'GSTIN check digit mismatch' }, fieldCodes: { gstin: 'GSTIN_CHECKSUM' } }),
        );
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await user.type(screen.getByLabelText('GSTIN (optional)'), '27aaaaa0000a1z0');
    await submit(user);

    expect(await screen.findByText('This GSTIN is not valid. Check it for typos.')).toBeInTheDocument();
    expect(screen.getByLabelText('GSTIN (optional)')).toHaveFocus();
    expect(sentGstin).toBe('27AAAAA0000A1Z0');
  });

  it('after a lost response, a SLUG_TAKEN for the same slug offers sign-in instead of a field error', async () => {
    let calls = 0;
    server.use(
      open,
      mswHttp.post('*/api/v1/auth/signup', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.error()
          : HttpResponse.json(errorBody('CONFLICT', 'slug already taken', { fields: { slug: 'slug already taken' }, fieldCodes: { slug: 'SLUG_TAKEN' } }), {
              status: 409,
            });
      }),
    );
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);
    expect(await screen.findByText("Can't reach EasyCRM. Check your connection and try again.")).toBeInTheDocument();

    await submit(user);

    // Scoped to the hint: the page footer ("Already have a workspace? Sign in") has a link of the same name.
    const hint = within(await screen.findByRole('status')).getByRole('link', { name: 'Sign in' });
    expect(hint).toHaveAttribute('href', '/login');
    expect(screen.getByLabelText('Workspace name')).not.toHaveAttribute('aria-invalid');
  });

  it('keeps a genuine SLUG_TAKEN (no earlier lost response) as a field error', async () => {
    server.use(open, slugTaken);
    const { user } = renderApp('/signup', { session: anonymous });

    await fillValidForm(user);
    await submit(user);
    await screen.findByText('This workspace name is taken.');
    await submit(user);

    await waitFor(() => expect(screen.getByLabelText('Workspace name')).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.queryByText(/may already have been created/)).not.toBeInTheDocument();
  });
});
```

Add the route (above `RequireSession`, below `/login`):
```tsx
      {
        path: 'signup',
        lazy: () => import('@/features/auth/pages/SignupPage').then((m) => ({ Component: m.SignupPage })),
        errorElement: <RouteErrorBoundary />,
      },
```

Run — Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

`frontend/src/features/auth/api/useSignupStatus.ts`:
```ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import { authKeys } from './authKeys';

export function useSignupStatus() {
  return useQuery({
    queryKey: authKeys.signupStatus(),
    queryFn: async () => unwrap(await api.GET('/api/v1/auth/signup/status')),
  });
}
```

`frontend/src/features/auth/api/useSignup.ts`:
```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { SignupRequest } from '@/api/types';

export function useSignup() {
  return useMutation({
    mutationFn: async (body: SignupRequest) => unwrap(await api.POST('/api/v1/auth/signup', { body })),
  });
}
```

`frontend/src/features/auth/pages/SignupPage.tsx`:
```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router';
import * as z from 'zod/mini';
import { toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PasswordField } from '@/components/form/PasswordField';
import { SelectField } from '@/components/form/SelectField';
import { TextField } from '@/components/form/TextField';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { applyApiError, parseEnvelope } from '@/lib/apiError';
import { GST_STATES } from '@/lib/gst/states';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { suggestSlug } from '@/lib/slug';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useSignup } from '../api/useSignup';
import { useSignupStatus } from '../api/useSignupStatus';
import { establishSession } from '../session/session';
import { useSessionStore } from '../session/sessionStore';

const signupSchema = z.object({
  businessName: z.string().check(z.trim(), z.minLength(1, { error: 'validation.required' })),
  slug: z.string().check(z.regex(/^[a-z0-9-]{3,64}$/, { error: 'validation.slugFormat' })),
  stateCode: z.string().check(z.regex(/^\d{2}$/, { error: 'validation.stateRequired' })),
  gstin: z.string().check(
    z.trim(),
    z.toUpperCase(),
    z.refine((value) => value === '' || /^[0-9A-Z]{15}$/.test(value), { error: 'validation.gstinShape' }),
  ),
  email: z.email({ error: 'validation.email' }),
  phone: z.string().check(z.trim()),
  password: z.string().check(z.minLength(8, { error: 'validation.passwordMin' })),
});
type SignupValues = z.infer<typeof signupSchema>;
const SIGNUP_FIELDS = ['businessName', 'slug', 'stateCode', 'gstin', 'email', 'phone', 'password'] as const;
const STATE_OPTIONS = GST_STATES.map((s) => ({ value: s.code, label: `${s.code} – ${s.name}` }));

export function SignupPage() {
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const status = useSessionStore((s) => s.status);
  const signupStatus = useSignupStatus();
  const signup = useSignup();
  // Slugs whose submit got no response: the workspace may exist even though we never heard back.
  const lostSlugs = useRef(new Set<string>());
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [maybeCreatedSlug, setMaybeCreatedSlug] = useState<string | null>(null);
  useDocumentTitle(t('signup.title'));

  const form = useForm<SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { businessName: '', slug: '', stateCode: '', gstin: '', email: '', phone: '', password: '' },
  });
  const businessName = useWatch({ control: form.control, name: 'businessName' });
  useEffect(() => {
    if (!form.getFieldState('slug').isDirty) form.setValue('slug', suggestSlug(businessName));
  }, [businessName, form]);

  if (status === 'authenticated') return <Navigate to="/" replace />;

  if (signupStatus.isPending) {
    return (
      <main className="mx-auto grid w-full max-w-md gap-6 px-4 py-10" aria-busy="true">
        <p className="sr-only">{t('signup.loading')}</p>
        <Skeleton aria-hidden className="h-8 w-3/4" />
        <Skeleton aria-hidden className="h-[34rem] w-full" />
      </main>
    );
  }

  if (signupStatus.data?.open === false) {
    return (
      <main className="mx-auto grid w-full max-w-md gap-4 px-4 py-10">
        <PageHeading>{t('signup.closedHeading')}</PageHeading>
        <p>
          <Trans t={t} i18nKey="signup.closedBody" components={{ link: <Link to="/login" className="underline" /> }} />
        </p>
      </main>
    );
  }
  // A failed status check still shows the form: the server enforces the switch either way.

  const onSubmit = form.handleSubmit(async (values) => {
    setFormMessage(null);
    setMaybeCreatedSlug(null);
    try {
      establishSession(
        await signup.mutateAsync({
          businessName: values.businessName,
          slug: values.slug,
          stateCode: values.stateCode,
          email: values.email,
          password: values.password,
          gstin: values.gstin || undefined,
          phone: values.phone || undefined,
        }),
      );
    } catch (error) {
      const failure = toApiFailure(error);
      if (failure.kind === 'network') lostSlugs.current.add(values.slug);
      const slugTaken =
        failure.kind === 'http' && failure.status === 409 && parseEnvelope(failure.body)?.fieldCodes.slug === 'SLUG_TAKEN';
      if (slugTaken && lostSlugs.current.has(values.slug)) {
        setMaybeCreatedSlug(values.slug);
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: SIGNUP_FIELDS }, translator).formMessage);
    }
  });

  const slugField = form.register('slug');
  const gstinField = form.register('gstin');
  const { errors, isSubmitting } = form.formState;

  return (
    <main className="mx-auto grid w-full max-w-md gap-6 px-4 py-10">
      <PageHeading>{t('signup.heading')}</PageHeading>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        <FormAlert message={formMessage} />
        {maybeCreatedSlug && (
          <p role="status" className="rounded-md border p-3 text-sm">
            <Trans
              t={t}
              i18nKey="signup.maybeCreated"
              components={{ link: <Link to="/login" state={{ workspace: maybeCreatedSlug }} className="underline" /> }}
            />
          </p>
        )}
        <TextField
          id="signup-business"
          autoComplete="organization"
          label={t('signup.businessName')}
          error={fieldError(errors.businessName)}
          {...form.register('businessName')}
        />
        <TextField
          id="signup-slug"
          label={t('signup.slug')}
          description={t('signup.slugHint')}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          error={fieldError(errors.slug)}
          {...slugField}
          onChange={(event) => {
            const lower = event.target.value.toLowerCase();
            if (lower !== event.target.value) event.target.value = lower;
            void slugField.onChange(event);
          }}
        />
        <SelectField
          id="signup-state"
          label={t('signup.state')}
          placeholder={t('signup.statePlaceholder')}
          options={STATE_OPTIONS}
          error={fieldError(errors.stateCode)}
          {...form.register('stateCode')}
        />
        <TextField
          id="signup-gstin"
          label={t('signup.gstin')}
          description={t('signup.gstinHint')}
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          error={fieldError(errors.gstin)}
          {...gstinField}
          onChange={(event) => {
            const upper = event.target.value.toUpperCase();
            if (upper !== event.target.value) event.target.value = upper;
            void gstinField.onChange(event);
          }}
        />
        <TextField
          id="signup-email"
          type="email"
          autoComplete="email"
          label={t('signup.email')}
          error={fieldError(errors.email)}
          {...form.register('email')}
        />
        <TextField
          id="signup-phone"
          type="tel"
          autoComplete="tel"
          label={t('signup.phone')}
          error={fieldError(errors.phone)}
          {...form.register('phone')}
        />
        <PasswordField
          id="signup-password"
          autoComplete="new-password"
          label={t('signup.password')}
          description={t('signup.passwordHint')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? t('signup.submitting') : t('signup.submit')}
        </Button>
      </form>
      <p className="text-sm">
        <Trans t={t} i18nKey="signup.haveAccount" components={{ link: <Link to="/login" className="underline" /> }} />
      </p>
    </main>
  );
}
```

- [ ] **Step 4: Run, lint, commit**

```bash
pnpm test src/features/auth/pages/SignupPage.test.tsx src/lib && pnpm lint && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): signup page with slug suggestion, GST state picker and lost-response hint"
```
Expected: PASS; clean.

---

### Task 12: `/invite/:token`

**Files:**
- Create: `frontend/src/features/auth/api/useInvitationPreview.ts`, `useAcceptInvitation.ts`, `frontend/src/features/auth/roleLabel.ts`, `frontend/src/features/auth/pages/InvitePage.tsx`, `InvitePage.test.tsx`
- Modify: `frontend/src/app/router.tsx`

**Interfaces:**
- Consumes: Tasks 10–11's building blocks, `sessionControls().logout()`, `ApiHttpError`, `isRole`.
- Produces: `useInvitationPreview(token)`, `useAcceptInvitation(token)`, `useRoleLabel(): (role: string) => string`, route `invite/:token` (the path the backend's `acceptUrl` uses: `publicBaseUrl + "/invite/" + rawToken`).

- [ ] **Step 1: Write the failing page tests**

`frontend/src/features/auth/pages/InvitePage.test.tsx`:
```tsx
import { screen, waitFor } from '@testing-library/react';
import { delay, HttpResponse, http as mswHttp } from 'msw';
import { describe, expect, it } from 'vitest';
import { errorBody, inviteeSession, ownerMe } from '@/test/fixtures';
import { server } from '@/test/msw';
import { http } from '@/test/openapiHttp';
import { renderApp } from '@/test/renderApp';

const preview = { businessName: 'Ravi Traders', email: 'asha@shop.in', role: 'SALES_EXEC' };
const validPreview = http.get('/api/v1/auth/invitations/{token}', ({ params, response }) =>
  params.token === 'good-token' ? response(200).json(preview) : response(404).json(errorBody('NOT_FOUND', 'not found')),
);
const paragraph = (text: string) => (_: string, el: Element | null) => el?.tagName === 'P' && el.textContent === text;

describe('InvitePage', () => {
  it('shows a loading state sized like the card, then the invitation', async () => {
    server.use(
      http.get('/api/v1/auth/invitations/{token}', async ({ response }) => {
        await delay(50);
        return response(200).json(preview);
      }),
    );
    renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    expect(await screen.findByText('Loading invitation')).toBeInTheDocument();
    expect(await screen.findByText(paragraph('Join Ravi Traders as Sales executive'))).toBeInTheDocument();
  });

  it('shows one invalid state for an unknown or expired token', async () => {
    server.use(validPreview);
    renderApp('/invite/bad-token', { session: { status: 'anonymous' } });
    expect(await screen.findByText('This invitation link is invalid or has expired.')).toBeInTheDocument();
  });

  it('accepts as an anonymous visitor and replaces the token URL with home', async () => {
    let sent: unknown;
    server.use(
      validPreview,
      http.post('/api/v1/auth/invitations/{token}/accept', async ({ request, response }) => {
        sent = await request.json();
        return response(201).json(inviteeSession);
      }),
    );
    const { user, router } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    const email = await screen.findByLabelText('Email');
    expect(email).toHaveValue('asha@shop.in');
    expect(email).toHaveAttribute('readonly');
    expect(email).toHaveAttribute('autocomplete', 'username');

    await user.type(screen.getByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    expect(await screen.findByText('Signed in to ravi-traders as asha@shop.in (Sales executive)')).toBeInTheDocument();
    expect(sent).toEqual({ password: 'correct-horse-9' });
    expect(router.state.location.pathname).toBe('/');
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('offers sign-out-and-accept to a signed-in user, then shows the accept form', async () => {
    server.use(validPreview, http.post('/api/v1/auth/logout', ({ response }) => response(204).empty()));
    const { user } = renderApp('/invite/good-token', { session: { status: 'authenticated', me: ownerMe, accessToken: 't' } });

    expect(
      await screen.findByText(paragraph("This invitation is for asha@shop.in to join Ravi Traders. You're signed in as ravi@shop.in.")),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to my workspace' })).toHaveAttribute('href', '/');

    await user.click(screen.getByRole('button', { name: 'Sign out and accept' }));

    expect(await screen.findByLabelText('Choose a password')).toBeInTheDocument();
  });

  it('after a lost accept response, a 404 suggests signing in instead of the invalid state', async () => {
    let calls = 0;
    server.use(
      validPreview,
      mswHttp.post('*/api/v1/auth/invitations/good-token/accept', () => {
        calls += 1;
        return calls === 1 ? HttpResponse.error() : HttpResponse.json(errorBody('NOT_FOUND', 'not found'), { status: 404 });
      }),
    );
    const { user } = renderApp('/invite/good-token', { session: { status: 'anonymous' } });

    await user.type(await screen.findByLabelText('Choose a password'), 'correct-horse-9');
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));
    await screen.findByText("Can't reach EasyCRM. Check your connection and try again.");
    await user.click(screen.getByRole('button', { name: 'Join workspace' }));

    expect(await screen.findByText(paragraph('If you already set your password, sign in.'))).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('This invitation link is invalid or has expired.')).not.toBeInTheDocument());
  });
});
```

Add the route (above `RequireSession`):
```tsx
      {
        path: 'invite/:token',
        lazy: () => import('@/features/auth/pages/InvitePage').then((m) => ({ Component: m.InvitePage })),
        errorElement: <RouteErrorBoundary />,
      },
```

Run — Expected: FAIL (module not found).

- [ ] **Step 2: Implement**

`frontend/src/features/auth/api/useInvitationPreview.ts`:
```ts
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import { authKeys } from './authKeys';

export function useInvitationPreview(token: string) {
  return useQuery({
    queryKey: authKeys.invitation(token),
    queryFn: async () => unwrap(await api.GET('/api/v1/auth/invitations/{token}', { params: { path: { token } } })),
    enabled: token !== '',
  });
}
```

`frontend/src/features/auth/api/useAcceptInvitation.ts`:
```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '@/api/client';
import { unwrap } from '@/api/errors';
import type { AcceptInvitationRequest } from '@/api/types';

export function useAcceptInvitation(token: string) {
  return useMutation({
    mutationFn: async (body: AcceptInvitationRequest) =>
      unwrap(await api.POST('/api/v1/auth/invitations/{token}/accept', { params: { path: { token } }, body })),
  });
}
```

`frontend/src/features/auth/roleLabel.ts`:
```ts
import { useTranslation } from 'react-i18next';
import { isRole } from './session/toMe';

export function useRoleLabel(): (role: string) => string {
  const { t } = useTranslation('common');
  return (role) => (isRole(role) ? t(`roles.${role}`) : role);
}
```

`frontend/src/features/auth/pages/InvitePage.tsx`:
```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import * as z from 'zod/mini';
import { ApiHttpError, toApiFailure } from '@/api/errors';
import { FormAlert } from '@/components/form/FormAlert';
import { PasswordField } from '@/components/form/PasswordField';
import { TextField } from '@/components/form/TextField';
import { PageHeading } from '@/components/PageHeading';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { applyApiError } from '@/lib/apiError';
import { useFieldError, useTranslator } from '@/lib/i18n/translator';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useAcceptInvitation } from '../api/useAcceptInvitation';
import { useInvitationPreview } from '../api/useInvitationPreview';
import { useRoleLabel } from '../roleLabel';
import { establishSession } from '../session/session';
import { useSessionStore } from '../session/sessionStore';
import { sessionControls } from '../session/start';

const acceptSchema = z.object({
  password: z.string().check(z.minLength(8, { error: 'validation.passwordMin' })),
  phone: z.string().check(z.trim()),
});
type AcceptValues = z.infer<typeof acceptSchema>;
const ACCEPT_FIELDS = ['password', 'phone'] as const;
const CARD = 'mx-auto grid w-full max-w-md gap-6 px-4 py-10';

export function InvitePage() {
  const { token = '' } = useParams();
  const { t } = useTranslation('auth');
  const { t: tc } = useTranslation('common');
  const translator = useTranslator();
  const fieldError = useFieldError();
  const roleLabel = useRoleLabel();
  const navigate = useNavigate();
  const status = useSessionStore((s) => s.status);
  const me = useSessionStore((s) => s.me);
  const preview = useInvitationPreview(token); // in parallel with boot (spec §5.1)
  const accept = useAcceptInvitation(token);
  const acceptLost = useRef(false);
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [maybeAccepted, setMaybeAccepted] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [accepted, setAccepted] = useState(false);
  useDocumentTitle(t('invite.title'));

  const form = useForm<AcceptValues>({ resolver: zodResolver(acceptSchema), defaultValues: { password: '', phone: '' } });

  if (accepted) return null; // navigating home; never flash the signed-in card for our own new session

  if (preview.isPending || status === 'booting') {
    return (
      <main className={CARD} aria-busy="true">
        <p className="sr-only">{t('invite.loading')}</p>
        <Skeleton aria-hidden className="h-8 w-2/3" />
        <Skeleton aria-hidden className="h-6 w-full" />
        <Skeleton aria-hidden className="h-64 w-full" />
      </main>
    );
  }

  if (preview.isError) {
    const invalid = preview.error instanceof ApiHttpError && preview.error.status === 404;
    return (
      <main className={CARD}>
        <PageHeading>{t('invite.heading')}</PageHeading>
        {invalid ? (
          <p>{t('invite.invalid')}</p>
        ) : (
          <>
            <FormAlert message={tc('errors.network')} />
            <Button onClick={() => void preview.refetch()}>{tc('actions.retry')}</Button>
          </>
        )}
      </main>
    );
  }

  const invitation = preview.data;

  if (status === 'authenticated' && me) {
    // F0-10: no redirect (it would lose the link), no silent accept (it would replace this session in every tab).
    return (
      <main className={CARD}>
        <PageHeading>{t('invite.heading')}</PageHeading>
        <p>
          <Trans
            t={t}
            i18nKey="invite.signedInAs"
            values={{ inviteEmail: invitation.email, businessName: invitation.businessName, email: me.email }}
            components={{ strong: <strong /> }}
          />
        </p>
        <div className="flex flex-wrap gap-3">
          <Button
            disabled={signingOut}
            onClick={async () => {
              setSigningOut(true);
              try {
                await sessionControls().logout();
              } finally {
                setSigningOut(false);
              }
            }}
          >
            {t('invite.signOutAndAccept')}
          </Button>
          <Button variant="outline" asChild>
            <Link to="/">{t('invite.goToWorkspace')}</Link>
          </Button>
        </div>
      </main>
    );
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setFormMessage(null);
    setMaybeAccepted(false);
    try {
      const session = await accept.mutateAsync({ password: values.password, phone: values.phone || undefined });
      setAccepted(true);
      establishSession(session);
      void navigate('/', { replace: true }); // Back must not return to the token URL
    } catch (error) {
      const failure = toApiFailure(error);
      if (failure.kind === 'network') {
        acceptLost.current = true;
      } else if (failure.status === 404) {
        if (acceptLost.current) setMaybeAccepted(true);
        else setFormMessage(t('invite.invalid'));
        return;
      }
      setFormMessage(applyApiError(failure, { setError: form.setError, fields: ACCEPT_FIELDS }, translator).formMessage);
    }
  });

  const { errors, isSubmitting } = form.formState;

  return (
    <main className={CARD}>
      <PageHeading>{t('invite.heading')}</PageHeading>
      <p>
        <Trans
          t={t}
          i18nKey="invite.join"
          values={{ businessName: invitation.businessName, role: roleLabel(invitation.role) }}
          components={{ strong: <strong /> }}
        />
      </p>
      <form noValidate onSubmit={onSubmit} className="grid gap-4">
        <FormAlert message={formMessage} />
        {maybeAccepted && (
          <p role="status" className="rounded-md border p-3 text-sm">
            <Trans t={t} i18nKey="invite.maybeAccepted" components={{ link: <Link to="/login" className="underline" /> }} />
          </p>
        )}
        {/* Read-only, but a real input with autocomplete=username so password managers save the right account. */}
        <TextField id="invite-email" type="email" autoComplete="username" readOnly value={invitation.email} label={t('invite.email')} />
        <PasswordField
          id="invite-password"
          autoComplete="new-password"
          label={t('invite.password')}
          description={t('invite.passwordHint')}
          showLabel={tc('password.show')}
          error={fieldError(errors.password)}
          {...form.register('password')}
        />
        <TextField
          id="invite-phone"
          type="tel"
          autoComplete="tel"
          label={t('invite.phone')}
          error={fieldError(errors.phone)}
          {...form.register('phone')}
        />
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? t('invite.submitting') : t('invite.submit')}
        </Button>
      </form>
    </main>
  );
}
```
(`Button asChild` requires the shadcn button's Radix `Slot` — present in the generated `button.tsx`. The JSX comment is not JSX text, so `no-literal-string` does not flag it.)

- [ ] **Step 3: Run, lint, commit**

```bash
pnpm test && pnpm lint && pnpm typecheck
cd .. && git add frontend
git commit -m "feat(frontend): invite page with sign-out-and-accept and lost-response hint"
```
Expected: PASS; clean.

---

### Task 13: Per-route JS budget and the measured dependency ledger

**Files:**
- Create: `frontend/scripts/check-budget.mjs`, `frontend/scripts/check-budget.test.ts`, `frontend/scripts/dependency-sizes.mjs`
- Modify: `frontend/vite.config.ts` (visualizer), `frontend/DEPENDENCIES.md`

**Interfaces:**
- Consumes: `dist/.vite/manifest.json` from `vite build` (Task 2 set `build.manifest: true`).
- Produces: `pnpm budget` (exit 1 if any public route's first-visit JS exceeds 200 KB gzipped **or resolves zero files**); `ANALYZE=true pnpm build` writes `reports/bundle.html` and `reports/bundle.json`; `pnpm deps:sizes` prints per-package gzipped sizes.

- [ ] **Step 1: Write the failing budget tests**

`frontend/scripts/check-budget.test.ts`:
```ts
// @vitest-environment node
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { BUDGET_BYTES, measure, routeFiles } from './check-budget.mjs';

const script = fileURLToPath(new URL('./check-budget.mjs', import.meta.url));
const dirs: string[] = [];
afterEach(() => dirs.splice(0).forEach((d) => rmSync(d, { recursive: true, force: true })));

/** A fake dist/: random bytes are incompressible, so gzip size ≈ file size. */
function dist(files: Record<string, number>, manifest: Record<string, unknown>) {
  const dir = mkdtempSync(join(tmpdir(), 'budget-'));
  dirs.push(dir);
  mkdirSync(join(dir, '.vite'), { recursive: true });
  mkdirSync(join(dir, 'assets'), { recursive: true });
  for (const [name, size] of Object.entries(files)) writeFileSync(join(dir, name), randomBytes(size));
  writeFileSync(join(dir, '.vite', 'manifest.json'), JSON.stringify(manifest));
  return dir;
}

const ROUTES = { '/login': 'src/Login.tsx' };
const manifest = {
  'index.html': { file: 'assets/entry.js', imports: ['_shared.js'], dynamicImports: ['src/Login.tsx', 'src/Huge.tsx'], css: ['assets/entry.css'] },
  '_shared.js': { file: 'assets/shared.js' },
  'src/Login.tsx': { file: 'assets/login.js', imports: ['_shared.js'] },
  'src/Huge.tsx': { file: 'assets/huge.js' },
};

describe('routeFiles', () => {
  it('follows static imports from the entry and the route chunk, but not other dynamic imports', () => {
    expect(routeFiles(manifest, 'src/Login.tsx').sort()).toEqual(['assets/entry.js', 'assets/login.js', 'assets/shared.js']);
  });

  it('throws when the route is not in the manifest', () => {
    expect(() => routeFiles(manifest, 'src/Missing.tsx')).toThrow(/no entry src\/Missing.tsx/);
  });
});

describe('measure', () => {
  it('passes a route under budget and ignores unrelated lazy chunks', () => {
    const dir = dist({ 'assets/entry.js': 20_000, 'assets/shared.js': 10_000, 'assets/login.js': 5_000, 'assets/huge.js': 400_000 }, manifest);
    const [login] = measure(dir, ROUTES);
    expect(login?.overBudget).toBe(false);
    expect(login?.gzipBytes).toBeLessThan(BUDGET_BYTES);
  });

  it('fails a route over budget', () => {
    const dir = dist({ 'assets/entry.js': 20_000, 'assets/shared.js': 10_000, 'assets/login.js': 210_000, 'assets/huge.js': 1 }, manifest);
    expect(measure(dir, ROUTES)[0]?.overBudget).toBe(true);
  });

  it('fails a route that resolves zero JS files', () => {
    const cssOnly = { 'index.html': { file: 'assets/entry.css' }, 'src/Login.tsx': { file: 'assets/login.css' } };
    const dir = dist({ 'assets/entry.css': 10, 'assets/login.css': 10 }, cssOnly);
    expect(measure(dir, ROUTES)[0]).toMatchObject({ files: [], overBudget: true });
  });

  it('exits 1 from the CLI when a real route is over budget', () => {
    const real = {
      'index.html': { file: 'assets/entry.js' },
      'src/features/auth/pages/LoginPage.tsx': { file: 'assets/login.js' },
      'src/features/auth/pages/SignupPage.tsx': { file: 'assets/signup.js' },
      'src/features/auth/pages/InvitePage.tsx': { file: 'assets/invite.js' },
    };
    const dir = dist({ 'assets/entry.js': 1_000, 'assets/login.js': 250_000, 'assets/signup.js': 1_000, 'assets/invite.js': 1_000 }, real);
    const run = spawnSync(process.execPath, [script, dir], { encoding: 'utf8' });
    expect(run.status).toBe(1);
    expect(run.stdout).toMatch(/\/login: .* OVER BUDGET/);
  });
});
```

Run: `pnpm test scripts/check-budget.test.ts` — Expected: FAIL (module missing).

- [ ] **Step 2: Implement the budget script**

`frontend/scripts/check-budget.mjs`:
```js
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';

export const BUDGET_BYTES = 200 * 1024;

// Spec §6.5: the three public routes a first-time visitor (often from a WhatsApp link) lands on.
export const ROUTE_ENTRIES = {
  '/login': 'src/features/auth/pages/LoginPage.tsx',
  '/signup': 'src/features/auth/pages/SignupPage.tsx',
  '/invite/:token': 'src/features/auth/pages/InvitePage.tsx',
};

/**
 * Every JS file a first visit to the route downloads: the HTML entry and the route's lazy chunk, each
 * with its static imports (which Vite modulepreloads). Other dynamic imports are not followed.
 * @param {Record<string, { file: string; imports?: string[] }>} manifest
 * @param {string} routeKey
 * @returns {string[]}
 */
export function routeFiles(manifest, routeKey) {
  const seen = new Set();
  const files = new Set();
  const visit = (key) => {
    if (seen.has(key)) return;
    seen.add(key);
    const chunk = manifest[key];
    if (!chunk) throw new Error(`manifest has no entry ${key}`);
    if (chunk.file.endsWith('.js')) files.add(chunk.file);
    for (const dep of chunk.imports ?? []) visit(dep);
  };
  visit('index.html');
  visit(routeKey);
  return [...files];
}

/**
 * @param {string} distDir
 * @param {Record<string, string>} [routes]
 * @param {number} [budget]
 */
export function measure(distDir, routes = ROUTE_ENTRIES, budget = BUDGET_BYTES) {
  const manifestPath = join(distDir, '.vite', 'manifest.json');
  if (!existsSync(manifestPath)) throw new Error(`no manifest at ${manifestPath}; run pnpm build first`);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  return Object.entries(routes).map(([route, key]) => {
    const files = routeFiles(manifest, key);
    const gzipBytes = files.reduce((sum, file) => sum + gzipSync(readFileSync(join(distDir, file))).length, 0);
    return { route, files, gzipBytes, overBudget: files.length === 0 || gzipBytes > budget };
  });
}

function main() {
  const distDir = resolve(process.argv[2] ?? 'dist');
  const results = measure(distDir);
  for (const r of results) {
    const flag = r.overBudget ? '  OVER BUDGET' : '';
    console.log(`${r.route}: ${(r.gzipBytes / 1024).toFixed(1)} KB gzipped across ${r.files.length} files${flag}`);
  }
  if (results.some((r) => r.overBudget)) {
    console.error(`Per-route JS budget is ${BUDGET_BYTES / 1024} KB gzipped; a route resolving zero files also fails.`);
    process.exit(1);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) main();
```

Run: `pnpm test scripts/check-budget.test.ts` — Expected: PASS.

- [ ] **Step 3: Visualizer and the size script**

```bash
pnpm add -D rollup-plugin-visualizer
```

In `frontend/vite.config.ts` add `import { visualizer } from 'rollup-plugin-visualizer';` and replace the `plugins` line with:
```ts
  plugins: [
    react(),
    tailwindcss(),
    ...(process.env.ANALYZE === 'true'
      ? [
          visualizer({ filename: 'reports/bundle.html', template: 'treemap', gzipSize: true }),
          visualizer({ filename: 'reports/bundle.json', template: 'raw-data', gzipSize: true }),
        ]
      : []),
  ],
```

`frontend/scripts/dependency-sizes.mjs`:
```js
import { existsSync, readFileSync } from 'node:fs';

// Sums the visualizer's per-module gzip sizes by npm package. Per-module gzip slightly overstates a
// chunk's real gzip size (each module is compressed alone), so treat numbers as an upper bound.
const file = process.argv[2] ?? 'reports/bundle.json';
if (!existsSync(file)) {
  console.error(`no ${file}; run ANALYZE=true pnpm build first`);
  process.exit(1);
}
const data = JSON.parse(readFileSync(file, 'utf8'));
const totals = new Map();
for (const meta of Object.values(data.nodeMetas)) {
  const at = meta.id.lastIndexOf('node_modules/');
  if (at === -1) continue;
  const rest = meta.id.slice(at + 'node_modules/'.length).split('/');
  const pkg = rest[0]?.startsWith('@') ? `${rest[0]}/${rest[1]}` : rest[0];
  for (const [bundle, partUid] of Object.entries(meta.moduleParts)) {
    const part = data.nodeParts[partUid];
    const entry = totals.get(pkg) ?? { gzip: 0, chunks: new Set() };
    entry.gzip += part?.gzipLength ?? 0;
    entry.chunks.add(bundle);
    totals.set(pkg, entry);
  }
}
console.log('| Package | Chunk(s) | Gzipped (upper bound) |');
console.log('|---|---|---|');
for (const [pkg, { gzip, chunks }] of [...totals].sort((a, b) => b[1].gzip - a[1].gzip)) {
  console.log(`| ${pkg} | ${[...chunks].join(', ')} | ${(gzip / 1024).toFixed(1)} KB |`);
}
```

- [ ] **Step 4: Measure the real build**

```bash
ANALYZE=true pnpm build
pnpm budget
pnpm deps:sizes
pnpm list --prod --depth 0
```
Expected: `pnpm budget` prints three routes, each under 200 KB, and exits 0. If a route is over budget, find the heaviest static import in `reports/bundle.html` and make it lazy — **do not raise the budget**.

- [ ] **Step 5: Fill the ledger**

Replace the table in `frontend/DEPENDENCIES.md` with one row per **runtime** dependency from `pnpm list --prod --depth 0`, filling `Version` from that list and `Chunk` / `Gzipped` from `pnpm deps:sizes` (map a chunk file to `entry` if it is the `index.html` chunk, else to the route whose manifest key imports it). Keep the "Why" column: react/react-dom (UI runtime), react-router (routing), @tanstack/react-query (server state), zustand (session status), react-hook-form + @hookform/resolvers + zod (forms; `zod/mini` only), i18next + react-i18next + i18next-resources-to-backend (i18n), openapi-fetch (typed client), class-variance-authority + clsx + tailwind-merge + the Radix package (shadcn primitives), lucide-react (password toggle icons), tw-animate-css (CSS only; no JS). Add a line under the table: `Route totals at <commit>: /login N KB, /signup N KB, /invite/:token N KB (gzipped, budget 200 KB).`

- [ ] **Step 6: Commit**

```bash
pnpm test && pnpm lint && pnpm typecheck
cd .. && git add frontend
git commit -m "build(frontend): per-route 200 KB JS budget with red fixtures, and a measured dependency ledger"
```

---

### Task 14: End-to-end — Playwright against two real backends (`e2e-main`)

**Files:**
- Create: `frontend/e2e/playwright.config.ts`, `frontend/e2e/fixtures.ts`, `frontend/e2e/support/api.ts`, `frontend/e2e/support/ui.ts`, `frontend/e2e/scripts/run-backend.sh`, `frontend/e2e/scripts/flaky-summary.mjs`
- Create: `frontend/e2e/main/signup-reload-logout.spec.ts`, `invite-accept.spec.ts`, `invite-signed-in.spec.ts`, `cross-tab-logout.spec.ts`, `invite-invalid.spec.ts`, `guards.spec.ts`

**Interfaces:**
- Consumes: the production build (`pnpm build`), `vite preview` with `E2E_API_TARGET` and the CSP header (Task 2), the backend boot jar, a Postgres at `localhost:5432` with the `docker-compose.yml` credentials.
- Produces: `pnpm e2e` running two Playwright projects: `e2e-main` (backend :18080, web :4173) and `e2e-refresh` (backend :18081 with a 5 s access-token TTL, web :4174). Every page is checked for CSP violations and runs axe. `e2e/support/{api,ui}.ts` helpers used by Task 15: `newAccount(label)`, `signupViaApi(request, account)`, `inviteViaApi(request, ownerToken, email, role?)`, `signUpThroughUi(page, account)`, `signInThroughUi(page, account, roleLabel?)`, `signOutThroughUi(page)`, `signedInText(slug, email, roleLabel?)`; `e2e/fixtures.ts`: `test` (auto CSP check on `page`), `expect`, `watchCsp(page)`, `expectAccessible(page)`.

- [ ] **Step 1: Install and prepare the local prerequisites**

```bash
cd frontend
pnpm add -D @playwright/test @axe-core/playwright
pnpm exec playwright install chromium
(cd ../backend && docker compose up -d && ./gradlew bootJar --console=plain)
ls ../backend/build/libs
```
Expected: a `easycrm-backend-*.jar` (and a `-plain.jar`, which the launcher ignores). Postgres is up on 5432.

- [ ] **Step 2: Launcher, config and fixtures**

`frontend/e2e/scripts/run-backend.sh` (then `chmod +x`):
```bash
#!/usr/bin/env bash
# Starts one EasyCRM backend for E2E from the boot jar (plan decision P10).
# Usage: run-backend.sh <port> [extra --spring.args...]
#
# The rate-limit policy list is restated IN FULL: Spring Boot replaces a whole list when a
# higher-precedence source sets any index, so overriding only policies[1].capacity would drop the
# other entries' names and paths and fail validation at startup. Capacities are raised because every
# E2E request arrives from 127.0.0.1 through the vite preview proxy (one bucket for the whole suite).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
libs="$here/../../../backend/build/libs"
jar="$(find "$libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
if [ -z "$jar" ]; then
  echo "no boot jar in $libs; run ./gradlew bootJar in backend/ first" >&2
  exit 1
fi
port="$1"
shift
exec java -jar "$jar" \
  --server.port="$port" \
  --easycrm.jobs.quotation-expiry.cron=- \
  '--easycrm.rate-limit.policies[0].name=public-share' \
  '--easycrm.rate-limit.policies[0].path=/public/q/*' \
  '--easycrm.rate-limit.policies[0].capacity=60' \
  '--easycrm.rate-limit.policies[0].refill-period=1h' \
  '--easycrm.rate-limit.policies[1].name=session' \
  '--easycrm.rate-limit.policies[1].path=/api/v1/auth/{endpoint:refresh|logout|me}' \
  '--easycrm.rate-limit.policies[1].capacity=100000' \
  '--easycrm.rate-limit.policies[1].refill-period=1m' \
  '--easycrm.rate-limit.policies[2].name=auth' \
  '--easycrm.rate-limit.policies[2].path=/api/v1/auth/**' \
  '--easycrm.rate-limit.policies[2].capacity=100000' \
  '--easycrm.rate-limit.policies[2].refill-period=1m' \
  "$@"
```

`frontend/e2e/playwright.config.ts`:
```ts
import { defineConfig, devices } from '@playwright/test';

const MAIN_API = 18080;
const REFRESH_API = 18081;
const MAIN_WEB = 4173;
const REFRESH_WEB = 4174;
const reuse = !process.env.CI;

// Commands run from frontend/ (cwd '..' is relative to this file).
const backend = (apiPort: number, webPort: number, extra: string[] = []) => ({
  // PUBLIC_BASE_URL = the preview origin, so the backend-minted acceptUrl is a link this suite can open (spec §6.4).
  command: ['bash e2e/scripts/run-backend.sh', String(apiPort), `--easycrm.public-base-url=http://localhost:${webPort}`, ...extra].join(' '),
  url: `http://localhost:${apiPort}/actuator/health`,
  cwd: '..',
  timeout: 180_000,
  reuseExistingServer: reuse,
});

const preview = (webPort: number, apiPort: number) => ({
  command: `pnpm exec vite preview --port ${webPort} --strictPort`,
  url: `http://localhost:${webPort}/login`,
  cwd: '..',
  env: { E2E_API_TARGET: `http://localhost:${apiPort}` },
  timeout: 60_000,
  reuseExistingServer: reuse,
});

export default defineConfig({
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: [
    ['list'],
    ['json', { outputFile: '../test-results/e2e-results.json' }],
    ['html', { open: 'never', outputFolder: '../playwright-report' }],
  ],
  outputDir: '../test-results/artifacts',
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure' },
  projects: [
    { name: 'e2e-main', testDir: './main', use: { baseURL: `http://localhost:${MAIN_WEB}` } },
    { name: 'e2e-refresh', testDir: './refresh', use: { baseURL: `http://localhost:${REFRESH_WEB}` } },
  ],
  webServer: [
    backend(MAIN_API, MAIN_WEB),
    backend(REFRESH_API, REFRESH_WEB, ['--easycrm.jwt.access-ttl-seconds=5']),
    preview(MAIN_WEB, MAIN_API),
    preview(REFRESH_WEB, REFRESH_API),
  ],
});
```

`frontend/e2e/fixtures.ts`:
```ts
import AxeBuilder from '@axe-core/playwright';
import { test as base, expect, type Page } from '@playwright/test';

/** Collects CSP violations Chromium reports on the console for this page. */
export function watchCsp(page: Page): string[] {
  const violations: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) violations.push(message.text());
  });
  return violations;
}

/** Spec §6.4: every E2E page runs axe; any violation fails the test. */
export async function expectAccessible(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.map((v) => `${v.id} (${v.nodes.length}): ${v.help}`)).toEqual([]);
}

export const test = base.extend<{ cspGuard: void }>({
  cspGuard: [
    async ({ page }, use) => {
      const violations = watchCsp(page);
      await use();
      expect(violations, 'CSP violations on this page').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
```

`frontend/e2e/support/api.ts`:
```ts
import { expect, type APIRequestContext } from '@playwright/test';

export interface Account {
  slug: string;
  businessName: string;
  email: string;
  password: string;
}

export function newAccount(label: string): Account {
  const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  return {
    slug: `e2e-${label}-${id}`.slice(0, 64),
    businessName: `E2E ${label} ${id}`,
    email: `${label}-${id}@e2e.test`,
    password: 'correct-horse-9',
  };
}

/** Signs up through the API (its own cookie jar, separate from any page). Returns the access token. */
export async function signupViaApi(request: APIRequestContext, account: Account): Promise<string> {
  const response = await request.post('/api/v1/auth/signup', {
    data: { slug: account.slug, businessName: account.businessName, stateCode: '27', email: account.email, password: account.password },
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()).accessToken as string;
}

/** Returns the backend-minted acceptUrl. */
export async function inviteViaApi(request: APIRequestContext, ownerToken: string, email: string, role = 'SALES_EXEC'): Promise<string> {
  const response = await request.post('/api/v1/invitations', {
    headers: { Authorization: `Bearer ${ownerToken}` },
    data: { email, role },
  });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()).acceptUrl as string;
}
```

`frontend/e2e/support/ui.ts`:
```ts
import { expect, type Page } from '@playwright/test';
import { expectAccessible } from '../fixtures';
import type { Account } from './api';

export const signedInText = (slug: string, email: string, roleLabel = 'Owner') => `Signed in to ${slug} as ${email} (${roleLabel})`;

export async function signUpThroughUi(page: Page, account: Account): Promise<void> {
  await page.goto('/signup');
  await expect(page.getByRole('heading', { name: 'Create your EasyCRM workspace' })).toBeVisible();
  await expectAccessible(page);
  await page.getByLabel('Business name', { exact: true }).fill(account.businessName);
  await page.getByLabel('Workspace name', { exact: true }).fill(account.slug);
  await page.getByLabel('State', { exact: true }).selectOption('27');
  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Create workspace' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();
}

export async function signInThroughUi(page: Page, account: Account, roleLabel = 'Owner'): Promise<void> {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();
  await page.getByLabel('Workspace', { exact: true }).fill(account.slug);
  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email, roleLabel))).toBeVisible();
}

export async function signOutThroughUi(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();
}
```

`frontend/e2e/scripts/flaky-summary.mjs`:
```js
import { existsSync, readFileSync } from 'node:fs';

// Spec §6.4: CI retries once; a test that passed only on retry is reported, not hidden.
const file = process.argv[2] ?? 'test-results/e2e-results.json';
console.log('## E2E flaky tests');
if (!existsSync(file)) {
  console.log('No Playwright JSON report was produced; see the step log.');
  process.exit(0);
}
const report = JSON.parse(readFileSync(file, 'utf8'));
const flaky = [];
const walk = (suite, trail) => {
  for (const spec of suite.specs ?? []) {
    for (const test of spec.tests ?? []) {
      if (test.status === 'flaky') flaky.push(`${test.projectName}: ${[...trail, spec.title].filter(Boolean).join(' › ')}`);
    }
  }
  for (const child of suite.suites ?? []) walk(child, [...trail, child.title]);
};
for (const suite of report.suites ?? []) walk(suite, [suite.title]);
console.log(flaky.length ? flaky.map((f) => `- ${f}`).join('\n') : 'None: every test passed on its first attempt.');
```

- [ ] **Step 3: Write the five `e2e-main` paths and the guard proofs**

`frontend/e2e/main/signup-reload-logout.spec.ts`:
```ts
import { expect, expectAccessible, test } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signOutThroughUi, signUpThroughUi } from '../support/ui';

test('signup, survive a reload, sign out, and sign back in with the remembered workspace', async ({ page }) => {
  const account = newAccount('owner');

  await signUpThroughUi(page, account);
  await expectAccessible(page);

  await page.reload();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();

  await signOutThroughUi(page);
  await expect(page.getByLabel('Workspace', { exact: true })).toHaveValue(account.slug);
  await expectAccessible(page);

  await page.getByLabel('Email', { exact: true }).fill(account.email);
  await page.getByLabel('Password', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible();
});
```

`frontend/e2e/main/invite-accept.spec.ts`:
```ts
import { expect, expectAccessible, test } from '../fixtures';
import { inviteViaApi, newAccount, signupViaApi } from '../support/api';
import { signedInText } from '../support/ui';

test('an invitee opens the backend-minted accept link and joins', async ({ page, request, baseURL }) => {
  const owner = newAccount('owner');
  const invitee = newAccount('invitee');
  const ownerToken = await signupViaApi(request, owner);
  const acceptUrl = await inviteViaApi(request, ownerToken, invitee.email);
  expect(new URL(acceptUrl).origin).toBe(new URL(String(baseURL)).origin);

  await page.goto(acceptUrl);
  await expect(page.getByText(`Join ${owner.businessName} as Sales executive`)).toBeVisible();
  await expectAccessible(page);
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(invitee.email);

  await page.getByLabel('Choose a password', { exact: true }).fill(invitee.password);
  await page.getByRole('button', { name: 'Join workspace' }).click();
  await expect(page.getByText(signedInText(owner.slug, invitee.email, 'Sales executive'))).toBeVisible();

  await page.goBack();
  expect(page.url()).not.toContain('/invite/');
});
```

`frontend/e2e/main/invite-signed-in.spec.ts`:
```ts
import { expect, expectAccessible, test } from '../fixtures';
import { inviteViaApi, newAccount, signupViaApi } from '../support/api';
import { signedInText, signInThroughUi } from '../support/ui';

test('a signed-in owner opening an invite link can sign out and accept as the invitee', async ({ page, request }) => {
  const owner = newAccount('owner');
  const invitee = newAccount('invitee');
  const ownerToken = await signupViaApi(request, owner);
  const acceptUrl = await inviteViaApi(request, ownerToken, invitee.email);
  await signInThroughUi(page, owner);

  await page.goto(acceptUrl);
  await expect(
    page.getByText(`This invitation is for ${invitee.email} to join ${owner.businessName}. You're signed in as ${owner.email}.`),
  ).toBeVisible();
  await expectAccessible(page);

  await page.getByRole('button', { name: 'Sign out and accept' }).click();
  await page.getByLabel('Choose a password', { exact: true }).fill(invitee.password);
  await page.getByRole('button', { name: 'Join workspace' }).click();

  await expect(page.getByText(signedInText(owner.slug, invitee.email, 'Sales executive'))).toBeVisible();
});
```

`frontend/e2e/main/cross-tab-logout.spec.ts`:
```ts
import { expect, test, watchCsp } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signOutThroughUi, signUpThroughUi } from '../support/ui';

test('signing out in one tab signs the other out, and the next user sees nothing of the last', async ({ context, page }) => {
  const first = newAccount('first');
  const second = newAccount('second');
  await signUpThroughUi(page, first);

  const other = await context.newPage();
  const otherCsp = watchCsp(other);
  await other.goto('/');
  await expect(other.getByText(signedInText(first.slug, first.email))).toBeVisible();

  await signOutThroughUi(page);
  await expect(other.getByRole('heading', { name: 'Sign in to EasyCRM' })).toBeVisible();

  await signUpThroughUi(page, second);
  await expect(other.getByText(first.email)).toHaveCount(0);

  await other.reload();
  await expect(other.getByText(signedInText(second.slug, second.email))).toBeVisible();
  for (const tab of [page, other]) await expect(tab.getByText(first.email)).toHaveCount(0);
  expect(otherCsp).toEqual([]);
});
```

`frontend/e2e/main/invite-invalid.spec.ts`:
```ts
import { expect, expectAccessible, test } from '../fixtures';

// The only real-backend check that an unknown token renders the single invalid state (challenge #55).
test('an invalid invite token shows the invalid state', async ({ page }) => {
  await page.goto('/invite/not-a-real-token');
  await expect(page.getByText('This invitation link is invalid or has expired.')).toBeVisible();
  await expectAccessible(page);
});
```

`frontend/e2e/main/guards.spec.ts`:
```ts
import { expect, expectAccessible, test, watchCsp } from '../fixtures';

// Non-vacuity for the two checks every other test relies on.
test('the axe helper fails on a real violation', async ({ page }) => {
  await page.setContent('<main><img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></main>');
  await expect(expectAccessible(page)).rejects.toThrow();
});

test('vite preview sends the CSP, and the watcher sees an inline-script violation', async ({ browser, baseURL }) => {
  const probe = await browser.newPage({ baseURL: String(baseURL) });
  const violations = watchCsp(probe);
  const response = await probe.goto('/login');
  expect(response?.headers()['content-security-policy']).toContain("script-src 'self'");

  await probe.addScriptTag({ content: 'window.__cspProbe = 1;' }).catch(() => undefined);

  await expect.poll(() => violations.length).toBeGreaterThan(0);
  await probe.close();
});
```

- [ ] **Step 4: Build and run**

```bash
pnpm build
pnpm e2e --project=e2e-main
```
Expected: 7 tests PASS (5 paths + 2 guards). A failing axe assertion names the rule and node count — fix the markup, never exclude the rule. A CSP violation names the directive — fix the source (e.g. an inline style attribute from a dependency), never loosen the CSP.

- [ ] **Step 5: Commit**

```bash
cd .. && git add frontend
git commit -m "test(frontend): Playwright E2E against real backends with CSP and axe on every page"
```

---

### Task 15: End-to-end — refresh races (`e2e-refresh`), with a recorded red run

**Files:**
- Create: `frontend/e2e/refresh/multi-tab-refresh.spec.ts`, `frontend/e2e/refresh/lost-refresh-response.spec.ts`

**Interfaces:**
- Consumes: Task 14's config, fixtures and helpers; `noopLocks` from Task 5 (red run only).
- Produces: two tests that fail if Web Locks serialization or the grace-window recovery regresses.

- [ ] **Step 1: The multi-tab test (plan decision P11)**

`frontend/e2e/refresh/multi-tab-refresh.spec.ts`:
```ts
import type { Page } from '@playwright/test';
import { expect, test, watchCsp } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signUpThroughUi } from '../support/ui';

// Spec §6.4 test 6, asserted as the invariant Web Locks provide (plan decision P11): across two tabs
// booting at once, at most one refresh is ever in flight. Each response is held 1.5 s so that, without
// serialization, the second tab's refresh is guaranteed to overlap the first.
test('two tabs booting at once never run two refreshes, and both stay signed in', async ({ context, page }) => {
  const account = newAccount('tabs');
  await signUpThroughUi(page, account);
  const other = await context.newPage();
  const otherCsp = watchCsp(other);

  let inFlight = 0;
  let maxInFlight = 0;
  let refreshes = 0;
  await context.route('**/api/v1/auth/refresh', async (route) => {
    inFlight += 1;
    refreshes += 1;
    maxInFlight = Math.max(maxInFlight, inFlight);
    try {
      const response = await route.fetch();
      await new Promise((resolve) => setTimeout(resolve, 1_500));
      await route.fulfill({ response });
    } finally {
      inFlight -= 1;
    }
  });

  const signedIn = (tab: Page) => expect(tab.getByText(signedInText(account.slug, account.email))).toBeVisible({ timeout: 15_000 });
  await Promise.all([page.reload(), other.goto('/')]);
  await Promise.all([signedIn(page), signedIn(other)]);

  expect(refreshes).toBe(2);
  expect(maxInFlight, 'two refreshes overlapped: cross-tab Web Locks serialization is broken').toBe(1);

  // And the cookie both tabs now share is live.
  await context.unroute('**/api/v1/auth/refresh');
  await Promise.all([page.reload(), other.reload()]);
  await Promise.all([signedIn(page), signedIn(other)]);
  expect(otherCsp).toEqual([]);
});
```

- [ ] **Step 2: The lost-response test**

`frontend/e2e/refresh/lost-refresh-response.spec.ts`:
```ts
import { expect, expectAccessible, test } from '../fixtures';
import { newAccount } from '../support/api';
import { signedInText, signUpThroughUi } from '../support/ui';

// Spec §6.4 test 7. The server commits a rotation, the response never arrives, and the browser still
// holds the pre-rotation cookie. The cookie is restored explicitly: route.fetch() shares the context's
// cookie jar and may already have stored the successor, which would skip the grace path silently.
test('a refresh whose response is lost recovers through the grace window', async ({ context, page }) => {
  const account = newAccount('lost');
  await signUpThroughUi(page, account);
  const before = (await context.cookies()).find((cookie) => cookie.name === 'easycrm_rt');
  if (!before) throw new Error('signup did not set the easycrm_rt cookie');

  let calls = 0;
  let replayedCookieHeader: string | undefined;
  await context.route('**/api/v1/auth/refresh', async (route) => {
    calls += 1;
    if (calls === 1) {
      const response = await route.fetch(); // the server rotates and commits
      expect(response.status()).toBe(200);
      await context.addCookies([before]); // ...but the browser never learns the successor
      await route.abort('connectionreset');
      return;
    }
    replayedCookieHeader = (await route.request().allHeaders())['cookie'];
    await route.continue();
  });

  await page.reload();
  await expect(page.getByRole('heading', { name: "Can't reach EasyCRM" })).toBeVisible();
  await expectAccessible(page);

  // Boot's first automatic retry fires after 2 s (plan decision P2), inside the 30 s grace window.
  await expect(page.getByText(signedInText(account.slug, account.email))).toBeVisible({ timeout: 10_000 });
  expect(calls).toBe(2);
  expect(replayedCookieHeader, 'the recovering refresh must present the pre-rotation token').toContain(`easycrm_rt=${before.value}`);
});
```

- [ ] **Step 3: Run green**

```bash
pnpm build
pnpm e2e --project=e2e-refresh
```
Expected: 2 PASS. If `replayedCookieHeader` is `undefined` (the header was not exposed on the intercepted request), stop and report rather than deleting the assertion — without it the test cannot tell the grace path from an ordinary refresh.

- [ ] **Step 4: Record the red run with a no-op lock (spec §6.4: "runs this once with a no-op LockProvider and records it red")**

In `frontend/src/app/bootstrap.ts`, temporarily change the `locks:` line to `locks: noopLocks,` and add `noopLocks` to its import. Then:
```bash
pnpm build
pnpm e2e --project=e2e-refresh -g 'two tabs' 2>&1 | tee /tmp/f0b-noop-locks-red.txt | tail -20
```
Expected: FAIL with `two refreshes overlapped: cross-tab Web Locks serialization is broken` — `Expected: 1, Received: 2`. **If it passes, the test is vacuous: stop and redesign it before continuing.** Copy the failure lines into your notes for the Task 17 HANDOFF entry, then restore and re-verify:
```bash
git checkout -- src/app/bootstrap.ts
pnpm build && pnpm e2e --project=e2e-refresh
```
Expected: 2 PASS.

- [ ] **Step 5: Full E2E run and commit**

```bash
pnpm e2e
cd .. && git add frontend
git commit -m "test(frontend): E2E proof of cross-tab refresh serialization and grace-window recovery"
```
Expected: 9 PASS across both projects.

---

### Task 16: CI jobs, their guard test, oasdiff wording, Dependabot — and every gate seen red

**Files:**
- Modify: `.github/workflows/ci.yml` (add `frontend` and `e2e` jobs; reword the oasdiff comment)
- Modify: `.github/dependabot.yml`
- Create: `backend/src/test/java/com/easycrm/platform/frontend/FrontendWorkflowTest.java`
- Modify: `backend/src/test/java/com/easycrm/platform/openapi/OasdiffWorkflowTest.java` (assertion message)

**Interfaces:**
- Consumes: every `pnpm` script from Tasks 2–15.
- Produces: jobs `frontend` and `e2e` with gate steps named exactly: frontend — `Install`, `Generated client matches the contract`, `Typecheck`, `Lint`, `Unit and component tests`, `Build`, `Route JS budget`; e2e — `Build the backend jar`, `Install`, `Install Chromium`, `Build the frontend`, `End-to-end tests`. `FrontendWorkflowTest` fails `./gradlew check` if any is removed, conditioned or softened.

- [ ] **Step 0: Coverage tooling (the CI test step runs `pnpm test:coverage`; Task 17 sets the floor)**

```bash
cd frontend && pnpm add -D @vitest/coverage-v8 && cd ..
```
In `frontend/vite.config.ts`, inside `test:`, add:
```ts
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/api/schema.d.ts', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/**/*.typecheck.ts', 'src/main.tsx', 'src/components/ui/**'],
    },
```
Run `pnpm test:coverage` from `frontend/` — Expected: PASS with a coverage summary (no thresholds yet).

- [ ] **Step 1: Resolve pinned versions**

```bash
gh api repos/pnpm/action-setup/releases/latest --jq .tag_name
gh api repos/actions/setup-node/releases/latest --jq .tag_name
docker run --rm postgres:16-alpine postgres --version
```
Use the **major** tag for each action (e.g. `v4` → `pnpm/action-setup@v4`), matching how `ci.yml` pins `actions/checkout@v7`. From the Postgres output `postgres (PostgreSQL) 16.N`, the image is `postgres:16.N-alpine`. Substitute these three values wherever `<PNPM_ACTION>`, `<SETUP_NODE>` and `<PG_IMAGE>` appear below.

- [ ] **Step 2: Write the failing guard test**

`backend/src/test/java/com/easycrm/platform/frontend/FrontendWorkflowTest.java`:
```java
package com.easycrm.platform.frontend;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the frontend and E2E CI jobs (spec 2026-09-14-f0 §6.5). SupplyChainWorkflowTest iterates only
 * the supply-chain job, so without this class a new job's gate could be deleted or softened and every
 * local build would stay green.
 *
 * <p>Shaped around how a gate actually gets neutered — an {@code if:}, a {@code continue-on-error}, an
 * {@code || true}, dropping {@code --frozen-lockfile}, or a floating image tag — rather than around a
 * step's mere absence. Each assertion was seen failing by making that change (F0b Task 16).
 */
class FrontendWorkflowTest {

    private static final Map<String, List<String>> GATES = Map.of(
            "frontend",
            List.of(
                    "Install",
                    "Generated client matches the contract",
                    "Typecheck",
                    "Lint",
                    "Unit and component tests",
                    "Build",
                    "Route JS budget"),
            "e2e",
            List.of("Build the backend jar", "Install", "Install Chromium", "Build the frontend", "End-to-end tests"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(String name) throws IOException {
        Path wf = Path.of(System.getProperty("ci.workflow"));
        assertTrue(Files.exists(wf), "ci.workflow points at a missing file: " + wf);
        try (var in = Files.newInputStream(wf)) {
            Map<String, Object> workflow = new Yaml().load(in);
            var jobs = (Map<String, Object>) workflow.get("jobs");
            var found = (Map<String, Object>) jobs.get(name);
            assertNotNull(found, "no job named " + name + " in ci.yml; jobs are " + jobs.keySet());
            return found;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(String jobName) throws IOException {
        return (List<Map<String, Object>>) job(jobName).get("steps");
    }

    private static Map<String, Object> step(String jobName, String stepName) throws IOException {
        return steps(jobName).stream()
                .filter(s -> stepName.equals(s.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("job " + jobName + " has no step named exactly '" + stepName + "'"));
    }

    private static String run(String jobName, String stepName) throws IOException {
        return String.valueOf(step(jobName, stepName).get("run"));
    }

    @Test
    @DisplayName("both jobs exist and neither is softened at job level")
    void jobsAreBlocking() throws IOException {
        for (String name : GATES.keySet()) {
            var job = job(name);
            assertNull(job.get("continue-on-error"), name + " must not carry continue-on-error");
            assertNull(job.get("if"), name + " must not be conditional");
        }
    }

    @Test
    @DisplayName("every gate step exists and runs unconditionally")
    void gateStepsAreUnconditional() throws IOException {
        int checked = 0;
        for (var entry : GATES.entrySet()) {
            for (String name : entry.getValue()) {
                var step = step(entry.getKey(), name);
                assertNull(step.get("if"), entry.getKey() + " / " + name + " must not carry if:");
                assertNull(step.get("continue-on-error"), entry.getKey() + " / " + name + " must not carry continue-on-error");
                assertFalse(String.valueOf(step.get("run")).contains("|| true"), entry.getKey() + " / " + name + " must not swallow failure");
                checked++;
            }
        }
        assertEquals(12, checked, "non-vacuity: every named gate was inspected");
    }

    @Test
    @DisplayName("only artifact uploads may be conditional, and only on failure")
    void onlyUploadsAreConditional() throws IOException {
        for (String name : GATES.keySet()) {
            for (var step : steps(name)) {
                Object condition = step.get("if");
                if (condition == null) continue;
                assertTrue(
                        String.valueOf(step.get("uses")).startsWith("actions/upload-artifact"),
                        name + " / " + step.get("name") + " is conditional but is not an artifact upload");
                assertEquals("failure()", String.valueOf(condition), name + " / " + step.get("name"));
            }
        }
    }

    @Test
    @DisplayName("the gates run what they claim to run")
    void gateBodies() throws IOException {
        assertTrue(run("frontend", "Install").contains("--frozen-lockfile"));
        assertTrue(run("e2e", "Install").contains("--frozen-lockfile"));
        String drift = run("frontend", "Generated client matches the contract");
        assertTrue(drift.contains("pnpm gen:api") && drift.contains("git diff --exit-code"), drift);
        assertTrue(run("frontend", "Typecheck").contains("pnpm typecheck"));
        assertTrue(run("frontend", "Lint").contains("pnpm lint"));
        assertTrue(run("frontend", "Unit and component tests").contains("pnpm test"));
        assertTrue(run("frontend", "Route JS budget").contains("pnpm budget"));
        String e2e = run("e2e", "End-to-end tests");
        assertTrue(e2e.contains("pnpm e2e"), e2e);
        if (e2e.contains("set +e")) {
            assertTrue(e2e.strip().endsWith("exit $status"), "set +e is only allowed when the step exits with the test status:\n" + e2e);
        }
    }

    @Test
    @DisplayName("the E2E Postgres image has an exact version tag")
    @SuppressWarnings("unchecked")
    void postgresImageIsPinned() throws IOException {
        var services = (Map<String, Object>) job("e2e").get("services");
        assertNotNull(services, "e2e must declare a postgres service");
        var postgres = (Map<String, Object>) services.get("postgres");
        String image = String.valueOf(postgres.get("image"));
        assertTrue(image.matches("postgres:\\d+\\.\\d+-alpine[0-9.]*"), "expected an exact tag like postgres:16.10-alpine, got " + image);
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.easycrm.platform.frontend.FrontendWorkflowTest' --console=plain`
Expected: FAIL — `no job named frontend in ci.yml`.

- [ ] **Step 3: Add the jobs to `ci.yml`**

Append under `jobs:` (after `dependency-check`):
```yaml
  # The SPA (spec 2026-09-14-f0 §6.5). Blocking in the same sense as `check`: CI runs post-merge on main,
  # so red is an alarm, not a stop, until branch protection (roadmap item 8). FrontendWorkflowTest fails
  # ./gradlew check if a gate here is removed, conditioned or softened.
  frontend:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    defaults:
      run:
        working-directory: frontend

    steps:
      - uses: actions/checkout@v7

      - uses: pnpm/action-setup@<PNPM_ACTION>
        with:
          package_json_file: frontend/package.json

      - uses: actions/setup-node@<SETUP_NODE>
        with:
          node-version-file: frontend/.nvmrc
          cache: pnpm
          cache-dependency-path: frontend/pnpm-lock.yaml

      - name: Install
        run: pnpm install --frozen-lockfile

      # schema.d.ts is generated from docs/api/openapi.yaml. A contract change the client has not
      # followed, or a hand edit to the generated file, fails here.
      - name: Generated client matches the contract
        run: |
          pnpm gen:api
          git diff --exit-code -- src/api/schema.d.ts

      - name: Typecheck
        run: pnpm typecheck

      - name: Lint
        run: pnpm lint

      - name: Unit and component tests
        run: pnpm test:coverage

      - name: Build
        env:
          ANALYZE: 'true'
        run: pnpm build

      - name: Route JS budget
        run: pnpm budget

      - name: Upload bundle report
        uses: actions/upload-artifact@v7
        with:
          name: bundle-report
          path: frontend/reports/
          retention-days: 7

  # Real backends, real Postgres, Chromium (spec §6.4). Two backend processes from one boot jar
  # (plan P10): e2e-main with default TTLs, e2e-refresh with a 5 s access token.
  e2e:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    services:
      postgres:
        image: <PG_IMAGE>
        env:
          POSTGRES_DB: easycrm
          POSTGRES_USER: easycrm_owner
          POSTGRES_PASSWORD: easycrm_owner
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -U easycrm_owner -d easycrm"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 20

    steps:
      - uses: actions/checkout@v7

      - name: Set up JDK 25
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Build the backend jar
        working-directory: backend
        run: ./gradlew bootJar --no-daemon --console=plain

      - uses: pnpm/action-setup@<PNPM_ACTION>
        with:
          package_json_file: frontend/package.json

      - uses: actions/setup-node@<SETUP_NODE>
        with:
          node-version-file: frontend/.nvmrc
          cache: pnpm
          cache-dependency-path: frontend/pnpm-lock.yaml

      - name: Install
        working-directory: frontend
        run: pnpm install --frozen-lockfile

      - name: Install Chromium
        working-directory: frontend
        run: pnpm exec playwright install --with-deps chromium

      - name: Build the frontend
        working-directory: frontend
        run: pnpm build

      # The flaky summary must run whether or not the tests pass, without an `if:` that could also skip
      # the tests; so the step captures the status, writes the summary, and exits with the test status.
      - name: End-to-end tests
        working-directory: frontend
        run: |
          set +e
          pnpm e2e
          status=$?
          node e2e/scripts/flaky-summary.mjs test-results/e2e-results.json >> "$GITHUB_STEP_SUMMARY"
          exit $status

      - name: Upload Playwright traces
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: playwright-traces
          path: |
            frontend/test-results/
            frontend/playwright-report/
          retention-days: 7
```

- [ ] **Step 4: Reword the oasdiff trigger (F0-12)**

In `.github/workflows/ci.yml`, replace these lines of the oasdiff comment:
```yaml
      # There is also no consumer yet: members management, cursor pagination and the frontend's
      # own needs will all legitimately change endpoints, so a blocking gate would fire
      # regularly, at nobody, on correct work. That is how gates get ignored.
      #
      # Flip continue-on-error to false when the frontend exists and consumes this spec. At
      # that point there is a real party to break.
```
with:
```yaml
      # The frontend now consumes this spec, and its "Generated client matches the contract" step
      # (job `frontend`) already fails on any contract change the client has not followed. What a
      # blocking breaking-change gate adds is stopping the change BEFORE it merges, which post-merge
      # CI cannot do.
      #
      # Flip continue-on-error to false at branch protection (roadmap item 8), when a red gate can
      # hold a pull request instead of reporting on main (spec 2026-09-14-f0 F0-12).
```

In `backend/src/test/java/com/easycrm/platform/openapi/OasdiffWorkflowTest.java`, method `theStepDoesNotBlockTheBuild`, replace the message string with:
```java
                "flipping this to blocking is a deliberate policy change tied to branch protection"
                        + " (roadmap item 8) — see the comment in ci.yml — not something to drift into");
```

- [ ] **Step 5: Dependabot**

Append to `.github/dependabot.yml` under `updates:`:
```yaml
  # The SPA. pnpm projects use the npm ecosystem; Dependabot reads frontend/pnpm-lock.yaml.
  - package-ecosystem: npm
    directory: /frontend
    schedule:
      interval: weekly
      day: monday
      time: "04:00"
      timezone: Asia/Kolkata
    open-pull-requests-limit: 5
    groups:
      react:
        patterns:
          - "react"
          - "react-dom"
          - "@types/react"
          - "@types/react-dom"
      lint-and-build:
        patterns:
          - "eslint*"
          - "@eslint/*"
          - "typescript-eslint"
          - "vite"
          - "@vitejs/*"
          - "vitest"
          - "@vitest/*"
    commit-message:
      prefix: build
```

- [ ] **Step 6: Guards green, then each softening seen red**

```bash
cd backend && ./gradlew test --tests 'com.easycrm.platform.frontend.*' --tests 'com.easycrm.platform.openapi.OasdiffWorkflowTest' --tests 'com.easycrm.platform.supplychain.*' --console=plain && cd ..
docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:1.7.12 -color
```
Expected: PASS; actionlint prints nothing and exits 0.

Then, one at a time, make each change to `ci.yml`, run `./gradlew test --tests 'com.easycrm.platform.frontend.FrontendWorkflowTest'` from `backend/`, confirm the named failure, and `git checkout -- .github/workflows/ci.yml`:
1. `continue-on-error: true` on `Typecheck` → `frontend / Typecheck must not carry continue-on-error`.
2. `if: github.event_name == 'push'` on `Lint` → `must not carry if:`.
3. `pnpm budget || true` → `must not swallow failure`.
4. `image: postgres:16-alpine` → `expected an exact tag`.
5. `if: always()` on `Upload bundle report` → `expected: <failure()>`.
6. Remove `exit $status` from the E2E step → `set +e is only allowed when…`.

- [ ] **Step 7: Every frontend gate seen red (spec §6.5)**

From `frontend/`, one at a time; after each, restore with `git checkout -- <file>` (or `rm` for a new file) and confirm the gate is green again. Keep the one-line failure output of each for the Task 17 HANDOFF entry.

| Gate | Break | Command | Expected failure |
|---|---|---|---|
| Generated client | `sed -i.bak 's/accessToken: string;/accessToken: number;/' src/api/schema.d.ts && rm src/api/schema.d.ts.bak && git add src/api/schema.d.ts` | `pnpm gen:api && git diff --exit-code -- src/api/schema.d.ts` | exit 1, diff shows `accessToken` (then `git add src/api/schema.d.ts` to restore) |
| Typecheck | in `LoginPage.tsx`, `t('login.heading')` → `t('login.nope')` | `pnpm typecheck` | TS2345/TS2769 on `login.nope` |
| Lint: a11y | add `<img src="/logo.png" />` inside LoginPage's `<main>` | `pnpm lint` | `jsx-a11y/alt-text` |
| Lint: literal text | add `<p>Welcome back</p>` inside LoginPage's `<main>` | `pnpm lint` | `i18next/no-literal-string` |
| Lint: cross-feature | create `src/features/zz/x.ts` with `import { useLogin } from '@/features/auth/api/useLogin'; export const u = useLogin;` | `pnpm lint` | `import-x/no-restricted-paths` |
| Lint: raw fetch | in `useLogin.ts`, add `export const leak = () => fetch('/api/v1/auth/me');` | `pnpm lint` | `no-restricted-globals` |
| Tests | in `safeNext.test.ts`, add `'/ok'` to the rejected list | `pnpm test` | `safeNext > rejects /ok` |
| Budget | create `src/features/auth/pages/heavy.ts` exporting `export const blob = "<400 KB of random base64>";` (generate: `node -e "console.log('export const blob = ' + JSON.stringify(require('crypto').randomBytes(300000).toString('base64')) + ';')" > src/features/auth/pages/heavy.ts`) and `import { blob } from './heavy'; void blob;` at the top of `LoginPage.tsx` | `pnpm build && pnpm budget` | `/login: … OVER BUDGET`, exit 1 |

- [ ] **Step 8: Full check and commit**

```bash
cd backend && ./gradlew clean check --console=plain 2>&1 | tail -3 && cd ..
cd frontend && pnpm typecheck && pnpm lint && pnpm test:coverage && pnpm build && pnpm budget && cd ..
git status --short
git add .github backend/src/test frontend
git commit -m "ci: blocking frontend and e2e jobs, their workflow guard, npm Dependabot, oasdiff trigger at branch protection"
```
Expected: `BUILD SUCCESSFUL`; every frontend gate green; `git status` shows no leftover break files.

---

### Task 17: Coverage floor, manual walkthrough, documentation, final verification

**Files:**
- Modify: `frontend/vite.config.ts` (coverage), `frontend/package.json`
- Modify: `docs/superpowers/engineering-challenges.md`, `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md`, `docs/superpowers/specs/2026-07-22-easycrm-design.md`, `docs/superpowers/HANDOFF.md`, `docs/ROADMAP.md`

**Interfaces:**
- Consumes: everything above.
- Produces: a coverage floor taken from the measured baseline (spec §6.1); the docs Part 7 of the spec requires; a branch ready for `superpowers:finishing-a-development-branch`.

- [ ] **Step 1: Measure coverage and set the floor**

From `frontend/`, run `pnpm test:coverage` (configured in Task 16 Step 0) and read the `Statements / Branches / Functions / Lines` percentages. Add `thresholds: { statements: S, branches: B, functions: F, lines: L }` inside `coverage`, each the measured value **rounded down to a whole percent** (mirrors how the backend's JaCoCo floors were set from a measured baseline). Prove it bites: temporarily set `lines: 100`, run `pnpm test:coverage`, expect `ERROR: Coverage for lines (…) does not meet global threshold (100%)`, restore the measured value, rerun green.

- [ ] **Step 2: Manual keyboard and 320 px walkthrough (spec §6.4)**

With the stack running (`docker compose up -d` in `backend/`, `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` in `backend/`, `pnpm dev` in `frontend/`), in Chromium, **keyboard only** (Tab, Shift+Tab, Enter, Space, arrow keys in the select), then again with DevTools device toolbar at **320 px** width:

- [ ] `/login`: focus starts on the heading; tab order Workspace → Email → Password → Show password → Sign in → Create a workspace; a wrong password announces the alert; nothing scrolls horizontally at 320 px.
- [ ] `/signup`: every field reachable; the state select opens and selects with arrows; a taken slug moves focus to Workspace name; the password toggle works with Space; no clipped text at 320 px.
- [ ] `/invite/<valid token>` anonymous: Email is read-only but focusable; accept completes with Enter.
- [ ] `/invite/<valid token>` signed in: both actions reachable; "Sign out and accept" leads to the form with focus on the heading.
- [ ] `/invite/not-a-real-token`: invalid message is readable; heading focused.
- [ ] Shell: Sign out reachable and works; header wraps rather than overflowing at 320 px.

Record pass/fail per line for the HANDOFF entry. Fix any failure in code (with a test where one is possible) before continuing.

- [ ] **Step 3: Engineering challenges #84 and #85**

Append to `docs/superpowers/engineering-challenges.md`, following the template at the top of that file:

```markdown
## Challenge 84 — Two tabs, one cookie jar, two different users

**Phase:** Design and implementation (F0b)

### The problem

Every tab of the SPA holds its own in-memory access token and its own TanStack Query cache, but all
tabs share one cookie jar — and the refresh token lives in that jar. Sign in as Ravi in tab A, then as
Asha in tab B: the `easycrm_rt` cookie now belongs to Asha. Tab A still shows Ravi's name and Ravi's
cached data. Its next silent refresh presents Asha's cookie, **succeeds**, and hands tab A an access
token for Asha. From then on tab A sends requests as Asha from a screen, a cache and possibly a
half-filled form that belong to Ravi.

Nothing errors. The naive client treats a 200 from refresh as "still signed in", which is exactly the
wrong conclusion here: the success *is* the bug.

### The solution

- F0a put identity (`userId`, `tenantId`, …) on every session response, so a refresh says *who* it
  refreshed.
- `establishSession` — the single entry point for boot, login, signup, accept and every refresh —
  compares that identity with the current `me`. A different principal calls
  `endSession('principal-changed')`, which clears the token, the store and `queryClient.clear()`, then
  reloads the tab.
- Tabs also broadcast `login {userId, tenantId}` on a `BroadcastChannel`; a tab holding a different
  principal ends and reloads without waiting for its next refresh. `logout` broadcasts end every tab.
- Every way a session ends goes through `endSession`, so no path can end a session and keep the cache.

### Lesson

When a credential lives in shared storage (a cookie jar) but the state derived from it is per tab,
every credential refresh is a potential identity switch. Key cached state to the principal, and check
the principal on *refresh*, not only on login — a status code cannot tell you the user changed.

---

## Challenge 85 — Proving cross-tab refresh serialization in E2E without controlling cookie order

**Phase:** Design (F0b plan)

### The problem

The spec's E2E test for Web Locks held one tab's refresh response until the other tab's refresh was
observed, intending the stale `Set-Cookie` to land last and break the unserialized client. With
Playwright that ordering cannot be forced: `route.fetch()` performs the request through the browser
context's request API, which shares — and can update — the context's cookie jar when the response is
*fetched*, not when it is *fulfilled*. Whether the dead cookie ends up in the jar would depend on
harness internals, so the "red without locks" run could pass, making the test vacuous.

The companion lost-response test had the same trap: if `route.fetch()` stored the successor cookie,
aborting the response would no longer leave the browser holding the pre-rotation token, and the test
would pass through an ordinary refresh instead of the grace window.

### The solution

- Assert the invariant the control provides rather than a downstream symptom: count refresh requests
  in flight across both tabs inside the route handler, holding each response 1.5 s. With Web Locks the
  maximum is 1; with a no-op lock it is 2. The red run with `noopLocks` is recorded.
- For the lost response, restore the pre-rotation cookie explicitly with `context.addCookies` before
  aborting, and assert that the recovering request's `Cookie` header carries the pre-rotation value —
  proof the grace path, not a normal rotation, did the recovery.

### Lesson

An end-to-end test for a concurrency control should assert the control's own invariant, measured at a
point the harness genuinely observes, and must be run once with the control removed. If the failure
you are trying to provoke depends on an ordering your tooling does not let you pin, the test can pass
for the wrong reason.
```

- [ ] **Step 4: Spec corrections and amendments**

In `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md`:
1. §3.8: replace `Login/accept's revoke path (§3.1)` with `Signup/login/accept's revoke path (§3.1)`.
2. Append after Part 9:
```markdown
---

# Part 10 — Amendments from the F0b plan (2026-09-16)

`docs/superpowers/plans/2026-09-16-f0b-frontend-foundation.md` resolved these; each row there has its
reasoning.

- **P1** `AuthResponse`, `MeResponse`, `InvitationPreviewResponse`, `SignupStatusResponse`,
  `ApiErrorResponse` and `ApiError` declare `required` (§4.4's known contract gap, fixed in the backend).
- **P2** Boot retry: a 429 honours `Retry-After` (1–60 s); otherwise 2 s → 5 s → 15 s → 30 s (§4.4).
- **P3** The bare client's header is enforced at runtime and by the contract's required header parameter.
- **P4** Request middleware is openapi-fetch's `fetch` option with a byte snapshot per attempt (§4.4 item 2).
- **P5** A fifth status, `signing-out`, models §4.4's logout-failure state.
- **P6** A boot result arriving after an interactive sign-in is discarded.
- **P7** The splash is outside `#root`, hidden by an external stylesheet (§4.4, §4.9).
- **P8** No toast container in F0 (§4.2): no F0 failure is background, and Sonner's runtime `<style>`
  conflicts with `style-src 'self'`. F1 adds it with its first background failure.
- **P9** Hand-written field components and a native `<select>` instead of shadcn `Form`/Radix `Select` (§5.1).
- **P10** E2E runs `java -jar` twice on one `bootJar`, not `bootRun` twice (§6.4, §6.5).
- **P11** E2E test 6 asserts at most one refresh in flight across tabs (challenge #85) (§6.4).
- **P12** Boundary lint uses `import-x/no-restricted-paths` zones (§4.3).
- **P13** The dependency ledger is measured from the production build (§4.2).
```

In `docs/superpowers/specs/2026-07-22-easycrm-design.md`, at the end of section `## 5. Frontend Architecture` (immediately before the next `## ` heading), add:
```markdown
> **F0 notes (2026-09-16).** English only for now, i18n-ready and enforced by lint and types (F0 spec
> F0-6); the cross-tenant 404 E2E path moves to F1 and throttled Lighthouse to F2 (F0-14). The browser
> refresh model is an httpOnly cookie with Web-Locks-serialized rotation and a 30 s single-use grace
> window for lost responses (F0 spec §3.3, §4.4).
```

- [ ] **Step 5: HANDOFF and ROADMAP**

`docs/superpowers/HANDOFF.md` — add a new top section `## 2026-09-16 (later) — START HERE: F0b built on branch f0b-frontend` (or dated to the day it finishes) that records, replacing nothing below it:
- Branch, head SHA, merged or not, pushed or not (verify with the three `git rev-parse`/`rev-list` commands, as separate invocations).
- Baselines: backend test count from JUnit XML (was 677; +2 `OpenApiRequiredFieldsTest`, + `FrontendWorkflowTest`'s count); frontend Vitest count; coverage floors; E2E 9 tests; per-route gzipped JS from `pnpm budget`.
- The recorded red runs: Task 15 Step 4 (no-op locks) and every row of Task 16 Steps 6–7, one line each.
- The manual walkthrough results from Step 2.
- How to run locally: `docker compose up -d` + `bootRun` (dev profile) + `pnpm dev`; E2E prerequisites (`bootJar`, `pnpm build`, `playwright install chromium`).
- Carried forward, still open: F0a's follow-ups (grace-use audit, logout-vs-grace race, `IssuedSession.toString()`, class-level arch test, `CustomerService` `fieldCodes` before F1), H7, item 3b, DNS provider, the skipped test-tooling Dependabot group, and P8 (toast container arrives with F1).
- Next: F1 (master data) brainstorm → spec → plan.

`docs/ROADMAP.md` — update the header "what changed" block, §1.1 (frontend row), §1.4 (remove "Frontend (zero lines)" and "`/invite/{token}` page"), Part 5 Frontend row (F0 done; F1–F3 left), Part 6 item 4 and §6.1 ("F0b next" → "F1 next"), with the verified commit and test counts.

- [ ] **Step 6: End-of-session challenge pass**

Ask: did implementation hit anything non-obvious not yet logged — e.g. the jsdom `AbortSignal` probe needing the happy-dom fallback, a Radix/shadcn primitive tripping the CSP, `requiredProperties` being ignored and needing the per-component form, or Spring's whole-list replacement for the rate-limit args? If yes, log it as #86+ using the template, in this same commit.

- [ ] **Step 7: Final verification**

```bash
cd backend && ./gradlew clean check --console=plain 2>&1 | tail -3
find . -path '*build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
cd ../frontend
pnpm install --frozen-lockfile
pnpm gen:api && git diff --exit-code -- src/api/schema.d.ts
pnpm typecheck && pnpm lint && pnpm test:coverage && ANALYZE=true pnpm build && pnpm budget
pnpm e2e
cd .. && git status --short
```
Expected: `BUILD SUCCESSFUL` with the count recorded in HANDOFF; every frontend gate green; 9 E2E PASS; only intended files changed (plus the pre-existing untracked `.tessl/` and `docs/architecture/pre-screening-answers.md`, which stay untouched).

- [ ] **Step 8: Commit, then finish the branch**

```bash
git add docs frontend
git commit -m "docs: record F0b -- challenges 84-85, spec amendments, coverage floor, handoff and roadmap"
```
Then request the final review — general code review **plus the specialist reviewers whose "Use when" matches** (all five frontend lenses plausibly do: architecture, performance, security, a11y-i18n, testing), dispatched in parallel on the branch range; if `subagent_type: frontend-review-<lens>` is rejected, dispatch a `general-purpose` agent told to read `.claude/agents/frontend-review-<lens>.md` and follow it verbatim (it worked for F0a) — verify each finding against the code before acting, and use `superpowers:finishing-a-development-branch`. **Do not push**; pushing is the owner's call.
