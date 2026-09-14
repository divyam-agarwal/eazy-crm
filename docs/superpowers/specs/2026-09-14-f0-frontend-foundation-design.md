# F0 — Frontend foundation: cookie auth, the contract fix, and the first screens

**Date:** 2026-09-14
**Status:** Design. Approved section by section in brainstorming. No code changed yet. Specialist
review pending (§8).
**Code baseline:** `main` at `ce1db36` — 626 tests (598 root + 28 `platform-primitives`), 0 failures.
**Parent:** [`2026-07-22-easycrm-design.md`](2026-07-22-easycrm-design.md) §5 (frontend architecture) ·
[`../../ROADMAP.md`](../../ROADMAP.md) Phase 1, item 4, decomposition D-d (F0 → F1 → F2 → F3)

F0 is the first of four frontend sub-projects. It makes the backend safe for a browser to hold a
session, fixes the contract so a generated client is well-typed, and ships the screens that need no
domain data: login, signup, `/invite/{token}`, and an empty authenticated shell. F1 (master data),
F2 (the wedge) and F3 (daily work + team) build on it.

---

# Part 1 — Decisions

| # | Decision | Why |
|---|---|---|
| **F0-1** | **One spec, two slices merged in order: F0a (backend) then F0b (frontend)** | The generated client needs the fixed contract (§3.5) and cookie auth (§3.1) to exist first. F0a is independently mergeable and leaves the backend green; F0b builds on a stable contract instead of a moving one. Building the frontend first against mocks would encode the JSON-body refresh token and `*/*` types that F0a is about to change |
| **F0-2** | **Self-serve signup page is IN F0**, guarded by a server-side switch (§3.4) and the existing `auth` rate limit (30/min per IP, unchanged) | Owner's call. The switch closes signup without a release. No CAPTCHA and no email verification: there is no real email sender (`LoggingEmailSender` is a stub), so verification would pull an email provider into F0 |
| **F0-3** | **The signup switch is a property (`SIGNUP_ENABLED`), default OPEN in every environment.** Toggling means changing the env var and restarting — no rebuild, but not live | A live toggle needs a platform-admin role that does not exist. That role is now a roadmap item (ROADMAP Part 6), so this becomes a runtime toggle later |
| **F0-4** | **Local-only.** Vite dev server proxies `/api` to Spring Boot; nothing is deployed to `app.easycustomerrelationship.site` | Phase 1's exit is "usable on a laptop". Hosting arrives with item 5 (containerise) and SP2 (item 7). The DNS provider (rest of D-g) therefore does not gate F0 |
| **F0-5** | **Refresh race: tabs serialize via the Web Locks API; backend rotation becomes one conditional UPDATE; reuse of a rotated token is a plain 401 with no family revoke** | See §3.3. Family revoke would log out every tab whenever a refresh response is lost on flaky 4G — exactly this audience's network. Reuse detection is deferred to SP7 hardening |
| **F0-6** | **i18n plumbing, English only.** Every string goes through `t()` keys; only the English resource file exists | Owner's call. Hindi is a later pass; the spec's "English + Hindi from day one" is relaxed to "i18n-ready from day one" |
| **F0-7** | **Login keeps a Workspace (slug) field, pre-filled from the last workspace used on this device** | No backend change and no enumeration surface. An email-first workspace lookup would need an unauthenticated endpoint revealing which emails exist |
| **F0-8** | **Refresh cookie is path-scoped (`Path=/api/v1/auth`), not `__Host-`-prefixed** | `__Host-` forces `Path=/`, attaching the refresh token to every API call. Path scoping sends it only where it is consumed |
| **F0-9** | **CSRF defence-in-depth is a required custom header (`X-EasyCRM-Client: web`) on the cookie-reading routes**, on top of `SameSite=Strict` and same-origin | A cross-site form cannot set a custom header; a cross-origin `fetch` that tries triggers a preflight that fails (no CORS config exists). No token-synchronizer machinery needed |
| **F0-10** | **Cross-tenant 404 E2E path moves to F1** | The design spec lists it among the four Playwright paths, but F0 has no tenant-owned resource to attempt to reach |
| **F0-11** | **Throttled Slow-4G Lighthouse runs are deferred to F2**; F0 enforces the 200 KB initial-JS budget instead | F0 has no screen heavy enough to measure; the quotation builder is the first that is |

