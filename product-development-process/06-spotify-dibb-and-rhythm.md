# 06 — Spotify: DIBB & Spotify Rhythm

> **Source confidence: moderate — read this file with two caveats.**
>
> 1. The primary source is **Henrik Kniberg's *Spotify Rhythm* talk (Agila Sverige, 2016)**. Kniberg
>    was an agile coach at Spotify, so this is an insider account — but it is a **2016 snapshot**,
>    not a current description.
> 2. Spotify has **publicly distanced itself from its other famous export** (the squads/tribes/
>    chapters/guilds model), which was similarly popularised from a Kniberg artifact and then widely
>    cargo-culted. Treat DIBB the same way: **a real framework Spotify used, not a claim about how
>    Spotify works today.**
>
> It's included because the framework itself is genuinely useful and unusually well-specified about
> one thing every other entry here handles vaguely: **how do you get from data to a company-wide
> commitment without either ignoring the data or being paralysed by it?**

---

## DIBB: Data → Insight → Belief → Bet

A four-stage chain, where each stage is a different *kind* of statement:

| Stage | What it is | Example shape |
|---|---|---|
| **Data** | An observed fact. Not an interpretation | *"X% of new users never complete step 3."* |
| **Insight** | What the data means — the interpretation, stated separately so it can be challenged | *"Users don't understand what step 3 is for."* |
| **Belief** | What we therefore think is true about the world, including things the data doesn't prove | *"If step 3 explained its value, completion would rise substantially."* |
| **Bet** | A funded, time-bound commitment to act on the belief | *"We will rebuild the step-3 experience this quarter."* |

The value is in **forcing the separation**. Most product arguments collapse all four into one
sentence, which makes them impossible to interrogate — you can't tell whether someone is disputing
the data, the interpretation, the inference, or the priority. DIBB makes each layer challengeable on
its own terms. It is entirely reasonable to accept the data, accept the insight, and reject the bet.

A **bet**, in Kniberg's framing, is *an action to either test or capitalise on a strong belief* — and
critically it is **funded and time-bound**. Same instinct as Basecamp's betting table: naming it a
bet makes it acceptable to lose, and bounds the loss.

---

## The Bets Board

Once formulated, bets are **stack-ranked, resourced, and shared across the entire company.**

They're visualised on a **Bets Board** — a Kanban-style board at company level, with bets grouped
into **Now / Next / Later** columns.

Two properties that make this more than a roadmap:

1. **It's public across the company.** Everyone can see what the company is betting on, in priority
   order, and what it is explicitly *not* doing yet.
2. **Boards are interlinked across levels.** Different parts of the company maintain their own bet
   boards, each linked upward to the higher-level bets they serve. Alignment is visible as a
   structure rather than asserted in a slide.

**Now / Next / Later** instead of dates is the other deliberate choice: it communicates sequence and
priority honestly without manufacturing false precision about timing — and it can be reordered
without anyone "missing a deadline."

Different levels of the company then **synchronise and prioritise on different cadences** — leadership
less frequently, teams more frequently — rather than forcing the whole organisation onto one
planning heartbeat.

Bets ladder up to **north star goals** — the small number of company-level outcomes everything is
meant to serve.

---

## What to steal

1. **Separate data, insight, belief, and bet in writing.** One line each. This is the cheapest,
   highest-value idea in this file, and it works in a five-person company. It immediately clarifies
   whether an argument is about facts or about priorities.
2. **State the belief explicitly**, including the part the data doesn't prove. Every product decision
   involves a leap; writing it down means you can check later whether the leap was right — which is
   the only way a team gets better at making them.
3. **Now / Next / Later beats a dated roadmap** for anything beyond the current cycle. It's more
   honest and it survives reprioritisation without a credibility cost.
4. **Make the bet list public and ranked.** Most of the value of a roadmap is the *ordering* and the
   visible *exclusions*, not the contents.
5. **Different cadences at different levels.** Forcing leadership and teams onto the same planning
   rhythm makes one of them wrong.

## Sources

- [Henrik Kniberg — *Spotify Rhythm: how we get aligned* (slides, Agila Sverige 2016)](https://blog.crisp.se/2016/06/08/henrikkniberg/spotify-rhythm) *(primary-adjacent: internal coach)*
- [AvailAgility — Strategy Deployment and Spotify Rhythm](https://availagility.co.uk/2016/07/11/strategy-deployment-and-spotify-rhythm/)
- [Product Frameworks — DIBB](https://www.product-frameworks.com/DIBB.html)
- [Think Insights — DIBB Decision Framework](https://thinkinsights.net/data-ai/dibb-decision-framework)
