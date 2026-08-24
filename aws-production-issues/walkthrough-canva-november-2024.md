# Walkthrough — Canva API Gateway outage, 12 November 2024

**Three independent, individually-harmless conditions aligned and took canva.com down for 52
minutes.** The clearest published example of a Swiss-cheese failure, and of a canary system that
reported success while the deployment was silently failing.

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** in Canva's public incident report (Brendan Humphreys, 20 Dec 2024), including the quoted Cloudflare statement |
| **[INFERRED]** | Follows from documented facts, not stated |
| **[ASSUMPTION]** | Not in the record — flagged |

Canva's report is exceptionally detailed. It is also **their first publicly shared incident
report** — internally they have required one for every high- or medium-severity incident **since
2017**. Gaps in [§9](#9-what-the-record-does-not-say). All times **UTC**.

---

## 1. The system

**Canva's editor is a single-page application, deployed multiple times a day** through a continuous
deployment pipeline. Each deployment bundles and publishes **over 100 new static assets** —
JavaScript files essential for features like the object panel — to an **AWS S3 bucket**.

**Asset delivery is tiered through Cloudflare:**
1. A client requests an asset; Cloudflare checks the **local data centre** cache.
2. On a miss, the local DC requests it from **regional or upper-tier** data centres.
3. On a further miss, the upper tier fetches from **Canva's origin S3 bucket**.

**Once assets load, the editor's JavaScript calls the API Gateway** — the entry point for all API
requests, handling authentication, authorisation, rate limiting and other cross-cutting concerns.
It is built on **Netty** (the networking library powering Canva's standard Java microservice stack)
and runs as an **autoscaled group of tasks on Amazon ECS**.

Two properties of that stack become critical:

- **Netty uses an event loop model**: code on event loop threads **must not perform blocking
  operations.** A blocked event loop thread stops serving *all* connections assigned to it.
- **ECS autoscaling reacts in minutes**, and health-checks tasks before routing traffic to them.

---

## 2. Slice one: a stale Cloudflare routing rule

**08:47.** A new version of Canva's editor page is deployed. Clients begin fetching the new static
assets. Routine — this happens multiple times a day.

**At the same time**, the network path between Cloudflare's **Singapore (SIN)** and **Ashburn
(IAD)** locations develops latency problems.

Cloudflare's own explanation, quoted in Canva's report:

> *"The network issue was caused by a stale rule in Cloudflare's traffic management system that was
> sending user IPv6 traffic over public transit between Ashburn and Singapore instead of its default
> route over the private backbone. This rule was preventing Cloudflare's automation from taking
> preventative actions from routing around the packet loss because the rule prevented alternative
> paths from being considered.
> The packet loss over this path reached about 66% at peak during the impact window."*

Two distinct faults in one paragraph:

1. IPv6 traffic was on **public transit instead of the private backbone**.
2. **The rule blocked Cloudflare's own automation from routing around the loss**, because alternative
   paths weren't considered.

Result: **p90 time-to-first-byte increased by over 1,700%**, with **~66% packet loss** at peak.

**One particular asset took 20 minutes to fetch** — the JavaScript chunk responsible for the
editor's **object panel**. For many users, particularly **in Asia**, the object panel sat in a
perpetual loading state.

> Slice one alone = a degraded panel for some Asian users. Bad; not an outage.

---

## 3. The canary that couldn't see the failure

This is the most transferable paragraph in Canva's report:

> *"Normally, an increase in errors would cause our canary system to abort a deployment. However, in
> this case, no errors were recorded because requests didn't complete."*

The automated canary watches **JavaScript error rate** as its primary indicator. But a request that
**hangs for 20 minutes never errors**. It never completes, so it never fails, so it never increments
the metric.

**The deployment proceeded to completion, reported healthy, while being catastrophically broken for
a large population of users.**

> **Error-rate monitoring cannot detect the absence of completion.** Anything that measures
> *failures* is blind to *hangs* — and a hang is worse, because it holds resources on both ends.
> Same shape as GitLab's silently-rejected backup alerts: the signal that mattered was the *absence
> of success*, and nothing was watching for it.

---

## 4. Slice two: request coalescing builds the herd

Cloudflare uses **cache streams** (Concurrent Streaming Acceleration), which **consolidate multiple
user requests for the same asset into a single origin request**, progressively serving the response
body as it becomes available.

This is a *good* feature. Normally it protects the origin from a stampede.

Here, over the ~20 minutes the fetch was stalled, **over 270,000 user requests accumulated on the
same cache stream**, building a backlog concentrated in Southeast Asia.

**09:07.** The asset fetch completes. **All 270,000+ pending requests complete simultaneously.**

Every one of those clients now has the JavaScript, resumes loading the editor, loads the previously
blocked object panel — **simultaneously, across all waiting devices** — and each one calls the API.

**Peak: 1.5 million requests per second to the API Gateway. Three times typical peak load.**

> **A protection mechanism became the weapon.** Deduplicating 270,000 requests is exactly right;
> releasing 270,000 responses in the same millisecond is not. Anything that queues N requests and
> releases them together needs a **jittered release**.

---

## 5. Slice three: a telemetry lock on the event loop

Before the incident, Canva had changed their telemetry library code and **inadvertently introduced a
performance regression**:

> *"The change caused certain metrics to be re-registered each time a new value was recorded. This
> re-registration occurred under a lock within a third-party library."*

On Netty's event loop model, **blocking operations on event loop threads are forbidden** — and a lock
acquisition on every metric write is exactly that. Under load, **lock contention significantly
reduced the maximum throughput a single API Gateway task could handle.**

So when the herd arrived, the fleet was running at **a fraction of its believed capacity**, and
nobody knew, because traffic had been normal and no alarm watches "throughput per task is lower than
it used to be."

### The detail that stings

> *"Although the issue had already been identified and a fix had entered our release process the day
> of the incident, we'd underestimated the impact of the bug and didn't expedite deploying the fix.
> This meant it wasn't deployed before the incident occurred."*

**They knew. The fix was in the pipeline. They graded it as low priority — reasonably, on the
evidence they had — and it missed by a day.**

> **A performance regression is a latent availability incident waiting for a traffic event.**
> A throughput regression with no traffic spike is invisible; with one, it's the difference between
> degradation and total failure. Grade capacity regressions by *what they cost under peak*, not by
> what they cost today.

---

## 6. The cascade — from spike to total outage in two minutes

**09:08.** canva.com becomes unavailable. The mechanism, step by step:

1. The traffic spike **plus the throughput regression** causes a rapid **build-up of backlogged
   requests**.
2. Because tasks are failing to handle requests in a timely manner, **the load balancers start
   opening new connections to the already-overloaded tasks.** *(The load balancer's response to
   slowness is more concurrency — which is precisely wrong here.)*
3. More connections → **further increased memory pressure**, specifically **off-heap** memory growth.
4. **The Linux Out Of Memory Killer terminates all of the running containers in the first 2
   minutes**, causing a **cascading failure across all API Gateway tasks**.
5. **This outpaces autoscaling capability.** All requests to canva.com fail.

**Off-heap memory is why the JVM couldn't save itself** — this isn't a heap exhaustion the GC could
fight, it's native memory from connection buffers. **[INFERRED]** from "growth of off-heap memory"
plus Netty's use of direct byte buffers.

**A homogeneous fleet fails homogeneously.** Every task had the same memory limit, the same
regression, and the same traffic share, so the OOM killer took them all inside two minutes rather
than degrading progressively.

---

## 7. The recovery

### What didn't work

**Autoscaling made it worse.** As tasks were terminated, autoscaling policies brought up replacements.
But:

> *"These new tasks became overwhelmed by the ongoing traffic spike as soon as they were marked
> healthy and were promptly terminated."*

A new task starts cold, is marked healthy, receives a share of 1.5M rps, and dies. Autoscaling had
become a machine for feeding fresh victims into the fire.

**Manual scaling also failed.** They **significantly increased the desired task count manually.** It
did not help — for the same reason. **You cannot start a service under a load that kills it during
startup.**

### What worked: turn everything off, then let it back in slowly

**09:29** — Canva adds a **temporary Cloudflare firewall rule blocking all traffic at the CDN.**

Zero traffic reaches the API Gateway. New tasks can now start and stabilise without being
immediately overwhelmed. They then **redirect canva.com to the status page** so users see an
explanation rather than a raw block.

**Gradual restoration.** Once healthy task count stabilised at a comfortable level, they
**incrementally restored traffic — starting with Australian users under strict rate limits**, then
widening as stability held.

**~10:00** — canva.com available again. **Total: ~52 minutes.**

> **The recovery lever was the ability to shed 100% of load at the edge and re-admit it in slices.**
> Identical to AWS throttling DWFM work in the [October 2025 walkthrough](walkthrough-aws-us-east-1-october-2025.md).
> If you cannot turn all traffic off in under a minute without a deploy, **you cannot restart a
> saturated service.**
>
> Starting with **Australia** is a nice detail: Canva's home market, smallest relevant traffic slice
> at that hour, and the population with the most goodwill. **[INFERRED]** on the reasoning — the
> report states the fact, not the rationale.

---

## 8. Action items

Canva grouped their remediations into five areas. Reproduced in substance:

**Incident response process**
- **Runbook for traffic blocking and restoration** — granular reroute, block, and progressive
  scale-up, so the mitigation that worked is repeatable rather than improvised.
- **User communication** — a more informative error page when canva.com is unavailable.

**API Gateway resilience**
- **Task configuration** — increased baseline task count *and* per-task memory, for headroom under
  abnormal conditions.
- **Load shedding** — additional rules in the API Gateway targeting this traffic pattern.
- **Load testing** — regular load testing of the API Gateway.

**The telemetry bug**
- **Patch deployed** for the thread-locking bug.
- **Library hardening** — the telemetry microbenchmark harness now includes **multithreaded tests for
  the code path that caused contention.** *(Not just fix the bug — fix the test suite that couldn't
  have caught it.)*

**Detecting page deployment failures**
- **Page load completion events as a canary indicator** — currently JS error rate is primary; page
  load events become a **secondary indicator**. This directly fixes [§3](#3-the-canary-that-couldnt-see-the-failure).
- **Increase canary duration** — experimenting with an additional rollout stage for more detection time.
- **Asset fetching timeouts** — so user requests don't wait excessively, reducing the impact of
  future consolidated requests. This caps the herd size at the source.

**Cloudflare** — working closely with them to understand the system interactions.

---

## 9. What the record does not say

- **The detailed timeline table.** The report contains a "Timeline of events" section rendered as an
  image, so the intermediate timestamps between 09:08 and 09:29 aren't in the extractable text.
  The anchors given — 08:47, 09:07, 09:08, 09:29, ~10:00 — are documented.
- **How many users were affected**, or revenue impact.
- **How long the telemetry regression had been deployed**, or how much throughput it cost. Only
  "significantly reduced."
- **Task counts** — before, after, or the manual number attempted.
- **Why the stale Cloudflare rule existed** or how long it had been in place.
- **Whether the 20-minute asset was uniquely unlucky** or whether other assets were also slow.
  **[INFERRED]** that others were affected but only this one blocked a critical render path.
- **Detection time.** The report doesn't state when Canva was paged; the 21 minutes between 09:08 and
  the 09:29 mitigation covers detect, diagnose, decide and act. **[ASSUMPTION]** — that is fast, and
  suggests good alerting on the availability signal even though the canary missed the deployment.

---

## 10. Ten lessons

1. **Error-rate monitoring is blind to hangs.** Canary on **completion**, not just on failures. A
   request that never finishes never errors.
2. **Canary on user-visible success.** "Page load completed" catches classes of failure that every
   backend metric reports as green.
3. **Request coalescing needs jittered release.** Anything that batches N and releases them together
   builds a thundering herd by design.
4. **Load shedding must exist before you need it.** Under saturation the only choices are "reject
   cheaply" or "die completely," and the default is die completely.
5. **An edge kill switch is a recovery tool, not just a safety feature.** Block 100%, restart, re-admit
   in slices. Without it you cannot recover a saturated service.
6. **Autoscaling is not a saturation remedy.** New capacity that dies on arrival is worse than no new
   capacity — it consumes control-plane capacity and masks the real problem.
7. **Grade performance regressions by their cost at peak, not today.** Canva had the fix in the
   pipeline and under-prioritised it by one day.
8. **Never block on an event loop thread.** And test for it — Canva's fix included adding
   **multithreaded** benchmarks for the contended path.
9. **Homogeneous fleets fail homogeneously.** Identical limits and identical load share meant every
   task died within two minutes.
10. **Timeouts everywhere, including on asset fetches.** A 20-minute client-side fetch should have been
    impossible.

---

## Sources

- [Canva Engineering — Canva incident report: API Gateway outage](https://www.canva.dev/blog/engineering/canva-incident-report-api-gateway-outage/) *(primary; Brendan Humphreys, 20 Dec 2024, with contributions from Ben Mitchell, Sergey Tselovalnikov and Steve Strugnell — includes the quoted Cloudflare statement)*
- [Surfing Complexity — The Canva outage: another tale of saturation and resilience](https://surfingcomplexity.blog/2024/12/21/the-canva-outage-another-tale-of-saturation-and-resilience/)
- [Cloudflare — Concurrent Streaming Acceleration](https://blog.cloudflare.com/introducing-concurrent-streaming-acceleration/)

**Related in this library:** [02 — Saturation, thundering herds & retry storms](02-saturation-thundering-herds-and-retry-storms.md) ·
[walkthrough — AWS us-east-1, October 2025](walkthrough-aws-us-east-1-october-2025.md) ·
[09 — Patterns & checklist](09-patterns-and-checklist.md)
