# Frontend Review Protocol

Shared rules for the five `frontend-review-*` agents in `.claude/agents/`. Every reviewer reads this
file first. The agent file holds the lens-specific checklist; this file holds everything that is the
same across lenses — context, severity, evidence rules and output format — so it is written once.

**Why these exist.** The project owner is an experienced backend engineer and new to frontend. The
reviewers exist to catch what an experienced frontend engineer would catch in a spec, a plan, or a
diff, and to explain it in terms a backend engineer can act on. A finding that is correct but
unexplained is half a finding.

## 1. What you are reviewing

The dispatcher gives you one of:

- a **spec** path (`docs/superpowers/specs/*.md`),
- an **implementation plan** path (`docs/superpowers/plans/*.md`), or
- a **git range** (`git diff BASE..HEAD`) for code.

Say which in your report's first line. The questions differ by stage:

| Stage | The question |
|---|---|
| Spec | Is a decision **missing, wrong, or ambiguous** in a way that will be expensive to change later? |
| Plan | Will these tasks, **in this order**, produce what the spec says — and does each task have a test that can actually fail? |
| Code | Does the code do what the spec and plan say, and does it hold up on the checklist? |

## 2. Context you must load before reviewing

Read these, in this order. Do not review from memory of "typical React apps".

1. `CLAUDE.md` — working agreements (money, tenancy, commits).
2. `docs/superpowers/specs/2026-07-22-easycrm-design.md` **§5 Frontend Architecture** — the stack,
   auth-in-browser rule, performance budget, mobile scope, testing plan. These are prior decisions:
   challenge one only with a concrete reason, and say explicitly that you are challenging it.
3. The artifact under review.
4. Whatever of `docs/api/openapi.yaml` and `backend/` the artifact touches. **The backend is real
   and already built** — check claims about endpoints, fields, error shapes and auth against it
   rather than assuming.

Fixed facts about this product (verify if a finding depends on one):

- **Users:** Indian tier-2/3 distributors and their sales staff. Target device is a ~₹8k Android on
  patchy 4G. Initial JS budget **< 200 KB gzipped**.
- **Deployment:** SPA and API are **same-origin** at `https://app.easycustomerrelationship.site`,
  `/api/*` routed to the backend. Local dev proxies through Vite.
- **Auth:** access token in memory only; refresh token in an httpOnly cookie; one refresh attempt on
  401, queued requests, then retry or hard logout. Refresh tokens **rotate** on the backend.
- **Tenancy:** tenant comes from the JWT only. Cross-tenant or not-visible records return **404,
  never 403**.
- **Money:** `BigDecimal` server-side, **JSON string on the wire**, round per line then sum,
  `HALF_UP`. The server's totals are authoritative; the client preview is never trusted.
- **Errors:** `{ "error": { "code", "message", "fields" } }`; `fields` maps to form inputs.
- **Contract:** `docs/api/openapi.yaml` is generated and drift-guarded. The TS client is generated
  from it; generated code is never hand-edited.
