# Walkthrough — Amazon Prime, 2004–2007

**A big-company rollout, end to end: an engineer's suggestion-box idea, a crash project run to an
earnings-call deadline, a price set with no financial model over the objections of finance and
operations, a launch that initially *lost* money — and the post-rollout analysis that took two years
to vindicate it.**

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** across *Working Backwards* (Bryar & Carr), the Commoncog case study, and contemporaneous reporting (Seattle Times' 10-year retrospective, Quartz) |
| **[INFERRED]** | Follows from documented facts, not stated |
| **[ASSUMPTION]** | Not in the record — flagged |

Gaps and a significant caveat about how this case is usually mis-told are in [§8](#8-what-the-record-does-not-say--and-one-correction).

---

## An important correction, up front

Prime is routinely cited as *the* example of Amazon's Working Backwards process. **The documented
record does not support that.** What it shows is a **founder-driven crash project on a compressed
deadline**, in which the decisive input was Bezos's judgement rather than a PR/FAQ and the
supporting analysis.

That makes it *more* useful, not less, for two reasons:

1. It's an honest picture of how a genuinely novel bet actually gets made at a large company — the
   formal mechanism handles the other 95%.
2. **The post-rollout analysis is the rigorous part**, and it is the part almost every retelling
   skips. Prime looked like a failure for two years.

For the formalised mechanism, see [01 — Amazon: Working Backwards](01-amazon-working-backwards.md).

---

## 1. Stage 1 — Opportunity: an engineer, a suggestion box

**Mid-October 2004.** **Charlie Ward**, an Amazon engineer who worked on the **ordering system** —
not a strategist, not a PM — submits an idea into **Amazon's digital employee suggestion box**.

His proposal: an **"all-you-can-eat" shipping model** that would simplify the existing **Super Saver
Shipping** program.

Super Saver Shipping was the status quo: free shipping above an order threshold, but **slow**. It had
a well-known behavioural side effect — customers would *add items to their basket to qualify*, and
then *wait* for slow delivery. Ward's insight was that a subscription removes the per-order
calculation entirely.

**What matters structurally here:**
- The idea came from **an engineer with no product authority**, through **a mechanism that existed for
  exactly this**.
- It reached the CEO. In most companies of Amazon's size in 2004, it would not have.
- Ward later **transferred onto the project team** — the person with the idea got to build it.

> **The transferable mechanism is not "have a suggestion box." It's having a suggestion box that a
> decision-maker actually reads, and a culture where acting on what's in it is normal.**

---

## 2. Stage 2–3 — Definition & approval: the boathouse

**November 2004.** Bezos **pulls together a group of executives at the boathouse of his home in
Medina**, and gives them a deadline: **produce a proposal by Amazon's next earnings call, at the end
of January.**

That's roughly **ten weeks**, over the US holiday season — Amazon's single busiest operational
period of the year.

The project is code-named **"Futurama."**

### What Bezos was actually optimising for

The documented framing was **not** a financial one. It was behavioural:

- **Draw a moat around Amazon's best customers.**
- **Change the psychology** of shoppers so that Amazon becomes their **default products provider and
  shipper** — not a place you comparison-shop, but the place you start.

**[INFERRED]** — this is why the finance objections in [§3](#3-the-pricing-decision) failed to land.
They were arguing about the unit economics of shipping; he was buying a change in habit. Those are
not the same argument, and the second one has no spreadsheet.

### The deadline choice

Tying the deadline to **the earnings call** is a deliberate, powerful move: an **externally-fixed,
publicly-visible date that cannot slip.** Prime launched **2 February 2005** — at the earnings
announcement.

Compare Shopify anchoring roadmaps to **Editions**, their twice-yearly public release event
([05 — Shopify GSD](05-shopify-gsd.md)). **An external date does what internal deadlines cannot.**

---

## 3. The pricing decision

**$79 per year.**

**Set by Bezos, with no financial modelling.**

His stated rationale: the number needed to be **large enough to matter to customers** — so that
members would consciously want to get their money's worth — **but small enough that they'd be willing
to try it.**

There was **no data on how membership would change purchasing behaviour**, because nothing like it
existed. So the decision rested on **intuition**, not analysis.

The *psychological* design is the clever part, and it's the opposite of what a revenue model would
produce: a fee **big enough to be remembered** creates the sunk-cost pressure that drives members to
consolidate their shopping at Amazon. **The price is a behaviour-change mechanism, not a revenue
line.** **[INFERRED]** — the sunk-cost mechanism is not spelled out in the sources, though it is
consistent with the stated "large enough to matter" rationale.

### The objections — which were correct, on the evidence available

**Finance and operations objected**, and Bezos overrode them. Their concerns were entirely reasonable:

- The **up-front fee might not cover the shipping charges** members would rack up.
- Customers would **order more precisely because shipping was free**, potentially abusing the program.
- Margin compression on **low-value orders** — the canonical example being **a $3 toothbrush shipped
  two-day.**
- The cost asymmetry was brutal: **land delivery cost ~$1.50 per package versus ~$15 by air**
  *(2006 figures)*.

One engineer told VP **Greg Greeley**: ***"I think it's going to take down the company."***

### The mitigations that made it survivable

The design was not reckless. Two documented mechanisms bounded the downside:

1. **Add-on restrictions** — low-value items can't be shipped alone, killing the $3-toothbrush case.
2. **"Prime standard"** — free **standard** shipping rather than expedited, for orders where two-day
   economics don't work.

> **The lesson isn't "overrule your finance team."** It's that a bet on changing customer behaviour
> can't be evaluated with a model built on unchanged behaviour — **and that you should ship the
> guardrails that cap the downside at the same time as the bet.**

---

## 4. Stage 5–7 — Build and launch

**Ten weeks**, spanning the holiday peak.

**Launch: 2 February 2005.**

**Terms: two-day shipping, no minimum purchase, $79/year.**

The name matters too. **"Prime"** was chosen deliberately — **[INFERRED]** from the positioning, but
widely reported: it signals the *customer's* status, not the *service's* speed. A membership you are
part of, rather than a shipping option you select.

### What's notably absent

By the standards of the [nine-stage lifecycle](08-lifecycle-idea-to-ga.md), Prime skipped most of
stage 7 (de-risking). There is no documented beta, no percentage rollout, no A/B test, no dogfooding
period. It launched to everyone, at once, on a fixed date.

**[INFERRED]** — feature-flag infrastructure and large-scale online experimentation weren't yet
standard practice in 2004–05, and the deadline was externally fixed. A modern equivalent would
almost certainly be tested in a limited market first. **Do not read this as an endorsement of
big-bang launches.**

---

## 5. Stage 9 — Post-rollout analysis: the two-year wait

**This is the part worth studying.**

### The early signal was bad

> **"Initial growth was slow."**

Worse, the composition of early subscribers was actively harmful to the P&L:

- Early Prime subscribers were **existing customers who had already been paying extra for expedited
  shipping**.
- They signed up **because Prime made fast delivery cheaper for them**.
- Result: Prime **reduced shipping revenue and compressed profit margins.**

So at the six-month mark, the honest read was: *slow adoption, and the people adopting are the ones
who cost us money.* **Finance's objections were being empirically vindicated.**

### The three metrics they actually tracked

Amazon measured Prime on:

1. **Annual orders** (per customer)
2. **Items per order**
3. **Items annually** (per customer)

Look at what's *not* there: no revenue, no margin, no subscriber count. **All three are behaviour
metrics** — measuring whether the psychological change Bezos was buying had actually happened.

> This is Amazon's **input vs. output metric** discipline
> ([01](01-amazon-working-backwards.md)) applied exactly where it's hardest: the output metrics were
> *negative*, and they kept measuring the inputs because the inputs were the thesis.

### The result

**By 2007 — the two-year mark — internal data showed Prime members had *doubled their annual
spending*.**

**Why it took two years:** in the mid-2000s, **customers shopped online infrequently.** The
behavioural change Bezos predicted was real, but it had to propagate through a purchase cycle
measured in months, across a population that hadn't yet made the internet their default shopping
channel. The thesis was right and the *measurement window* had to be long enough to see it.

### The long run

- **2019:** 100+ million Prime members.
- **2021:** 200+ million globally.

---

## 6. The decision-making lesson

The genuinely hard part was **not killing it in 2005 and 2006**.

Every organisation's instinct — and every well-run metrics review — would have flagged Prime as
underperforming: slow growth, negative margin impact, and its own finance organisation on record
against it. The mechanisms that let it survive:

1. **The thesis was written down as a behavioural claim**, not a financial one — *change the
   psychology so Amazon is the default.* You cannot falsify that with six months of margin data.
2. **The metrics tracked matched the thesis** (orders, items per order, items annually), so there was
   a real signal to watch that wasn't the misleading one.
3. **A single decision-maker with the authority and the appetite to wait.** **[INFERRED]** — no
   source states an explicit "we will not evaluate this for N years" commitment, but the two-year
   measurement horizon implies one in practice.

> **The general principle: decide in advance what would prove you right, and how long that will take
> to become visible. Otherwise the first bad quarter kills every long-horizon bet you make.**
>
> The failure mode this guards against is real and common: a company sets a metric, sees it move the
> wrong way for two quarters, and kills a correct decision.

---

## 7. Mapped onto the nine-stage lifecycle

| Stage | Prime | Notes |
|---|---|---|
| 1. Opportunity | Engineer's suggestion-box idea, Oct 2004 | Mechanism existed and was read |
| 2. Definition | Boathouse working group, Nov 2004 | **Not a PR/FAQ** — an exec working group |
| 3. **Approval** | **Bezos**, over finance and ops objections | Single decider; classic single-threaded |
| 4. Technical design | "Futurama" project | Not documented publicly |
| 5. Planning & funding | ~10 weeks to the earnings call | Externally fixed, unslippable deadline |
| 6. Build | Nov 2004 – Jan 2005, through holiday peak | — |
| 7. De-risk | **Effectively skipped** | No beta/staged rollout documented |
| 8. **Launch** | **2 Feb 2005**, everyone at once | At the earnings announcement |
| 9. Learn & iterate | Orders / items-per-order / items-annually; **doubled spend by 2007** | The rigorous part |

---

## 8. What the record does not say — and one correction

**The correction:** as noted up front, Prime is widely presented as a Working Backwards case study.
The documented record describes an executive-driven crash project. **No PR/FAQ for Prime is
publicly documented.** If you cite this as a Working Backwards example, you'll be repeating a
plausible but unsupported claim.

**Genuine gaps:**

- **Whether any written artifact existed** — a memo, a proposal document, a six-pager — beyond "come
  up with a proposal." Not documented.
- **Who was in the boathouse**, beyond it being "a group of executives."
- **Whether alternative price points were considered**, and what they were.
- **The size of the launch team**, or the engineering scope of the ten weeks.
- **Subscriber counts for 2005–2006.** Amazon didn't disclose them; "growth was slow" is qualitative.
- **How the "doubled annual spending" figure was computed** — whether it controlled for selection
  effects. **[ASSUMPTION]** — Prime members were self-selected heavy shoppers, so some of the doubling
  is almost certainly selection rather than causation. No source addresses this, and it's the obvious
  analytical weakness in the headline number.
- **Whether an explicit evaluation horizon was set in advance**, or whether patience was retrospective.
- **The 2006 land-vs-air cost figures** are the closest documented proxy for 2005 economics; exact
  launch-period costs aren't published.

---

## 9. Seven lessons

1. **Build a route for ideas from people without authority — and make sure someone with authority
   reads it.** Prime came from the ordering-system engineer.
2. **Anchor deadlines to external, public events.** An earnings call cannot slip; a sprint can.
3. **Price can be a behaviour-change mechanism rather than a revenue calculation** — but say which
   one you're doing, because the two are evaluated completely differently.
4. **Ship the guardrails with the bet.** Add-on restrictions and "Prime standard" capped a downside
   that a colleague genuinely believed could "take down the company."
5. **State the thesis as a falsifiable behavioural claim**, then pick metrics that measure *that
   claim* — not the metrics that happen to be easy.
6. **Decide the evaluation horizon before you launch.** Prime looked like a failure for two years and
   was right. Without a pre-agreed horizon, the first bad quarter wins the argument.
7. **A confident, informed objection can be correct on the evidence and still be the wrong call.**
   Finance and ops reasoned correctly from a model that assumed behaviour wouldn't change. The whole
   bet was that it would.

---

## Sources

- Colin Bryar & Bill Carr, *Working Backwards: Insights, Stories, and Secrets from Inside Amazon* (2021)
- [Commoncog Case Library — Amazon Prime: Burn to Grow](https://commoncog.com/c/cases/amazon-prime/)
- [Seattle Times — 10 years later, Amazon celebrates Prime's triumph](https://www.seattletimes.com/business/amazon/10-years-later-amazon-celebrates-primes-triumph/)
- [Quartz — The very unscientific tale of how Amazon first set the price of Prime](https://qz.com/187442/the-very-unscientific-tale-of-how-amazon-first-set-the-price-of-prime)

**Related:** [01 — Amazon: Working Backwards](01-amazon-working-backwards.md) ·
[08 — The lifecycle: idea to GA](08-lifecycle-idea-to-ga.md) ·
[walkthrough — Superhuman](walkthrough-superhuman.md)
