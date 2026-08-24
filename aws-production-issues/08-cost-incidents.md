# 08 — Cost incidents: when the bill is the outage

A cost blowout is a production incident with a slow detector. It has a trigger, a blast radius,
an MTTD (usually "the 3rd of next month") and an MTTR. The difference is that nothing pages you,
so it runs for weeks.

On AWS, compute is the line item everyone watches and **data movement is the line item that
actually surprises people**. The through-line of this file: *AWS charges for bytes crossing
boundaries you can't see on an architecture diagram.*

> Prices below are indicative US-East rates at time of writing (Aug 2026) and are used to show
> the *shape* of the arithmetic. Always check current pricing.

---

## 8.1 — NAT Gateway and cross-AZ transfer *(composite pattern)*

The most common six-figure surprise in Kubernetes-on-AWS shops.

### What happens

A NAT Gateway costs an hourly rate **plus ~$0.045 per GB processed**. That per-GB charge applies
to *everything* your private-subnet workloads send to or receive from the internet — including
things you don't think of as internet traffic:

- **S3 and DynamoDB calls**, if you don't have a VPC endpoint. They resolve to public IPs, so
  they route through the NAT Gateway and you pay per GB — to talk to AWS, from AWS.
- Container image pulls (ECR, Docker Hub) on every node scale-up and every deploy.
- OS/package updates, telemetry, third-party API calls, log shipping to a SaaS vendor.

Then the part nearly everyone misses: **cross-AZ data transfer**, ~$0.01/GB each direction.
In the standard "one NAT Gateway, three AZs" layout, pods in the two *other* AZs pay the cross-AZ
surcharge on every byte, automatically. Effective rate for that traffic: **~$0.055/GB**
(0.045 NAT processing + 0.01 cross-AZ) instead of 0.045.

Worked example from a published measurement: a 3-AZ production cluster running ~400 pods was
paying **$4,200/month for NAT Gateway alone**, reduced to **$2,016** by two targeted changes.
And a smaller, sharper one: Geocodio woke up to a **$1,000 bill spike caused by a single
misrouted S3 call** going out through a NAT Gateway.

Cross-AZ charges also apply to your own internal traffic, which matters enormously for:
- **Kafka**: producers and consumers reading across AZs, plus inter-broker replication.
- **Cassandra/Elasticsearch/etcd**: replication is cross-AZ by design (that's the point of
  multi-AZ), so replication factor multiplies your transfer bill.
- **Chatty microservices** load-balanced round-robin across AZs — roughly ⅔ of internal calls
  cross an AZ boundary by default.

### Why it's hard

- **The cost is invisible in the architecture.** Nothing in your Helm chart says "$0.055/GB."
  Multi-AZ is *mandated* for availability, so the traffic pattern is one you were told to create.
- **Cost Explorer aggregates it away.** "EC2-Other" and "Data Transfer" are opaque buckets; you
  need VPC Flow Logs, Cost and Usage Report at resource granularity, or a tool that maps transfer
  to workloads to find the culprit.
- **It scales with success.** The bill grows with traffic, so it looks like normal growth until
  someone plots dollars per request.
- The rate *looks* trivial. $0.01/GB feels like nothing right up until you're moving petabytes.

### How teams fix it

Ordered by return on effort:

1. **Gateway VPC endpoints for S3 and DynamoDB — these are free.** There is no reason not to have
   them. This alone often removes the largest single chunk of NAT traffic.
2. **Interface (PrivateLink) endpoints** for ECR, CloudWatch Logs, STS, Secrets Manager, SQS, etc.
   These have their own hourly + per-GB cost, so compute the crossover — they usually win for
   ECR image pulls and log shipping.
3. **One NAT Gateway per AZ**, with route tables sending each subnet to its local NAT. Rule of
   thumb from the same analysis: **if cross-AZ traffic is >20% of NAT cost, per-AZ gateways pay
   for themselves.** (Trade-off: 3× the hourly charge, and you lose a single egress IP for
   allowlisting.)
4. **Topology-aware routing** for internal traffic: Kubernetes `topology.kubernetes.io/zone`
   hints / `trafficDistribution: PreferClose`, Envoy zone-aware routing, and Kafka's
   `rack.id` + follower fetching so consumers read from an in-AZ replica.
