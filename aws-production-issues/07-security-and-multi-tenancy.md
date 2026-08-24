# 07 — Security & multi-tenancy failures

Security incidents belong in a production-issues library because on AWS they have the same
structure as availability incidents: **a small local defect plus an unbounded blast radius**.
The application bug in the case below is ordinary. What made it a 100-million-record breach
was the IAM role attached to the instance.

---

## 7.1 — Capital One, 2019: SSRF → instance metadata → IAM credentials → 700+ S3 buckets

**Company:** Capital One (AWS-primary, and a public flagship AWS customer at the time).
Impact: personal data of ~**100 million** US and **6 million** Canadian individuals; **$80M**
OCC fine; **$190M** class-action settlement.

### What happened — the chain

Every link is small. The chain is what kills.

1. **A misconfigured WAF.** A ModSecurity WAF running on EC2 was configured in a way that let a
   request reach a backend the attacker chose.
2. **An SSRF vulnerability.** The application behind the WAF could be induced to make an HTTP
   request to an attacker-supplied URL — the single most under-rated web vulnerability class in
   cloud environments.
3. **The instance metadata service, v1.** The attacker pointed the SSRF at
   `http://169.254.169.254/latest/meta-data/iam/security-credentials/`. **IMDSv1 requires no
   authentication and no headers** — a plain GET returns the instance's IAM role name, and a
   second GET returns **temporary AWS credentials** for that role.
4. **An over-permissioned role.** The role attached to the WAF instance had permissions to
   `ListBuckets` and read data across the estate.
5. **Exfiltration.** `aws s3 sync` against **700+ buckets**, roughly 30 GB of customer data.

### Why it was hard

- **Steps 1–2 are the only "bugs."** Steps 3–5 were the AWS environment working exactly as
  designed. SSRF in a datacentre is an annoyance; SSRF on EC2 with IMDSv1 is **credential
  disclosure**, because there is a well-known, unauthenticated, link-local URL that hands out
  keys to anything running on — or able to make a request through — the host.
- **The WAF should never have had data-plane permissions at all.** A traffic-filtering component
  held an IAM role that could read customer data. Nobody designed that maliciously; roles
  accumulate permissions.
- **Detection was absent.** The activity — `ListBuckets` from an instance role, then a mass `sync` —
  is anomalous but only if you're watching. The breach was reported to Capital One by an outside
  tip months after it occurred.

### How it was fixed — and what AWS changed

*Instance level:*
- **IMDSv2**, now the default posture to enforce: session-oriented, requiring a `PUT` to obtain a
  token, the token supplied in a header (`X-aws-ec2-metadata-token`), and a **TTL-limited hop count
  (`http-put-response-hop-limit`)**. Every one of those defeats basic SSRF: an SSRF that can only
  issue GETs cannot obtain a token; a hop limit of 1 stops containers and proxies reaching it.
  Enforce with `HttpTokens=required` and audit for any instance still permitting IMDSv1.
- Block `169.254.169.254` egress from application containers that have no business calling it.

*IAM level:*
- **Least privilege per workload.** A WAF/proxy role should have zero S3 data permissions.
- **Scope with conditions**, not just actions: restrict by `aws:SourceVpce`, resource ARN prefix,
  and tag. Use **S3 bucket policies** as a second, independent check — the bucket refuses the
  principal even if the principal's IAM policy allows it (defence in depth: two independent
  systems must both agree).
- **SCPs at the org level** to make certain actions structurally impossible for whole account
  classes.

*Detection level:*
- **GuardDuty** specifically detects `UnauthorizedAccess:IAMUser/InstanceCredentialExfiltration`
  — instance-role credentials used from outside the instance.
- CloudTrail data events on sensitive buckets; alarm on `ListBuckets` from roles that never
  otherwise call it.

### Transferable lesson

1. **SSRF is a cloud-credential vulnerability, not a web vulnerability.** Rate it accordingly in
   threat models and code review. Validate outbound URLs against an allowlist; resolve and check
   the IP (including redirects) before connecting; block link-local and private ranges.
2. **The blast radius of an application bug equals the permissions of the identity it runs as.**
   This is the whole lesson. You cannot prevent every SSRF/RCE; you *can* ensure the compromised
   identity can reach almost nothing.
3. **Defence in depth means two *independent* mechanisms.** IAM policy + bucket policy. WAF +
   input validation. Application filter + network egress rules. Any single mechanism will
   eventually be misconfigured.
4. **The relevant question in any design review is "what could this component read if it were
   fully compromised right now?"** If the answer is "everything," fix that before fixing anything else.

---

## 7.2 — Long-lived credentials in source control *(composite pattern)*

Endemic; the most common way AWS accounts get compromised outside of application vulnerabilities.

### What happens

A long-lived IAM **access key pair** (`AKIA...`) is committed to a repo, embedded in a mobile app
bundle, pasted into a CI config, or exposed in a public S3 bucket or container image layer.
Automated scanners sweep GitHub continuously; keys are typically found and used **within minutes**
of being pushed. Typical outcomes: crypto-mining across every region (the bill arrives before the
alert), data exfiltration, or ransoming S3 buckets.

Removing the commit does not help — the key is in the reflog, in forks, in clones, and in the
scanner's database. **Only rotation helps.**

### Why it's hard

- Long-lived keys have **no expiry by default**. A key issued in 2019 for a one-off script is
  still valid in 2026, still has its original over-broad permissions, and nobody remembers it exists.
- CI/CD, local dev, and third-party SaaS integrations all *want* a static key, because it's the
  easy path.
