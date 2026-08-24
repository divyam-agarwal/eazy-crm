# 05 — Kubernetes & networking on AWS

Running Kubernetes on AWS means your pods are first-class VPC citizens — which is elegant,
and which means **VPC limits become application limits**. This section covers the two most
common ways that bites, plus the instance-level network allowances almost nobody accounts for.

---

## 5.1 — Adevinta: running out of IP addresses in EKS

**Company:** Adevinta (large classifieds group; AWS-primary, EKS). Notable because they
caught it *before* the outage.

### What happened

The **Amazon VPC CNI** gives every pod a **real IP address from the VPC subnet**. That's what
makes EKS networking transparent — security groups, VPC flow logs, and routing all work on pods
exactly as they do on instances. The cost is that **your maximum pod count is bounded by your
subnet size**, not by your CPU or memory.

Worse, the CNI keeps a **warm pool**: each node pre-allocates a batch of IPs to its ENIs so pods
start fast. So consumption is not `1 IP per pod` — it's `1 IP per pod + warm pool per node`, and
it grows in steps every time a node joins.

During Adevinta's migration to EKS, they hit:

```
failed to assign an IP address to container
```

on **DaemonSets** — meaning core platform components (logging, monitoring, CNI itself) could not
start on new nodes. They had to **pause the EKS migration in September** because IP availability
was critically low.

### Why it was hard

- **CIDR sizing is a decision made once, early, by whoever created the VPC** — usually before
  anyone knew the cluster would run 20,000 pods. Resizing a subnet in place is not possible;
  you can only add secondary CIDRs.
- The exhaustion symptom is a **pod-scheduling failure**, which looks like a Kubernetes problem
  and sends you down the wrong debugging path.
- It fails **exactly during scale-up** — that is, during a traffic spike or a node replacement,
  which is precisely when you need new pods.
- DaemonSets failing first means your **observability dies before your workload does**.

### How they fixed it

They evaluated three options and chose on *risk under time pressure*, not on elegance:

| Option | Verdict |
|---|---|
| Replace the CNI with **Cilium** (overlay networking, pods get non-VPC IPs) | Solves it completely; unknown unknowns, needed resources they didn't have |
| **IPv6** cluster networking | Lower risk than Cilium, still a significant time investment |
| **Secondary CIDR + custom networking** | **Chosen** — pragmatic, effective, fits the timeline |

**Custom networking** works by attaching an additional (typically non-routable, e.g. `100.64.0.0/10`
CGNAT space) CIDR to the VPC and declaring, via an `ENIConfig` custom resource, that *pod* IPs come
from subnets in that new range while *node* IPs stay in the original space. Pods get a huge new
address pool without renumbering anything.

They rolled it out to **smaller clusters first**, then across the fleet.

**The Karpenter gotcha:** later, migrating to Karpenter for node provisioning, they found Karpenter
**was not aware of custom networking**. With custom networking, the node's *first* ENI is reserved
and not usable for pod IPs — so Karpenter's computed pod capacity per instance type was wrong,
and it made bad provisioning decisions. Fix: pass **`--reserved-enis`** so Karpenter's capacity
calculation accounts for the reserved ENI.

### Transferable lesson

1. **Alarm on free IPs per subnet.** It is a trivial CloudWatch/Prometheus metric and almost nobody
   has it. Treat "available IPs" like disk space.
2. **Also consider prefix delegation** (`ENABLE_PREFIX_DELEGATION=true`): each ENI is assigned a
   `/28` prefix (16 IPs) instead of individual secondary IPs, massively increasing pod density per
   node and reducing EC2 API calls. It's often the cheapest first move if your CIDR has room.
3. **Every layer that "computes capacity" must agree on the capacity model.** Karpenter, the CNI,
   and the scheduler each had a view of "how many pods fit here"; when one was wrong, provisioning
   broke. Look for this class of bug wherever two components independently derive the same number.
4. **Size CIDRs for the 5-year cluster, not the launch cluster** — and keep spare secondary CIDR
   space reserved from day one.

---

## 5.2 — Honeycomb: EC2 network allowances throttled their Kafka brokers

**Company:** Honeycomb (AWS-primary; self-managed Kafka on EC2, later EKS).

### What happened

During a month of accumulated incidents, Honeycomb found that **their Kafka brokers were being
throttled by AWS over network allowances**.

This is one of the least-known EC2 behaviours. Every instance type has a documented network
bandwidth figure — but for most types that figure is a **burst** allowance backed by a credit
bucket, with a much lower **baseline** sustained rate. Exceed the baseline for long enough and
EC2 silently **shapes your traffic**: packets are queued and dropped at the hypervisor.

For Kafka this is maximally painful, because Kafka's traffic is a multiplier of your ingest:
every produced byte is also **replicated to N-1 followers** and then **fetched by every consumer
group**. A 100 MB/s ingest can easily be 500 MB/s of instance NIC traffic. Brokers hit the
allowance long before CPU or disk looks interesting.

Honeycomb also hit two adjacent problems in the same period: **EXT4 filesystem corruption and
crashes on their retriever instances** (a Linux kernel version bug, not an AWS bug — but it
presented as random instance failures), and their **main RDS instance simply becoming undersized**.

### Why it was hard

