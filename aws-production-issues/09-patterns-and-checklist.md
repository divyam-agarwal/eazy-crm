# 09 — Cross-cutting patterns & a pre-mortem checklist

What twenty-odd incidents across AWS, Slack, Canva, Notion, Figma, Monzo, DoorDash, Coinbase,
Datadog, Atlassian, Segment, Prime Video, Honeycomb, Adevinta and Capital One have in common.

---

## The twelve patterns

### 1. Metastable failure: removing the trigger does not end the outage

Once offered load exceeds capacity, retries and reconnects generate load *derived from the
failure itself*. The system now sustains its own outage. Seen in: DynamoDB→EC2 DWFM congestive
collapse (Oct 2025), Slack's provision-service loop, Canva's OOM cascade, DoorDash's retry storm,
Datadog's mass node replacement.

**Exit requires one of three levers, and you must be able to pull one in under a minute without a
deploy:** shed load, add capacity faster than the loop grows, or break the amplifier.

### 2. The control plane fails while the data plane is healthy

Running instances kept running through the Oct 2025 AWS event; *launching new ones* failed for
12 hours. Your existing capacity is usually fine. What you lose is the ability to change anything.

**Implication:** static stability beats dynamic reaction. Pre-provisioned capacity that needs no
control-plane call to keep serving is worth more during an incident than an autoscaling group that
can't scale.

### 3. Recovery is the long pole, and it's untested

Kinesis: fixing it took minutes, restarting the fleet took 17 hours. Atlassian: detecting took
minutes, restoring took 14 days. S3 2017: the subsystems' restart time had silently regressed for
years because nobody had restarted them at that scale.

**Test the recovery path, not just the failure path.** "Dependency returns after 3 hours and 10,000
clients reconnect at once" is the scenario nobody rehearses and everybody eventually experiences.

### 4. Correlated failure through non-architectural dependencies

Datadog's five regions across three clouds shared no network — and shared **one package-update
channel with a 06:00 UTC window**. Slack's *dashboards* depended on the Transit Gateways that were
failing. AWS's own Service Health Dashboard was hosted on the S3 that was down.

**Draw the dependency diagram that includes** package repos, base AMIs, container base images,
CI/CD, DNS, TLS issuance, IAM/SSO, feature flags, NTP, and your observability stack. Then check
whether your monitoring can survive what it monitors.

### 5. Aggregate metrics hide per-partition saturation

DynamoDB table capacity at 8% while a single partition throttles. Kafka topic throughput fine
while one broker is network-shaped. Cluster CPU healthy while one subnet is out of IPs.

**Always graph the hottest unit, not the average** — hottest partition, hottest shard, hottest
node, fullest subnet, tightest quota.

### 6. Limits you can't buy your way past

RDS single-instance IOPS (Figma). Per-partition WCU (DynamoDB). OS thread count (Kinesis).
Subnet IPs (Adevinta). EC2 network baseline allowance (Honeycomb). Quorum arithmetic (Monzo).

For each of these, "spend more money" is not a mitigation. **Know where you are against every hard
limit, with an alarm at 70%.**

### 7. Baseline vs. burst — the resource that passes your load test and fails on Tuesday

Credit-bucket resources look fine in a 10-minute test and throttle under sustained load: EC2
network allowances, EBS `gp2`/`gp3` IOPS, T-family CPU credits, TGW scaling rate, Lambda burst
concurrency, DynamoDB burst capacity.

**Load-test for duration, not just for peak.**

### 8. Scaling *rate* matters as much as scaling *ceiling*

Slack's TGWs would have handled the traffic — eventually. Coinbase's GraphQL autoscaler would have
reached the right size — after the crash was over. Demand arrives as a step function; autoscaling
responds as a ramp.

**Reactive autoscaling is a cost optimisation, not an availability mechanism.** For known spikes
(market crashes, holiday returns, product launches, sales), pre-scale.

### 9. Redundancy and isolation mechanisms create their own failure modes

Independent DNS Enactors made the race possible. Cloudflare's request coalescing built the
thundering herd. DoorDash's circuit breaker turned a degradation into an outage. Segment's
per-destination isolation produced an unmanageable 140-service estate. RDS Proxy held a stale
health view and failed to reroute reads.

**Every resilience mechanism is code, and code has bugs.** Test each one in the exact failure mode
it exists for, and ask what it does when *it* is wrong.

### 10. Guardrails belong in the system, not in the operator

The durable fixes across this library are structural, never "be more careful":