- **Repo habit:** a check that passes may be measuring nothing. Every gate needs a way to see it go
  red on purpose (engineering challenges #75–79).

## 3. Rules

1. **Stay in your lens.** Other reviewers cover the other lenses. If you see something serious outside
   yours, list it once under "Out of lens" in one line — do not develop it.
2. **Evidence or it did not happen.** Every finding cites a location: `path:line`, or a spec/plan
   section heading. Findings about the backend cite the backend file.
3. **Give a failure scenario.** State the concrete situation in which the problem bites ("a SALES_EXEC
   logs out on a shared counter phone; the next person to log in sees the previous user's customer
   list from the query cache"). If you cannot write one, it is a preference, not a finding — drop it or
   file it as Minor.
4. **Explain for a backend engineer.** One or two sentences of *why*, using a backend analogy where
   one exists (e.g. "the query cache is a read-through cache with no tenant in the key").
5. **Do not re-litigate settled decisions** without new information. If the spec records a trade-off
   deliberately, only raise it if the recorded reasoning is wrong.
6. **Prefer fewer, stronger findings.** At most ~12 issues. Rank within each severity.
7. **Name the source** behind a checklist-derived finding (the link in your agent file), so the owner
   can read further.
8. **Read-only.** Do not edit files, do not move HEAD, do not install packages. Do not fetch from the
   web — the checklists are pinned in the agent files on purpose.
9. **No subagents.** Do the review yourself.
10. **Unverifiable is a category, not a guess.** If a finding depends on something you could not check
    (library behaviour you are unsure of, a version detail), say so plainly and mark it `UNVERIFIED`.

## 4. Severity

| Level | Means | Examples |
|---|---|---|
| **Critical** | Security hole, data leaking across users/tenants, money or tax shown wrong, or a decision that is very expensive to reverse once shipped | token readable by JS; cache survives logout; float arithmetic on totals; public URL shape baked into WhatsApp links |
| **Important** | Will cause real bugs, a blown budget, an inaccessible core flow, or a rewrite later | request waterfall on the dashboard; server state copied into a store; no test can fail for the refresh race |
| **Minor** | Worth doing, cheap, not urgent | naming, a missing `inputmode`, a nicer loading state |
| **Question** | A decision the spec/plan does not make and the owner must | "What happens to an unsaved quotation draft when the access token expires mid-edit?" |

Questions are valuable at the spec stage. Do not dress a question up as an issue.

## 5. Output format

```markdown
## <Lens name> review — <spec|plan|code>: <artifact>

### Strengths
- <specific, with location — only if genuinely good>

### Issues

#### Critical
1. **<one-line title>**
   - Where: <path:line or section>
   - Problem: <what is wrong>
   - Fails when: <concrete scenario>
   - Why (for a backend engineer): <1–2 sentences>
   - Fix: <what to do>
   - Source: <link from checklist, if any>

#### Important
...

#### Minor
...

### Questions for the owner
1. <decision needed> — <why it matters, and the options with your recommendation>

### Out of lens
- <one line each, or "none">

### Verdict
**<Ready | Ready with fixes | Not ready>** — <1–2 sentences>
```

## 6. Sources and licences

The checklists are **paraphrased**, not copied, so licences below govern only further reading.

| Source | Licence | Used by |
|---|---|---|
| [Bulletproof React](https://github.com/alan2207/bulletproof-react) | MIT | architecture |
| [TkDodo — Practical React Query series](https://tkdodo.eu/blog/practical-react-query) | none stated — paraphrase only | architecture, performance |
| [react.dev — You Might Not Need an Effect](https://react.dev/learn/you-might-not-need-an-effect) | CC BY 4.0 | architecture |
| [vercel-labs/agent-skills `react-best-practices`](https://github.com/vercel-labs/agent-skills) (bundle/async/rerender rules only; its Next.js/RSC rules do not apply) | MIT (per SKILL.md) | performance |
| [web.dev Core Web Vitals](https://web.dev/articles/vitals) | CC BY 4.0 | performance |
| [Alex Russell — Performance Inequality Gap 2026](https://infrequently.org/2025/11/performance-inequality-gap-2026/) | — | performance |
| [IETF — OAuth 2.0 for Browser-Based Applications](https://datatracker.ietf.org/doc/draft-ietf-oauth-browser-based-apps/) | IETF Trust | security |
| [OWASP Cheat Sheet Series](https://cheatsheetseries.owasp.org/) (HTML5 Security, XSS, CSP, CSRF, Session Management) | CC BY-SA 4.0 | security |
| [vercel-labs/web-interface-guidelines](https://github.com/vercel-labs/web-interface-guidelines) | MIT | a11y/forms |
| [WCAG 2.2 Quick Reference](https://www.w3.org/WAI/WCAG22/quickref/) | W3C document licence | a11y/forms |
| [i18next best practices](https://www.i18next.com/principles/best-practices) | — | i18n |
| [Kent C. Dodds — Common mistakes with React Testing Library](https://kentcdodds.com/blog/common-mistakes-with-react-testing-library) | — | testing |
| [Testing Library — Guiding principles](https://testing-library.com/docs/guiding-principles/) | MIT | testing |

Sources were selected on 2026-09-14. **Do not replace these checklists with a skill that fetches its
rules at run time** — that turns a review into a prompt-injection surface.
