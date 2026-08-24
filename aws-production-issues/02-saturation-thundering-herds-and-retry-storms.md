# 02 — Saturation, thundering herds & retry storms

Failures whose shape is set by **traffic**, not by a broken component. Nothing crashed;
something got *full*. These are the hardest incidents to reason about because every
individual component looks healthy and every individual retry looks correct.

---

## 2.1 — Slack, 4 January 2021: the first Monday back from holidays

**Company:** Slack (AWS-primary). Impact: multi-hour degradation/outage on the busiest
onboarding day of the year.

### What happened

Slack's traffic has a pathological annual shape: over the holidays everybody disconnects,
so client caches go **cold**; on the first working Monday everyone reconnects at once and
each client pulls down far more data than on a normal day. "The quietest time of the year
to one of the biggest days, overnight."

Slack's own services scaled. But **AWS Transit Gateways** — which route traffic between
Slack's VPCs and are documented as scaling transparently — **did not scale fast enough**.
The TGWs saturated and started dropping packets.

Then the feedback loop. Slack's autoscaling responded correctly by trying to add ~1,200
servers between 07:01 and 07:15. But the **provision-service**, which configures and
health-tests each new instance, has to talk to other internal systems and AWS APIs
**over the same degraded network**. Connections took longer, so more were held open
concurrently, so the service exhausted its Linux open-file limit and hit AWS API quotas.
Provisioning failed → capacity stayed short → autoscaling tried harder → more provisioning
attempts over the same saturated network.

And the monitoring failed at the worst moment: Slack's **dashboard/alerting service ran in
a different VPC from its own backing database**, so the dashboards depended on the TGWs
that were dropping packets. Engineers lost visibility precisely when they needed it.

AWS engineers, alerted by their own internal packet-drop monitoring, **manually scaled the
TGW capacity**, which resolved it.

### Why it was hard

- "Transparently scaling" managed services still have a **scaling rate**, and that rate is
  undocumented. Slack's demand curve was a step function; TGW's scaling was a ramp.
- The autoscaler was **working as designed** and making things worse. There was no bug to find.
- The remediation path (provision new capacity) ran over the failed resource (the network).
  This is the classic **recovery-depends-on-the-broken-thing** trap.
- Monitoring was in the blast radius.

### How they fixed it

*Immediate:* AWS manually increased TGW capacity.

*Durable:*
- **Co-locate the dashboard service with its database**, removing the TGW dependency from
  the observability path.
- **Load-test the provision-service regularly**, treating it as a production-critical
  service with its own capacity model (it had never been load-tested as the bottleneck it was).
- **Pre-warm**: ask AWS to pre-emptively scale TGWs at the end of each holiday season.
- Re-evaluate autoscaling and health-check configuration so that failed provisioning
  doesn't amplify.

### Transferable lesson

1. **Know the scaling *rate*, not just the scaling *ceiling*, of every managed service you
   depend on.** ELB/ALB pre-warming, TGW, Kinesis shards, DynamoDB auto-scaling, Lambda burst
   concurrency — all have ramp limits.
2. **Your control plane must not traverse your data plane.** Provisioning, deploys,
   dashboards, and alerting should have an independent path.
3. **Cold caches are a load multiplier.** Any event that invalidates client caches globally
   (holiday return, forced logout, app release, cache flush) produces traffic unlike anything
   in your load tests.
4. If your capacity response is "spin up more instances," measure **how long that takes when
   the network is degraded**, not when it's healthy.

---

## 2.2 — Canva, 12 November 2024: 1.5 million requests per second from one JS file

**Company:** Canva (AWS-primary; Cloudflare as CDN). Impact: canva.com down ~09:08–10:00 UTC.

### What happened

Three unrelated things lined up.

1. **CDN routing.** A stale Cloudflare traffic-management rule pushed IPv6 traffic over a
   lossy public path from Singapore to Ashburn instead of the private backbone. P90
   time-to-first-byte rose by **over 1,700%**. One JavaScript chunk for the editor's object
   panel took **20 minutes** to fetch.
2. **Thundering herd.** Cloudflare's cache-stream mechanism *coalesces* concurrent requests
   for the same asset — correct behaviour, normally. It accumulated **270,000+ pending
   requests** for that one chunk. When the fetch finally succeeded at 09:07, all 270,000
   were released **simultaneously**. Every one of those newly-loaded editors then called
   the API. Peak: **1.5 million requests/second** — roughly 3× normal peak.