| Incident | Procedural fix (rejected) | Structural fix (adopted) |
|---|---|---|
| S3 2017 | "double-check the command" | tool refuses to go below minimum capacity |
| Atlassian 2022 | "verify the ID list" | soft deletes; type-safe IDs; dry-run |
| AWS DNS 2025 | "check before applying" | re-validate at write; velocity limits |
| Multi-tenant leaks | "remember `WHERE tenant_id`" | RLS + `@TenantId` + build-time ArchUnit rule |
| Monzo 2019 | "read the docs" | document every setting; alert on `not found` |

### 11. Correctness changes and data movement should never ship together

Figma's central insight: deploy **logical** shards as views (reversible, no data moved), prove the
application correct in production, and only then perform **physical** splits. First physical shard
split cost ~10 seconds of partial availability after nine months of de-risking.

Notion's equivalent: an **audit log + replayable catch-up script** rather than dual-writes, so
every step is restartable and verifiable.

### 12. The bill is an incident with a 30-day detector

Data movement across boundaries you can't see on a diagram (NAT, cross-AZ, S3 hand-offs), and
billing units that track work items rather than users (Step Functions transitions, CloudWatch Logs
GB, S3 requests). Prime Video's cost problem *was* a scaling problem — it stopped them at 5% of
target load.

**Track unit cost** (dollars per order, per tenant, per 1,000 calls), not total spend.

---

## Pre-mortem checklist

Run this against any system you operate on AWS. Each line maps to at least one real incident above.

### Limits & saturation
- [ ] Every hard limit identified, with an alarm at 70%: subnet free IPs, DB `max_connections`,
      per-partition WCU/RCU, thread/FD limits, ENA allowances, Lambda account concurrency,
      EC2/API rate limits, `age(datfrozenxid)`.
- [ ] Hottest-partition metrics exist (not just averages) for every partitioned system.
- [ ] Static config ceilings audited: nginx `worker_connections`, pool sizes, ALB target limits.
- [ ] Load tests run long enough to exhaust burst credits.

### Overload behaviour
- [ ] **Load shedding exists and has been exercised** — the system rejects excess cheaply rather
      than dying.
- [ ] Retry budgets (cap retries as a % of traffic), exponential backoff **with full jitter**,
      at every hop.
- [ ] Timeouts are shorter going down the stack than coming up, so slowness doesn't become failure.
- [ ] Circuit breakers tested in the failure mode they exist for.
- [ ] An "off switch" at the edge (CDN/WAF/LB) that can block 100% of traffic and re-admit in
      slices, usable in under a minute, without a deploy.

### Elastic-in-front-of-inelastic
- [ ] Every autoscaling tier in front of a fixed-capacity dependency has an explicit throttle:
      pool, proxy, queue, or reserved concurrency.
- [ ] Connection pooling via RDS Proxy / PgBouncer where connection churn is high.
- [ ] JVM DNS TTL set low; reconnect logic uses backoff + jitter.

### Blast radius
- [ ] Soft deletes for anything customer-owned, with a grace period and a tested restore.
- [ ] Type-safe / prefixed identifiers so wrong-object-class calls are rejected.
- [ ] Bulk destructive tools have: dry-run with human-readable output, a scope cap, a rate limit,
      and a refusal-to-cross-floor rule.
- [ ] IAM: every workload's role reviewed against "what could this read if fully compromised?"
- [ ] IMDSv2 enforced (`HttpTokens=required`); no long-lived `AKIA` keys anywhere.
- [ ] Tenant isolation enforced structurally (RLS / `@TenantId` / build-time check), tenant from
      the token only, tenant-aware cache keys by construction.

### Change management
- [ ] No unsupervised fleet-wide auto-updates. OS/AMI/agent rollouts staged with bake time.
- [ ] Capacity additions treated as changes (staged, reviewed, blast-radius bounded).
- [ ] Maintenance automation has a global stand-down switch, interlocked with emergency scaling.
- [ ] Changes tested **at the shape they'll run at** (6 nodes, not 1).

### Recovery
- [ ] Cold-start time measured for every stateful system; it *is* your MTTR.
- [ ] Selective/partial restore tested — "restore these 12 tenants while the rest keep serving."
- [ ] Recovery paths are rate-limited so they don't stampede your own or AWS's control plane.
- [ ] Which systems need quorum to recover? Those set your MTTR floor.
- [ ] Observability and deploy tooling do not traverse the resource most likely to fail.
- [ ] Status page independent of the system it reports on; customer contact details stored
      outside the tenant.

