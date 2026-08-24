# Walkthrough — AWS us-east-1, 19–20 October 2025

**A 15-hour outage that began with a race condition in DNS automation and ended with three separate
cascading recoveries.** The single most instructive incident in modern cloud operations, because
almost none of the duration was caused by the original fault.

---

## How to read this document

| Marker | Meaning |
|---|---|
| *(unmarked)* | **Documented** in AWS's official post-event summary (`aws.amazon.com/message/101925`) |
| **[INFERRED]** | Follows directly from documented facts, but not stated |
| **[ASSUMPTION]** | Not in the record — my judgement, flagged |

AWS's RCA is unusually specific about mechanism. Nearly everything below is documented. Gaps are
listed in [§10](#10-what-the-record-does-not-say).

All times **PDT**. Event window: **19 Oct 23:48 → 20 Oct 14:20**.

---

## 1. The three impact windows

The most common misreading of this incident is that DynamoDB was down for 15 hours. It wasn't.
There were **three distinct, partially overlapping failures**, and only the first was DynamoDB:

| # | Window | Duration | What customers saw |
|---|---|---|---|
| 1 | 19 Oct 23:48 → 20 Oct 02:40 | **~2h 52m** | DynamoDB API errors — could not establish new connections |
| 2 | 20 Oct 02:25 → 13:50 | **~11h 25m** | **EC2 instance launches failed**; then launched instances had no network |
| 3 | 20 Oct 05:30 → 14:09 | **~8h 39m** | NLB connection errors from health-check failures |

**The trigger lasted under three hours. The consequences lasted twelve.** Windows 2 and 3 are both
*recovery* failures — they began at or after the moment DynamoDB came back.

**Crucially: existing EC2 instances were never affected.** AWS states it plainly — instances
launched before the event *"remained healthy and did not experience any impact for the duration of
the event."* This was a **control plane** outage. If your architecture needed to launch, scale,
replace, or reconfigure anything, you were down. If it didn't, you weren't.

---

## 2. The system: how DynamoDB manages DNS

DynamoDB maintains **hundreds of thousands of DNS records** to operate a large heterogeneous fleet
of load balancers per region. Automation keeps them fresh: adding capacity, handling hardware
failures, distributing traffic. Beyond the public regional endpoint, it maintains endpoints for a
**FIPS-compliant variant, an IPv6 variant, and account-specific endpoints**.

The system is **deliberately split into two independent components, for availability**:

**The DNS Planner** monitors load balancer health and capacity and periodically produces a **DNS
plan** for each endpoint — a set of load balancers with weights. There is a **single regional plan**,
because sharing capacity across endpoints (the public regional endpoint and the newer IPv6 endpoint)
greatly simplifies capacity management.

**The DNS Enactor** applies plans to Route 53. It is **designed to have minimal dependencies**, so it
can recover the system in any scenario. And — this is the important part — **it runs redundantly and
fully independently in three different Availability Zones.**

Each Enactor instance:
- looks for new plans,
- applies them by replacing the current plan via a **Route 53 transaction**, which ensures each
  endpoint ends up with a consistent plan even when multiple Enactors update concurrently,
- **makes a one-time check, before it begins, that its plan is newer than the previously applied plan**,
- retries individual endpoints when blocked by another Enactor.

Read that list again with an adversarial eye. **The freshness check happens once, at the start, for
the whole run.** The run then walks hundreds of thousands of records, retrying on contention. The
window between "I checked" and "I write the last record" is unbounded.

> **This is a check-then-act race, and the gap is the entire duration of a long job.**
> Redundancy across three AZs is what made two Enactors run concurrently in the first place: the
> availability mechanism created the failure mode.

---

## 3. 23:48 — the race fires

Documented sequence:

1. **One DNS Enactor experiences unusually high delays**, needing to retry its update on several
   endpoints. It works through the list slowly.
2. Meanwhile, **the DNS Planner keeps running and produces many newer generations of plans.**
3. **A second Enactor picks up one of the newer plans and progresses rapidly** through all endpoints.
4. The second Enactor finishes and **invokes the plan clean-up process**, which identifies plans
   *significantly older* than the one just applied and **deletes them**.
5. **At the same time**, the first (delayed) Enactor **applies its much older plan to the regional
   DynamoDB endpoint, overwriting the newer plan.** Its freshness check — made at the start — is now
   **stale**, so nothing stops it.
6. **The clean-up process then deletes that older plan**, because it is many generations behind.

**As the plan is deleted, all IP addresses for the regional endpoint are immediately removed.**

`dynamodb.us-east-1.amazonaws.com` resolves to nothing.

### Why it could not self-heal

> *"Additionally, because the active plan was deleted, the system was left in an inconsistent state
> that prevented subsequent plan updates from being applied by any DNS Enactors. This situation
> ultimately required manual operator intervention to correct."*

The automation designed to recover from *"a wide variety of operational issues"* had reached a state
outside its model. The Enactor's core loop compares against a current plan; with no current plan,
every Enactor is stuck. **All three redundant copies failed identically, because redundancy protects
against independent failure, not against a shared logical flaw.**

### Immediate blast radius

Everything needing the public endpoint — customer traffic **and internal AWS services** — began
failing DNS resolution instantly.

**Global tables customers could still read and write replicas in other regions**, but experienced
prolonged replication lag to and from us-east-1. **[INFERRED]** — this is the clearest evidence that
multi-region replication provided real value here, and that the failure was regional endpoint
resolution rather than data-plane damage.

---

## 4. The cascade into EC2

### The mechanism

Two subsystems matter:

- **DropletWorkflow Manager (DWFM)** manages the physical servers hosting EC2 instances — AWS calls
  these **"droplets."** Each DWFM maintains a **lease** for every droplet it manages, and **each DWFM
  host must check in and complete a state check with each droplet every few minutes.** Lease state
  lives in DynamoDB.
- **Network Manager** propagates network configuration to instances and network appliances.

**From 23:48**, DWFM state checks began failing, because the process depends on DynamoDB.

Running instances were unaffected. But **between 23:48 and 02:24, droplet leases across the EC2
fleet slowly timed out.** A droplet without an active lease is **not a candidate for new launches** —
which is why the EC2 API returned **"insufficient capacity"** errors. Not a capacity shortage: a
bookkeeping outage.

### Congestive collapse — the second failure

**02:25.** DynamoDB recovers. DWFM begins re-establishing leases across the entire fleet.

And here the incident gets much worse:

> *"due to the large number of droplets, efforts to establish new droplet leases took long enough
> that the work could not be completed before they timed out. Additional work was queued to reattempt
> establishing the droplet lease. At this point, DWFM had entered a state of congestive collapse and
> was unable to make forward progress."*

Leases time out faster than they can be re-established. Each timeout queues more work. The queue
grows, processing slows, more leases time out. **The system is now generating its own load faster
than it can serve it, and removing the original fault does nothing.**

This is the textbook definition of a **metastable failure**: a system that will not return to a good
state on its own even after the trigger is gone.

### The recovery decision — and the honest admission

> *"Since this situation had no established operational recovery procedure, engineers took care in
> attempting to resolve the issue with DWFM without causing further issues."*

**AWS had never rehearsed this.** For roughly two hours, engineers worked cautiously on a system in
collapse with no runbook, correctly worried that a wrong move would extend the outage.

**04:14** — after multiple mitigation attempts, they **throttle incoming work and begin selective
restarts of DWFM hosts.** Restarting clears the queues, cuts processing times, and lets leases be
established.

**05:28** — DWFM has leases on all droplets in us-east-1. New launches begin succeeding, though many
requests still see **"request limit exceeded"** from the throttling deliberately introduced.

> **The fix was to shed load and restart.** Exactly the same lever Canva used at the CDN
> ([walkthrough](walkthrough-canva-november-2024.md)) and the same one Slack needed. There are only
> three exits from a metastable failure: shed load, add capacity faster than the loop grows, or break
> the amplifier.

### The third failure: Network Manager backlog

**05:28.** Network Manager starts propagating configuration for newly launched instances *and* for
instances terminated during the event. Because these propagations had been blocked by DWFM, there is
now **a significant backlog**.

**06:21** — Network Manager latency climbs. Now: **instances launch successfully but have no network
connectivity.** Arguably worse than a failed launch, because autoscaling counts them as capacity.

**10:36** — propagation times normal; launches operating normally.
**11:23** — engineers begin relaxing throttles.
**13:50** — all EC2 APIs and launches fully normal.

---

## 5. The fourth failure: NLB health-check flapping

NLB runs a **separate health check subsystem** that continuously checks all nodes and removes
unhealthy ones from service.

The failure:

1. Health checks began **bringing new EC2 instances into service while their network state had not
   fully propagated**.
2. Checks **failed even though the NLB node and backend targets were healthy**.
3. Results **alternated between failing and healthy**.
4. Each flap **removed nodes and targets from DNS, then returned them** on the next success.
5. **The flapping increased load on the health check subsystem, causing it to degrade**, delaying
   health checks further.
6. Degradation **triggered automatic AZ DNS failover**. For multi-AZ load balancers this **took
   capacity out of service**.
7. Applications saw connection errors **if the remaining healthy capacity couldn't carry the load**.

**A health system responding to a false signal removed healthy capacity, which made the health system
sicker, which removed more capacity.**

**06:52** — AWS monitoring detects it.
**09:36** — engineers **disable automatic health check failovers for NLB**, letting all healthy nodes
and targets return. Connection errors resolve.
**14:09** — automatic DNS health check failover re-enabled after EC2 recovery.

**The mitigation was to turn off the automation.** Note that this is the *third* time in this
incident that the fix was to stop a self-healing mechanism from healing.

---

## 6. The full timeline

| Time (PDT) | System | Event |
|---|---|---|
| **19 Oct 23:47** | Redshift | Query/cluster API errors begin |
| **19 Oct 23:48** | **DynamoDB** | **Race condition fires; all regional endpoint IPs removed** |
| 19 Oct 23:48 | EC2 | DWFM state checks begin failing |
| 19 Oct 23:51 | Lambda, STS, IAM/Console | Errors begin |
| 20 Oct 00:20 | Connect | Elevated errors on calls, chats, cases |
| **20 Oct 00:38** | — | **Engineers identify DynamoDB DNS state as the source** (50 min in) |
| 20 Oct 01:15 | DynamoDB | Temporary mitigations let some internal services reconnect; **key internal tooling repaired, unblocking recovery** |
| 20 Oct 01:19 | STS | Recovered |
| 20 Oct 01:25 | IAM / Console sign-in | Recovered |
| 20 Oct 02:15–02:21 | Redshift | Query operations resume |
| **20 Oct 02:25** | **DynamoDB** | **All DNS information restored** |
| 20 Oct 02:24–02:25 | EC2 | Droplet leases have timed out fleet-wide; DWFM begins re-establishing |
| 20 Oct 02:32 | DynamoDB | Global table replicas fully caught up |
| **20 Oct 02:40** | DynamoDB | **Cached DNS records expired; customer connections succeed. Primary event over** |
| 20 Oct 02:40 | Support | Support Console case access restored (mitigated) |
| 20 Oct 02:58 | Support | Additional preventive action taken |
| — | EC2 | **DWFM in congestive collapse; no established recovery procedure** |
| **20 Oct 04:14** | EC2 | Engineers **throttle incoming work + selectively restart DWFM hosts** |
| 20 Oct 04:40 | Lambda | Internal SQS-polling subsystem restored (had failed and not auto-recovered) |
| 20 Oct 05:00 | Connect | Chat errors resolve |
| **20 Oct 05:28** | EC2 | **All droplet leases established; launches succeed** (throttled) |
| 20 Oct 05:30 | NLB | Connection errors begin |
| 20 Oct 06:21 | EC2 | Network Manager latency climbs; instances launch **without connectivity** |
| 20 Oct 06:45 | Redshift | Action taken to stop workflow backlog growing |
| 20 Oct 06:52 | NLB | AWS monitoring detects health-check failures |
| 20 Oct 07:04 | Connect | Second wave of errors (NLB + Lambda) |
| 20 Oct 08:31–09:59 | STS | Second impact window from NLB health-check failures |
| **20 Oct 09:36** | NLB | **Automatic health check failover disabled**; connection errors resolve |
| **20 Oct 10:36** | EC2 | **Network propagation times normal; launches normal** |
| 20 Oct 11:23 | EC2 | Throttles begin to be relaxed |
| 20 Oct 12:01 | EC2 | Recovery starts in earnest |
| 20 Oct 13:20 | Connect | Service availability restored |
| **20 Oct 13:50** | EC2 | **Full recovery** |
| **20 Oct 14:09** | NLB | Automatic DNS health check failover re-enabled |
| **20 Oct 14:20** | — | **Event ends** |
| 21 Oct 04:05 | Redshift | Last impaired clusters restored |
| 28 Oct | Connect | Dashboard/Data Lake data backfill completed |

---

## 7. The downstream services — where the real lessons hide

AWS documented several dependent-service failures that are individually more instructive than the
headline:

**Lambda** — recovered by 02:24, *except* SQS queue processing. An internal subsystem responsible
for polling SQS queues **failed and did not recover automatically**; AWS restored it manually at
**04:40**. **[INFERRED]** — another component whose recovery path was untested.

**STS and IAM** — sign-in failures. Critically, **customers using root credentials or federation via
`signin.aws.amazon.com` got errors logging into the console in regions *outside* us-east-1.**
A us-east-1 regional failure prevented console access to *other* regions. This is the single most
important detail in the whole RCA for anyone designing multi-region failover: **your escape hatch may
be in the burning building.**

**Redshift** — a two-stage failure. First, queries needed DynamoDB. Then the deeper one: **as
credentials expire for cluster nodes without being refreshed, Redshift automation triggers workflows
to replace the underlying EC2 hosts.** With EC2 launches impaired, those workflows blocked, putting
clusters in a permanent **"modifying"** state. Automated remediation, blocked by the outage, left
clusters *worse* than if it hadn't run. Some weren't restored until **04:05 on 21 October** — over a
day later. Separately, **Redshift customers in *all* regions** couldn't use IAM user credentials,
because of *"a Redshift defect that used an IAM API in the N. Virginia (us-east-1) Region to resolve
user groups."* A hardcoded regional dependency made a regional outage global.

**AWS Support** — customers couldn't create or view support cases. The Support Center **failed over
to another region as designed**, but a subsystem serving account metadata **returned invalid
responses**, and although Support was designed to **bypass that system if responses were
unsuccessful**, invalid-but-well-formed responses weren't treated as unsuccessful. **The failover
worked and the fallback logic still blocked legitimate users** — because the fallback tested for
failure, not for correctness. Customers could not open a ticket about the outage.

---

## 8. AWS's remediations

Verbatim in substance:

1. **DynamoDB DNS Planner and DNS Enactor automation disabled worldwide.** Before re-enabling: fix
   the race condition and **"add additional protections to prevent the application of incorrect DNS
   plans."**
2. **NLB: a velocity control mechanism** limiting how much capacity a single NLB can remove when
   health-check failures cause AZ failover.
3. **EC2: an additional test suite** augmenting existing scale testing, which **exercises the DWFM
   recovery workflow** to catch regressions.
4. **EC2: improved throttling** that **rate limits incoming work based on the size of the waiting
   queue.**

Every one addresses a *recovery* failure rather than the trigger. AWS drew the same conclusion this
document does.

---

## 9. Nine lessons

1. **Check-then-act across a long-running job is a race.** Re-validate immediately before each
   mutating write, or use a fencing token / conditional write. The freshness check was made once and
   consumed hundreds of thousands of operations later.
2. **Garbage collection must never be able to delete the live object.** "Delete plans much older than
   the one I just applied" is unsafe when "the one I just applied" can be overwritten by a stale writer.
3. **Independent redundant actors need mutual exclusion, not just independence.** Three AZ-independent
   Enactors made the race possible *and* failed identically to the logical flaw.
4. **Rehearse recovery, not just failure.** AWS's own words: *"no established operational recovery
   procedure."* Chaos-test "the dependency returns after three hours and 10,000 clients reconnect at
   once," not just "kill the dependency."
5. **Every self-healing mechanism needs a velocity limit and an off switch.** Three separate
   automations made things worse — DWFM lease re-establishment, NLB health failover, Redshift host
   replacement — and two mitigations were literally *turn the automation off*.
6. **Queue-depth-aware throttling beats fixed-rate throttling.** AWS's own remediation. A fixed rate
   can't distinguish a healthy burst from a collapse.
7. **Your control plane and your escape hatch must not live in the failing region.** Console sign-in
   for *other* regions, and AWS Support itself, both failed. Pre-establish a break-glass path that
   doesn't touch us-east-1.
8. **Audit for hardcoded regional dependencies.** Redshift's IAM group-resolution defect made a
   regional failure global. Grep your code and your vendors' for `us-east-1`.
9. **Fallback logic must test for correctness, not just for failure.** Support's bypass triggered on
   *unsuccessful* responses; it received *invalid* ones and treated them as authoritative.

---

## 10. What the record does not say

- **Why the first Enactor was delayed.** "Unusually high delays" and retries are described; the cause
  is not.
- **How long the race condition had been latent.** Called a "latent defect"; no age given.
- **The number of droplets, or DWFM hosts restarted.** Only "the large number of droplets."
- **What the "temporary mitigations" at 01:15 were**, or what "key internal tooling" was repaired.
- **What was attempted between 02:25 and 04:14**, beyond "multiple mitigation steps."
- **Customer-facing impact numbers.** No error rates, request counts, or affected-customer figures.
  Third-party trackers reported 17M+ user outage reports; that's not AWS data.
- **Whether the DNS plan clean-up deletion was recoverable** from Route 53 change history. Never
  addressed. **[ASSUMPTION]** — the "manual operator intervention" was probably re-creating the
  records rather than reverting, but AWS doesn't say.

---

## Sources

- [AWS — Summary of the Amazon DynamoDB Service Disruption in the Northern Virginia (US-EAST-1) Region](https://aws.amazon.com/message/101925) *(primary; all quoted material)*
- [ThousandEyes — AWS Outage Analysis: October 20, 2025](https://www.thousandeyes.com/blog/aws-outage-analysis-october-20-2025) *(independent network-level observation)*
- [Gremlin — Reliability lessons from the 2025 AWS DynamoDB outage](https://www.gremlin.com/blog/reliability-lessons-from-the-2025-aws-dynamodb-outage)

**Related in this library:** [01 — Control plane, DNS & metastable failure](01-control-plane-dns-and-metastable-failure.md) ·
[02 — Saturation, thundering herds & retry storms](02-saturation-thundering-herds-and-retry-storms.md) ·
[09 — Patterns & checklist](09-patterns-and-checklist.md)