3. **A latent performance regression.** Canva had recently introduced a bug in a telemetry
   library causing certain metrics to be **re-registered under a lock on every recorded
   value**. On Netty's event-loop model, that lock contention drastically cut the throughput
   a single API Gateway task could sustain. So the gateway was already running at a fraction
   of its believed capacity when the herd arrived.

Cascade: overwhelmed tasks couldn't drain their backlog → load balancers opened *more*
connections to them → memory pressure climbed → the **Linux OOM killer terminated all
running containers within 2 minutes**, taking out the entire API Gateway fleet at once.

### Why it was hard

- None of the three causes is an outage on its own. This is a textbook **Swiss-cheese**
  incident.
- **Request coalescing is a protection mechanism** that here became the attack. Deduplicating
  270k requests is exactly what you want — releasing all 270k responses in the same
  millisecond is not.
- The capacity regression was invisible: throughput per task had degraded, but nothing
  alarmed on it because traffic was normal.
- OOM-killing *all* containers simultaneously turned a partial degradation into a total one —
  a homogeneous fleet fails homogeneously.

### How they fixed it

*Immediate:* at 09:29 a **Cloudflare firewall rule blocking all traffic** at the CDN edge —
i.e. deliberately shed 100% of load to let the fleet come up cold. canva.com redirected to
the status page. Then a **staged restore**: Australian users first, under strict rate limits,
widening gradually. Fully restored by 10:00.

*Durable:*
- Higher baseline API Gateway task count and more memory headroom.
- **Explicit load-shedding rules** so the gateway rejects excess cheaply instead of dying.
- Telemetry lock bug fixed, with test coverage for metric-registration cost.
- **Page-load-completion events as a canary** — a signal that catches "the app is broken for
  users" even when every backend metric is green.
- Asset-fetch timeouts so no client waits 20 minutes for a chunk.

### Transferable lesson

1. **Load shedding is a feature you must build before you need it.** The choice under
   saturation is "reject 40% cheaply" or "die completely." Default is die completely.
2. **The ability to turn everything off at the edge is a recovery tool.** Canva recovered by
   blocking 100% of traffic and re-admitting it in slices. If you can't do that, you can't
   restart under load.
3. **Beware coalescing/batching releases.** Anything that queues N requests and releases them
   together needs jittered release.
4. **Measure per-task throughput capacity continuously**, not just during load tests — a
   regression in capacity is an outage waiting for a traffic spike.
5. **Canary on user-visible completion**, not just on server health.

---

## 2.3 — DoorDash, May 2022: the circuit breaker that caused the outage

**Company:** DoorDash (AWS-primary, ~1,000 microservices on ~2,000 Kubernetes nodes).
Impact: ~3-hour outage.

### What happened

Routine database maintenance raised read/write latency slightly. Upstream services saw
slower responses and started timing out. Clients interpreted the slow responses as failures
and **retried** — each retry adding load to an already-slow service, so latency rose
further, so more retries. A **retry storm**.

The rising error rate then tripped a **misconfigured circuit breaker**, which cut traffic
in a way that prevented recovery rather than enabling it. Services depending on payments
began timing out and failing, and the failure propagated across the mesh.

### Why it was hard

- **The trigger was a normal maintenance window** — a latency blip, not an error.
- Retries are individually correct and collectively lethal. A service with 3 retries at each
  of 3 hops amplifies one user request into up to 27 backend calls at exactly the moment the
  backend can least afford it.
- The circuit breaker — the mechanism designed to *stop* cascades — was misconfigured, so it
  turned a degradation into an outage. Resilience mechanisms are code, and code has bugs.

### How they fixed it

DoorDash's response was structural: move retry policy, timeouts, circuit breaking, and
load balancing **out of application code and into a service mesh** (Envoy sidecars), so that
these policies are (a) uniform, (b) centrally observable, and (c) changeable at runtime
without redeploying 1,000 services. They also adopted **asynchronous production via Kafka** —
validate the payload, put it in the producer buffer, respond immediately — decoupling
request latency from downstream availability.

### Transferable lesson

1. **Retry budgets, not retry counts.** Cap retries as a *percentage of total requests*
   (e.g. ≤10%), so retries can't multiply under widespread failure. Always add
   **exponential backoff with full jitter**.
2. **Circuit breakers must be tested in the failure mode they exist for.** Untested
   resilience config is a liability with a false sense of safety.
3. **Distinguish "slow" from "failed."** Timeouts that treat slowness as failure convert
   latency incidents into availability incidents.
