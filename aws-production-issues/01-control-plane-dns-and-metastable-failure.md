# 01 — Control plane, DNS & metastable failure

The cloud's own outages. The common thread: **the data plane was mostly healthy and
the control plane was dead**, and in every case *recovery took far longer than the
original fault* because the system got stuck in a state where retries produced more
load than the incident did.

---

## 1.1 — AWS us-east-1, 20 October 2025: a DNS race condition in DynamoDB

**Company:** AWS itself (blast radius: Snapchat, Roblox, Reddit, Venmo, Coinbase,
Robinhood, Amazon.com — 17M+ user-reported outage reports).

### What happened

DynamoDB's DNS management has two components:

- a **DNS Planner** that watches endpoint health/capacity and produces a *plan* (the
  set of IPs that `dynamodb.us-east-1.amazonaws.com` should resolve to);
- multiple **DNS Enactors**, running independently per-AZ for redundancy, that apply
  plans to Route 53.

Enactor #1 picked up an older plan and stalled mid-apply. While it was stalled,
Enactor #2 applied a *newer* plan across all endpoints and then ran its cleanup pass,
which deletes plans older than the one just applied. Enactor #1 then woke up and
finished writing its stale plan over the top of the newer one — its "is my plan still
current?" check had been made at the *start* of the apply and was now hours stale.

Cleanup then deleted that plan, because it was old. Deleting the active plan removed
**every IP address for the regional endpoint**. `dynamodb.us-east-1.amazonaws.com`
resolved to nothing. And the system was now in an inconsistent state that blocked all
subsequent automated plan updates — it could not repair itself.

Then the cascade:

- **EC2**: the DropletWorkflow Manager (DWFM), which holds leases on the physical hosts
  ("droplets") that back EC2 instances, stores lease state in DynamoDB. With DynamoDB
  unreachable from 11:48 PM to 2:24 AM, leases timed out across the region. When DynamoDB
  came back, DWFM tried to re-establish leases on *thousands of droplets simultaneously*
  and entered **congestive collapse**: work queued faster than it drained, so nothing
  ever completed, so it retried. Running instances were fine; **new launches failed**.
- **NLB**: once launches resumed, the health-check subsystem began checking brand-new
  instances whose network state hadn't propagated yet. Checks flapped pass/fail, which
  triggered automatic AZ-level DNS failover, which removed *healthy* capacity, which
  increased load on the health-check subsystem, which degraded it further.

**Timeline:** fault at 11:48 PM PDT Oct 19 → root cause identified 12:38 AM →
DNS restored 2:25 AM (caches clear 2:40 AM) → EC2 launches healthy 1:50 PM →
full resolution 2:20 PM PDT Oct 20. **~15 hours**, of which the actual DNS outage
was under 3.

### Why it was hard

- The race required an Enactor to be *unusually* slow — a latent bug that had survived
  years of normal operation. Timing bugs in redundant, independent actors don't
  reproduce in test.
- **Redundancy caused the bug.** Multiple independent Enactors existed for availability;
  their independence is exactly what allowed the overwrite.
- The end state was **not self-healing**. Most automation is written assuming "run it
  again and it converges." Here, running it again did nothing, because the invariant it
  needed was already broken.
- Both downstream cascades (DWFM, NLB) were **recovery-time failures**, not
  failure-time failures. They only fired *after* the trigger was removed.

### How they fixed it

*Immediate:* manual DNS repair by engineers; then for EC2, engineers **throttled incoming
work and selectively restarted DWFM hosts** to break the collapse; then **disabled NLB
automatic AZ failover** at 9:36 AM to stop the flapping, re-enabling at 2:09 PM.

*Durable (AWS commitments):*
- DNS Planner/Enactor automation **disabled globally** until the race is fixed, plus
  "additional protections to prevent the application of incorrect DNS plans" (i.e. a
  fresh currency check at *write* time, not just at start).
- **Velocity control** on NLB: cap how much capacity health checks may remove per unit time.
- EC2 test suites extended to actually exercise DWFM recovery-from-cold.
- EC2 throttling made **queue-depth aware** rather than fixed-rate.

### Transferable lesson

1. **Check-then-act across a slow boundary is a race.** Re-validate immediately before the
   mutating write, or use a compare-and-swap / fencing token. This is the distributed-systems
   version of TOCTOU.
2. **Garbage collection must never be able to delete the live thing.** "Delete anything older
   than the current active version" is unsafe if "current active" can be computed from stale state.
3. **Test the recovery path, not just the failure path.** Most teams chaos-test "kill the
   dependency." Almost nobody tests "dependency returns after 3 hours and 10,000 clients
   reconnect at once."
4. **Velocity limits on automated remediation.** Any automation that *removes* capacity
   in response to a signal needs a cap — otherwise a bad signal takes out the fleet.

---

## 1.2 — AWS Kinesis, 25 November 2020: an OS thread limit

**Company:** AWS (blast radius: ECS, EKS, Cognito, CloudWatch, Lambda, and everyone
downstream — Roku, Adobe, Flickr, iRobot).

### What happened

Kinesis's front-end fleet uses a **full mesh**: every front-end server maintains a
connection *and a dedicated OS thread* to every other front-end server, in order to
build a shard-map cache.

AWS added a **small amount of capacity** to the fleet (2:44 AM–3:47 AM PST). Every
existing server dutifully spun up threads for the new members. That pushed all servers
past the maximum thread count allowed by an OS configuration. Cache construction then
failed to complete, leaving front-ends holding **useless shard-maps** — they could not
route any request to any backend.