---

# Part 2 — What exploring the code found

All verified against `main` at `ce1db36`.

1. **The refresh token travels in JSON.** `AuthResponse.refreshToken`, `TokenResponse.refreshToken` and
   `RefreshRequest.refreshToken` in `docs/api/openapi.yaml`. There is no cookie code and no CORS config
   anywhere in `backend/src/main/java`. Design spec §5 requires an httpOnly Secure SameSite cookie.
2. **458 responses are typed `'*/*'`, only 32 `application/json`.** `openapi-typescript` keys response types
   by media type, so a client generated today would be badly typed.
3. **The two PDF routes set `application/pdf` only at runtime.** `QuotationController` (line ~81) and
   `PublicShareController` (line ~57) call `.contentType(MediaType.APPLICATION_PDF)` on the response and
   declare no `produces`. Changing springdoc's default media type to JSON without adding `produces` to
   those mappings would make the contract claim they return JSON.
4. **`RefreshTokenService.rotate` is not race-safe.** It reads the row, checks `revokedAt`, saves a
   replacement, then marks the current row revoked — no lock, no `@Version`. Two concurrent rotations of
   the same token can both pass the check and both mint a live successor, forking the session.
5. **No refresh-token reuse detection.** Presenting a revoked token returns 401; nothing else happens.
6. **Signup has no abuse protection beyond the shared `auth` rate limit** (`/api/v1/auth/**`, 30/min per
   IP). It creates a `TRIAL` tenant and `OWNER` user and returns tokens immediately. The welcome email goes
   to `LoggingEmailSender`, which logs.
7. **`InvitationPreviewResponse` has no tenant slug** (`businessName`, `email`, `role` only). After accept,
   `GET /api/v1/auth/me` returns `tenantSlug`, so the client can still remember the workspace with no
   backend change.
8. **Login needs `slug + email + password`** (`LoginRequest`); every failure is one generic 401 by design
   (no slug/email enumeration).
9. **Access tokens live 900 s** (`easycrm.jwt.access-ttl-seconds`), configurable — which is what lets E2E
   shrink it (§6.3).

---

# Part 3 — F0a: backend auth prep

## 3.0 Clear Dependabot first

Before any F0a work, merge the open Dependabot branches onto `main` if green — at minimum
**springdoc 3.1.1** (may change `openapi.yaml`) and **jjwt 0.13.0** (touches token minting). F0a then
regenerates the contract once, deliberately, instead of twice.

## 3.1 Refresh token moves to an httpOnly cookie

| | |
|---|---|
| Name | `easycrm_rt` |
| Attributes | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=2592000` (30 days, `RefreshTokenService.TTL_DAYS`) |
| Set by | `POST /auth/signup`, `POST /auth/login`, `POST /auth/invitations/{token}/accept`, `POST /auth/refresh` |
| Read by | `POST /auth/refresh`, `POST /auth/logout` |
| Cleared by | `POST /auth/logout` (`Max-Age=0`, same name/path/attributes) |

- **Removed from the wire:** `AuthResponse.refreshToken`, `TokenResponse.refreshToken`, and the
  `RefreshRequest` body entirely. The access token stays in the JSON body.
- **Refresh with no cookie** → 401, the same generic envelope as any other refresh failure.
- **Logout** reads the cookie, revokes that token if present, clears the cookie, and returns **204
  whether or not a cookie was sent** — logout is idempotent.
- **Local dev:** Chromium and Firefox accept `Secure` cookies on `http://localhost`, so dev and Playwright
  need no insecure-cookie switch. Safari on localhost is not a supported dev browser.

## 3.2 CSRF defence-in-depth

`POST /auth/refresh` and `POST /auth/logout` require the header `X-EasyCRM-Client: web`. Missing or
different → **403**. Signup, login and invite-accept do not read the cookie and do not need it. The
primary defence remains `SameSite=Strict` on a same-origin deployment.

