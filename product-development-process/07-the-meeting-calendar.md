# 07 — The meeting calendar

**What meetings actually happen, who is in them, what document goes in, and what decision comes out.**

This file is split deliberately. **Part A** is meetings that are documented, by name, at a specific
company — every row traces to a source in this directory. **Part B** is the generic set found across
large software organisations, labelled as such because it's industry-standard practice rather than
one company's named ritual.

---

## Part A — Named, sourced meetings

| Meeting | Company | Cadence | Who | Input artifact | Output |
|---|---|---|---|---|---|
| **Narrative / PR-FAQ review** | Amazon | Per initiative, repeatedly | Author + reviewers; escalating to execs | The **PR/FAQ** or six-pager, handed out **at the meeting** | Approve (resources allocated) / reject with a named reason / revise |
| **Weekly Business Review (WBR)** | Amazon | Weekly | Business + finance | Standard metrics deck; **input and output metrics**, finance-audited | Corrective action on input metrics |
| **OP1 / OP2 planning** | Amazon | Annual + post-holiday revision | Each group, then leadership | Group's **bottom-up operating plan** | Reconciled plan, resources, targets |
| **Quarterly OKR grading** | Google | Quarterly, **company-wide** | Everyone | Scored OKRs (0.0–1.0) | Shared scores; recalibrated goals |
| **Launch readiness review** | Google | Per launch | **LCE** + product/eng team | The **launch checklist** + audit findings | **LCE gatekeeper sign-off** — or launch blocked |
| **Production Readiness Review** | Google | Per service | SRE + dev team | Service against production standards | SRE takes on-call, or gaps must be fixed first |
| **The betting table** | Basecamp | Every 8 weeks, in cool-down | **CEO, CTO, senior programmer, product strategist** — 4 people, **1–2 hours** | **Pitches** (read beforehand) | Which bets get the next 6-week cycle. **Binding — no further approval** |
| **DACI decision meeting** | Atlassian | Per decision | Driver, Approver, Contributors | DACI template + options analysis | **One decision by one Approver**, written down with rationale |
| **OK1 review** | Shopify | Per project, per phase | **Directors** — product, UX, engineering, (data) | GSD phase submission | Advance to next phase, or not |
| **OK2 review** | Shopify | Per project, at senior gate | **Senior leadership** — product, UX, engineering, data | GSD phase submission | Advance to next phase, or not |
| **Office hours** | Shopify | Ongoing | Reviewers | Anything needing fast input | **Expedited 30-minute feedback slot** |

### Three things to notice

**1. Most review is asynchronous.** Shopify's OK1/OK2 gates are conducted **via comments**, with
synchronous meetings only for **controversial or high-stakes projects**. Google's design docs are
reviewed in-document. This is what makes multi-gate approval affordable — the meeting is the
exception, not the mechanism.

**2. The decision meetings are small and final.** Basecamp's betting table is four people for
one to two hours, and *there is no approval step after it* — that's why it's senior. Atlassian
names exactly one Approver. Amazon names one single-threaded leader. The pattern is universal:
**a small group with real authority beats a large group with none.**

**3. Preparation is structural, not aspirational.** Basecamp's participants read pitches beforehand.
Amazon solves the same problem the opposite way — assume nobody read it, hand it out at the meeting,
and **spend the first 20 minutes reading in silence.** Both are designs for the same failure; the
Amazon version is more robust because it doesn't depend on anyone's discipline.

---

## Part B — The generic large-company meeting set

Standard practice across large software organisations. Use it as a menu, not a prescription.

### Strategy & planning

| Meeting | Cadence | Purpose |
|---|---|---|
| Annual strategy / goal setting | Yearly | Themes, objectives, budget, headcount |
| Quarterly planning | Quarterly | Commit the next quarter's work; reconcile top-down and bottom-up |
| Roadmap review | Monthly/quarterly | Re-rank; kill things; handle new information |
| Business review | Weekly/monthly | Metrics vs plan; corrective action |

### Discovery & definition

| Meeting | Cadence | Purpose |
|---|---|---|
| Problem / opportunity review | Per initiative | Is this problem worth solving at all? |
| User research readout | Per study | Share findings; decide what they change |
| Spec / PRD review | Per project | Agree what "done" means before building |
| Design review / crit | Weekly or per milestone | Critique design against the problem |
| Technical design / RFC review | Per project | Agree the architecture; surface risk early |
| Architecture review board | Per significant change | Approve system-level decisions |

### Delivery

| Meeting | Cadence | Purpose |
|---|---|---|
| Standup | Daily | Unblock — **not** status reporting |
| Sprint planning / cycle kickoff | Per cycle | Commit to the cycle's work |
| Backlog refinement | Weekly | Clarify upcoming work (Basecamp deliberately rejects this) |
| Demo / review | Per cycle | Show working software to stakeholders |
| Retrospective | Per cycle | Improve the process, with owned actions |
| Bug triage / bug bash | Weekly / pre-launch | Prioritise defects; hunt them deliberately before launch |

### Pre-launch gates

| Meeting | Cadence | Purpose |
|---|---|---|
| Security review | Per launch | Threat model; findings must be resolved |
| Privacy / legal / compliance review | Per launch | Data handling, retention, consent, regulatory exposure |
| Accessibility review | Per launch | Conformance to standards |
| Launch readiness / go-no-go | Per launch | **The gate.** Checklist, rollout plan, kill switch, on-call, rollback |
| Operational readiness | Per service | Monitoring, alerting, runbooks, on-call ownership |

### Post-launch

| Meeting | Cadence | Purpose |
|---|---|---|
| Launch retrospective | Post-launch | Did it work? What did we learn? |
| Metrics / experiment readout | 2–6 weeks post-launch | Did it move the number? Keep, iterate, or roll back |
| Incident review / postmortem | Per incident | Blameless root cause + owned action items |
| Operational review | Weekly | Incidents, alert noise, toil, reliability trend |

---

## The honest version: which of these actually matter

If you stripped a large company's calendar to the meetings that change outcomes, you'd keep six:

1. **A decision meeting with one named decider** and a written artifact read in advance (or in the room).
2. **A technical design review** before anyone builds — because rework is the most expensive thing you do.
3. **A go/no-go gate** with a checklist, owned by someone who is not the author.
4. **A post-launch metrics readout** with a real option to roll back.
5. **A blameless incident review** with owned actions.
6. **A regular business/metrics review** on leading indicators, not lagging ones.

Everything else is coordination overhead that scales with headcount. Note that **five of the six
produce a written artifact**, and the sixth (incident review) produces the most valuable document
the company owns.

**Two meta-rules worth adopting from the sources:**

- **Shopify cancelled all recurring meetings and made them re-earn their slot**, and now displays the
  real-time cost of attendance on invitations.
- **Every meeting in Part A produces a decision, not a status update.** If a meeting's output is
  "everyone is now informed," it should have been a document.

## Sources

See the per-company files: [Amazon](01-amazon-working-backwards.md),
[Google](02-google-okrs-design-docs-and-launch-review.md),
[Basecamp](03-basecamp-shape-up.md), [Atlassian](04-atlassian-daci.md),
[Shopify](05-shopify-gsd.md), [Spotify](06-spotify-dibb-and-rhythm.md).
Part B is generic industry practice and is not attributed to any single company.