### Cost
- [ ] Gateway VPC endpoints for S3 and DynamoDB (free — no excuse).
- [ ] Per-AZ NAT if cross-AZ is >20% of NAT cost; zone-aware routing for internal traffic.
- [ ] Dominant billing unit documented for every managed service, projected at 10× volume.
- [ ] Cost anomaly alerts routed to the on-call channel; unit-cost dashboard maintained.

---

## Applying this to a multi-tenant B2B SaaS (e.g. this repo's EasyCRM)

The incidents above map onto a small-to-mid multi-tenant CRM more directly than their scale
suggests. In rough priority order:

1. **Tenant isolation must be structural** — `@TenantId` + Postgres RLS + the ArchUnit build gate,
   tenant from the JWT only, tenant-aware cache keys. See [07.3](07-security-and-multi-tenancy.md#73--cross-tenant-data-leaks-in-multi-tenant-saas-composite-pattern).
   This is already the repo's stated policy; the case studies are the *why*.
2. **Soft-delete everything customer-owned.** The Atlassian lesson costs one column and a reaper
   job, and it is the difference between an apology and an extinction event.
3. **Choose the shard key now, even if you never shard.** Notion's regret was not sharding earlier
   and not making the partition key part of the primary key from day one. For a CRM the key is
   obviously `tenant_id` — so **make it part of every primary key and every index prefix now**,
   while it's free.
4. **Watch `VACUUM` and TXID age on Postgres from day one.** Both Notion and Figma hit this. It is
   the most likely thing to actually take a growing Postgres-backed SaaS down.
5. **Bound the connection count**: `pods × pool_size` must sit comfortably under `max_connections`,
   with room for migrations and an emergency `psql`.
6. **Retry budgets and jittered backoff** on every outbound call — payment gateways, SMS/WhatsApp,
   GST/e-invoice APIs. A slow third party must not become your outage.
7. **Load-shed before you die**: a request-concurrency limit and a cheap 503 beats an OOM kill.
8. **Alarm on unexpected absence** (spikes in 404 / empty result sets), not just on 5xx — the
   Monzo lesson, and the exact signature a tenant-isolation bug would produce.
9. **Gateway VPC endpoints for S3** on day one, and a cost-anomaly alert wired to the same place
   as your pager.

---

## Sources

Consolidated primary sources across this library:

- [AWS — DynamoDB service disruption, Oct 2025](https://aws.amazon.com/message/101925)
- [AWS — Kinesis event, Nov 2020](https://aws.amazon.com/message/11201)
- [AWS — S3 service disruption, Feb 2017](https://aws.amazon.com/message/41926/)
- [Slack — Outage on January 4th 2021](https://slack.engineering/slacks-outage-on-january-4th-2021/)
- [Canva — API Gateway outage incident report](https://www.canva.dev/blog/engineering/canva-incident-report-api-gateway-outage/)
- [Notion — Herding elephants: sharding Postgres](https://www.notion.com/blog/sharding-postgres-at-notion)
- [Figma — How Figma's Databases Team Lived to Tell the Scale](https://www.figma.com/blog/how-figmas-databases-team-lived-to-tell-the-scale/)
- [Monzo — Why Monzo wasn't working on July 29th](https://monzo.com/blog/2019/09/08/why-monzo-wasnt-working-on-july-29th)
- [Datadog — 2023-03-08 multi-region connectivity incident](https://www.datadoghq.com/blog/2023-03-08-multiregion-infrastructure-connectivity-issue/)
- [Atlassian — April 2022 Post-Incident Review](https://www.atlassian.com/blog/atlassian-engineering/post-incident-review-april-2022-outage)
- [Twilio Segment — Goodbye Microservices](https://www.twilio.com/en-us/blog/developers/best-practices/goodbye-microservices/)
- [DoorDash — Service Mesh Journey, Part 1](https://careersatdoordash.com/blog/inside-doordashs-service-mesh-journey-part-1-migration-at-scale/)
- [Coinbase — Incident post-mortems (2021)](https://www.coinbase.com/blog/landing/engineering)
- [Honeycomb — Incident Resolution: Twenty Fires of September](https://www.honeycomb.io/blog/incident-resolution-september-retrospective/)
- [Adevinta — How we avoided an outage caused by running out of IPs in EKS](https://adevinta.com/techblog/how-we-avoided-an-outage-caused-by-running-out-of-IPs-in-EKS/)
- [Netflix — Lessons Netflix Learned from the AWS Outage (2011)](https://netflixtechblog.com/lessons-netflix-learned-from-the-aws-outage-deefe5fd0c04)
- [Dan Luu — A collection of postmortems](https://github.com/danluu/post-mortems) *(the best index of public postmortems)*