- Detection is asymmetric: the attacker knows instantly; you find out from the bill.

### How teams fix it

- **Eliminate long-lived keys.** IAM roles for EC2/ECS/EKS (**IRSA** / EKS Pod Identity),
  **IAM Identity Center** for humans, and **OIDC federation for CI** (GitHub Actions can assume a
  role with no stored secret at all). The goal is *zero* `AKIA` keys in the account.
- **Secret scanning in pre-commit hooks and CI**, plus GitHub push protection.
- **Short-lived credentials everywhere** (`sts:AssumeRole` with a session duration measured in
  minutes-to-hours).
- **Alarms on the shape of abuse**: new regions activated, unusual `RunInstances` volume, spend
  anomaly detection, `GetCallerIdentity` from unfamiliar ASNs.

### Transferable lesson

**Design so there is no credential to leak.** Every static secret is a permanent liability whose
expected cost only goes up with time; every workload identity that expires in an hour bounds the
damage automatically.

---

## 7.3 — Cross-tenant data leaks in multi-tenant SaaS *(composite pattern)*

The failure mode with the worst consequence-to-cause ratio in B2B SaaS: **one missing predicate
shows Customer A the data of Customer B.**

### Where they actually come from

Rarely from a missing `WHERE` clause in the main CRUD path — that gets caught. Almost always from
the paths nobody thinks of as data access:

- **Caches keyed without the tenant.** `cache.get("customer:" + id)` — correct-looking, and it
  will happily serve one tenant's object to another if IDs are not globally unique. Same for
  memoisation, HTTP caches, and CDN keys.
- **Background jobs, exports, and report builders** that run outside the request context and
  therefore outside whatever tenant filter the request pipeline applies.
- **Search indexes** (OpenSearch/Elasticsearch) where the tenant field exists but a query path
  forgets to filter it.
- **Admin/debug endpoints** and support impersonation tooling.
- **Aggregate queries** — counts, sums, dashboards — where the aggregation was written directly
  in SQL rather than through the guarded repository layer.
- **Object storage**: presigned URLs generated with a key path that doesn't encode the tenant, or
  buckets where object ACLs are the only separation.

### Why it's hard

- **It's silent.** No error, no alert. You find out when a customer emails you a screenshot of
  someone else's data. For a CRM, that's a customer's competitor's pipeline.
- **Correctness by convention doesn't survive growth.** "Every query must filter by `tenant_id`"
  works with 3 engineers and 40 queries; at 30 engineers and 2,000 queries, someone will forget,
  and the code that forgets will pass review because it looks like normal code.

### How teams fix it — structurally

The durable answer is to make the correct thing the *only* thing:

- **Database-enforced isolation**: PostgreSQL **Row-Level Security**, so the database itself
  refuses to return other tenants' rows even if the application forgets. RLS is enforced below the
  ORM, below the query builder, below the ad-hoc script someone runs at 2 a.m.
- **Framework-enforced filtering**: Hibernate's `@TenantId`, or an equivalent interceptor, so the
  predicate is added by the framework rather than by the author.
- **Tenant from the token only.** The tenant ID must come from the authenticated principal
  (JWT claim), never from a request parameter, header, or path segment the client controls.
  A client-supplied tenant ID is an IDOR waiting to happen.
- **Build-time enforcement**: an ArchUnit/lint rule that fails the build if a new entity lacks the
  tenant annotation, or if raw SQL appears outside an approved package. This converts a
  code-review responsibility into a compiler responsibility.
- **Tenant-aware cache keys by construction** — a cache wrapper that takes the tenant from context
  and prefixes every key, so a caller *cannot* write an unscoped key.
- **Tests that specifically try to cross the boundary**: seed two tenants, authenticate as one,
  and assert 404 (not 403 — don't confirm existence) on every endpoint for the other's IDs.

### Transferable lesson

This is exactly the [S3 2017](01-control-plane-dns-and-metastable-failure.md#13--aws-s3-28-february-2017-a-typo-in-a-runbook-command)
and [Atlassian](06-automation-blast-radius.md#62--atlassian-5-april-2022-883-customer-sites-deleted-by-a-maintenance-script)
lesson in a different costume:

> **Guardrails belong in the system, not in the discipline of the person writing the code.**
> Procedural correctness ("remember to filter by tenant", "remember to check the ID type",
> "be careful with that command") fails at scale, 100% of the time, eventually.
> Structural correctness — RLS, `@TenantId`, typed IDs, tool-enforced capacity floors —
> fails closed.

*(This is the principle already codified in this repo's `CLAUDE.md`: "Tenant isolation is
structural, not procedural.")*

## Sources

- [Appsecco — An SSRF, privileged AWS keys and the Capital One breach](https://blog.appsecco.com/an-ssrf-privileged-aws-keys-and-the-capital-one-breach-4c3c2cded3af)
- [Zscaler — Lessons Learned from the Capital One Data Breach (PDF)](https://www.zscaler.com/resources/white-papers/capital-one-data-breach.pdf)
- [hackaws.cloud — The Capital One Breach, Seven Years Later: The Blast Radius Problem](https://hackaws.cloud/blog/capital-one-ssrf-imds-blast-radius)
- [AWS — Use IMDSv2 (EC2 User Guide)](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html) *(primary)*
- [AWS — GuardDuty finding: UnauthorizedAccess:IAMUser/InstanceCredentialExfiltration](https://docs.aws.amazon.com/guardduty/latest/ug/guardduty_finding-types-iam.html) *(primary)*
- [PostgreSQL — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) *(primary)*
