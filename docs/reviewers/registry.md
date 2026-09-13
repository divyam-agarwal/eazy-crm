# Specialist Reviewer Registry

Specialist reviewers are read-only subagents in `.claude/agents/`, each covering one lens. This file
is imported into `CLAUDE.md`, so every agent working in this repo sees it. `scripts/check-reviewer-registry.sh`
fails CI if an entry here and its agent file disagree. To add a reviewer, see `docs/reviewers/README.md`.

## When to use them

Whenever you **request a review** of a spec, an implementation plan, or code, scan the list below and
pick the specialists whose "Use when" matches what the artifact actually touches.

- **Pick only matching lenses.** Zero is a valid answer; do not dispatch a lens for completeness.
- **Specialists add to the general review, they do not replace it.** Plan-alignment and general code
  quality still get their normal reviewer.
- **Dispatch the chosen specialists in parallel**, passing the artifact path or git range.
- **Verify before acting.** Treat each finding as a claim to check against the code and specs
  (`superpowers:receiving-code-review`), not an instruction.
- **If you cannot dispatch agents** (no agent-dispatch tool available to you, or your instructions say
  not to — reviewers themselves never do), name the specialists and the reason in your report instead;
  whoever dispatched you runs them.

## Registry

<!-- One line per reviewer: - `name` — sentence. The sentence must equal the agent's `description`. -->

### Frontend

- `frontend-review-architecture` — Use when a frontend spec, plan or diff decides feature boundaries, where state lives (TanStack Query, URL, forms, Zustand), effects, the generated API client, money handling, or conflict and idempotency behaviour.
- `frontend-review-performance` — Use when a frontend spec, plan or diff affects bundle size, code splitting, dependencies, data-fetching patterns, rendering of large lists or forms, fonts, or behaviour on low-end Android over flaky 4G.
- `frontend-review-security` — Use when a frontend spec, plan or diff touches browser auth (tokens, refresh, cookies, CSRF, logout), token-bearing URLs, XSS and CSP, or frontend dependencies and build-time secrets.
- `frontend-review-a11y-i18n` — Use when a frontend spec, plan or diff adds or changes screens, forms, keyboard and focus behaviour, user-facing text, Hindi translations, or Indian number, currency and date formatting.
- `frontend-review-testing` — Use when a frontend spec, plan or diff defines or changes tests, MSW mocks, Playwright end-to-end paths, frontend CI gates, or the test-first ordering of implementation tasks.
