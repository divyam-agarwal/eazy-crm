# F0 — Frontend foundation: cookie auth, the contract fix, and the first screens

**Date:** 2026-09-14
**Status:** Design. Approved section by section in brainstorming, then revised after specialist review
(Part 9). No code changed yet.
**Code baseline:** `main` at `ce1db36` — 626 tests (598 root + 28 `platform-primitives`), 0 failures.
**Parent:** [`2026-07-22-easycrm-design.md`](2026-07-22-easycrm-design.md) §5 (frontend architecture) ·
[`../../ROADMAP.md`](../../ROADMAP.md) Phase 1, item 4, decomposition D-d (F0 → F1 → F2 → F3)

F0 is the first of four frontend sub-projects. It makes the backend safe for a browser to hold a
session, fixes the contract so a generated client is well-typed, and ships the screens that need no
domain data: login, signup, `/invite/{token}`, and an empty authenticated shell. F1 (master data),
F2 (the wedge) and F3 (daily work + team) build on it — so the plumbing decided here (session
lifecycle, query defaults, error mapping, i18n, folder boundaries, CI gates) is inherited three times.

---

# Part 1 — Decisions

| # | Decision | Why |
|---|---|---|
| **F0-1** | **One spec, two slices merged in order: F0a (backend) then F0b (frontend)** | The generated client needs the fixed contract (§3.7) and cookie auth (§3.1) to exist first. F0a is independently mergeable and leaves the backend green |
| **F0-2** | **Self-serve signup page is IN F0**, guarded by a server-side switch (§3.6). Login/signup/accept keep the existing `auth` rate limit (30/min per IP) | Owner's call. No CAPTCHA and no email verification: there is no real email sender (`LoggingEmailSender` is a stub) |
| **F0-3** | **The signup switch is a property (`SIGNUP_ENABLED`), default OPEN in every environment.** Toggling = env var + restart | A live toggle needs a platform-admin role; that role is ROADMAP item 4a |
| **F0-4** | **Local-only.** Vite proxies `/api` to Spring Boot; nothing is deployed | Phase 1's exit is "usable on a laptop". Hosting arrives with items 5 and 7; the DNS provider does not gate F0 |
| **F0-5** | **Refresh: tabs serialize via Web Locks; rotation is one conditional UPDATE; a token rotated within the last 30 s whose successor is still unused may be presented once more (grace window, §3.3)** | A refresh whose response is lost on flaky 4G leaves the browser holding a revoked cookie shared by every tab — without grace, that logs the user out everywhere. Cost, recorded: a stolen refresh token is replayable for ≤30 s, and only while the legitimate client has not used its successor. Token-family revocation stays declined (SP7) |
| **F0-6** | **i18n plumbing, English only**, enforced structurally (§4.7) | Owner's call. The design spec's "English + Hindi from day one" becomes "i18n-ready from day one, enforced by lint and types" |
| **F0-7** | **Login keeps a Workspace (slug) field, pre-filled from the last workspace used on this device** | No backend change, no enumeration surface |
| **F0-8** | **Refresh cookie is path-scoped (`Path=/api/v1/auth`), not `__Host-`-prefixed** | `__Host-` forces `Path=/`, attaching the token to every API call. Path scoping still sends it to the other `/auth/*` routes; §3.8's guard ensures only refresh and logout *read* it |
| **F0-9** | **CSRF defence-in-depth: required header `X-EasyCRM-Client: web` on refresh and logout**, on top of `SameSite=Strict` and same-origin | A cross-site form cannot set a custom header; a cross-origin `fetch` that tries triggers a preflight that fails (no CORS config exists). It also covers same-site sibling subdomains that `SameSite` does not |
| **F0-10** | **A signed-in user opening `/invite/:token` sees who they are signed in as and is offered "Sign out and accept"** — no redirect, no silent accept | Owner's call. Redirecting loses the link; silently accepting overwrites a live session's cookie in every tab |
| **F0-11** | **F0a adds machine-readable field reason codes (`fieldCodes`) for the validators F0 touches**; the pattern is documented for F1+ | Owner's call. Server field messages are English prose; without codes, a Hindi UI would show English field errors or string-match backend text |
| **F0-12** | **oasdiff stays non-blocking.** Its recorded trigger moves from "the frontend exists" to branch protection (roadmap item 8) | Owner's call. The generated-client drift guard already fails CI on unfollowed contract changes; on post-merge-only CI a blocking breaking-change gate is still only an alarm |
| **F0-13** | **Access token in memory + httpOnly refresh cookie, not a BFF.** Residual risk recorded in §4.9 | A BFF adds a server tier F0 does not have. The residual XSS risk is bounded by CSP and lint, not by the token location |
| **F0-14** | **Cross-tenant 404 E2E path moves to F1; throttled Slow-4G Lighthouse runs move to F2** | F0 has no tenant-owned resource and no heavy screen. F0 enforces a per-route JS budget instead (§6.5) |

