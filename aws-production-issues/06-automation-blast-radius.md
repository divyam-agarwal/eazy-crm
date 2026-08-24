# 06 — Automation blast radius

Nobody attacked these companies and no AWS service failed. In both cases **the organisation's
own automation executed correctly against the wrong scope**, and the resulting outage was
measured in days, not minutes, because *undo* had never been built.

If you take one thing from this file: **the interesting number is not "how likely is this
mistake" but "how many things can one execution touch, and how fast can it be reversed."**

---

## 6.1 — Datadog, 8 March 2023: a routine OS security update took down five regions at once

**Company:** Datadog (multi-cloud, AWS among them). Impact: ~24 hours of near-total loss of
user-facing functionality — metrics, logs, traces, alerting. Widely reported cost: ~$5M in
credits, plus the reputational sting of a **monitoring company** going dark.

### What happened

At 06:00 UTC a **systemd security update was automatically applied** to a large number of VMs.
On Ubuntu 22.04 (systemd v249), restarting `systemd-networkd` after that update caused it to
**forcibly delete the routing rules managed by Cilium**, their container network interface.

Nodes didn't crash. They lost their pod networking and went offline. **Tens of thousands of
nodes across five regions — US1, EU1, US3, US4, US5 — simultaneously.** Roughly **50–60% of
production Kubernetes nodes** vanished from the cluster.

The regions are designed to be **autonomous, independently operated, with no network coupling**
between them. Multi-region and multi-cloud, exactly as the resilience playbook prescribes. And
they all failed within the same minute, because they shared one thing nobody had modelled as a
dependency: **a legacy automated Ubuntu security-update channel with a default 06:00–07:00 UTC
maintenance window**.

### Why it was hard

- **No test reproduces it.** During a normal boot, `systemd-networkd` starts *before* the CNI
  installs routes, so nothing is deleted. The destructive sequence only occurs when
  `systemd-networkd` restarts on an *already-running* node — which is what an in-place package
  update does. As Datadog put it, "no obvious test reproduces the exact sequence."
- **The coupling was invisible because it wasn't in the architecture.** The regions shared no
  network, no database, no control plane. They shared a *package repository and a cron window*.
  Correlated failure through a configuration channel, not a data path.
- Recovery was far worse than the failure:
  - **Cloud providers helpfully replaced "unhealthy" nodes** — destroying local data on stateful
    workloads that then had to be recovered from elsewhere.
  - **Tens of thousands of simultaneous node replacements** produced a thundering herd against
    each cloud's control plane and hit **regional API rate limits**.
  - **Quorum-dependent systems recovered slowest.** Their metadata stores need distributed
    consensus; a share-nothing, statically-sharded telemetry pipeline could come back
    independently, but anything requiring quorum had to wait for enough peers.

### How they fixed it

*Immediate:* disable the legacy update channel to stop further nodes being affected; then a long,
manual, carefully-paced fleet recovery — with the recovery itself rate-limited to avoid
overwhelming cloud control planes.

*Durable:*
- **Disabled the legacy automated update channel** entirely — no unsupervised, unstaged,
  fleet-wide OS updates.
- **Reconfigured `systemd-networkd` to preserve routing tables on restart** (the specific bug).
- **Audited the whole infrastructure for other legacy dependencies** that silently span regions.
- Publicly reframed their reliability approach around **reliability in depth**: assume the
  platform will fail and design so that each region's recovery is independent.

### Transferable lesson

1. **Enumerate your *non-architectural* shared dependencies.** Package repos, base AMIs, container
   base images, CI/CD, DNS providers, TLS certificate issuance, IAM/SSO, feature-flag services,
   NTP. Any of these can correlate "independent" regions. Draw the dependency diagram that includes
   them and you will find at least one surprise.
2. **Nothing should update everywhere at once.** Stagger OS/AMI/agent rollouts across regions and
   AZs on a schedule with bake time between waves — the same discipline you apply to application
   deploys, applied to the substrate.
3. **Multi-region ≠ resilient.** Multi-region protects against *regional* failures. It does nothing
   against a *global change*. Most companies' catastrophic outages are global changes.
4. **Rate-limit your own recovery.** Mass node replacement will hit cloud API limits. Have a
   throttled recovery path, and know your account's EC2/API quotas before you need them.
5. **Understand which of your systems need quorum to recover.** Those set your MTTR floor.

---

## 6.2 — Atlassian, 5 April 2022: 883 customer sites deleted by a maintenance script

**Company:** Atlassian (cloud products on AWS). Impact: **883 sites belonging to 775 customers
deleted** in a 23-minute window (07:38–08:01 UTC). Restoration took **up to 14 days**; the last
customer came back on 18 April.

### What happened

A maintenance script was written to delete the deprecated standalone "Insight – Asset Management"
app from customer sites. Two failures combined:

1. **The requesting team handed the executing team the wrong identifiers** — *site* IDs instead
   of *app* IDs.
2. **The deletion API accepted both** and "assumes the input is correct." No type discrimination,
   no validation that the ID referred to the object class the caller intended.

The script was peer-reviewed. The review did not — and realistically could not — cross-check
that the opaque ID list referred to apps rather than whole sites.

