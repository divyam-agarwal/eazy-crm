# Walkthrough — Shopify's AI products (Magic & Sidekick), 2022–2023

**A modern big-company rollout running through a formal, tool-enforced gate process.** The most
directly copyable of the three walkthroughs, because it shows the
[GSD five phases](05-shopify-gsd.md) executing on a real product — including the **Results** phase
most companies never institutionalise, and an unusually disciplined refusal to set adoption targets.

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** in an on-the-record interview with **Miqdad Jaffer**, Shopify product lead for AI, and Lenny Rachitsky's interview with **Glen Coates**, VP Product (Core) |
| **[INFERRED]** | Follows from documented facts, not stated |
| **[ASSUMPTION]** | Not in the record — flagged |

Both sources are detailed practitioner interviews rather than a company manual. Gaps in
[§8](#8-what-the-record-does-not-say).

---

## 1. Stage 1 — Opportunity: it starts with the annual letter

**Winter 2022.** CEO **Tobi Lütke's annual letter** sets the year's thematic priority, framed — as
Shopify's themes always are — **from the merchant's point of view**:

> **"Shopify keeps me on the cutting edge."**

Note what that is and isn't. It is **not** "ship AI features," "increase AI adoption 20%," or "launch
a chatbot." It's a **statement about how a merchant should feel**, which leaves the entire solution
space open to the teams below.

From that theme, the team identified a specific, high-impact use case:

**Product descriptions** — because **"merchants often don't have time to write great product
descriptions."**

> **The cascade in action:** an annual theme in the customer's voice → a team-level identification of
> a concrete merchant pain → a proposal. See the annual → six-month → six-week structure in
> [05 — Shopify GSD](05-shopify-gsd.md).

---

## 2. Stage 2–3 — Proposal (GSD Phase 1)

The **Proposal** phase requires three things:

1. **The merchant problem** — merchants lack time to write good product descriptions.
2. **The product principles** — the constraints the solution must respect.
3. **The solution approach.**

### The principle that shaped everything

> **"Let the merchant decide."**

Merchants **must review and approve AI suggestions rather than having them auto-implemented.**

This is a *product principle*, established at proposal time, before any code. And it's load-bearing:
it determines the UI (a suggestion you accept, not text that appears), the failure mode (a bad
suggestion is ignored, not published to a storefront), the trust model, and the liability posture.

> **Writing the principles down at the proposal stage is the highest-leverage thing in this
> walkthrough.** A principle decided before you build constrains a thousand later decisions
> consistently. A principle "decided" during code review constrains one.

### The gate

Per the GSD process, the proposal passes **OK1** — sign-off from **directors across product, UX,
engineering, and sometimes data** — with **OK2** (senior leadership: product, UX, engineering, data)
at the senior gate.

Reviews are **asynchronous, via comments**, with synchronous meetings scheduled only for
controversial or high-stakes work. **[INFERRED]** — a novel AI product touching every merchant's
storefront almost certainly qualified as high-stakes and drew synchronous review.

---

## 3. Stage 4 — Prototype (GSD Phase 2)

The Prototype phase exists to **"quickly build working versions to understand product behaviour."**

**What the prototype actually taught them:** **retrieval-augmented generation was necessary to
provide sufficient context for quality outputs.**

That is a genuinely important finding and it's the whole justification for the phase. A
generic LLM call produces generic descriptions; useful ones need the merchant's actual product data,
brand voice, and catalogue context retrieved and injected.

> **You could not have learned this from a spec.** With generative features especially, **the
> behaviour of the system is not derivable from its design** — you have to build a thin version and
> look at the outputs. The Prototype phase is where an assumption about model quality gets replaced
> by an architecture decision.

**The review mechanism here is distinctive:** Shopify's prototype phase includes **a video
explanation from the PM** ([05](05-shopify-gsd.md)), and more broadly the AI team replaced
traditional synchronous reviews with **async video demonstrations**, with **leadership providing
rapid "friction logs"** for iterative improvement.

A friction log is a reviewer narrating their experience of actually using the thing, noting every
point of friction. It is far more useful than a design critique, and it scales async across
timezones.

---

## 4. Stage 5–6 — Build (GSD Phase 3)

**"Expand prototype into full product with leadership updates."**

### Sidekick's architecture

The AI assistant operates across **four integrated layers**:

| Layer | Responsibility |
|---|---|
| **Model layer** | The LLM, with adapters / fine-tuning |
| **API layer** | Chat history, error handling, streaming |
| **UI layer** | Merchant interface for chat and feedback |
| **Admin layer** | Integration with Shopify's admin systems |

**The UI layer explicitly includes feedback capture** — the measurement path is designed in from the
start, not bolted on before launch. **[INFERRED]** — but a "feedback" responsibility named in the
architecture is a strong signal that Results-phase data collection was a build-time requirement.

Note also that "leadership updates" are part of the *Build* phase definition. Progress reporting is a
named obligation of the phase, not an ad-hoc thing that happens when someone asks.

---

## 5. Stage 7–8 — Release (GSD Phase 4)

**"Launch to user segments — initially English-speaking merchants only."**

That is the entire documented rollout strategy, and it's a sensible one for a generative feature:

- **English-only** bounds the quality risk. LLM output quality varies enormously by language, and the
  team can actually evaluate English output themselves.
- **A segment, not a percentage.** Unlike a typical A/B rollout, this segment is chosen because it's
  the population where they can **judge whether the output is any good**.

> **For generative features, the first rollout segment should be the one where you can evaluate
> quality, not the one that's statistically convenient.** You cannot canary your way through a
> quality problem you can't read.

---

## 6. Stage 9 — Results (GSD Phase 5): the interesting part

**"Share outcomes and plan next iterations."** A formal, required phase of the tool — the project is
not closed until it exists.

### The metrics decision

This is the most instructive paragraph in the whole case:

> The team **avoided adoption-based OKRs**, believing they lead to **"growth hacks that aren't
> sustainable"** that undermine genuine value. Instead, they validated that AI features remained
> **"neutral or positive" to company metrics.**

Unpack what that means:

- **They did not set a target like "30% of merchants use AI descriptions."** Because if you do, the
  rational way to hit it is to make the feature harder to avoid — auto-enable it, interrupt with
  modals, place it in the flow of unrelated tasks. Every one of those directly violates the
  **"let the merchant decide"** principle from the proposal.
- **The bar was instead a guardrail: does this stay neutral-or-positive on the metrics that already
  matter?** Not "did AI adoption go up," but "did the business get worse because of this?"

This is Shopify's stated anti-OKR position ([05](05-shopify-gsd.md)) applied precisely where the
temptation is strongest — a strategically-mandated initiative from the CEO's annual letter, the exact
situation where teams normally invent a vanity adoption metric to demonstrate progress.

> **The general principle: an adoption target on a feature the user should be free to ignore is a
> corrupted metric.** It converts "is this useful?" into "can we make people use it?" — and the
> second question has easy, destructive answers. Guardrail metrics ("this doesn't make anything
> worse") plus qualitative signal beat a vanity target.

**[INFERRED]** — "neutral or positive to company metrics" almost certainly means checkout conversion
and merchant GMV-related measures, which Shopify does watch intensely ([05](05-shopify-gsd.md)).
The sources don't name them.

---

## 7. Mapped onto GSD and the nine-stage lifecycle

| GSD phase | Lifecycle stage | What happened | Gate |
|---|---|---|---|
| **1. Proposal** | 1–3 Opportunity → Approval | Merchant problem, **product principles** ("let the merchant decide"), solution approach | **OK1** → **OK2** |
| **2. Prototype** | 4 Technical design | Working version; **discovered RAG was required**; async video demos + leadership friction logs | phase gate |
| **3. Build** | 5–6 Planning → Build | Four-layer architecture (model / API / UI / admin), leadership updates | phase gate |
| **4. Release** | 7–8 De-risk → Launch | **English-speaking merchants only** | phase gate |
| **5. Results** | 9 Learn & iterate | Share outcomes, plan next iterations; **no adoption OKRs**, neutral-or-positive guardrail | — |

**What makes this the most copyable of the three walkthroughs:** it is the only one where the process
itself is a durable, reusable artifact. Prime was a one-off founder bet; Superhuman's engine depends
on unusual unit economics. **GSD is a five-phase workflow with named sign-offs that a 20-person
company could implement in a spreadsheet on a Monday.**

---

## 8. What the record does not say

- **Dates.** "Winter 2022" for the annual letter and first initiative; individual phase transitions,
  the Release date, and the Results review date are not given.
- **Any quantitative outcome.** No adoption numbers, quality scores, merchant satisfaction, or metric
  movements. Given their stated refusal to set adoption OKRs, this may be deliberate. **[ASSUMPTION]**
- **Who specifically signed OK1 and OK2** for these projects, or whether either gate was contested.
- **How long each phase took**, or whether any phase was sent back.
- **What "neutral or positive to company metrics" meant concretely** — which metrics, what thresholds,
  over what window.
- **Whether there was a staged percentage rollout within the English-speaking segment**, or a
  kill switch. **[ASSUMPTION]** — a company deploying continuously at Shopify's scale almost certainly
  used flags, but it isn't stated.
- **How merchant feedback captured in the UI layer fed back into prioritisation.** The layer exists;
  the loop isn't described.
- **Whether the Results phase produced a decision to continue, change, or stop** for these specific
  products.

---

## 9. Eight lessons

1. **Write product principles at the proposal stage**, before any code. "Let the merchant decide"
   determined the UI, the failure mode, the trust model, *and* the metrics strategy — one sentence,
   enormous leverage.
2. **Frame annual goals in the customer's voice**, not as metrics. "Shopify keeps me on the cutting
   edge" leaves teams the whole solution space; "increase AI adoption 20%" doesn't.
3. **Prototype to learn system behaviour, not to demo.** The RAG requirement was a finding, not a plan.
4. **Async video demos plus friction logs** beat synchronous design reviews for reach and honesty —
   and scale across timezones.
5. **Don't set adoption targets on optional features.** It corrupts the metric and licenses dark
   patterns. Use guardrail metrics instead.
6. **For generative features, pick a first rollout segment you can evaluate**, not one that's
   statistically tidy.
7. **Make "Results" a required, gated phase.** A project isn't done when it ships.
8. **Design the feedback path into the architecture**, not into the launch checklist.

---

## Sources

- [Creator Economy — Inside How Shopify Built Its Generative AI Products](https://creatoreconomy.so/p/how-shopify-built-generative-ai) *(interview with Miqdad Jaffer, Shopify product lead for AI)*
- [Lenny's Newsletter — How Shopify builds product](https://www.lennysnewsletter.com/p/how-shopify-builds-product) *(interview with Glen Coates, VP Product, Shopify Core)*

**Related:** [05 — Shopify: GSD](05-shopify-gsd.md) ·
[08 — The lifecycle: idea to GA](08-lifecycle-idea-to-ga.md) ·
[walkthrough — Amazon Prime](walkthrough-amazon-prime.md) ·
[walkthrough — Superhuman](walkthrough-superhuman.md)