## 3.3 Atomic rotation

`rotate` becomes a single conditional update inside the transaction that inserts the successor:

```sql
UPDATE refresh_token
   SET revoked_at = now(), replaced_by_id = :successorId
 WHERE token_hash = :hash
   AND revoked_at IS NULL
   AND expires_at > now()
```

- **0 rows** → throw 401; the transaction rolls back and the successor insert disappears with it.
- **1 row** → the caller gets the successor.
- Postgres row-locks the target during the UPDATE, so the second of two concurrent rotations blocks, then
  re-evaluates `revoked_at IS NULL` against the committed row, matches zero, and fails. Exactly one wins.
- **Reuse of a rotated token is a plain 401.** No token-family revocation (F0-5).

Client-side serialization (§4.3) makes concurrent rotation rare for legitimate tabs; this makes it
harmless when it happens anyway (a lock-less browser, a second device, a retried request).
**Owed: an engineering-challenges entry** — why the fix is split across a browser lock and a
conditional UPDATE, and why reuse detection was declined for this audience.

## 3.4 Signup switch

- Property `easycrm.signup.enabled`, bound from `SIGNUP_ENABLED`, **default `true` in every profile**.
- When **false**: `POST /auth/signup` returns **404** with the standard envelope (`message: "signup is
  closed"`).
- New public route **`GET /api/v1/auth/signup/status`** → `{ "open": boolean }`. Unauthenticated,
  under `/api/v1/auth/**` so it inherits the `auth` rate-limit policy (an unmatched path is unlimited —
  `RateLimitProperties.policyFor`), permitted in `SecurityConfig`, and marked `@SecurityRequirements` so
  the contract does not demand a token.
- Changing it requires a restart (F0-3).

## 3.5 Contract fix

- Set `springdoc.default-produces-media-type: application/json`.
- Add `produces = MediaType.APPLICATION_PDF_VALUE` to the PDF mappings in `QuotationController` and
  `PublicShareController` (Part 2, finding 3).