And it was a **permanent delete**, not a soft delete, because the deletion path was designed for
"remove this app," where permanence is fine.

### Why it was hard — the recovery, not the deletion

Atlassian had backups, and **no customer lost more than five minutes of data** (30-day retention,
restored to five minutes pre-deletion for consistency across systems). The 14 days were **entirely
about restore mechanics**:

- A "customer site" is not a database row. It spans **databases, identity services, configuration
  stores, and metadata across multiple systems and regions** in a distributed multi-tenant
  architecture. Restoring one means reassembling all of it consistently.
- Their DR design assumed **whole-system or whole-region restore** (fast) — not **selective restore
  of 883 specific tenants out of a live, shared, multi-tenant system where every other tenant must
  keep running**. That capability did not exist and had to be built during the incident.
- **Restoration 1** (~48 hours per batch): recreate sites with *new* identifiers, then remap
  immutable IDs across **~70 sequential steps**.
- **Restoration 2** (~12 hours per batch, built mid-incident): reuse the *original* site
  identifiers, cutting dependencies and parallelising to **~30 steps**. This was used to restore
  ~47% of impacted users (771 sites) between 14–17 April.

**And they couldn't talk to the affected customers.** Support systems required a valid Cloud URL
and Atlassian ID — credentials that only exist if your site exists. The deleted customers were
locked out of the very channel they'd use to report being deleted. Public communication was also
slow, so the story ran ahead of Atlassian for days.

### How they fixed it

The four committed remediations are a good template for anyone running multi-tenant SaaS:

1. **Soft deletes universally**, across all systems, with staged rollout and *tested* rollback.
2. **Automated multi-site, multi-product disaster recovery** — i.e. build and regularly exercise
   selective, per-tenant restore, not just region-level DR.
3. **Redesign large-scale incident management** with playbooks for incidents needing a
   hundred-plus responders over multiple weeks (their standard IM process assumed hours).
4. **Retrofit communications**: backup contact details held outside the tenant, alternative
   support access for customers without a working site, unified escalation tracking.

### Transferable lesson

1. **Soft-delete everything customer-owned, always.** A `deleted_at` timestamp plus a reaper job
   with a long grace period converts a catastrophe into a `UPDATE ... SET deleted_at = NULL`.
   This is the single highest-leverage line of defence in this entire library.
2. **Make identifiers type-safe.** Prefixed IDs (`site_01H...`, `app_01H...`) or distinct types make
   "you passed a site ID where an app ID was expected" a rejected request instead of a disaster.
   The API "accepts both and assumes the input is correct" is the actual root cause.
3. **Destructive bulk operations need a dry-run that prints what will be affected, in
   human-readable form** — names, not opaque IDs — plus a scope cap ("refuse to act on more than
   N objects without an explicit override") and a rate limit. Compare AWS's own fix after the 2017
   S3 outage: the tool was changed so it *cannot* take a subsystem below its minimum capacity.
4. **Your DR plan must cover the granularity you'll actually need.** Everyone tests "restore the
   region." Almost nobody tests "restore these 12 tenants, right now, while the other 100,000 keep
   serving traffic." Test the selective restore.
5. **Keep a communication channel that survives the failure**, including customer contact details
   stored outside the tenant's own data.

---

## The shared shape

| | Datadog | Atlassian |
|---|---|---|
| The automation | OS security update channel | one-off maintenance script |
| Scope error | applied to *all regions, all clouds* at once | applied to *sites* instead of *apps* |
| Guardrail that was missing | staged rollout / bake time | type-safe IDs, dry-run, soft delete |
| Detection | fast (nodes vanished) | fast (customers gone) |
| **What set the duration** | **stateful recovery + control-plane rate limits** | **absence of selective restore** |

Both companies detected the problem within minutes. Both spent days recovering. **Your incident
duration is a property of your undo capability, and undo is a feature you have to build before
you need it.**

## Sources

- [Datadog — 2023-03-08 Incident: Infrastructure connectivity issue affecting multiple regions](https://www.datadoghq.com/blog/2023-03-08-multiregion-infrastructure-connectivity-issue/) *(primary)*
- [Datadog — Deep dive into the platform-level impact](https://www.datadoghq.com/blog/engineering/2023-03-08-deep-dive-into-platform-level-impact/) *(primary)*
- [Datadog — Deep dive into platform-level recovery](https://www.datadoghq.com/blog/engineering/2023-03-08-deep-dive-into-platform-level-recovery/) *(primary)*
- [Datadog — Failure is inevitable: building for reliability in depth](https://www.datadoghq.com/blog/engineering/rethinking-reliability/) *(primary)*
- [The Pragmatic Engineer — Inside DataDog's $5M Outage](https://newsletter.pragmaticengineer.com/p/inside-the-datadog-outage)
- [Atlassian — Post-Incident Review on the April 2022 outage](https://www.atlassian.com/blog/atlassian-engineering/post-incident-review-april-2022-outage) *(primary)*
- [Rootly — What SREs Can Learn from the Atlassian Nightmare Outage of 2022](https://rootly.com/blog/what-sres-can-learn-from-the-atlassian-nightmare-outage-of-2022)