Because the failure was fleet-wide and the fix required a full restart, and because
front-end cold-start took hours (each server has to rebuild the mesh), the outage ran
roughly **17 hours**.

### Why it was hard

- The trigger was a *routine, small* capacity addition — the kind of change nobody
  reviews. The failure was **O(n²) in fleet size**, so it was invisible until the fleet
  crossed a threshold.
- Rolling back the capacity addition didn't immediately help; the fleet was already in a
  bad state and had to be restarted carefully to avoid re-triggering the collapse.
- **Cold-start time was the real outage.** Restarting was the fix, but restarting cost hours.

### How they fixed it

*Immediate:* remove the added capacity, then restart the front-end fleet in controlled
batches so the mesh could rebuild without re-exhausting threads.

*Durable:* raise the OS thread limit substantially; **radically reduce front-end cold-start
time**; move to larger/fewer front-end hosts (reducing n in the O(n²)); add alarming on
thread-count headroom; and — the structural fix — plan to move the shard-map cache off the
full mesh entirely.

### Transferable lesson

1. **O(n²) topologies have a cliff, not a slope.** Full meshes, all-to-all gossip, and
   "every service connects to every other service" all look fine until one node too many.
   Alarm on the *resource* (threads, FDs, connections) as a percentage of the hard limit,
   not on the symptom.
2. **Cold-start time is a first-class availability metric.** If your recovery procedure
   is "restart everything," your MTTR is your cold-start time — measure it.
3. **Capacity additions are changes.** They deserve the same staged rollout and blast-radius
   thinking as code deploys.

---

## 1.3 — AWS S3, 28 February 2017: a typo in a runbook command

**Company:** AWS (blast radius: a large fraction of the public internet — and famously,
AWS's own Service Health Dashboard, which was hosted on S3 and could not be updated to
report the S3 outage).

### What happened

An engineer was debugging a billing-system slowdown and ran an established playbook command
to remove a small number of servers from an S3 **billing** subsystem. A typo in the command
removed a **much larger set** of servers than intended — enough that it took out two
core subsystems: the **index subsystem** (metadata and object location for all objects in
us-east-1) and the **placement subsystem** (allocating storage for new objects).

Both required a **full restart**. S3 had grown enormously since those subsystems were last
fully restarted, and the safety checks and metadata validation on start-up took far longer
than anyone had estimated. GET/PUT/LIST/DELETE were unavailable in us-east-1 for ~4 hours.

### Why it was hard

- The command was **legitimate and routine**; only the argument was wrong. No amount of
  "review your deploys" catches this.
- The dependency graph was invisible: nobody expected a *billing* subsystem operation to
  be able to remove index-subsystem capacity.
- **Restart time had silently regressed for years** because the systems had never been
  fully restarted at that scale.

### How they fixed it

*Durable:* the removal tool was changed to (a) **remove capacity more slowly** and
(b) **refuse to drop any subsystem below its minimum required capacity level**. S3 was
partitioned into smaller **cells** so that recovery operations act on a bounded amount of
state. The Service Health Dashboard was moved off a single region.

### Transferable lesson

1. **Guardrails belong in the tool, not the human.** The fix was never "be more careful."
   It was "the tool cannot take capacity below the safe floor" — a *structural* constraint,
   exactly like preferring `@TenantId` + RLS over hand-written `WHERE tenant_id = ?`.
2. **Your status page must not depend on the system it reports on.**
3. **If you have never restarted it, you do not know how long it takes to restart.**
   Schedule the rehearsal.

---

## Cross-case observations

| | Oct 2025 DynamoDB | Nov 2020 Kinesis | Feb 2017 S3 |
|---|---|---|---|
| Trigger | latent race | routine capacity add | typo'd runbook arg |
| Data plane healthy? | mostly yes | no | no |
| Self-healing? | **no** | no | no |
| Real cost driver | recovery cascade (DWFM/NLB) | cold-start time | restart time |
| Structural fix | fencing + velocity limits | reduce n, cut cold-start | tool-enforced floors, cells |

The pattern in all three: **the trigger is small and boring; the duration is set by how
badly the system recovers.** Optimise for recovery, not just for avoiding the trigger.

## Sources

- [AWS — Summary of the DynamoDB Service Disruption in Northern Virginia (Oct 19-20, 2025)](https://aws.amazon.com/message/101925) *(primary)*
- [AWS — Summary of the Amazon Kinesis Event in Northern Virginia (Nov 25, 2020)](https://aws.amazon.com/message/11201) *(primary)*
- [AWS — Summary of the Amazon S3 Service Disruption (Feb 28, 2017)](https://aws.amazon.com/message/41926/) *(primary)*
- [ThousandEyes — AWS Outage Analysis: October 20, 2025](https://www.thousandeyes.com/blog/aws-outage-analysis-october-20-2025)
- [Gremlin — Reliability lessons from the 2025 AWS DynamoDB outage](https://www.gremlin.com/blog/reliability-lessons-from-the-2025-aws-dynamodb-outage)
- [Evan Jones — Lessons from the AWS Kinesis Outage](https://www.evanjones.ca/kinesis-outage.html)
- [The Downtime Project — Kinesis Hits the Thread Limit](https://downtimeproject.com/podcast/kinesis-hits-the-thread-limit/)
