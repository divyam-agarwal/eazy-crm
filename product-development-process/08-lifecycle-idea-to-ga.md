# 08 — The lifecycle: idea to GA

The nine stages every company in this directory runs, what gates them, and how the same stage looks
at each company. Company-specific claims are sourced in the per-company files; the spine itself is
the common structure they share.

---

## The spine

```
1 Opportunity    →  2 Definition   →  3 Approval    →  4 Technical design
      ↓                                   ▲                    ↓
9 Learn & iterate ← 8 Launch ← 7 De-risk ← 6 Build ← 5 Planning & funding
```

The **only two hard gates** that every one of these companies has:
**(3) approval to build**, and **(8) approval to ship to everyone.**
Everything else varies.

---

## Stage 1 — Opportunity

**Question:** is there a problem worth solving?

**Inputs:** customer research, support tickets, sales objections, usage data, competitive pressure,
a strategic thesis, or an engineer's hunch.

**How the companies differ:**
- **Spotify** formalises exactly this step: **Data → Insight → Belief**, three separately-challengeable
  statements before anyone proposes a bet.
- **Amazon** starts from the customer experience and works backwards — the opportunity is expressed
  as the finished experience, not as a problem statement.
- **Shopify** derives it from an annual theme written in the merchant's voice.

**Output:** a stated problem and a reason to believe it matters.

---

## Stage 2 — Definition (the written artifact)

**Question:** what exactly are we proposing, and what are we *not* proposing?

This is where the companies diverge most visibly — and yet **all of them require a written document
before funding.** Nobody builds off a conversation.

