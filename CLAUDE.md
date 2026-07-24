# EasyCRM

Multi-tenant SaaS CRM for Indian tier-2/3 distributors, traders, and small
manufacturers. React + TypeScript frontend, Spring Boot + PostgreSQL backend.
Scope stops at the **Order** — no invoicing/stock/ledger (that's Tally's job).

## Key docs

- **Design spec:** `docs/superpowers/specs/2026-07-22-easycrm-design.md`
- **Engineering challenges log:** `docs/superpowers/engineering-challenges.md`

## Working agreements

### ALWAYS log engineering challenges

Whenever we solve a **non-obvious engineering problem** — during design OR
implementation — append an entry to `docs/superpowers/engineering-challenges.md`
using the template at the bottom of that file (Problem → why it's hard → Solution
→ Lesson). Do this **as part of the same change**, not "later."

A problem qualifies if any of these are true:
- The naive approach is subtly wrong (e.g. money as `double`, round-then-sum).
- It required defence-in-depth or an unusual trade-off.
- It touches correctness under concurrency, crashes, or multi-tenancy.
- You'd want to explain it in an interview.

Do NOT log routine CRUD, config, or boilerplate. Quality over volume.

At the **end of any implementation session**, do a quick pass: "did we solve
anything challenge-worthy that isn't yet logged?" If yes, log it before wrapping up.

### Money is never a `double`

`BigDecimal` in Java, `NUMERIC` in Postgres, JSON **string** on the wire. Round
per-line then sum (matches Tally), `RoundingMode.HALF_UP`. Server recomputes and
is authoritative; the client preview is never trusted. See challenge log #2.

### Tenant isolation is structural, not procedural

Never hand-write `WHERE tenant_id = ?`. Rely on Hibernate `@TenantId` + Postgres
RLS; tenant comes from the JWT only. New entities must declare `@TenantId` or the
ArchUnit build fails. See challenge log #1.