4. Centralising cross-cutting network policy (mesh, or a shared client library with enforced
   defaults) beats per-service configuration once you pass a few dozen services.

---

## 2.4 — Coinbase, 2021: demand spikes you don't control

**Company:** Coinbase (AWS-primary). Multiple published postmortems in 2021.

### What happened

**19 May 2021:** ETH dropped ~20% and BTC ~25% in a short window. Every user opened the app
at once. The spike hit a **max-connections threshold in the Nginx routers** (raised manually
during the incident). Simultaneously the **GraphQL service autoscaled too slowly**, producing
timeouts and latency.

**27 October 2021:** intermittent outages across two windows. A traffic spike overloaded
payment processing; an **automated maintenance event already in progress slowed the ability
to scale the cluster up** to meet demand. Resolved by deploying caching changes and scaling
up the rewards database with additional replicas.

### Why it was hard

- **Demand is exogenous and correlated with bad news.** You cannot smooth it, predict it, or
  negotiate with it — and it arrives precisely when users most need the service to work.
- Autoscaling responds in minutes; a crypto crash arrives in seconds. Reactive autoscaling
  is structurally too slow for step-function demand.
- The October incident shows the second-order problem: **automated maintenance and emergency
  scaling contend for the same control plane.** Your recovery lever was already busy.

### How they fixed it

Coinbase's published approach: aggressively **pre-scale ahead of volatility** rather than
react to it, load-test to multiples of historical peak, cache read-heavy paths (the rewards
data), add read replicas, and raise/audit static limits (Nginx connection ceilings) that
silently cap throughput long before compute does.

### Transferable lesson

1. **For spiky exogenous demand, provision statically for the peak you fear** and treat
   autoscaling as a cost optimisation, not an availability mechanism.
2. **Audit static config ceilings.** `worker_connections`, DB `max_connections`, thread pools,
   file descriptors, ALB target-group limits — these bind before CPU does and never show up
   on a CPU dashboard.
3. **Maintenance automation needs a global "stand down" switch** that an incident commander
   can hit, and it must be interlocked with emergency scaling.

---

## Cross-case observations

The four incidents share one arithmetic:

```
offered_load  >  service_capacity
  ⇒ latency ↑
    ⇒ retries ↑  (client-side)  and  connections held ↑ (server-side)
      ⇒ offered_load ↑↑
```

Once you enter this loop, **removing the original trigger does not exit it** — that's what
makes these failures *metastable*. Exit requires one of:

- **shed load** (Canva: block everything at the CDN, then re-admit in slices),
- **add capacity faster than the loop grows** (Slack: AWS manually scaling TGWs),
- **break the amplifier** (DoorDash: retry budgets, mesh-enforced backoff).

Design so that at least one of those three levers exists *and can be pulled in under a
minute*, without a deploy.

## Sources

- [Slack Engineering — Slack's Outage on January 4th 2021](https://slack.engineering/slacks-outage-on-january-4th-2021/) *(primary)*
- [Surfing Complexity — Slack's Jan 2021 outage: a tale of saturation](https://surfingcomplexity.blog/2021/02/08/slacks-jan-2021-outage-a-tale-of-saturation/)
- [Canva Engineering — Canva incident report: API Gateway outage](https://www.canva.dev/blog/engineering/canva-incident-report-api-gateway-outage/) *(primary)*
- [Surfing Complexity — The Canva outage: another tale of saturation and resilience](https://surfingcomplexity.blog/2024/12/21/the-canva-outage-another-tale-of-saturation-and-resilience/)
- [DoorDash Engineering — Inside DoorDash's Service Mesh Journey, Part 1](https://careersatdoordash.com/blog/inside-doordashs-service-mesh-journey-part-1-migration-at-scale/) *(primary)*
- [ByteByteGo — How DoorDash Moved to a Service Mesh to Handle 80M Requests/Second](https://blog.bytebytego.com/p/how-doordash-moved-to-a-service-mesh)
- [Coinbase — Incident Post Mortem: May 19, 2021](https://www.coinbase.com/blog/incident-post-mortem-may-19-2021) *(primary)*
- [Coinbase — Incident Post Mortem: October 27, 2021](https://www.coinbase.com/blog/incident-post-mortem-october-27-2021) *(primary)*
- [Coinbase — How we're scaling our platform for spikes in customer demand](https://www.coinbase.com/blog/how-were-scaling-our-platform-for-spikes-in-customer-demand) *(primary)*
