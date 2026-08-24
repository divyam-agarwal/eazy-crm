# Walkthrough — Superhuman, 2017–2019

**A small-startup rollout, end to end: a deliberately rate-limited launch, a quantitative
product-market-fit engine that turned qualitative feedback into a roadmap, and a post-rollout metric
tracked quarterly from 22% to 58%.**

The most useful small-company counterpart to [Amazon Prime](walkthrough-amazon-prime.md), because it
inverts almost every choice: no launch date, no big-bang release, no executive intuition — a slow,
measured, gated rollout with a single number governing the roadmap.

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** in Rahul Vohra's First Round Review article *"How Superhuman Built an Engine to Find Product/Market Fit"*, his Lenny's Newsletter interview, First Round's onboarding playbook, and contemporaneous reporting (TechCrunch, Forbes) |
| **[INFERRED]** | Follows from documented facts, not stated |
| **[ASSUMPTION]** | Not in the record — flagged |

Gaps in [§8](#8-what-the-record-does-not-say). Rahul Vohra is Superhuman's founder and CEO, so these
are first-party accounts — with the usual caveat that founders narrate their own successes tidily.

---

## 1. The situation, summer 2017

Superhuman is an email client for professionals, founded 2014, charging **$30/month** — an
extraordinary price for consumer-adjacent email.

Vohra ran Sean Ellis's product-market-fit survey and got the answer nobody wants:

**22% of users said they would be "very disappointed" if they could no longer use Superhuman.**

The benchmark — established by **Sean Ellis**, who ran early growth at **Dropbox, LogMeIn and
Eventbrite**, after benchmarking nearly 100 startups — is **40%**. Companies with strong traction
"almost always exceeded" it; struggling companies fell below.

At 22%, Superhuman was decisively below the line. The conventional options were to iterate on
intuition or pivot.

**Vohra's actual move was different: treat PMF as a metric with an engine attached, and make it the
company's most visible number.**

---

## 2. The rollout strategy: deliberately rate-limited

Before the PMF engine, understand the rollout — because it's what made the engine possible.

Superhuman **has never formally launched.** Instead:

- **A waitlist.** You cannot simply sign up.
- **Every single customer is onboarded one-on-one**, in a **live concierge video call lasting about
  30 minutes** with an onboarding specialist — teaching keyboard shortcuts, customising settings,
  importing email.
- **Vohra personally onboarded early users** himself.
- Only after that call do you get the privilege of paying **$30/month**.

**Onboarding capacity was explicitly one of the rate-limiting factors on growth.** At peak they had
**around 14–20 people doing this full time.**

### Why deliberately throttle your own growth?

Four documented or directly-implied reasons:

1. **A $30/month email client has to be used properly to be worth $30/month.** Superhuman's value is
   speed and keyboard shortcuts. A user who never learns the shortcuts churns — correctly. The
   onboarding call converts a trial into a competent user.
2. **It creates superfans who drive word-of-mouth**, replacing paid acquisition.
3. **It let engineering focus on the product rather than on self-service onboarding flows.**
   **[INFERRED]** — a self-serve funnel is itself a large product to build; the humans were cheaper
   at that stage.
4. **The waitlist created mystique** that amplified demand.

And the fifth reason, which is the link to everything below:

5. **Every onboarding call is a structured research interview.** They **collected PMF survey data
   during onboarding sessions.** The rollout mechanism *is* the data collection mechanism.

> **This is the inverse of the standard startup instinct.** Instead of removing friction to maximise
> signups, Superhuman added expensive human friction — and got activation, retention, word-of-mouth,
> and a continuous research pipeline out of it. It only works because the unit economics
> ($30/month, recurring) can support ~30 minutes of human time per customer.

---

## 3. The engine: four steps

### Step 1 — Survey

Sent to users who had **engaged with the product at least twice in the last two weeks** — i.e. people
who had actually experienced it, not signups.

**The four questions:**

1. **"How would you feel if you could no longer use Superhuman?"**
   *(Very disappointed / Somewhat disappointed / Not disappointed)*
2. **"What type of people do you think would most benefit from Superhuman?"**
3. **"What is the main benefit you receive from Superhuman?"**
4. **"How can we improve Superhuman for you?"**

Each question does a specific job: Q1 is the metric. Q2 finds the segment. Q3 identifies what to
protect and amplify. Q4 identifies what's blocking everyone else.

**Sample size:** they surveyed **100–200 users** initially, and note that **around 40 respondents**
gives directionally accurate results.

**The score:** the **percentage answering "Very disappointed"** to Q1.

### Step 2 — Segment

Vohra applied **Julie Supan's "high-expectation customer" (HXC)** framework — *the most discerning
person within your target demographic.*

Superhuman built a detailed persona, **"Nicole"**: a busy professional handling **100–200 emails a
day** who values responsiveness and efficiency.

**The method:** look at which personas appear in the **"very disappointed" segment**, and narrow the
market to them.

**This alone moved the score from 22% to 33%.**

> Nothing was built. The product didn't change. **The score rose because they stopped counting people
> the product was never for.** This is the step most teams skip, and it reframes the whole exercise:
> a low PMF score is often a targeting problem before it's a product problem.

### Step 3 — Analyse

**From Q3 ("main benefit"):** they extracted the answers of the *very disappointed* segment and built
**word clouds**. The dominant themes: **speed, keyboard shortcuts, focus.** A representative response:

> *"Speed! The app is crazy fast, and the UX + keyboard shortcuts make me an actual superhuman."*

**From Q4 ("how can we improve"):** they analysed the answers of the ***somewhat* disappointed**
segment specifically — the fence-sitters. These are people who like it but aren't hooked. Their
blockers were: **mobile app, integrations, attachment handling, calendaring, unified inbox, better
search, read receipts.**

> **The key analytical move: read the two segments for different purposes.**
> *Very disappointed* users tell you **what to protect and double down on**.
> *Somewhat disappointed* users tell you **what's blocking conversion**.
> *Not disappointed* users are **deliberately ignored** — they're not your market, and averaging their
> feedback in is how products become mediocre for everyone.

### Step 4 — Implement: the 50/50 roadmap rule

**Half the roadmap** → **double down on what the very-disappointed users love**: speed, shortcuts,
automation, design.

**Half the roadmap** → **address the blockers** identified by somewhat-disappointed users.

Concrete features shipped as a direct result:

| Category | What was built |
|---|---|
| **Speed** | UI responses **under 50ms**; instantaneous search |
| **Keyboard shortcuts** | Additional shortcuts unavailable in other clients; **keystroke pipelining** |
| **Snippets** | Phrase auto-completion with CRM/ATS integration, attachments, CC automation |
| **Design detail** | Micro-interactions — e.g. typing `-->` converts to `→` |
| **Mobile app** | Identified as the critical missing piece |
| **Calendaring** | **Prioritised despite the team not using it themselves** |

That last row is the discipline showing. **The team built calendaring because the data said
fence-sitters needed it, not because they wanted it.**

---

## 4. Post-rollout analysis — the part that makes this a case study

Superhuman didn't run the survey once. They **built custom tooling to continuously survey new users
and update the aggregate**, tracked it **weekly, monthly and quarterly**, and were **careful never to
survey the same user more than once.**

**The "very disappointed" percentage became their most highly visible metric.**

### The progression

| Point | PMF score |
|---|---|
| Summer 2017 (baseline) | **22%** |
| After segmentation *(no product change)* | **33%** |
| After ~three quarters of the 50/50 roadmap | **58%** |

Reported quarterly progression: **22% → 33% → 47% → 56% → 58%** — from 22% to 58% in under a year,
passing the 40% benchmark on the way.

### Why this counts as rigorous post-rollout analysis

1. **One number, defined before the work started.** Not chosen after the fact to flatter the outcome.
2. **Measured continuously, not at milestones**, with a fresh population each time (never re-surveying
   a user avoids the obvious bias).
3. **It closed the loop on specific decisions.** Building calendaring was a bet derived from the Q4
   analysis; the subsequent score movement tested it.
4. **Segmentation was recorded as a separate, attributable step** (+11 points, zero engineering),
   which correctly credits an analytical insight rather than folding it into "the roadmap worked."

> **The honest caveat, which the sources do not raise:** the score is measured on *new users being
> onboarded*, and the *target segment was narrowed* during the same period. Some of the 22→58 movement
> is therefore **composition, not improvement** — you raise the average by admitting better-fitting
> users. **[ASSUMPTION]** — no source addresses this, and it's the obvious methodological weakness.
> It doesn't invalidate the engine; it means the number measures *"fit between our current audience
> and our current product,"* which is what you want to optimise, but isn't the same as *"the product
> got 2.6× better."*

---

## 5. Mapped onto the nine-stage lifecycle

| Stage | Superhuman | Contrast with Amazon Prime |
|---|---|---|
| 1. Opportunity | PMF score of 22% — a measured deficiency, not an idea | Prime: an idea in a suggestion box |
| 2. Definition | HXC persona ("Nicole") + segment analysis | Prime: an exec working group |
| 3. Approval | Founder-led; the 50/50 rule *is* the prioritisation policy | Prime: one decision by Bezos |
| 4. Technical design | Not documented | — |
| 5. Planning | Quarterly, governed by the 50/50 split | Prime: one 10-week crash project |
| 6. Build | Continuous | — |
| 7. **De-risk** | **The whole rollout is the de-risking**: waitlist + 1:1 onboarding | Prime: effectively skipped |
| 8. Launch | **Never formally launched** — continuous gated admission | Prime: a single fixed public date |
| 9. **Learn** | **Continuous PMF measurement, quarterly review** | Prime: three behaviour metrics, 2-year horizon |

**The two cases are near-opposites and both worked**, which is the point. Prime: one big irreversible
bet on a founder's behavioural thesis, judged over two years. Superhuman: many small reversible bets
governed by a single continuously-measured number.

**The variable that determines which is right: whether the thing you're building can be measured on a
timescale shorter than your runway.** Prime's thesis needed two years to show up; Superhuman's needed
one quarter.

---

## 6. What made this replicable — and what didn't

**Replicable by almost anyone:**
- The four survey questions and the "very disappointed" metric.
- Segmenting before concluding — *stop counting people you're not for.*
- Reading very-disappointed and somewhat-disappointed responses for **different purposes**.
- The 50/50 roadmap split as an explicit, written policy.
- Making one number the company's most visible metric.

**Not replicable without the right conditions:**
- **1:1 concierge onboarding** needs unit economics that support ~30 minutes of human time per
  customer. At $30/month recurring, that pays back. At $5/month, it does not.
- **The waitlist** creates mystique only if there is genuine demand pressure. Without it, a waitlist
  is just a broken signup form.
- **Ignoring most customer feedback** — Vohra's own framing — requires enough conviction about the
  target segment to withstand being wrong.

---

## 7. Nine lessons

1. **Make PMF a number, and measure it continuously** on a rolling population of new users.
2. **Segment before you conclude.** 22% → 33% with no product change. A low score is often a targeting
   problem.
3. **Survey people who have actually used the product** — Superhuman's bar was engaged twice in two
   weeks.
4. **~40 responses is enough** to be directionally accurate. This is not a research project.
5. **Read your promoters and your fence-sitters for different things** — protect vs. unblock — and
   deliberately ignore the third group.
6. **Write the prioritisation rule down** (50/50) so every roadmap argument resolves against a policy
   rather than the loudest person.
7. **Build what the data says, not what the team wants.** They shipped calendaring without using it.
8. **A deliberately rate-limited rollout can be a feature**: activation, word-of-mouth, engineering
   focus, and a continuous research pipeline, all from one mechanism.
9. **Never survey the same user twice** — the cleanest and cheapest bias control in the whole method.

---

## 8. What the record does not say

- **Absolute user or revenue numbers** during the 2017–2019 period. Only the PMF percentages.
- **How individual feature launches were rolled out** — flags, staged rollout, beta cohorts. The
  First Round article explicitly does not cover rollout cadence or per-feature post-launch
  measurement. Everything in [§4](#4-post-rollout-analysis--the-part-that-makes-this-a-case-study)
  is about the *aggregate* metric.
- **Whether individual features were A/B tested.** Not documented. **[ASSUMPTION]** — at their scale
  and with a gated user base, probably not in any statistically rigorous sense.
- **The exact survey cadence** beyond "constantly" and "weekly, monthly and quarterly."
- **How the 47% and 56% intermediate quarters map to specific shipped features.**
- **Churn, retention, or conversion figures** alongside the PMF score.
- **What happened after 58%** — whether it kept rising, plateaued, or fell as the audience widened.
  **[ASSUMPTION]** — widening beyond the HXC segment would mechanically pull the score down, which is
  the built-in tension in using this metric as a long-term north star.

---

## Sources

- [First Round Review — How Superhuman Built an Engine to Find Product/Market Fit](https://review.firstround.com/how-superhuman-built-an-engine-to-find-product-market-fit/) *(primary; Rahul Vohra, founder/CEO)*
- [Superhuman Blog — How Superhuman built an engine to find product-market fit](https://blog.superhuman.com/how-superhuman-built-an-engine-to-find-product-market-fit/) *(primary)*
- [First Round Review — Superhuman's Onboarding Playbook](https://review.firstround.com/superhuman-onboarding-playbook/) *(primary)*
- [Lenny's Newsletter — Superhuman's secret to success: Rahul Vohra](https://www.lennysnewsletter.com/p/superhumans-secret-to-success-rahul-vohra) *(primary interview)*
- [TechCrunch — Superhuman CEO Rahul Vohra on waitlists, freemium pricing and future products](https://techcrunch.com/2020/02/28/superhuman-ceo-rahul-vohra-on-waitlists-freemium-pricing-and-future-products/)
- [Forbes — Superhuman Raises $75 Million For Its Waitlist-Only Email Productivity App](https://www.forbes.com/sites/alexkonrad/2021/08/04/superhuman-raises-75-million-for-its-waitlist-only-email-productivity-app/)

**Related:** [08 — The lifecycle: idea to GA](08-lifecycle-idea-to-ga.md) ·
[walkthrough — Amazon Prime](walkthrough-amazon-prime.md) ·
[walkthrough — Shopify AI products](walkthrough-shopify-ai-products.md)
