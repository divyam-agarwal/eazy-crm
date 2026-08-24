# 05 — Shopify: GSD ("Get Shit Done")

**Source confidence: good.** Sourced from an on-the-record interview with **Glen Coates, VP Product
for Shopify Core**, who owns this process, published in Lenny Rachitsky's newsletter. Not a
first-party manual, but a detailed account from the person accountable for it.

Shopify is the most useful entry here for a normal product company, because it's the only one with
an **explicit, tool-enforced, multi-gate approval workflow** — and because it's unusual in
deliberately rejecting OKRs.

---

## Planning: annual → six months → six weeks

A cascading cadence, each level less abstract than the last:

| Horizon | Who | Output |
|---|---|---|
| **Annual** | CEO (Tobi) | **Thematic priorities framed from the merchant's point of view** — e.g. *"Shopify keeps me on the cutting edge."* Themes, not metrics |
| **Six months** | Teams | Rough roadmaps, aligned to **Shopify Editions** — the twice-yearly public release event |
| **Six weeks** | Teams | Detailed, sprint-level planning within the half |

Two things worth noting. First, **the annual layer is a theme written in the customer's voice**, not
a target — the same instinct as Amazon's press release. Second, the six-month layer is anchored to a
**public shipping event**, which is a forcing function no internal deadline can replicate.

Coates is explicit about holding it loosely: *"the world doesn't seem to be slowing down, and being
able to react and not being married to the plan is actually the most important thing."*

---

## GSD: the tool and its five phases

**GSD** is Shopify's internal project management and **stakeholder review** system — and that
distinction matters. It is not a task tracker. It is the mechanism by which projects get reviewed
and approved as they move through five sequential phases:

| Phase | What happens |
|---|---|
| **1. Proposal** | The initial concept is submitted for review |
| **2. Prototype** | Design validation — including **a video walkthrough from the PM** |
| **3. Build** | Development |
| **4. Release** | Launch readiness |
| **5. Results** | Post-launch analysis |

The **video explanation at the prototype stage** is a nice detail: a PM narrating the prototype
surfaces gaps that a written spec hides, and it scales asynchronously to reviewers in other
timezones far better than a meeting.

Phase 5 being a **formal phase of the tool** is the part most companies never institutionalise.
"Results" is not a nice-to-have retrospective — the project isn't finished until it exists.

---

## OK1 and OK2 — the two sign-off gates

Projects pass through two named approval gates:

- **OK1** — first-line review. **Directors** from **product, UX, engineering, and sometimes data**
  must sign off.
- **OK2** — senior review. The **senior leadership team** — **product, UX, engineering, and data** —
  all have to sign off.

Two things are structurally interesting:

1. **Every gate is multi-functional.** Engineering and UX are not consulted after the fact; they are
   **co-approvers**. Product cannot unilaterally push a project forward.
2. **Reviews are asynchronous by default** — conducted via comments — with synchronous meetings
   scheduled only for **controversial or high-stakes projects**. There are also **office hours
   offering expedited 30-minute feedback slots**.

That default-async design is what makes a two-gate approval process survivable at Shopify's size.
The meeting is the exception, reserved for genuine disagreement.

---

## Who decides what

Coates draws the boundaries unusually crisply:

> *"Product makes the call on **should we do this at all**, Engineering and UX essentially have the
> veto power on **how** we do it, and then at the end of the day, the PM has to put their body on
> the line for **is this ready to ship**."*

Three distinct decisions, three distinct owners:

| Decision | Owner |
|---|---|
| Should we do this at all? | **Product** |
| How do we do it? | **Engineering and UX** (veto power) |
| Is it ready to ship? | **The PM**, personally accountable |

Most organisational dysfunction comes from conflating these — engineering deciding what to build,
or product dictating implementation. Naming them separately is most of the fix.

---

## No formal OKRs

Shopify deliberately avoids rigid metrics frameworks, on the grounds that they create
**"micro-optimisations and product incoherence"** — teams optimising a number at the expense of a
coherent product.

The nuance, which keeps this from being an excuse:

- Where there is a **direct merchant ROI**, metrics are taken extremely seriously — **checkout
  conversion rate** gets intense focus.
- Where there isn't, projects can proceed on the grounds that they are **"the right thing to do"** —
  the Admin redesign is the cited example of an **aesthetic-driven** decision shipped without a
  target metric.

This is a real position, not laziness: **the argument is that a metric is appropriate when it
genuinely proxies customer value, and harmful when it doesn't.** Compare Google's OKRs
([02](02-google-okrs-design-docs-and-launch-review.md)) — the opposite bet, made workable by
decoupling scores from performance reviews. Both work; neither works half-adopted.

---

## Organisational context

- Reorganised from **10 GM divisions to two strategic divisions**: **Core** (online store, checkout,
  Admin, included features) and **Merchant Services** (POS, payments, shipping, Shop).
- Within Core, **11 teams organised around jobs to be done** rather than user segments —
  Merchandising, Engage (marketing), Build (developer platform), and others. The stated goal is that
  *"merchants don't bump into the ceiling"* as they grow from startup to enterprise: one team owns a
  job across all customer sizes, instead of separate SMB and enterprise teams diverging.
- **Zero-based meeting culture:** Shopify **cancelled all recurring meetings** and now **displays the
  real-time cost of attendance** on meeting invitations. Recurring meetings must re-earn their
  existence rather than persisting by default.

---

## What to steal

1. **Gate on phases, and make the gate multi-functional.** Product, engineering, and design sign off
   *together*. Even in a small team, "the engineer and the designer both have to say yes before we
   start building" prevents most rework.
2. **Separate the three decisions explicitly** — *should we*, *how*, *is it ready* — and name the
   owner of each.
3. **Async review as the default; meetings only for disagreement.** This is what makes gates
   affordable.
4. **Make "Results" a required phase.** A project is not done when it ships; it's done when someone
   has written down what happened.
5. **A PM video walkthrough of the prototype** — cheap, high-bandwidth, timezone-friendly.
6. **Anchor to a public shipping event** if you can. An external date does what internal deadlines
   cannot.
7. **Delete recurring meetings and make them re-earn their slot.** Nearly free; immediately effective.

## Sources

- [Lenny's Newsletter — How Shopify builds product](https://www.lennysnewsletter.com/p/how-shopify-builds-product) *(interview with Glen Coates, VP Product, Shopify Core)*
- [Creator Economy — Inside how Shopify built its generative AI products](https://creatoreconomy.so/p/how-shopify-built-generative-ai) *(interview with Miqdad Jaffer, Shopify product lead)*