---

# Part 2 — What exploring the code found

All verified against `main` at `ce1db36`. Items marked **(review)** were found or corrected by the
specialist review (Part 9) and re-verified.

1. **The refresh token travels in JSON.** `AuthResponse.refreshToken`, `TokenResponse.refreshToken`,
   `RefreshRequest.refreshToken`. No cookie code and no CORS config in `backend/src/main/java`.
2. **458 responses are typed `'*/*'`, only 32 `application/json`.**
3. **The two PDF routes set `application/pdf` only at runtime** (`QuotationController`,
   `PublicShareController` call `.contentType(...)`; no `produces`). A JSON default without `produces`
   on those mappings would mis-describe them.
4. **(review — corrects the first draft)** `RefreshToken extends BaseEntity`, which carries `@Version`.
   Two concurrent `rotate` calls therefore do **not** fork the session: the loser's versioned UPDATE
   matches zero rows, raises `OptimisticLockingFailureException`, and rolls back its successor insert.
   **The actual defect is the loser's status: `ApiExceptionHandler` maps that exception to 409**, where
   a refresh failure should be 401.
5. **No refresh-token reuse handling.** A revoked token returns 401; nothing else happens.
6. **Signup has no abuse protection beyond the shared `auth` policy** (`/api/v1/auth/**`, 30/min per IP).
   **(review)** It checks `findBySlug` before anything else, so any gate added later must run first or a
   closed signup still reveals whether a slug exists (409 vs 404).
7. **`InvitationPreviewResponse` has no tenant slug** (`businessName`, `email`, `role`).
8. **(review)** **Login email match is case-sensitive.** `AuthService.login` uses `users.findByEmail`,
   while `V32` made membership uniqueness case-insensitive and `InvitationService` uses
   `findByEmailIgnoreCase`. An Android keyboard's auto-capital ("Ravi@shop.in") fails login with the
   deliberately unhelpful generic 401.
9. **(review)** **The slug-taken 409 carries no `fields`** (`throw new ConflictException("slug already
   taken")`), so it cannot land on the slug input as-is.
10. **(review)** **Bean-validation failures are 400**, not 422 (`ApiExceptionHandler.invalid`);
    `ValidationException` is 422. Both carry `fields` as `field → English message`.
11. **(review)** **Spring Security's 401 has no body** (`HttpStatusEntryPoint(UNAUTHORIZED)` in
    `SecurityConfig`); `RateLimitFilter` writes its own 429.
