# How Big Companies Build Products, From the Ground Up

What actually happens between "someone has an idea" and "it's live for customers" at large
software companies: **the artifacts written, the meetings held, who holds the veto, and what
gates a project has to pass.** Compiled 2026-08-22.

## Scope and sourcing standard

This directory only covers companies where the process is documented by the company itself or in
an equally authoritative first-party account. Every claim below traces to one of:

- a **book written by the executives who ran the process** (Amazon),
- a **book the company published for free** (Basecamp),
- the **company's own engineering book / playbook** (Google SRE, Atlassian Team Playbook),
- a **detailed on-the-record interview with the executive who owns the process** (Shopify),
- a **talk by the internal coach who built the framework** (Spotify).

**Apple and Meta are deliberately excluded.** Both have famous processes, but the public record
for them is journalism, ex-employee recollection, and interview-prep content. Nothing about either
is documented at the level of specificity in the files below, so including them would mean mixing
verified mechanics with plausible folklore. If you want them later, they'd have to be written with
that caveat stated on every page.

| Company | Confidence | Why |
|---|---|---|
| Amazon | **High** | *Working Backwards* (Bryar & Carr — both ran the process; Bryar was Bezos's technical advisor) |
| Basecamp | **High** | *Shape Up* published free and in full by the company |
| Google | **High** | *Site Reliability Engineering* (O'Reilly, by Google) and Google re:Work, both first-party |
| Atlassian | **High** | The Team Playbook is a published Atlassian product |
| Shopify | **Good** | On-the-record interview with Glen Coates, VP Product (Core) |
| Spotify | **Moderate** | Henrik Kniberg's *Spotify Rhythm* talk — internal coach, but a 2016 snapshot, and Spotify has publicly distanced itself from its other famous model (squads/tribes). **Treat as "a real framework Spotify used," not "how Spotify works today."** |

## Index

| # | File | What it covers |
|---|------|----------------|
| 01 | [Amazon — Working Backwards](01-amazon-working-backwards.md) | PR/FAQ, six-page narratives, silent reading, single-threaded leadership, OP1/OP2, input metrics & the WBR |
| 02 | [Google — OKRs, design docs & launch review](02-google-okrs-design-docs-and-launch-review.md) | OKR mechanics and scoring, design-doc culture, Launch Coordination Engineering as gatekeeper, the launch checklist |
| 03 | [Basecamp — Shape Up](03-basecamp-shape-up.md) | Shaping, appetite, pitches, the betting table, 6-week cycles, the circuit breaker, no backlog |
| 04 | [Atlassian — DACI](04-atlassian-daci.md) | The decision framework itself: four roles, an 8-step 60-minute play, the artifacts |
| 05 | [Shopify — GSD](05-shopify-gsd.md) | Five gated phases, OK1/OK2 sign-off, cascading annual→6-month→6-week planning, no OKRs |
| 06 | [Spotify — DIBB & Rhythm](06-spotify-dibb-and-rhythm.md) | Data→Insight→Belief→Bet, the Bets Board, Now/Next/Later |
| 07 | [The meeting calendar](07-the-meeting-calendar.md) | **Every recurring meeting, its cadence, attendees, input artifact and output decision** |
| 08 | [The lifecycle: idea to GA](08-lifecycle-idea-to-ga.md) | The nine stages every one of these companies runs, and the gates between them |
| 09 | [Synthesis: what to copy](09-synthesis-what-to-copy.md) | The two real axes of variation, and what a small team should actually adopt |

### End-to-end rollout walkthroughs

One product, followed from idea to post-launch analysis, mapped onto the
[nine-stage lifecycle](08-lifecycle-idea-to-ga.md). Each marks **[INFERRED]** and **[ASSUMPTION]**
explicitly and ends with what the record does not say.

| Walkthrough | Company size | What it shows |
|---|---|---|
| [Amazon Prime, 2004–2007](walkthrough-amazon-prime.md) | **Big company** | A founder-driven crash project on an earnings-call deadline; a price set with no model over finance's objections; and a post-rollout analysis that took **two years** to vindicate it. Includes a correction: this is *not* a Working Backwards case, despite being cited as one |
| [Superhuman, 2017–2019](walkthrough-superhuman.md) | **Small startup** | A deliberately rate-limited rollout (waitlist + 1:1 onboarding) and a PMF engine tracked quarterly from 22% → 58%, with the roadmap governed by a written 50/50 rule |
| [Shopify Magic & Sidekick, 2022–2023](walkthrough-shopify-ai-products.md) | **Big company** | The GSD five phases executing on a real product, including the required **Results** phase and a deliberate refusal to set adoption targets |

**Prime and Superhuman are near-opposites and both worked** — one irreversible bet judged over two
years, versus many small reversible bets judged every quarter. The deciding variable is whether your
thesis can be measured on a timescale shorter than your runway.

## The one-paragraph version

Underneath the branded frameworks, all five companies do the same five things: **force the idea
into a written artifact before anyone builds; name exactly one person who decides; hold a meeting
whose purpose is a decision, not a status update; put an explicit gate between "designed" and
"shipped to everyone"; and review what actually happened after launch.** They differ mainly in
*what the artifact is* (a fake press release, a design doc, a pitch, a proposal in a tool) and
*who holds the veto* (an executive, a specialist engineering team, a betting table, a named
Approver). Everything else is local colour.