- Regenerate `docs/api/openapi.yaml` via the existing snapshot workflow; never hand-edit it.
- **New guard:** a test that fails if any response in the generated document is keyed `*/*`, beside a
  non-vacuity assertion that `application/json` responses exist and that the two PDF routes are keyed
  `application/pdf`. (Per challenges #75–79: a guard that passes must be proven able to fail.)

## 3.6 F0a tests

Integration tests, written first:

- The cookie is set, with every attribute in §3.1, on each of the four issuing routes; the JSON body no
  longer contains `refreshToken`.
- Refresh: with cookie + header → 200 and a rotated cookie; no cookie → 401; no header → 403.
- Logout: with cookie → 204, cookie cleared, token revoked (a subsequent refresh with the old value is
  401); without cookie → 204.
- Concurrent rotation: two threads rotate the same token behind a latch; exactly one succeeds, exactly
  one live successor exists.
- Signup switch: open → 201; closed → 404; status route reflects both.
- Contract guard from §3.5, and `OpenApiSnapshotTest` updated deliberately.

---

# Part 4 — F0b: frontend scaffold and API client

## 4.1 Location and tooling

- `frontend/` at the repository root, **pnpm**.
- **Node 24 LTS** pinned in `.nvmrc`, `package.json` `engines`, and CI.
- TypeScript `strict` plus `noUncheckedIndexedAccess`.

## 4.2 Stack (design spec §5, trimmed to what F0 uses)

| Concern | Choice | In F0? |
|---|---|---|
| Build | Vite + React 19 + TypeScript | yes |
| Routing | React Router, lazy route modules | yes |
| Server state | TanStack Query | yes |
| Client state | Zustand — `{status, me}` only | yes |
| Forms | React Hook Form + Zod | yes |
| UI | shadcn/ui + Tailwind v4 | yes |
| i18n | react-i18next, English resources only | yes |
| Tables | TanStack Table + virtualization | **no — F1** |

## 4.3 Generated client

- `openapi-typescript` generates `src/api/schema.d.ts` from `../docs/api/openapi.yaml`
  (`pnpm gen:api`). Committed, never hand-edited.
- `openapi-fetch` is the typed client.
- **Drift guard:** CI runs `pnpm gen:api` and fails on `git diff --exit-code`.

## 4.4 Auth client

**Access token** lives in a module-scoped variable inside the auth client — not Zustand, never
`localStorage`/`sessionStorage`. Zustand holds `{ status: 'booting' | 'authenticated' |
'anonymous', me }` for rendering.

**Middleware** on the `openapi-fetch` client:
1. Attaches `Authorization: Bearer <access>` and `X-EasyCRM-Client: web`.
2. On **401**, awaits the refresh coordinator once, then retries the original request. A second 401 clears
   the session and navigates to `/login?next=<current path>`.

**Refresh coordinator:**
- Depends on a `LockProvider` interface; production binds `navigator.locks`, tests bind a fake.
- `refresh()` runs `locks.request('easycrm-refresh', …)`. Inside the lock: if this tab's access token
  changed since the failing request captured it, return without calling the server. Otherwise
  `POST /auth/refresh`. A tab that waited on the lock sends the browser's *current* cookie, which the
  winning tab already rotated.
- Concurrent callers in one tab share a single in-flight promise.

**Boot:** the access token does not survive a reload, so app start runs refresh → `GET /auth/me`.
Success → `authenticated`; failure → `anonymous`. A splash renders while `booting`, so protected routes
never flash.

**Logout:** `POST /auth/logout`, clear memory and the query cache, then post `logout` on
`BroadcastChannel('easycrm-auth')`. Other tabs receiving it clear their own session immediately rather
than working on for up to 15 minutes.

## 4.5 Error envelope → forms

One helper, `applyApiError(error, setError)`, reused by every form in F0–F3:

- `error.fields` → React Hook Form field errors.
- `error.message` → a form-level message.
- Network failure or 5xx → toast.

## 4.6 Remembered workspace

`localStorage['easycrm.lastWorkspace']` = tenant slug. Written after login, signup and invite-accept.
Every read and write is wrapped in try/catch; failure means "no remembered workspace", never an error.
The slug is not a secret.

---

# Part 5 — Routes and screens

## 5.1 Public routes

A signed-in user visiting any of these is redirected to `/`.

**`/login`**
- Fields: Workspace (pre-filled from §4.6), email, password.
- A 401 renders one form-level message ("Workspace, email or password is incorrect"). No per-field
  blame — the backend deliberately does not distinguish.
- On success: navigate to `next` if it is a safe same-app path, else `/`. **Safe** means it starts with a
  single `/`, is not `//…`, contains no `\`, and parses as same-origin. Anything else is ignored.

**`/signup`**
- On load: `GET /auth/signup/status`. Closed → "Signups are currently closed" and a link to `/login`; no
  form rendered.
- Fields: business name; workspace slug (auto-suggested from the business name, editable, Zod pattern
  `[a-z0-9-]{3,64}`); state (select over GST state codes from `lib/gst/states.ts`); GSTIN (optional,
  shape-checked only — checksum and state match are server-authoritative); email; phone; password
  (min 8).
- 409 (slug taken) and 422 (field) land on their fields via `applyApiError`.
- On success: session is live, workspace remembered, navigate to `/`.

**`/invite/:token`** — matches the `acceptUrl` the backend already mints.
- On load: `GET /auth/invitations/{token}`. Three states:
  - **loading** — skeleton;
  - **valid** — "Join **{businessName}** as **{role}**", email shown read-only, then password and optional
    phone;
  - **invalid** — one message: "This invitation link is invalid or has expired." The backend returns a
    byte-identical 404 for every failure (challenge #55); the page does not attempt to distinguish.
- On accept: `GET /auth/me` for `tenantSlug`, remember it, navigate to `/`.
- The page sets `<meta name="referrer" content="no-referrer">` so the token cannot leak through the
  `Referer` of any navigation away.

## 5.2 Protected routes

Wrapped by `RequireSession`, which renders the splash while `booting`, redirects to `/login?next=…`
when `anonymous`, and renders children when `authenticated`.

- **App shell:** header with workspace slug, user email and role, and a logout button. Left navigation
  is present but empty until F1.
- **`/`** — placeholder: "Signed in to {slug} as {email} ({role})". F3 replaces it with the role-aware
  dashboard.

## 5.3 Everywhere

- `*` → not-found page.
- Per-route error boundary with a "Something went wrong" fallback and a reload action.
- Skeletons, not spinners (design spec §5 performance budget).

## 5.4 Out of scope for F0

Password reset (no backend) · profile editing · dashboard · Hindi resources · every F1–F3 screen ·
deployment · email verification · CAPTCHA.

---

# Part 6 — Testing and CI

## 6.1 Ordering

Test first, per task, in both slices.

## 6.2 F0b unit and component tests (Vitest + Testing Library + MSW)

**Unit:**
- Refresh coordinator: serialized across concurrent callers; concurrent 401s share one refresh; a tab whose
  token already changed skips the network call; failure clears the session; logout broadcast clears a
  second instance.
- `applyApiError`: field mapping, form-level message, 5xx → toast.
- `next` sanitizer: `/quotes` accepted; `//evil.com`, `https://evil.com`, `/\evil.com`, empty rejected.
- Slug suggestion from business names (spaces, punctuation, Devanagari input → ASCII fallback or empty).
- Remembered-workspace storage when `localStorage` throws.

**Component** (MSW handlers typed against the generated `schema.d.ts`, so a mock cannot drift from the
contract without `tsc` failing):
- Login: generic 401 message; workspace pre-fill.
- Signup: open vs closed; 409 on slug; 422 on GSTIN.
- Invite: valid, invalid, accept.
- `RequireSession`: splash → app, splash → redirect.

`eslint-plugin-jsx-a11y` runs as part of lint.

## 6.3 End-to-end (Playwright, real backend + Postgres)

1. Signup → logout → login with the remembered workspace.
2. Owner invites via the API → invitee accepts at `/invite/:token` → lands on `/`.
3. **Multi-tab refresh:** two pages in one browser context, access-token TTL set to ~5 s via
   `easycrm.jwt.access-ttl-seconds`; both call the API after expiry; both remain signed in.
4. Logout in one tab signs the other out.

## 6.4 CI (`.github/workflows/ci.yml`)

- **`frontend` job — blocking:** `pnpm install --frozen-lockfile` → `pnpm gen:api` + `git diff
  --exit-code` → `tsc --noEmit` → ESLint → Vitest → `vite build` → budget check failing if initial JS
  exceeds **200 KB gzipped**.
- **`e2e` job — blocking:** Postgres service container, backend via `bootRun`, `vite preview` with `/api`
  proxied, Playwright; trace uploaded on failure.
- New actions are pinned the same way as existing ones; `SupplyChainWorkflowTest`'s pin and
  unconditional-step assertions must cover the new jobs — the plan verifies this rather than assumes it.
- `.github/dependabot.yml` gains an `npm` ecosystem entry for `/frontend`.

---

# Part 7 — Documentation owed in the same change

- **Engineering challenges:** the refresh race (§3.3 + §4.4) — split fix, declined reuse detection.
- **Annotations reference:** any new annotation F0a introduces (e.g. `@CookieValue`).
- **ROADMAP / HANDOFF:** F0 status; platform-admin role item (added with this spec).
- **Design spec §5:** note F0-6 (English-only for now) and F0-10/F0-11 (deferred E2E path, Lighthouse)
  when F0 lands, so §5 does not read as already satisfied.

---

# Part 8 — Review

Before `writing-plans`, dispatch in parallel the specialist reviewers whose "Use when" matches — all five
apply: `frontend-review-architecture` (state placement, generated client, idempotency),
`frontend-review-performance` (bundle budget, dependencies), `frontend-review-security` (cookie, CSRF,
refresh, token-bearing invite URL), `frontend-review-a11y-i18n` (new screens and forms, i18n plumbing),
`frontend-review-testing` (MSW, Playwright paths, CI gates, test-first ordering). Findings are verified
against the code before being applied.