12. **(review)** **`SupplyChainWorkflowTest` guards only the `supply-chain` job** (`assertUnconditional`
    and `assertBlocks` iterate that job's steps). New jobs are not covered by it.
13. **(review)** **`OasdiffWorkflowTest` asserts oasdiff stays non-blocking**, with the message "flipping
    this to blocking is a deliberate policy change tied to the frontend".
14. **Access tokens live 900 s** (`easycrm.jwt.access-ttl-seconds`); refresh tokens 30 days
    (`RefreshTokenService.TTL_DAYS`).

---

# Part 3 — F0a: backend auth prep

## 3.0 Clear Dependabot first

Merge the open Dependabot branches onto `main` if green — at minimum springdoc 3.1.1 (may change
`openapi.yaml`) and jjwt 0.13.0 (touches token minting) — so F0a regenerates the contract once.

## 3.1 Refresh token moves to an httpOnly cookie

| | |
|---|---|
| Name | `easycrm_rt` |
| Attributes | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=2592000` (30 days) |
| Set by | `POST /auth/signup`, `POST /auth/login`, `POST /auth/invitations/{token}/accept`, `POST /auth/refresh` |
| Read by | `POST /auth/refresh`, `POST /auth/logout` only (guarded, §3.8) |
| Cleared by | `POST /auth/logout` (`Max-Age=0`, same name/path/attributes) |

- **Removed from the wire:** `AuthResponse.refreshToken`, `TokenResponse.refreshToken`, `RefreshRequest`.
- **Identity on every session response.** `AuthResponse` and `TokenResponse` both carry
  `accessToken, userId, tenantId, tenantSlug, email, role` — the same identity `MeResponse` has. Boot
  becomes one request, login/signup/accept need no follow-up `/me`, and the client can detect a
  principal change on refresh (§4.4).
- **Refresh with no cookie** → 401. **Logout** revokes the cookie's token if present, clears the cookie,
  returns **204 whether or not a cookie was sent**.
- **Login and accept revoke an incoming `easycrm_rt`** belonging to a different session before issuing a
  new one, so switching users on a device does not leave the previous refresh token live for 30 days.
  (These routes may *revoke* the incoming cookie; they never *authenticate* with it.)
- **Local dev and E2E run on Chromium**, which accepts `Secure` cookies on `http://localhost`. Other
  browsers' localhost behaviour is not relied on.

## 3.2 CSRF defence-in-depth

`POST /auth/refresh` and `POST /auth/logout` require `X-EasyCRM-Client: web`. The check is **explicit
code returning 403 with the standard error envelope** — not `@RequestHeader`, which would 400. A test
also asserts a cross-origin `OPTIONS` preflight to `/auth/refresh` is refused, so adding CORS later
cannot silently remove the defence.

## 3.3 Rotation: conditional UPDATE plus a 30-second grace window

`rotate(rawToken)` inside one transaction:

1. Insert the successor row.
2. **Normal path** —
   ```sql
   UPDATE refresh_token
      SET revoked_at = now(), replaced_by_id = :successorId, version = version + 1
    WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > now()
   ```
   One row → return the successor. (`version = version + 1` because a bulk UPDATE bypasses `@Version`,
   and `revokeAllForUser` still writes through the entity.)
3. **Grace path** — if step 2 matched zero rows:
   ```sql
   UPDATE refresh_token
      SET grace_used_at = now(), replaced_by_id = :successorId, version = version + 1
    WHERE token_hash = :hash
      AND revoked_at > now() - interval '30 seconds'
      AND grace_used_at IS NULL
      AND expires_at > now()
      AND replaced_by_id IN (SELECT id FROM refresh_token WHERE revoked_at IS NULL)
   ```
   One row → revoke the orphaned previous successor (the one the client never received) and return the
   new successor. The UPDATE overwrites `replaced_by_id`, so the orphan's id is read first under `SELECT …
   FOR UPDATE` on the presented row, in the same transaction; the plan fixes the exact statement order and
   proves it under the concurrency test.
4. Zero rows on both → **401**; the transaction rolls back and the inserted successor disappears.

- **Migration `V35`** adds nullable `grace_used_at TIMESTAMPTZ` to `refresh_token` (global,
  RLS-exempt table, so no per-tenant DML concern; squawk applies).
- **The successor must still be unused.** If the legitimate client already rotated the successor,
  presenting the old token is not a lost response — it is a replay, and it gets 401.
- **Grace is single-use** (`grace_used_at IS NULL`), so a replay loop is impossible.
- **`rotate` performs no versioned entity write** (the presented row is changed only by conditional
  native UPDATEs), so no optimistic-lock exception — and no 409 — can arise from it.
- **Owed: engineering-challenges entry** — the lost-ACK problem on a non-idempotent rotation, why Web Locks
  alone cannot fix it, why the grace condition requires an *unused* successor, and the replay-window
  cost. Include that the first draft of this spec misdiagnosed a fork `@Version` already prevented.

## 3.4 Rate limits

- **New policy `session`** for `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/me`: a looser
  per-IP limit (starting point 120/min), listed **before** `auth` in `application.yml` —
  `RateLimitProperties.policyFor` is first-match (`findFirst()`), so order decides. A test proves a
  refresh is governed by `session`, not `auth`, so a later reordering fails the build.
- **`auth` stays 30/min** and keeps governing signup, login, invitation preview/accept and
  `signup/status`. Its yml comment is updated: session resumption now has its own bucket.
- Why: every tab boot and every 15-minute refresh hits these routes, and an office sits behind one NAT
  IP. Starving refresh would log people out; starving login is the point.

## 3.5 Login email is case-insensitive

`AuthService.login` switches to `findByEmailIgnoreCase`. Test: a user created as `ravi@shop.in` logs in
as `Ravi@shop.in`. **Owed: engineering-challenges entry only if** the plan finds a second path with the
same mismatch; otherwise it is a routine fix.

## 3.6 Signup switch

- `easycrm.signup.enabled` from `SIGNUP_ENABLED`, **default `true` everywhere**.
- When false, `POST /auth/signup` returns **404** (`message: "signup is closed"`) **before** the slug
  lookup, so a closed signup reveals nothing about which slugs exist.
- New public **`GET /api/v1/auth/signup/status`** → `{ "open": boolean }`: permitted in `SecurityConfig`,
  `@SecurityRequirements`, governed by the `auth` rate-limit policy.

## 3.7 Contract: media types and field reason codes

**Media types.** `springdoc.default-produces-media-type: application/json`; `produces =
MediaType.APPLICATION_PDF_VALUE` on the two PDF mappings; regenerate `openapi.yaml`. Guard test: no
response keyed `*/*`, with non-vacuity assertions that `application/json` responses exist and both PDF
routes are keyed `application/pdf`.

**Field reason codes (F0-11).** `ApiError` gains an **additive, optional** `fieldCodes: Map<String,
String>` beside `fields` (omitted when null, like `fields`). Existing consumers and tests are unaffected.

- **Bean validation (400):** `fieldCodes` is populated automatically from `FieldError.getCode()`,
  converted to SCREAMING_SNAKE (`NOT_BLANK`, `SIZE`, `PATTERN`, `EMAIL`) — every endpoint gets codes for
  free.
- **`ValidationException` (422):** gains a constructor taking a code. F0's call sites pass one:
  `GSTIN_REQUIRED` / `GSTIN_LENGTH` / `GSTIN_CHARSET` / `GSTIN_CHECKSUM` (the four failure modes of
  `Gstin.parse` in `platform-primitives`), `STATE_CODE_INVALID` (`StateCode.requireValid`),
  `STATE_CODE_GSTIN_MISMATCH` (signup). `Gstin` and `StateCode` are shared, so `CustomerService` emits
  these codes too at no extra cost.
- **`ConflictException` for a taken slug** passes `fields: {slug: …}` and `fieldCodes: {slug:
  "SLUG_TAKEN"}`.
- Codes are `SCREAMING_SNAKE`, stable once shipped, and documented in `docs/api/error-codes.md` (new),
  which F1+ extends. Call sites F0 does not touch keep emitting messages only; that is acceptable because
  the client falls back to the server text (§4.6).

## 3.8 Guards

- **Only `refresh` and `logout` authenticate with `easycrm_rt`** — an ArchUnit (or source-scan) test
  fails if any other handler binds the cookie for authentication. Login/accept's revoke path (§3.1) goes
  through one named method the rule permits.

## 3.9 F0a tests (written first)

- Cookie set with every §3.1 attribute on all four issuing routes; JSON bodies carry identity and no
  `refreshToken`.
- Refresh: cookie + header → 200, rotated cookie, identity in body; no cookie → 401; no header → 403
  envelope; cross-origin preflight refused.
- **Concurrent rotation:** two threads behind a latch rotate the same token → exactly one live token
  remains and **no response is 409**. *This test must be seen failing on today's code* (today the loser
  gets 409) before the fix.
- **Grace window:** rotate, discard the response, present the old token within 30 s → 200, previous
  successor revoked; present it a third time → 401; present it after the successor was used → 401;
  present it after 30 s (clock injected) → 401.
- Logout: with cookie → 204, cookie cleared, token revoked; without → 204.
- Login/accept with a stale cookie from another session → that token revoked.
- Rate limits: refresh governed by `session`; login by `auth`.
- Login with a differently-cased email succeeds.
- Signup: open → 201; closed → 404 without consulting the slug (a taken slug and a free slug give
  identical responses); status route reflects both; taken slug → 409 with `fieldCodes.slug`.
- `fieldCodes` present for a bean-validation failure and for each coded `ValidationException`.
- Contract guard (§3.7); `OpenApiSnapshotTest` updated deliberately.

---

# Part 4 — F0b: frontend architecture

## 4.1 Location and tooling

`frontend/` at the repository root, **pnpm**, **Node 24 LTS** pinned in `.nvmrc`, `engines` and CI.
TypeScript `strict` + `noUncheckedIndexedAccess`.

## 4.2 Stack, and the dependency ledger

| Concern | Choice | Entry chunk? |
|---|---|---|
| Build | Vite + React 19 + TypeScript | — |
| Routing | React Router, lazy route modules | yes |
| Server state | TanStack Query (devtools only under `import.meta.env.DEV`) | yes |
| Client state | Zustand — `{status, me}` only | yes |
| Forms | React Hook Form + `@hookform/resolvers` + **`zod/mini`** | route chunks |
| UI | shadcn/ui on Tailwind v4, per-component imports, no barrel re-exports | route chunks |
| Toasts | shadcn's toast primitive (no second toast library) | yes (container) |
| i18n | i18next + react-i18next, resources lazy-loaded (§4.7) | yes (runtime) |
| API | `openapi-fetch` | yes |
| Fonts | **system font stack**, including system Devanagari fallback; no webfont in F0 | — |
| Tables | TanStack Table — **not in F0** | — |

The plan records each dependency's measured gzipped size in this ledger at install time. **Every later
F-slice that adds a dependency states its gzipped size and chunk.**

## 4.3 Folder map and enforced boundaries

```
frontend/src/
  api/            schema.d.ts (generated), client.ts (THE one openapi-fetch instance + middleware)
  app/            router.tsx, providers.tsx, queryClient.ts, RequireSession.tsx, errorBoundary.tsx
  features/
    auth/
      api/        query/mutation hooks, authKeys factory
      session/    sessionStore.ts (Zustand), endSession.ts, refreshCoordinator.ts, lockProvider.ts
      pages/      LoginPage, SignupPage, InvitePage
      components/
  components/ui/  shadcn primitives
  lib/            storage.ts, safeNext.ts, gst/states.ts, apiError.ts, i18n/
  locales/en/     common.json, auth.json
```

Enforced by ESLint in the blocking `frontend` job, each rule proven with a deliberately violating fixture:
- Import direction `lib`/`components` → `features` → `app`; **features never import each other**
  (`eslint-plugin-boundaries` or `import/no-restricted-paths`).
- **No `fetch` and no `openapi-fetch` import outside `src/api/`** (`no-restricted-globals`,
  `no-restricted-imports`) — a bypassing request would skip auth, the CSRF header and refresh.

## 4.4 Session lifecycle

**State.** The access token lives in a module-scoped variable in `features/auth/session`. Zustand holds
`{ status: 'booting' | 'authenticated' | 'anonymous' | 'unreachable', me }`. `me` changes **only**
through `establishSession` and `endSession`; no feature queries `/auth/me` directly.

**`establishSession(sessionResponse)`** — stores the token, sets `me` from the response's identity,
sets `authenticated`, remembers the workspace (§4.8), and broadcasts `login {userId, tenantId}` on
`BroadcastChannel('easycrm-auth')`. Used by boot, login, signup and accept.

**`endSession(reason)`** — clears the token, resets Zustand, calls `queryClient.clear()`. **Every** way a
session ends goes through it: explicit logout, a refresh 401, a received `logout` broadcast, a principal
change.

**Boot.** `POST /auth/refresh` (one request — identity is in the response).
- 200 → `establishSession`.
- 401 → `anonymous`.
- Network error, timeout, 5xx, 429 → `unreachable`: a retry screen with a button and automatic retry with
  backoff. **The session is not ended** — the cookie may be fine.
- **Public routes render immediately and do not wait on boot.** `/invite/:token` fetches its preview in
  parallel with boot.
- The splash is inline in `index.html` (paints before JS parses), has an accessible name ("Loading
  EasyCRM") and `aria-busy`, and is used only by protected routes.

**Client middleware** (on the single client in `src/api/client.ts`):
1. Attaches `Authorization`, `X-EasyCRM-Client: web`, and a **15 s `AbortSignal.timeout`**.
2. **Clones the request before dispatch** so a retry never sends a consumed body.
3. On **401** from any route **except** `/auth/login`, `/auth/signup`, `/auth/refresh`, `/auth/logout`,
   `/auth/invitations/**`: await the refresh coordinator, then retry once with the clone. A 401 on the
   retry → `endSession('expired')` and emit a **`session-expired` event**; the router listener navigates
   to `/login?next=…`. (The middleware never calls the router, so F2 can replace navigation with a
   re-login dialog that preserves an in-progress quotation.)
4. Refresh failures of the network/5xx/429 class do not end the session; the original request fails with a
   retryable error.

**Refresh coordinator.**
- Depends on a `LockProvider`; production binds `navigator.locks`, tests bind a fake with the same
  semantics (exclusive, held until the callback's promise settles).
- Inside `locks.request('easycrm-refresh', …)`: if this tab's token changed since the failing request
  captured it, return. Otherwise `POST /auth/refresh` through a **bare client with no middleware**.
- Concurrent callers in one tab share one in-flight promise.
- **Principal check:** if the refreshed `userId` or `tenantId` differs from `me`, `endSession('principal
  changed')` and reload. Same on receiving a `login` broadcast carrying a different principal.

**Logout.**
1. `POST /auth/logout`.
2. Always clear local state via `endSession` and broadcast `logout`.
3. **If the POST did not return 204**, the cookie is still live and JS cannot delete it (httpOnly). Show a
   blocking "Sign-out did not complete — retrying" state instead of the login page; retry automatically
   when the network returns; show the login page only after a 204. On a shared counter phone this is the
   difference between signed out and looking signed out.

## 4.5 Query client defaults (`app/queryClient.ts`)

- Queries: retry network errors and 5xx up to 2 times with backoff; **never retry 4xx**.
- Mutations: `retry: 0`.
- `refetchOnWindowFocus: false` globally (opt in per resource from F1).
- Default `staleTime: 30_000`; per-resource overrides from F1 (design spec §5).
- Signup status and invite preview are `useQuery` hooks keyed through `authKeys.signupStatus()` and
  `authKeys.invitation(token)` — no fetch-in-`useEffect`.

## 4.6 Error mapping (`lib/apiError.ts`)

One `applyApiError(response, form)` used by every form in F0–F3:
- **Parses defensively.** A body-less 401/403, a non-JSON body, or a missing envelope never throws.
- **Field errors** (400 and 422, and 409 with fields): for each field, message = `t('errors.fields.' +
  fieldCodes[field])` if the key exists, else `t('errors.' + code)` if it exists, else the server text.
  `setError(field, …, { shouldFocus: true })` on the first.
- **Form-level message** (401 on login, envelope `message`, 429, network failure, 5xx on submit) renders in
  a **persistent `role="alert"` region** above the form — not a toast. **429** uses `Retry-After` ("Too many
  attempts. Try again in N seconds.").
- **Toasts** only for background, non-blocking failures; the toast container is an `aria-live` region.

## 4.7 i18n, enforced

- `supportedLngs: ['en']`, `fallbackLng: 'en'`; **namespaces per feature** (`common`, `auth`).
- Resources load through a dynamic import per language/namespace (`i18next-resources-to-backend`), so a
  future `hi` adds nothing to the English user's bundle.
- **Typed keys** via i18next `CustomTypeOptions`: an unknown key fails `tsc`.
- **Lint bans literal JSX text** in `src/features/**` and `src/app/**` (`eslint-plugin-i18next`
  `no-literal-string` or equivalent), proven with a red fixture.
- **Zod messages are keys** (`'validation.password.min'`), resolved when rendered, with interpolated
  values — never English literals in schemas.
- Rich sentences use `<Trans>`; enum values render through `t('roles.' + role)`.
- `document.documentElement.lang` follows `i18n.language`.
- Vitest i18n setup throws on a missing key.
- **Recorded for the Hindi pass, not built:** a key-parity test between `en` and `hi`; language
  preference stored per device; GST terms (GSTIN, HSN, CGST/SGST/IGST) stay English; state names show
  code first.

## 4.8 Remembered workspace

`localStorage['easycrm.lastWorkspace']` = tenant slug, written by `establishSession`. All access through
`lib/storage.ts`, which wraps every call in try/catch; failure means "nothing remembered".

## 4.9 Browser security posture

- **Residual risk of F0-13, recorded:** while a page is open, XSS can call the API with the in-memory token
  and can call `/auth/refresh` itself (it can set the custom header). The token location limits
  *persistence* (nothing survives in storage), not *in-session* abuse; CSP and lint are the controls.
- ESLint `react/no-danger` from F0; any future `href` from user data must pass a scheme allowlist helper
  (added when the first such link appears, F1).
- **Intended CSP**, recorded now so F0b adopts nothing that would need loosening: `default-src 'self';
  script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none';
  connect-src 'self'`. The deployed header is owned by the hosting layer (item 5 / SP2). The E2E
  `vite preview` server sends it so violations surface in F0. No inline scripts, including the splash
  (CSS only) and any theme script.
- `<meta name="referrer" content="no-referrer">` **globally in `index.html`**, not per route.

---

# Part 5 — Routes and screens

## 5.1 Public routes

Public routes render without waiting for boot. When boot resolves to `authenticated`, `/login` and
`/signup` redirect to `/`; `/invite/:token` does not (F0-10).

**All forms:** shadcn `Form` primitives (label, description and error linked via `aria-describedby` and
`aria-invalid`); submit disabled only while pending, never to hide invalid state; paste never blocked;
password fields have a show/hide toggle.

**`/login`**
- Workspace: `autocapitalize="none" autocorrect="off" spellcheck="false"`, lowercased on input.
- Email: `type="email" autocomplete="username"`. Password: `autocomplete="current-password"`.
- 401 → one form-level message ("Workspace, email or password is incorrect").
- On success: `establishSession`, then navigate to `next` if safe, else `/`. **Safe** = starts with a single
  `/`, not `//`, no `\`, resolves same-origin.

**`/signup`**
- `useQuery(authKeys.signupStatus())`. Closed → "Signups are currently closed" + link to `/login`, no form.
- Fields: business name (`autocomplete="organization"`); workspace slug (auto-suggested, editable,
  lowercased, `[a-z0-9-]{3,64}`); state (select, "27 – Maharashtra" form, from `lib/gst/states.ts`);
  GSTIN (optional, `autocapitalize="characters"`, uppercased, shape-checked only); email (`type="email"
  autocomplete="email"`); phone (`type="tel" autocomplete="tel"`); password (`autocomplete="new-password"`,
  min 8).
- Errors via `applyApiError`; a taken slug lands on the slug field via `fieldCodes.slug`.
- **Retry after a lost response:** if this form already submitted this slug and a 409 `SLUG_TAKEN` returns,
  show "This workspace may already have been created. Sign in" with the workspace pre-filled, instead of
  a field error.
- On success: `establishSession`, navigate to `/` with `replace`.

**`/invite/:token`** — the path matches the `acceptUrl` the backend mints.
- `useQuery(authKeys.invitation(token))`, in parallel with boot. States:
  - **loading** — skeleton sized to the final card;
  - **invalid** — "This invitation link is invalid or has expired." (one state; the backend's 404s are
    byte-identical by design, challenge #55);
  - **valid + anonymous** — `<Trans>` "Join **{businessName}** as **{role}**"; email rendered as a
    read-only `autocomplete="username"` input (so password managers save the right account); password
    (`new-password`) and optional phone;
  - **valid + authenticated (F0-10)** — "This invitation is for **{invite email}** to join
    **{businessName}**. You're signed in as **{me.email}**." with **[Sign out and accept]** (runs the §4.4
    logout, including its failure handling, then shows the anonymous state on the same page) and **[Go
    to my workspace]**.
- **Retry after a lost response:** if accept was already submitted from this page and a 404 returns, show
  "If you already set your password, sign in" instead of the invalid state.
- On accept: `establishSession`, navigate to `/` with `replace` so Back does not return to the token URL.

## 5.2 Protected routes

`RequireSession` renders the splash while `booting`, the retry screen while `unreachable`, redirects to
`/login?next=…` when `anonymous`, else children.

- **App shell:** header with workspace slug, email, `t('roles.' + role)`, logout. Empty left nav until F1.
- **`/`** — placeholder "Signed in to {slug} as {email} ({role})". F3 replaces it.

## 5.3 Everywhere

- `*` → not-found page; per-route error boundary with reload.
- **Each route sets a `t()`-keyed `document.title`, and focus moves to the page `<h1>` (`tabIndex={-1}`)
  after navigation.**
- Skeletons match final layout dimensions and are `aria-hidden`; shimmer respects
  `prefers-reduced-motion`.
- No fixed-height text containers (Devanagari matras must not clip).

## 5.4 Out of scope for F0

Password reset · profile editing · dashboard · Hindi resources · `lib/format` money/date/phone helpers
(F1, first screen that shows money or dates) · deployment · email verification · CAPTCHA · every F1–F3
screen.

---

# Part 6 — Testing and CI

## 6.1 Ordering and conventions

Test first, per task, in both slices. Testing Library query priority: `getByRole` with name → label →
text; `userEvent`; `findBy*` for async. No frontend coverage floor in F0; one is set from the measured
baseline at the end of F0b, mirroring the backend's JaCoCo floors.

## 6.2 Unit tests (Vitest)

- **Refresh coordinator:** serialization; shared in-flight promise; skip when the token already changed;
  refresh 401 → `endSession`; refresh network/5xx/429 → session kept; principal change → `endSession`.
- **Middleware:** excluded auth routes never trigger refresh; retry of a POST sends the identical JSON body;
  second 401 → `endSession` + `session-expired` with a sanitized `next`; timeout aborts.
- **Logout:** 204 → login page; network failure → blocking retry state, session state cleared, login page
  not shown until a later 204.
- **`applyApiError`:** `fieldCodes` → translated key; unknown code → `code` key → server text; 400 and 422
  both map; 409 with fields; body-less 401/403 does not throw; 429 uses `Retry-After`; first error field
  receives focus.
- `safeNext`: `/quotes` accepted; `//evil.com`, `https://evil.com`, `/\evil.com`, empty rejected.
- Slug suggestion (spaces, punctuation, Devanagari → empty or ASCII fallback).
- `storage.ts` when `localStorage` throws.

## 6.3 Component tests (Testing Library + MSW)

- MSW handlers are typed through a small helper keyed on the generated `paths` type
  (`paths[P][M]['responses'][S]['content']['application/json']`), or `openapi-msw` if the plan confirms
  its API — so a mock cannot drift from the contract without `tsc` failing.
- Login: generic 401 renders and does **not** redirect; workspace pre-fill; lowercasing.
- Signup: open vs closed; `SLUG_TAKEN` on the field; 422 `GSTIN_CHECKSUM` focuses GSTIN; lost-response
  409 shows the sign-in hint.
- Invite: loading, invalid, valid+anonymous accept, **valid+authenticated shows sign-out-and-accept**,
  lost-response 404 hint.
- `RequireSession`: booting → app; booting → redirect; unreachable → retry screen.

## 6.4 End-to-end (Playwright, Chromium, real backend + Postgres)

Two Playwright projects against two backend processes:

**`e2e-main`** (default TTLs; rate limits raised through properties):
1. Signup → **reload `/` → still signed in** → logout → login with the remembered workspace.
2. Owner invites via the API → test navigates to **the backend-returned `acceptUrl`** (the `e2e` job sets
   `PUBLIC_BASE_URL` to the `vite preview` origin) → invitee accepts → lands on `/`.
3. Owner signed in opens an invite link → sees sign-out-and-accept → completes it → header shows the
   invitee.
4. Logout in one tab signs the other out; then a **different user** signs in and no previous user's data
   or header renders.
5. An invalid invite token shows the invalid state (the only real-backend check of the byte-identical 404).

**`e2e-refresh`** (access TTL ~5 s):
6. **Multi-tab refresh, overlap forced:** two pages in one context; `page.route('**/api/v1/auth/refresh')`
   holds the first refresh response until the second tab's refresh request is observed, then releases it;
   both tabs remain signed in. **The plan runs this once with a no-op `LockProvider` and records it red.**
7. **Lost refresh response:** the first refresh's response is aborted after the server commits; the next
   request succeeds via the grace window.

Every E2E page runs `@axe-core/playwright`; violations fail the test (proven with one red fixture).
CI retries: 1; a test that passes only on retry is reported as flaky in the job summary. The plan also
includes a manual keyboard walkthrough and 320 px-width check of the four screens as a checked task.

## 6.5 CI (`.github/workflows/ci.yml`)

- **`frontend` job — blocking:** `pnpm install --frozen-lockfile` → `pnpm gen:api` + `git diff
  --exit-code` → `tsc --noEmit` → ESLint (boundaries, no-fetch, jsx-a11y, no-literal-string, no-danger)
  → Vitest → `vite build` → **budget check** → bundle visualizer output uploaded as an artifact.
- **Budget check:** walks `.vite/manifest.json` from each public route's entry (`/login`, `/signup`,
  `/invite/:token`) through every static import and modulepreload, sums gzipped JS, fails if any route
  exceeds **200 KB**, and **fails if it resolves zero files**. Proven red with an over-budget fixture.