| Company | Artifact | Distinctive feature |
|---|---|---|
| Amazon | **PR/FAQ** | Written as the finished press release; internal FAQ carries economics, assumptions, risks |
| Basecamp | **Pitch** | **Appetite** (time we're willing to spend), **rabbit holes**, **no-gos** |
| Shopify | **GSD Proposal** | Enters a tool with defined review phases |
| Google | **Design doc** | **Non-goals** and **alternatives considered** are mandatory |
| Spotify | **Bet** | Explicitly ties back to the Data/Insight/Belief chain |

**Universal:** every one of these formats requires stating what's **out of scope** — Amazon's
internal FAQ risks, Basecamp's no-gos, Google's non-goals. That's not a coincidence. **Written
exclusions are the single most effective scope-control mechanism any of them has**, because they're
recorded before anyone is invested.

**Output:** a document someone can approve or reject.

---

## Stage 3 — Approval — **HARD GATE**

**Question:** are we doing this, and who said so?

| Company | Who decides | Format |
|---|---|---|
| Amazon | Escalating review, ending with executives | **Narrative meeting**: 20 min silent reading, ~40 min discussion |
| Basecamp | **The betting table** — CEO, CTO, senior programmer, product strategist | 1–2 hours, binding, no further approval |
| Shopify | **OK1** (directors: product, UX, eng, ±data), then **OK2** (senior leadership) | **Async via comments**; meetings only if controversial |
| Atlassian | **One named Approver** (DACI) | 60-minute play; decision + rationale written down |
| Google | Team-level, within OKR context | Bottom-up; goals connect to org objectives without cascading |

**The universal pattern: exactly one person or one small senior group decides, and everyone knows
who before the conversation starts.** Amazon's single-threaded leader, Atlassian's Approver,
Basecamp's four-person table — three companies, same conclusion.

**Output:** funded / rejected-with-a-reason / revise. Amazon's rejection reasons are specific
(differentiation, TAM, cost, technical barrier, capacity) rather than a bare no — which tells the
author what would change the answer.

---

## Stage 4 — Technical design

**Question:** how are we building it, and what could go wrong?

- **Google's design doc** is the canonical form: context, goals **and non-goals**, the design,
  **alternatives considered and why rejected**, plus security/privacy/scalability/cost.
- Reviewed **asynchronously in the document** by peers and domain experts.
- It's a **decision record**, not a maintained spec — its job is to explain, later, why the system
  looks like this.

**Output:** an agreed approach, with risks named. This is the cheapest stage at which to change your
mind, and the last one where changing it is cheap.

---

## Stage 5 — Planning & funding

**Question:** who does it, by when, with what?

| Company | Mechanism |
|---|---|
| Amazon | **OP1** bottom-up operating plan, reconciled with top-down goals; **OP2** revises post-holiday |
| Basecamp | **Six-week bet** — a fixed time box with a **circuit breaker** (no extension by default) |
| Shopify | Annual themes → six-month roadmaps aligned to **Editions** → six-week detail |
| Google | Quarterly **OKRs**, graded 0.0–1.0, target ~0.7 |

**The fundamental split** — and the most consequential choice on this page:

- **Fix scope, vary time** (traditional): "this feature, however long it takes." Produces slipping
  deadlines and a growing backlog.
- **Fix time, vary scope** (Basecamp, and Shopify's six-week rhythm): "six weeks, whatever fits."
  Forces scope negotiation to happen continuously, by the people doing the work.

The second only works if the deadline is **real** — which is precisely what Basecamp's circuit
breaker enforces. A deadline with routine extensions is fixed-scope wearing a costume.

---

## Stage 6 — Build

Cross-functional team; short internal feedback loops; **build behind a feature flag from day one**.

Two practices worth calling out:
- **Trunk-based development with flags** decouples *deploying* code from *releasing* a feature —
  which is what makes staged rollout and instant kill switches possible at stage 7–8.
- **Shopify's Prototype phase requires a PM video walkthrough**, catching gaps that a written spec
  hides, asynchronously.

---

## Stage 7 — De-risking

**Question:** does it work, for real users, at real load?

The escalating ladder, near-universal at scale:

1. **Dogfooding** — the company uses it internally.
2. **Internal alpha** — all employees.
3. **Trusted testers / closed beta** — a small set of friendly external customers.
4. **Open beta / early access** — self-selected users.
5. **Percentage rollout** — 1% → 5% → 25% → 50% → 100%, via feature flags.
6. **A/B experiment** — for anything where the effect size is genuinely uncertain.

Google's launch checklist explicitly enforces the tooling this requires: **feature flags allowing
gradual 0–100% rollout, staged rollouts with canaries, load testing, and kill switches.**

---

## Stage 8 — Launch — **HARD GATE**

**Question:** is it safe to give this to everyone?

**Google is the clearest published model:** a **Launch Coordination Engineer** — a specialist SRE
*outside the product team* — audits against the **launch checklist** and **acts as gatekeeper,
signing off only on launches determined to be safe.**

The checklist covers: architecture & dependencies, integration (DNS, load balancing, monitoring),
capacity planning, failure modes and graceful degradation, client retry behaviour, manual processes,
external dependencies, and rollout planning. It was **built from launch disasters** and is
**continuously pruned** of obsolete items.

At most companies this stage also collects the compliance gates — **security, privacy/legal,
accessibility** — plus operational readiness: monitoring, alerting, runbooks, and a named on-call
owner.

**The principle worth extracting: the team that wants to ship is not the team that decides it's
safe to ship.** Same structure as Amazon's Bar Raiser in hiring — install someone whose incentives
are deliberately different, and give them a veto.

**Output:** ship / ship to a subset / blocked with a specific list.

---

## Stage 9 — Learn & iterate

**Question:** did it do what we said it would?

- **Shopify makes "Results" a required phase of GSD.** The project is not closed until the
  post-launch analysis exists. Institutionalising this is rare and valuable.
- **Google grades OKRs quarterly, company-wide**, with 0.7 as the target.
- **Amazon reviews input metrics weekly** in the WBR, finance-audited.
- **Spotify** closes the loop back to Data — the bet either validated the belief or didn't, and the
  next DIBB starts from what actually happened.

**The failure mode:** almost every organisation ships and moves on. The document that justified the
project is never checked against reality, so the team never learns whether its judgement is any
good. **Stage 9 is the only stage that improves stages 1–3**, and it's the one most often skipped.

---

## The two hard gates, restated

| Gate | Question | Who should hold it | Consequence of skipping it |
|---|---|---|---|
| **Stage 3 — Approval** | Should this exist? | One named decider or a small senior group | Work with no owner, no scope boundary, and no way to stop it |
| **Stage 8 — Launch** | Is it safe for everyone? | **Someone other than the author** | Your users are the canary |

Everything else on this page is adjustable. These two are what every one of these companies has,
in some form, regardless of size or philosophy.
