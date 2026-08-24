# 04 — Cost & viral growth

For a big company a cost blowout is a budget variance. For a small one it is an **existential
event with a 30-day fuse**, because the bill arrives after the money is already spent, and there
is no finance team watching in between.

---

## Cara, mid-2024 — $2,000/month to ~$100,000 in one week

**Company:** Cara (artist portfolio and social platform; tiny team, founder-led, later a benefit
corporation). Infrastructure: Vercel.

### What happened

Cara had been running deliberately quiet. The founder, Jingna Zhang, later wrote that she
*"avoided promoting Cara most of this entire past year as I know we couldn't afford the
challenges that would come with scale."* Baseline spend was around **$2,000/month** for hosting
and development services.

Then the platform went viral — driven by artists migrating away from platforms training AI on
their work. The concrete numbers:

- **1 million users within the first month** after going viral.
- A bill of roughly **$100,000 from Vercel for a single week** of that traffic.
- Post-surge steady state: **4 million posts and 5 million images**, growing by hundreds of
  thousands weekly.
- Projected **~$660,000/year for hosting alone**, approaching **$1M/year** once spam moderation
  and legal costs were included.

Note what did *not* happen: no bug, no attack, no misconfiguration, no outage. **The system worked
exactly as designed, and that was the problem.** Serverless platforms are built to absorb a
traffic spike by scaling — and to bill for it linearly. The architecture converted a growth event
directly into an invoice, with no back-pressure and no ceiling in between.

### Why it was hard

- **The scaling was the product feature.** Cara stayed *up* through 1M users. A capacity-limited
  architecture would have fallen over instead — arguably the cheaper failure.
- **The feedback loop is 30 days long.** By the time the invoice makes the cost real, the money is
  spent. Contrast an outage, which pages you in 60 seconds.
- **For a bootstrapped company, the failure mode is insolvency, not degradation.** There is no
  "scale it back next quarter."
- **Image-heavy social platforms are the worst case** for per-request/per-GB pricing: every
  page view fans out into many optimised-image requests and a large amount of egress.

### How they responded

Publicly stated: optimising code for cost reduction and scale, then pursuing subscriptions,
partnerships, and fundraising from aligned investors while remaining a benefit corporation. The
founder published the actual finances — which is why this case is usable at all.

### Transferable lessons

1. **Set a hard spending ceiling before you need one.** Budget alerts at 50/75/100% of expected
   monthly spend, routed to a phone, not an inbox. On platforms that support it, a genuine spend
   cap. Decide *in advance* whether you would rather be down or bankrupt — for most small SaaS the
   honest answer is "down", and almost nobody configures for that.
2. **Rate-limit before you scale.** A per-IP and per-account request limit at the edge is the
   cheapest possible cost control, and it doubles as abuse protection. Unbounded autoscaling with
   no rate limit is an open invitation — to virality *and* to a denial-of-wallet attack.
3. **Know your cost per user and per request.** Cara's $660k/year over ~1M users is roughly
   **$0.66/user/year** — a perfectly viable number *if* you have revenue per user, and fatal if you
   don't. **Unit cost is the metric; total spend is the symptom.**
4. **Serve static and media assets from something that prices like storage, not like compute.**
   Object storage plus a CDN with committed bandwidth pricing is often an order of magnitude
   cheaper than per-invocation image optimisation at volume. For image-heavy products this single
   decision dominates the bill.
5. **Model your worst-case month before launch, not after.** One line in your design doc: *"at 100×
   current traffic, this costs $X."* If X is unaffordable, you have an architecture problem today,
   not a scaling problem later.

---

## The broader pattern: denial-of-wallet *(composite)*

Cara is the well-documented case, but the pattern is endemic enough that there's a site cataloguing
it ([ServerlessHorrors](https://serverlesshorrors.com/)). Recurring public examples include a
**$72,000 bill from testing Firebase and Cloud Run**, a **single-day $100,000 Firebase bill** for a
WebGL games site hit by a DoS, and a startup's Firebase spend jumping from **$25 to $1,750/month**
without a deliberate change.

**The common shape:**

```
usage-based pricing  +  no hard cap  +  autoscaling
  ⇒ any traffic event (viral, bot, DoS, infinite loop, retry storm)
    converts directly into unbounded spend, with a ~30-day detection lag
```

**Where it bites hardest**, in rough order:

| Cost driver | Typical trigger |
|---|---|
| Per-invocation compute (Lambda/Vercel/Cloud Run) | traffic spike; a recursive trigger (a function writing to the bucket that invokes it) |
| Image optimisation / transformation per request | image-heavy pages, no cache, unbounded variants |
| Egress bandwidth | media serving; hotlinking; a scraper |
| Per-read/write database ops (Firestore/DynamoDB) | an unbounded query in a render loop; a client-side listener bug |
| Log ingestion | DEBUG left on in production; logging full payloads |
| AI/LLM API tokens | an unmetered user-facing feature; a retry loop on a long prompt |

**The controls, in order of return on effort:**

1. **Billing alerts wired to a pager**, at several thresholds — the single highest-value item.
2. **Rate limits at the edge**, per IP and per account, on every public endpoint.
3. **Cache aggressively** — a CDN cache hit costs a rounding error compared to an origin invocation.
4. **Quotas on anything a user can trigger repeatedly** — uploads, exports, AI calls, report generation.
5. **A named person who looks at the bill weekly.** Same lesson as GitLab's backups: an unowned
   control decays to zero.
6. **Anomaly detection on daily spend**, so a 10× day pages you on day one rather than day thirty.

## Sources

- [Cara — Finances & the Future of Cara](https://blog.cara.app/blog/finances-and-future-of-cara) *(primary)*
- [ServerlessHorrors](https://serverlesshorrors.com/) — catalogue of surprise-bill incidents
- [HN — Burnt $72k testing Firebase and Cloud Run and almost went bankrupt](https://news.ycombinator.com/item?id=25372336)
- [HN — A startup's Firebase bill suddenly increased from $25 to $1750 per month](https://news.ycombinator.com/item?id=14356409)
