# 09 — Synthesis: the axes of variation, and what to copy

---

## The five things all of them do

Strip away the branding and every company in this directory does the same five things:

1. **Force the idea into writing before anyone builds.** A press release (Amazon), a pitch
   (Basecamp), a design doc (Google), a proposal in a tool (Shopify), a DIBB chain (Spotify).
   Different formats, identical function: **make the author think it through, and make the argument
   inspectable by someone who wasn't in the conversation.**

2. **Name exactly one decider, before the discussion.** Amazon's single-threaded leader. Atlassian's
   Approver. Basecamp's four-person betting table with no appeal. Shopify's OK1/OK2 with named
   functions. Three independent companies converged on this, which is the strongest signal here.

3. **Hold meetings that produce decisions, not status.** Every named meeting in
   [07](07-the-meeting-calendar.md) has a decision as its output. Amazon goes further and makes the
   meeting *contain* the reading, so it can't degrade into a walkthrough.

4. **Put a gate between "we finished it" and "everyone gets it," owned by someone else.** Google's
   LCE sign-off is the most formalised version. The structure is the same as Amazon's Bar Raiser:
   **install someone whose incentives differ from the team's, and give them a veto.**

5. **Close the loop after launch.** Shopify's mandatory Results phase, Google's quarterly OKR
   grading, Amazon's weekly input-metric review. This is the stage that makes the earlier stages
   better, and it's the one everyone skips.

---

## The two real axes of variation

Everything else is local colour. The genuine differences are two:

### Axis 1 — Who holds the veto?

| Model | Company | Trade-off |
|---|---|---|
| **Small senior group, binding** | Basecamp's betting table | Fast and decisive; scales only while leadership can hold the whole product in their heads |
| **Escalating written review** | Amazon's PR/FAQ chain | Scales enormously; slow, and demands genuine writing ability across the org |
| **Multi-functional gates in a tool** | Shopify's OK1/OK2 | Balanced, and async by default; risks becoming ceremony if the gates aren't real |
| **Specialist gatekeeper team** | Google's LCE | Best-in-class for *safety*; needs enough launches to justify a dedicated team |
| **Named Approver per decision** | Atlassian's DACI | Lightweight, works at any size; needs discipline to apply consistently |

### Axis 2 — Fixed scope or fixed time?

- **Fixed scope, variable time**: "build this feature, however long it takes." The default almost
  everywhere. Produces slipping dates and a growing backlog.
- **Fixed time, variable scope**: Basecamp's six-week bet, Shopify's six-week rhythm. Forces scope
  negotiation continuously, by the people doing the work.

The second is better **only if the deadline is real.** Basecamp enforces that with the **circuit
breaker** — no automatic extension; if it isn't done, it stops and must be re-argued. A deadline
with routine extensions is fixed-scope in disguise, and gets you the worst of both.

### A third difference worth naming: metrics

Google and Shopify take **opposite, defensible positions**:

- **Google:** OKRs everywhere, graded 0.0–1.0, **target 0.7**, decoupled from performance reviews.
  Works *because* of the decoupling — the aggressive goal is the safe one.
- **Shopify:** **no formal OKRs**, on the grounds that they cause "micro-optimisations and product
  incoherence." Metrics are used intensely where they genuinely proxy customer value (checkout
  conversion) and deliberately not used where they don't (the Admin redesign shipped as "the right
  thing to do").

**Both work. Neither works half-adopted.** The common failure is Google's framework without Google's
decoupling — OKRs tied to compensation, which guarantees sandbagged goals and turns the system into
quarterly status reporting.

---

## What a small team should actually adopt

Ordered by value per unit of effort. Nothing here requires more than a handful of people.

### Adopt immediately — these cost hours, not weeks

1. **Silent reading at the start of any document review** (Amazon). Hand out the doc, read for 15
   minutes, then discuss. Immediately fixes "nobody read it," and costs nothing.
2. **Name the Approver out loud before the discussion starts** (Atlassian). One sentence. Resolves
   most stalled decisions.
3. **"Input, not vote"** — say explicitly whether someone is being consulted or asked to agree.
4. **Non-goals / no-gos in every written proposal** (Google, Basecamp). Written exclusions are the
   best scope control anyone in this directory has.
5. **Write the decision and its reasoning down, and send it to the people affected** (Atlassian).
   Undocumented decisions get re-litigated, and the re-litigation costs more than the decision did.

### Adopt when you're 5–20 people

6. **Appetite instead of estimate** (Basecamp). "This is worth two weeks" is a business judgement you
   can defend; "this will take two weeks" is a prediction you'll be wrong about.
7. **Rabbit holes and no-gos in every pitch** (Basecamp). More effective than any estimation
   technique for actually landing on time.
8. **A design doc with "alternatives considered"** (Google) for anything non-trivial. Async review
   in comments, not a meeting.
9. **A launch checklist built from your own incidents** (Google). One line per postmortem. Prune it.
   Within a year it's the most valuable document you own.
10. **Feature flags and a kill switch as standard**, so shipping is reversible in seconds.
11. **A "Results" step that's actually required** (Shopify). The project isn't closed until someone
    writes down what happened.
12. **Separate the three decisions** (Shopify): *should we do this at all* (product), *how do we do
    it* (engineering/design, with veto), *is it ready to ship* (one accountable person).

### Adopt when you're 20+ people

13. **A written proposal before funding anything meaningful** — pick one format and stick to it.
14. **A fixed cycle with a circuit breaker** (Basecamp) — no automatic extensions.
15. **A go/no-go gate owned by someone other than the author** (Google). Even one person, even
    part-time.
16. **Input metrics reviewed on a fixed cadence** (Amazon), not just revenue and signups.
17. **Delete all recurring meetings and make them re-earn their slot** (Shopify).

### Explicitly don't copy

- **Squads/tribes/chapters/guilds.** Spotify itself has distanced from it; it was a snapshot that
  got cargo-culted into a methodology.
- **OKRs tied to performance reviews.** This is the single most common way companies break Google's
  system while believing they've adopted it.
- **Amazon's six-pager for everything.** The narrative format is expensive; it earns its cost for
  genuinely consequential decisions and wastes everyone's time for routine ones. Amazon uses it for
  the decisions that warrant it.
- **A groomed backlog you never build from.** Basecamp's position is defensible: if nobody will
  re-pitch it, it was never going to get built.

---

## The one-line version

> **Write it down. Name who decides. Meet to decide, not to report. Put someone else between
> "done" and "shipped." Check afterwards whether you were right.**

Everything in this directory is an elaboration of those five sentences, tuned to a particular
company's size, culture, and appetite for process.

## Sources

Per-company sources are in each file: [Amazon](01-amazon-working-backwards.md),
[Google](02-google-okrs-design-docs-and-launch-review.md), [Basecamp](03-basecamp-shape-up.md),
[Atlassian](04-atlassian-daci.md), [Shopify](05-shopify-gsd.md),
[Spotify](06-spotify-dibb-and-rhythm.md).
