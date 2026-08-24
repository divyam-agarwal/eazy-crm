# 04 — Serverless & the limits of decomposition

Two famous architecture *reversals*, and the connection-pool problem that catches every
team that puts Lambda in front of a relational database. The theme: **AWS makes it trivially
easy to decompose a system, and the costs of decomposition are paid somewhere you weren't
looking** — in per-transition billing, in inter-component data transfer, in operational
surface area, or in connections to a database that can't take them.

---

## 4.1 — Prime Video VQA: the serverless design that stopped at 5% of target load

**Company:** Amazon Prime Video (Video Quality Analysis team). Published by Amazon itself,
which is what made it explosive.

### What happened

The Video Quality Analysis service inspects every stream Prime Video delivers, frame by frame,
looking for defects (block corruption, audio/video sync drift, etc.). The v1 architecture was
canonical AWS serverless:

- **AWS Step Functions** orchestrating the workflow,
- **Lambda** functions for media conversion and for each defect detector,
- **S3** as the hand-off medium for intermediate data — i.e. video frames — between components.

Two problems, both structural rather than incidental:

1. **Step Functions bills per state transition.** Analysing a stream means a state transition
   per frame-ish unit of work. Across thousands of concurrent streams, transitions — and therefore
   cost — scale with the *content*, not with the number of customers.
2. **S3 as an inter-component bus.** Every frame written by the converter and read by each
   detector is an S3 PUT plus N GETs, plus the data transfer. The system spent most of its
   time and money moving bytes between components that all wanted the same bytes.

The result was a **hard scaling limit at roughly 5% of the expected load** — not a cost
inefficiency they could grow out of, a wall.

### Why it was hard

- Nothing was *wrong*. Every component was well-built, and the architecture is the one AWS's own
  reference material recommends.
- The failure was **in the seams, not the parts**. You cannot find "we spend 90% of our budget on
  state transitions and S3 round-trips" by profiling any individual Lambda.
- The natural instinct — optimise each function — makes no difference, because the cost is in
  the orchestration and data movement between functions.

### How they fixed it

Collapse the distributed components into **a single process** running on **ECS on EC2**:
the media converter and the defect detectors now share **process memory**, so frames are
passed by reference instead of through S3. Orchestration logic became in-process control flow
instead of Step Functions state transitions.

Crucially, they did **not** go back to one big monolith. They kept horizontal scale by running
**groups of detectors distributed across separate ECS tasks**, so adding new detector types
doesn't hit a vertical scaling limit on one box.

**Result: ~90% reduction in operational cost**, and the scaling ceiling removed.

### Transferable lesson

1. **Decompose along the axis where data does *not* need to flow.** If two components exchange
   large payloads on every unit of work, they belong in the same process. Service boundaries
   should cut where the data flow is thin.
2. **Serverless orchestration prices scale with *work items*, not with *users*.** Step Functions
   at one transition per frame, or per row, or per line item, is a different product than Step
   Functions at one transition per user action. Model the transition count before you build.
3. **"Monolith vs microservices" is the wrong framing.** The fix here was *the right granularity*:
   one process for the tightly-coupled pipeline, many tasks for horizontal scale.
4. This was one team's specific workload (high-throughput media processing) — not a general
   verdict on serverless. The transferable part is the **method**: measure orchestration and
   data-transfer cost as a first-class architectural constraint.

---

## 4.2 — Segment: 140+ microservices, one per destination, and the autoscaling that couldn't work

**Company:** Segment (now Twilio Segment), AWS-primary. Their "Goodbye Microservices" post is
one of the most honest architecture retrospectives ever published.

### What happened

Segment fans out customer events to hundreds of third-party **destinations** (Google Analytics,
Salesforce, Mixpanel…). The original problem was real: with a shared queue, a *single* slow or
down destination API caused **head-of-line blocking**, delaying events for every other destination.

The fix was the obvious one: **a separate service and a separate queue per destination**. It
worked — failures were isolated. Then it compounded:

- **140+ services, 140+ repos, 140+ queues.** Each with a completely different load profile:
  some handled thousands of events/second, some handled a trickle.
- **Autoscaling became "more art than science."** One rule couldn't fit 140 wildly different
  services, and per-service tuning didn't scale as a human activity. Engineers manually
  intervened during unexpected spikes.
- **Shared libraries diverged.** Updating a shared library meant 140 risky changes, so engineers
  updated only the repos they touched. Versions drifted apart, and the drift compounded until
  "update the shared library" was effectively impossible.
- And the isolation was never actually complete: **true isolation would have required a queue
  per destination *per customer*** — 10,000+ services. The architecture couldn't reach its own goal.

### Why it was hard

- The architecture solved the stated problem correctly. The cost showed up as **operational
  overhead accumulating over three years**, which no design review catches.
- The failure metric was **developer throughput**, not latency or errors — the least-instrumented
  thing in most organisations.
- Reversing it meant giving up genuine fault isolation, which is hard to argue for.

### How they fixed it

- **Centrifuge**: a centralised event router replacing all the individual queues, handling
  retries, per-destination backpressure and failure isolation **as a platform concern** rather
  than as an architectural one.
- Destination code consolidated from 140+ repos into **one monorepo, one service**.
- **Traffic Recorder** (built on `yakbak`): record and replay real HTTP requests to destination
  APIs, so tests don't depend on live third parties. Test time went from potentially hours to
  milliseconds — this is what made a monorepo of 140 destinations testable at all.