- **`e2e` job — blocking:** Postgres service container (exact image tag), both backend processes via
  `bootRun`, `vite preview` serving the §4.9 CSP with `/api` proxied, both Playwright projects; trace
  uploaded with `if: failure()`.
- **"Blocking" means what it means for the backend today:** CI runs post-merge on `main` (and on PRs,
  which this repo does not yet use), so a red job is an alarm on `main`, not a pre-merge stop. This
  changes at branch protection (roadmap item 8). The `e2e` job also runs on the nightly `schedule`.
- **Every gate above gets a deliberate break → recorded red run → revert** in the plan (hand-edited
  `schema.d.ts`, an `<img>` without `alt`, a literal JSX string, a cross-feature import, a raw `fetch`,
  an eagerly imported large dependency).
- **New `FrontendWorkflowTest`** (backend test module, same `ci.workflow` property as the existing guard
  tests): both jobs exist; neither has `continue-on-error`; the gate steps carry no `if:` and no `|| true`;
  only the artifact-upload steps may carry `if: failure()`; the Postgres image has an exact tag.
- **oasdiff (F0-12):** stays non-blocking. The `ci.yml` comment and `OasdiffWorkflowTest`'s message are
  updated to name branch protection (roadmap item 8) as the flip trigger.
- `.github/dependabot.yml` gains an `npm` ecosystem entry for `/frontend`.

