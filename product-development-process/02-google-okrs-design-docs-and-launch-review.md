# 02 — Google: OKRs, design docs & launch review

**Source confidence: high** for OKRs and launch review — both documented first-party in
**Google re:Work** and the **Google SRE book** (O'Reilly, written by Google SREs).
Design-doc culture is described from widely-corroborated ex-Googler accounts and is marked as such.

Google's process has an unusual shape: **goal-setting is deliberately loose and bottom-up, while
the gate immediately before launch is unusually strict and owned by a specialist engineering team
with veto power.** Freedom to decide what to build; a hard gate on shipping it.

---

## 1. OKRs — Objectives and Key Results

### The mechanics

- **Cadence:** annual **and** quarterly OKRs, with **company-wide meetings each quarter to share
  and grade them**. A typical rhythm: brainstorm in November, communicate in December, draft in
  January, monitor from February.
- **Who sets them:** hybrid. Company-wide objectives are set first; teams then set their own goals
  in service of them. Team leaders meet to agree priorities within that context.
- **Scoring:** a **0.0–1.0** scale, where 1.0 means fully achieved. Each key result is graded, then
  the objective gets a rough average. Some are binary (launched / not launched); others allow
  partial credit (0.5 for half).
- **The sweet spot is 0.6–0.7.** Consistently scoring higher means the goals weren't ambitious
  enough; consistently lower may mean the team is over-reaching. **Full attainment is
  "extraordinary," not the expectation.** These are stretch goals by construction.
- **Cascading is explicitly rejected:** *"not every organizational OKR needs to be reflected in every
  team OKR."* A team's goals should connect to at least one organisational objective — that's all.

### Why the 0.7 target is the whole design

If missing your goals is punished, everyone sets goals they know they'll hit, and the goal-setting
system becomes a sandbagging exercise that measures nothing. By declaring **0.7 the target**, Google
makes the aggressive goal the safe one. This only works if OKR scores are **decoupled from
performance reviews and compensation** — which is the part most companies copying OKRs skip, and
why their version degenerates into quarterly status reporting.

---

## 2. Design docs

*(Widely corroborated ex-Googler accounts rather than a formal first-party publication — flagged
accordingly, though the practice itself is not in dispute.)*

Before non-trivial engineering work starts, an engineer writes a **design doc**: context and scope,
goals **and explicit non-goals**, the proposed design, **alternatives considered and why they were
rejected**, and cross-cutting concerns — security, privacy, data handling, scalability, cost.

Key properties:
- **It circulates for comment** among peers and relevant experts, asynchronously, in the document.
  Review is comments-in-the-doc, not a meeting.
- **Alternatives considered** is the section that does the work. It converts "here's my plan" into
  "here's the decision and the reasoning," which is what a future reader actually needs.
- It's a **decision record**, not a specification. It doesn't need to be maintained; it needs to
  explain why the system looks the way it does.
- Docs are broadly readable across the company, which is how engineering context propagates without
  meetings.

---

## 3. Launch Coordination Engineering — the gate before shipping

This is the most distinctive part of Google's process, and it's documented in detail in the SRE book.

Google created a dedicated team of **Launch Coordination Engineers (LCEs)** — SRE specialists whose
job is consulting on the technical aspects of launches. Their responsibilities:

- **audit** products and services against Google's reliability standards,
- **act as liaison** between the many teams involved in a launch,
- **drive the technical aspects** of the launch,
- **act as gatekeepers, signing off on launches determined to be "safe,"**
- **educate** developers on best practices.

That fourth bullet is the important one: **a launch does not ship until a specialist engineering
team outside the product team says it may.** The team that wants to ship is not the team that
decides it's safe to ship.

### The launch checklist

LCEs maintain a curated **launch checklist** — accumulated questions plus known-good recipes for the
problems they raise. Its categories:

| Category | The kind of question asked |
|---|---|
| **Architecture & dependencies** | What's the request flow? What are the latency requirements? |
| **Integration** | DNS, load balancing, monitoring — is it wired into the standard infrastructure? |
| **Capacity planning** | Projected traffic, provisioned resources, headroom |
| **Failure modes** | Single points of failure; how does it degrade rather than fail? |
| **Client behaviour** | Auto-sync and retry logic — will clients stampede the backend? |
| **Processes & automation** | What is still manual? Is it documented? |
| **External dependencies** | Third-party services and the contingency if they're unavailable |
| **Rollout planning** | Staged sequence, and contingency at each stage |

**How the checklist came to exist** is instructive: it was **initially driven by launch disasters**,
then continuously curated — including **removing items that had become obsolete** — and adapted when
a genuinely new product category needed fresh expertise. It is a living artifact built from
incidents, not a compliance document written once.

### The techniques it enforces

- **Staged rollouts** with canary deployments, to catch problems before full release.
- **Feature flags** allowing gradual 0%→100% rollout and parallel testing.
- **Load testing** as mandatory capacity validation before launch.
- **Client behaviour controls**: exponential backoff, **jitter**, and server-side configuration so
  client retry behaviour can be changed without shipping a new client.
- **Kill switches** — the ability to turn the feature off immediately if it goes wrong.

*(Every one of these appears on the other side of the ledger in `../aws-production-issues/` —
they're on the checklist because their absence caused the outages that created the checklist.)*

### Production Readiness Review

Related, and broader: the **PRR** is SRE's engagement model for taking on operational responsibility
for a service. SRE reviews the service against production standards; the development team fixes the
gaps; only then does SRE take on-call. It creates a clear incentive — **the team that will carry the
pager sets the bar for what they'll carry.**

---

## What to steal if you're not Google

1. **Set the OKR target at ~70% and decouple it from performance reviews.** Without both halves,
   you have quarterly status reporting with extra ceremony.
2. **Write design docs with a mandatory "alternatives considered" and "non-goals" section.**
   Non-goals prevent scope creep better than any process, because they're written down before anyone
   is emotionally invested.
3. **Build a launch checklist from your own incidents.** Every postmortem contributes one line.
   Prune obsolete items. Within a year it becomes the most valuable document you own.
4. **Put a gate between "we finished it" and "everyone gets it."** Even in a five-person company:
   one person who isn't the author signs off against the checklist.
5. **Make kill switches and staged rollout non-negotiable**, not nice-to-haves. The ability to turn
   a feature off in seconds is worth more than any amount of pre-launch testing.
6. **Let the on-call team set the readiness bar.** Whoever gets woken up should decide what they're
   willing to be woken up by.

## Sources

- [Google SRE Book — Reliable Product Launches at Scale](https://sre.google/sre-book/reliable-product-launches/) *(primary)*
- [Google SRE Book — Evolving SRE Engagement Model (Production Readiness Reviews)](https://sre.google/sre-book/evolving-sre-engagement-model/) *(primary)*
- [Google re:Work — Set goals with OKRs](https://rework.withgoogle.com/en/guides/set-goals-with-okrs) *(primary)*
- [Malte Ubl — Design Docs at Google](https://www.industrialempathy.com/posts/design-docs-at-google/) *(ex-Google staff engineer; the standard reference for the practice)*