- **The throttle is invisible in the usual dashboards.** CPU is fine, disk is fine, and Kafka just
  gets slower with mysterious replication lag and consumer lag. The relevant signals are ENA
  driver metrics — `bw_in_allowance_exceeded`, `bw_out_allowance_exceeded`,
  `pps_allowance_exceeded`, `conntrack_allowance_exceeded` — which are **not** collected by default.
- Credit-based burst allowances mean the system passes load tests (short bursts) and fails in
  steady state (sustained load). The failure is time-dependent.
- The instinctive fix — add more brokers — spreads partitions but also **adds replication traffic**,
  and may not help if the hot partitions stay put.

### How they fixed it

*Immediate:* **scale vertically** to instance types with larger network allowances, and do it
surgically — **migrate the most impacted partitions to the larger instances first**, then move the
rest gradually over several days rather than attempting a big-bang cluster change.

*Durable:* Honeycomb subsequently re-platformed Kafka entirely — from self-hosted Confluent Platform
with ZooKeeper on EC2, to **open-source Apache Kafka in KRaft mode on EKS** — removing ZooKeeper as
a failure domain and making broker replacement a routine, automated operation instead of an event.

Their own stated conclusion is the valuable bit: rather than chasing individual component limits,
growth has to be planned against **costs, known bottlenecks, an explicit scaling model, and
expectations** — i.e. capacity planning as a continuous practice, not an incident response.

### Transferable lesson

1. **Collect ENA allowance metrics on every network-heavy instance.** `bw_*_allowance_exceeded`,
   `pps_allowance_exceeded`, and especially **`conntrack_allowance_exceeded`** (which throttles
   *connections*, not bandwidth, and is a notorious cause of "random" connection failures on
   NAT-heavy or high-fan-out workloads).
2. **For replicated systems, compute NIC traffic as a multiple of application traffic** —
   `ingest × (replication_factor + consumer_groups)` — before choosing an instance type.
3. **Baseline vs. burst is the general trap.** It applies to EC2 network, EBS `gp2`/`gp3` IOPS,
   T-family CPU credits, and Aurora Serverless scaling. Any resource with a credit bucket will
   pass your load test and fail your Tuesday.
4. **Migrate the hot partitions first.** When scaling a partitioned system under duress, target
   the actual hotspot rather than uniformly rebuilding the fleet.

---

## 5.3 — Related: mesh-level networking failures

DoorDash's May 2022 retry-storm-plus-misconfigured-circuit-breaker incident, and their subsequent
move to an Envoy service mesh across ~1,000 services on ~2,000 Kubernetes nodes, is covered in
[02 — Saturation, thundering herds & retry storms](02-saturation-thundering-herds-and-retry-storms.md#23--doordash-may-2022-the-circuit-breaker-that-caused-the-outage).
It belongs to this section too: once you're at that scale on EKS, **retry policy, timeouts, and
circuit breaking are networking configuration**, and leaving them in application code means you
have 1,000 different, untested, unobservable networking configurations.

---

## Quick reference: AWS limits that become Kubernetes limits

| Limit | Where it bites | Default signal | Fix |
|---|---|---|---|
| Subnet free IPs | Pod scheduling fails on scale-up | `failed to assign an IP address to container` | Prefix delegation; secondary CIDR + custom networking; IPv6; overlay CNI |
| IPs (and pods) per ENI, ENIs per instance | Pod density per node lower than memory allows | Pods `Pending` with nodes idle | Prefix delegation; larger instance types |
| EC2 network baseline vs. burst | Replication/consumer lag, mystery latency | *(none by default)* — ENA `bw_*_allowance_exceeded` | Bigger instance family; spread traffic |
| `conntrack_allowance_exceeded` | Random connection resets under fan-out | *(none by default)* | Fewer/longer-lived connections; larger instances |
| NAT Gateway throughput & per-GB cost | Egress-heavy workloads | Latency + a large bill | VPC endpoints; per-AZ NAT — see [08](08-cost-incidents.md) |
| EC2 API rate limits | Slow/failed node scale-up during a spike | `RequestLimitExceeded` in autoscaler logs | Backoff, fewer larger nodes, warm pools |

## Sources

- [Adevinta — How we avoided an outage caused by running out of IPs in EKS](https://adevinta.com/techblog/how-we-avoided-an-outage-caused-by-running-out-of-IPs-in-EKS/) *(primary)*
- [AWS Containers Blog — Automating custom networking to solve IPv4 exhaustion in Amazon EKS](https://aws.amazon.com/blogs/containers/automating-custom-networking-to-solve-ipv4-exhaustion-in-amazon-eks/) *(primary)*
- [Saifeddine Rajhi — Tackling IPv4 Address Exhaustion in Amazon EKS Clusters](https://seifrajhi.github.io/blog/eks-avoid-ip-exhaustion-practices/)
- [Honeycomb — Incident Resolution: Do You Remember, the Twenty Fires of September?](https://www.honeycomb.io/blog/incident-resolution-september-retrospective/) *(primary)*
- [Honeycomb — Transforming How We Run Kafka at Honeycomb](https://www.honeycomb.io/blog/transforming-how-we-run-kafka-honeycomb) *(primary)*
- [DoorDash — Inside DoorDash's Service Mesh Journey, Part 1](https://careersatdoordash.com/blog/inside-doordashs-service-mesh-journey-part-1-migration-at-scale/) *(primary)*