---

# Part 7 — Documentation owed in the same change

- **Engineering challenges:** (1) the refresh lost-ACK problem and the grace window (§3.3), including the
  corrected fork premise; (2) cross-tab principal change against a shared cookie jar (§4.4) — why the
  query cache must be keyed to the principal.
- **Annotations reference:** any new annotation (e.g. `@CookieValue`).
- **`docs/api/error-codes.md`** (new) — the `fieldCodes` vocabulary and the rule for adding codes.
- **ROADMAP / HANDOFF:** F0 status; item 4a (platform admin role) already added.
- **Design spec §5:** note F0-6 (English-only for now), F0-14 (deferred E2E path and Lighthouse), and that
  the refresh model now includes the grace window.

---

# Part 8 — Deployment notes recorded for item 5 / SP2

Not built in F0; recorded so they are not rediscovered:
- Hashed assets `Cache-Control: immutable`; `index.html` `no-cache`; Brotli.
- The §4.9 CSP, `Referrer-Policy`, and `frame-ancestors` are served by the hosting layer.
- `PUBLIC_BASE_URL=https://app.easycustomerrelationship.site`; `/invite/*` must route to the SPA, `/api/*`
  to the backend.

---

# Part 9 — Specialist review record

Dispatched in parallel on the first draft (`fca61d3`): architecture, performance, security, a11y-i18n,
testing. The named agents were not registered in the dispatching session, so each ran as a
general-purpose agent following its `.claude/agents/*.md` file verbatim. All five returned **"ready with
fixes"**. Findings were verified against the code before being applied.

