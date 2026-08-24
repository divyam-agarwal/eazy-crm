# AWS Production Issues — Research Library

A curated study of **real production incidents and scaling challenges at companies
that run AWS as their primary cloud**, with the root cause, the fix, and the
transferable lesson for each.

Compiled 2026-08-20. Every case study is sourced from a primary postmortem,
official AWS "message" page, or first-party engineering blog wherever one exists;
secondary analysis is marked as such.

## Why this exists

Most "AWS best practices" content is written from the happy path. This library is
written backwards — from the outage — because the failure modes that actually take
production down on AWS are rarely the ones the well-architected checklists warn
about. They cluster into a small number of repeating shapes:

1. **The control plane fails, not the data plane.** Your instances are fine; you
   just can't launch, resolve, or reconfigure anything.
2. **Recovery is harder than the failure.** The system is *metastable* — remove the
   trigger and it still won't come back, because retries now generate more load
   than the original traffic.
3. **A managed service has a limit you didn't know existed.** Threads per host,
   IPs per subnet, connections per instance, WCUs per partition.
4. **The blast radius of automation is the whole fleet.** One script, one config
   flag, one unsupervised auto-update.
5. **Your monitoring depends on the thing that's broken.**
6. **The bill is an incident too.** Data transfer and per-state-transition pricing
   fail silently until finance notices.

## Index

| # | File | Theme | Headline cases |
|---|------|-------|----------------|
| 01 | [Control plane, DNS & metastable failure](01-control-plane-dns-and-metastable-failure.md) | The cloud's own failures | AWS DynamoDB DNS race (Oct 2025), Kinesis thread limit (Nov 2020), S3 typo (2017) |
| 02 | [Saturation, thundering herds & retry storms](02-saturation-thundering-herds-and-retry-storms.md) | Traffic-shaped failures | Slack Transit Gateway, Canva API Gateway, DoorDash circuit breaker, Coinbase price-crash spikes |
| 03 | [Databases on AWS](03-databases-on-aws.md) | RDS / Aurora / DynamoDB / Cassandra | Notion 480 shards, Figma DBProxy, Monzo `auto_bootstrap`, DynamoDB hot partitions, Aurora failover |
| 04 | [Serverless & the limits of decomposition](04-serverless-and-decomposition-limits.md) | Architecture reversals | Prime Video serverless→monolith, Segment microservices→monolith, Lambda vs. RDS connections |
| 05 | [Kubernetes & networking on AWS](05-kubernetes-and-networking-on-aws.md) | EKS / VPC / self-hosted infra | VPC CNI IP exhaustion, Honeycomb Kafka network throttling, DoorDash service mesh |
| 06 | [Automation blast radius](06-automation-blast-radius.md) | Self-inflicted, fleet-wide | Datadog global systemd update, Atlassian 883 deleted sites |
| 07 | [Security & multi-tenancy failures](07-security-and-multi-tenancy.md) | IAM / IMDS / isolation | Capital One SSRF → IMDSv1 → S3, IAM blast radius, tenant isolation |
| 08 | [Cost incidents](08-cost-incidents.md) | The bill as an outage | NAT Gateway & cross-AZ transfer, Step Functions per-transition, S3 request amplification |
| 09 | [Cross-cutting patterns & checklist](09-patterns-and-checklist.md) | Synthesis | The 12 recurring patterns, plus a pre-mortem checklist |

### Deep-dive walkthroughs

Minute-by-minute reconstructions of two incidents where the published record is detailed enough to
follow the whole thing. Each marks **[INFERRED]** and **[ASSUMPTION]** explicitly and ends with a
list of what the record does *not* say.

| Walkthrough | Why this one |
|---|---|
| [AWS us-east-1, 19–20 Oct 2025](walkthrough-aws-us-east-1-october-2025.md) | AWS's official RCA is unusually specific about mechanism — the DNS Planner/Enactor race, DWFM congestive collapse, NLB health-check flapping, and four downstream service failures |
| [Canva API Gateway, 12 Nov 2024](walkthrough-canva-november-2024.md) | Three independent conditions aligning; a canary that reported success because requests never completed; recovery by blocking 100% of traffic at the CDN |

## How to read a case study

Each one follows the same shape, deliberately matching the repo's
`docs/superpowers/engineering-challenges.md` template:

- **What happened** — the timeline, compressed.
- **Why it was hard** — why the obvious answer was wrong or insufficient.
- **How they fixed it** — immediate mitigation and durable remediation, separated.
- **Transferable lesson** — what you should change in your own system.
- **Sources** — primary first.

## A note on sourcing

Where AWS itself published a root-cause analysis, I used it (`aws.amazon.com/message/...`).
Where the company published its own postmortem, I used that. A few entries — DynamoDB
hot partitions, Lambda/RDS connection exhaustion, EKS IP exhaustion, NAT Gateway cost —
are **composite patterns** rather than a single named incident: they are extremely common,
well documented across many teams, but rarely attached to one famous public postmortem.
They are labelled as composites so you don't cite them as a specific company's outage.