**Result:** 46 shared-library improvements in the year after vs. 32 before (~44% more), and one
engineer can deploy the whole service in minutes.

They were explicit about what they gave up:
1. **Fault isolation is gone** — one destination bug can take down the service.
2. **In-memory caches hit less** — spread across 3,000+ processes rather than concentrated.
3. **Dependency coupling** — a library update now touches every destination at once.

### Transferable lesson

1. **The unit of isolation and the unit of deployment don't have to be the same.** Segment got
   isolation back from Centrifuge (a routing/queueing layer) rather than from process boundaries.
   Ask "what property do I actually need?" before reaching for a service boundary.
2. **If perfect isolation requires N×M services, your isolation strategy is wrong.** Push it into
   a data-plane mechanism instead.
3. **Count the multiplication.** Every new service multiplies: repos, CI pipelines, deploy configs,
   alerting rules, autoscaling policies, dashboards, on-call runbooks, dependency upgrades. At 140
   services that product is the whole team's capacity.
4. **Make the tests fast enough for the architecture you want.** Traffic Recorder was the enabling
   investment; without it the consolidation was impossible.

---

## 4.3 — Lambda concurrency vs. relational databases *(composite pattern)*

The most common self-inflicted serverless outage.

### What happens

Lambda scales to concurrency by creating **independent execution environments**. Each environment
initialises its own connection pool. That's fine at 10 concurrent invocations; at 1,000 it is a
denial-of-service attack on your own database.

The arithmetic that surprises teams: **100 concurrent Lambdas × 5 pooled connections = 500
database connections** — about 3× the capacity of a `db.r5.large`. And connections aren't the only
cost: Lambda environments churn, so the database also absorbs a continuous stream of
connect/disconnect handshakes (expensive in Postgres, which forks a backend process per connection).

The failure sequence:
1. Traffic spikes → Lambda scales out (this is the feature).
2. Connections exceed `max_connections` → the database refuses new connections.
3. **Healthy, non-Lambda traffic is refused too** — the admin session you need to diagnose it
   included.
4. Lambda retries (event source mappings retry aggressively), amplifying the storm.
5. Failed invocations pile into the DLQ, or worse, are silently dropped.

The mirror-image failure: **reserved concurrency set too low** silently throttles the function,
and because throttled async invocations are retried and eventually discarded, you get **silent
data loss** that shows up days later as missing records.

### Why it's hard

- Lambda's headline feature — scale without thinking about it — is exactly the failure mode.
  The elasticity boundary is at the *database*, and Lambda has no idea it exists.
- **Account-level concurrency is shared.** One function's spike throttles every other function in
  the account/region, so an unrelated batch job can take down your API.
- The signal is confusing: the database reports "too many connections", the Lambda reports
  timeouts, and the load balancer reports 5xx — three teams looking at three different symptoms.

### How teams fix it

- **RDS Proxy** between Lambda and Aurora/RDS. The proxy owns a small, stable set of real database
  connections and **multiplexes** many client connections onto them, absorbing the churn. It also
  shortens failover for clients.
- **Reserved concurrency as a deliberate throttle**: cap the function at a number the downstream
  can survive (e.g. 50), so a spike queues instead of stampeding. Reserved concurrency is a
  *backpressure mechanism*, not just a quota.
- **Provisioned concurrency** for latency-sensitive paths, sized to the p99 of concurrent demand.
- **Open connections outside the handler** so they're reused across invocations in the same
  environment; never open per-invocation.
- **SQS between the trigger and the Lambda**, so the queue absorbs bursts and you control drain
  rate via batch size and concurrency.
- Alarm on `ConcurrentExecutions` vs. the account limit, and on `Throttles` — both are usually
  unmonitored.

### Transferable lesson

**An elastic tier in front of a non-elastic tier requires an explicit throttle at the boundary.**
This generalises well beyond Lambda: any autoscaling compute layer in front of a fixed-capacity
database, third-party API, or legacy system needs a concurrency limiter, a pool, or a queue at the
seam — otherwise elasticity is just a faster way to overload the bottleneck.

---

## Sources

- [InfoQ — Prime Video Switched from Serverless to EC2 and ECS to Save Costs](https://www.infoq.com/news/2023/05/prime-ec2-ecs-saves-costs/) *(reporting on the now-relocated Prime Video Tech blog post)*
- [Prime Video Tech — Scaling up the Prime Video audio/video monitoring service and reducing costs by 90% (archived PDF)](https://www.wudsn.com/productions/www/site/news/2023/2023-05-08-microservices-01.pdf) *(primary, mirrored)*
- [Twilio Segment — Goodbye Microservices: From 100s of problem children to 1 superstar](https://www.twilio.com/en-us/blog/developers/best-practices/goodbye-microservices/) *(primary)*
- [InfoQ — To Microservices and Back Again: Why Segment Went Back to a Monolith](https://www.infoq.com/news/2018/07/segment-microservices/)
- [Sergey Lapin — Lessons learned on concurrency limits of AWS Lambda backed by RDS](https://svlapin.github.io/engineering/2021/04/05/aws-lambda-rds-scaling.html)
- [RDS Proxy in Production: Solving Connection Exhaustion and Sub-Minute Failover](https://timderzhavets.com/blog/rds-proxy-in-production-solving-connection-exhaustion/)
- [AWS Lambda — Function concurrency limits and throttling](https://www.bluematador.com/blog/why-aws-lambda-throttles-functions)