**Corrections to the first draft's claims:** rotation does not fork (`@Version`; the defect is 409 vs 401);
F0-5's rationale was wrong (a plain 401 also logs every tab out on a lost response); `SupplyChainWorkflowTest`
does not cover new jobs; the 409 for a taken slug has no `fields`; validation failures are 400 as well as
422.

**Owner decisions taken on review findings:** 30 s grace window (F0-5); sign-out-and-accept on invite
(F0-10); field reason codes for F0's validators (F0-11); oasdiff stays non-blocking (F0-12).

**Applied without a product decision:** identity in session responses and one-request boot;
`establishSession`/`endSession` and the principal check; middleware exclusions, request cloning,
timeouts and the `unreachable` state; fail-safe logout; the `session` rate-limit policy; case-insensitive
login; query defaults; folder map and lint boundaries; enforced i18n; form accessibility and input
attributes; lost-response hints on signup and accept; the per-route budget definition; forced-overlap and
lost-response E2E; per-gate red runs; `FrontendWorkflowTest`; axe; two-backend E2E; CSP, referrer and
`no-danger`; signup switch ordering.

**Not adopted:** a non-blocking throttled LCP check on `/invite/:token` in F0 (performance Q1) — F0-14
stands; the per-route budget plus axe and E2E cover F0's risk, and F2 builds the throttled harness against
its first heavy screen.