5. **Alarm on `BytesOutToDestination` / NAT processed bytes**, not just on the monthly bill.
   Treat a step change in egress like a latency regression.

### Transferable lesson

**Draw your architecture with the AZ boundaries visible and price each arrow.** Multi-AZ
resilience and cross-AZ cost are the same design decision viewed from two departments; make the
trade-off deliberately rather than discovering it in a quarterly review.

---

## 8.2 — Per-unit-of-work pricing: Step Functions and friends

Covered in depth in [04 — Prime Video](04-serverless-and-decomposition-limits.md#41--prime-video-vqa-the-serverless-design-that-stopped-at-5-of-target-load),
and worth restating as a cost pattern in its own right.

**Step Functions Standard workflows bill per state transition.** That is perfectly economical when
a transition corresponds to a business event (an order, a signup) and ruinous when it corresponds
to a *unit of technical work* (a video frame, a CSV row, a retry). Prime Video's pipeline had
one-or-more transitions per frame; the orchestration and the S3 hand-offs between components were
**the two most expensive operations in the entire system**, and together they capped the service
at ~5% of target load.

The same shape appears in:

| Service | Billed per | Turns expensive when |
|---|---|---|
| Step Functions (Standard) | state transition | transitions track work items, not user actions |
| S3 | request (PUT/GET/LIST) | many small objects; LIST-heavy scans; per-frame/per-row objects |
| DynamoDB | RCU/WCU (rounded up per item, per 4KB/1KB) | tiny items, or full-item writes when one attribute changed |
| SQS/SNS | request | polling without long-poll; fan-out without batching |
| CloudWatch Logs | **GB ingested** (≈$0.50/GB) | DEBUG logging left on in prod; structured logs with big payloads |
| Lambda | GB-second + request | over-provisioned memory; long waits on I/O inside the function |
| Kinesis | shard-hour + PUT payload unit | over-sharding "just in case" |

**CloudWatch Logs deserves a specific warning**: at roughly $0.50/GB *ingested*, one over-chatty
service logging full request/response bodies can cost more than the compute running it. It is a
very common #2 or #3 line item on AWS bills, and nearly always fixable in an afternoon by
sampling, dropping health-check logs, and moving to structured logs with size limits.

### Transferable lesson

**Before adopting a managed service, write down the billing unit and multiply it by your
peak-year volume.** If the billing unit scales with *work items* rather than *users*, model it
explicitly — that arithmetic is an architectural constraint, not a finance detail, and it belongs
in the design doc next to latency and durability.

---

## 8.3 — Making cost an operational signal

The remediation for every case in this file is the same: **shorten the detection loop.**

- **Budgets and anomaly detection** (AWS Cost Anomaly Detection) wired to the same channel as your
  paging alerts — not to an inbox someone reads monthly.
- **Cost and Usage Report at resource granularity**, queried from Athena, so "which service, which
  pod, which bucket" is answerable in minutes.
- **Mandatory cost-allocation tags** enforced by SCP or an IaC policy check, so every resource maps
  to a team and a service.
- **Unit economics as a dashboard**: *dollars per order*, *dollars per active tenant*, *dollars per
  1,000 API calls*. Total spend always rises; unit cost rising is the actual signal, and it's the
  only cost metric that survives growth.
- **A cost gate in design review**: one line in every design doc naming the dominant billing unit
  and the projected spend at 10× current volume.

## Sources

- [CloudZero — AWS NAT Gateway Pricing: How It Works and 6 Ways To Cut Costs](https://www.cloudzero.com/blog/reduce-nat-gateway-costs/)
- [zop.dev — How We Cut AWS NAT Gateway Costs by 52%](https://zop.dev/resources/blogs/reduce-aws-nat-gateway-costs)
- [Stack Harbor — AWS data transfer costs explained: NAT Gateway, cross-AZ, and egress](https://stackharbor.com/en/knowledge-base/awsfix-data-transfer-costs-nat-cross-az-egress/)
- [Viktar Patotski — AWS NAT Gateway Pricing: The Hidden Cost Trap](https://patotski.com/blog/nat-gateway-cost-trap/)
- [InfoQ — Prime Video Switched from Serverless to EC2 and ECS to Save Costs](https://www.infoq.com/news/2023/05/prime-ec2-ecs-saves-costs/)
- [AWS — Gateway endpoints for Amazon S3](https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints-s3.html) *(primary)*
