# 01 — Amazon: Working Backwards

**Source confidence: high.** *Working Backwards* was written by **Colin Bryar** (12 years at
Amazon; Jeff Bezos's technical advisor / "shadow") and **Bill Carr** (15 years; ran digital media).
They designed and ran the mechanisms described below.

The core idea: **start from the finished customer experience and work backwards to the work**,
and refuse to fund anything until that experience can be described clearly in writing.

---

## 1. The PR/FAQ — write the press release before you write the code

Before a product is approved, someone writes a **mock press release announcing the finished
product**, plus an FAQ. Not a spec, not a slide deck — the announcement, written as though it
already shipped.

### The structure

**Press release:**

| Section | Contents |
|---|---|
| Heading | One sentence naming the product, as the customer would hear it |
| Subheading | Who the target customer is and what they get |
| Summary paragraph | Launch date and what the product is |
| Problem paragraph | The customer's pain, stated from their point of view |
| Solution paragraph(s) | How the product works and how it's differentiated |
| Quotes & getting started | A customer quote, and how someone begins using it |

**FAQ**, split in two:

- **External FAQ** — the questions a customer or journalist would ask: pricing, how it works,
  support, availability.
- **Internal FAQ** — the questions a skeptical executive would ask: competitive landscape, total
  addressable market, the hard technical problems, unit economics, the assumptions the plan rests
  on, and the risks.

The internal FAQ is where the actual argument happens. The press release forces clarity; the
internal FAQ forces honesty.

### How it's written

- An early draft takes **"only a few hours, not a few days."** That's deliberate — the document is
  cheap to write and cheap to kill, which is the entire point.
- It's usually drafted by **one person** (typically a product manager), then iterated:
  1. solo draft, with the research gaps visible
  2. feedback from manager and peers
  3. a small group review (~10 contributors)
  4. executive review
  5. more revisions if it's promising
- Successful Amazon products went through **months** of this before anyone built anything.

### What happens at the end

**If approved:** resources are allocated — people, budget, timeline — as laid out in the FAQs, and
leadership sets up regular check-ins. The finalised PR/FAQ becomes the shared reference: **anyone at
Amazon can read it and understand the plan identically.**

**If rejected**, the outcome is specific rather than a plain "no":
- insufficient differentiation → back to the drawing board
- TAM too small → change the customer segment or the problem
- cost too high for the payoff → reduce the upfront investment
- unsolved technical barrier → revisit later
- fine, but no capacity → into the backlog with a priority

---

## 2. The six-page narrative and the silent reading

Amazon **banned PowerPoint** for this class of decision after Bezos read Edward Tufte's critique of
bullet points. The replacement:

- **Exactly six pages of narrative prose**, 10-point font, **no bullet lists**, plus unlimited
  appendices.
- Prose is the constraint that does the work. As Bryar and Carr put it, a narrative forces the
  author to "demonstrate what's more important than what, and how things are related" — connective
  tissue that bullets let you skip.

**The meeting format:**
1. The document is **handed out at the start of the meeting**, not before. Everyone reads the same
   version, including any last-minute edits.
2. **The first ~20 minutes are silent reading** — roughly 3 minutes a page — with attendees writing
   comments into a shared copy as they go.
3. The remaining ~40 minutes are **discussion**, usually page-by-page or round-the-room.
4. Someone takes detailed notes on the feedback.

Two things this quietly solves: **nobody can bluff having read it**, and the meeting starts from a
shared, current understanding instead of spending half its time on a walkthrough.

---

## 3. Single-threaded leadership and two-pizza teams

- **Two-pizza teams**: small enough to be fed by two pizzas — the well-known part.
- **Single-threaded leadership** is the more important part: each initiative has **one leader with
  no competing responsibilities.** Not a part-time owner, not a committee. Their whole job is that
  one thing.
- Teams must be **separable** — able to make progress with minimal dependencies on other teams,
  the way APIs decouple software.

The reasoning behind it is characteristically blunt: Bezos regarded **"effective communication
across groups as a defect"** — a sign the boundaries were drawn wrong — and preferred "loosely
coupled interaction via machines through well-defined APIs rather than via humans." The org chart
is treated as a system architecture problem.

---

## 4. OP1 / OP2 — the annual planning mechanism

Planning is explicitly **bottom-up**:

- **OP1** ("Operating Plan 1"): each group builds its own detailed operating plan — a bottom-up
  proposal of what it intends to do, what it needs, and what it will deliver — which is then
  reviewed and reconciled against leadership's top-down goals. This runs in the second half of the
  year, ahead of the new fiscal year.
- **OP2**: a revision after the holiday quarter's actual results are in, adjusting the plan to
  reality before the year is properly underway.

The mechanism matters more than the names: **teams propose their own plans in writing, and the
reconciliation between bottom-up ambition and top-down goals is an explicit, scheduled event**
rather than a negotiation that happens implicitly all year.

---

## 5. Input metrics and the Weekly Business Review

Amazon separates:

- **Output metrics** (lagging): revenue, profit, market share. Real, but you can't act on them
  directly — they're the rear-view mirror.
- **Input metrics** (leading, controllable): the things a team directly influences that *cause* the
  outputs — selection, in-stock rate, page latency, delivery speed.

The **Weekly Business Review (WBR)** is the recurring meeting that walks both, weekly, in a standard
format. Crucially, the metrics are **audited independently by finance**, which removes the incentive
to define a metric so it flatters the team.

The design principle: **pick input metrics you can actually move, prove they drive the outputs, then
manage the inputs weekly.**

---

## 6. The Bar Raiser

For hiring: every loop includes a **trained interviewer from outside the hiring team who holds veto
power** over the offer. Their job is explicitly *not* to fill the role — it's to keep the standard
from drifting when a team is desperate.

It's included here because it's the same structural pattern as everything else above: **when a
decision has a predictable bias, install someone whose incentives are deliberately different and
give them a veto.** (Compare Google's Launch Coordination Engineers in
[02](02-google-okrs-design-docs-and-launch-review.md) — same idea, applied to launches.)

---

## What to steal if you're not Amazon

1. **Write the press release first.** Even alone, even for a feature. If you can't describe the
   customer benefit in a paragraph without jargon, you don't understand it yet. This costs an hour
   and kills bad ideas before they cost a quarter.
2. **The internal FAQ is the real artifact.** Force yourself to write down the assumptions, the
   economics, and the risks. Most bad projects die here honestly, or survive with the risk named.
3. **Silent reading at the start of the meeting.** Free, immediate, and it changes the quality of
   every document review you run. It also removes the "I skimmed it" problem entirely.
4. **Prose over bullets for anything that requires a decision.** Bullets hide the reasoning; that's
   why they're comfortable.
5. **One owner per initiative, with no competing responsibilities.** The most common failure in small
   companies is three people who each own it 30%.
6. **Distinguish input from output metrics**, and review the inputs on a fixed cadence.

## Sources

- Colin Bryar & Bill Carr, *Working Backwards: Insights, Stories, and Secrets from Inside Amazon* (2021) *(primary)*
- [Working Backwards — The Amazon PR/FAQ process](https://workingbackwards.com/concepts/working-backwards-pr-faq-process/) *(primary; the authors' own site)*
- [Commoncog — Book Summary: Working Backwards](https://commoncog.com/working-backwards/)
- [The PRFAQ — The Amazon Writing Culture](https://www.theprfaq.com/articles/amazon-writing-culture)
