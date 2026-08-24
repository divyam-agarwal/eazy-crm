# 08 — DNS, TLS, domains & vendor dependencies

Outages caused by things **you do not operate and cannot fix** — a registrar, a certificate
authority, a registry's rate limit. For a small team these are uniquely painful: there is no
mitigation you can deploy, no rollback, and often no support contract. The entire defence is
*preparation done months earlier*.

---

## Notion, 12 February 2021 — the registrar switched off the domain

**Company:** Notion. **Impact: hours-long total outage; 4+ million users unable to reach their
files.** Not a single server was unhealthy.

### What happened

A user created a Notion page that **linked out to a phishing site** hosted elsewhere. Notion did
not flag it. Phishing complaints were filed against the `notion.so` domain.

The domain was then **suspended**, and every Notion user lost access.

The mechanism matters, because it explains why Notion couldn't just fix it: `notion.so` is a
**`.so` domain**. Notion registered it through **Name.com** — but all `.so` domains are managed via
**Hexonet**, which connects **Sonic** (the `.so` registry) to registrars like Name.com. So the
takedown decision, and the ability to reverse it, sat several parties away from Notion, in a chain
Notion had no commercial relationship with and no escalation path into.

Notion said afterwards that a new procedure should prevent a recurrence. Separately — and this is
the structural fix — a Notion employee had noted almost a year earlier that Notion would "soon"
move to **notion.com**, which the company already owned.

### Why it was hard

- **You cannot engineer around a suspended domain.** No amount of multi-region, multi-cloud, or
  redundant infrastructure helps. DNS resolution for your apex name is a **hard single point of
  failure** above everything else you build.
- **The trigger came from user-generated content.** Any product where users can publish content —
  a CRM with public forms, a docs tool, a link shortener, a file-sharing feature — inherits the
  reputation risk of everything its users publish.
- **Novelty TLDs concentrate this risk.** A `.so`, `.io`, `.ly`, `.gg` or similar domain adds
  registry operators, sometimes in small jurisdictions, with less mature abuse processes and no
  path for you to escalate. The cute domain is a supply-chain dependency.

### Transferable lessons

1. **Own the `.com`, and use it for anything critical.** Cheap insurance against an entire class
   of risk. Notion had already bought theirs.
2. **Take abuse reports seriously and answer them fast**, with a monitored, published abuse contact.
   The suspension happened partly because complaints went unaddressed.
3. **Detect and block phishing in user-generated content** — scan outbound links against
   reputation feeds, rate-limit new-account publishing, require verification before content is
   publicly indexable.
4. **Keep the API and the marketing site on separate domains** where practical, so a takedown of
   one doesn't kill the other.
5. **Know your registrar chain**: registrar → reseller → registry, plus who can suspend you and
   what their process is. Enable **registrar lock**. Ensure domain renewal is on a corporate card
   that doesn't expire with an employee, with alerts to a shared inbox — a lapsed renewal is the
   same outage with a stupider cause.

---

## Let's Encrypt DST Root CA X3 expiry, 30 September 2021

**Impact:** widespread TLS failures across older clients, devices and API integrations —
disproportionately painful for small companies, who were both consumers and providers of the
broken connections.

### What happened

Let's Encrypt certificates had long chained to the **DST Root CA X3** cross-signature, which
provided trust on older devices that predated Let's Encrypt's own **ISRG Root X1**. On
**30 September 2021** that cross-sign **expired**.

The subtle part — and the reason it surprised well-prepared teams:

> In **OpenSSL 1.0.x**, a quirk in certificate-chain verification means that **even clients that
> trust ISRG Root X1 will fail** when presented with the Android-compatible chain Let's Encrypt
> serves by default. Clients need **OpenSSL 1.1.0 or later**.

So "we trust the new root" was not sufficient. Older Android devices, IoT devices, embedded
clients, and — most damagingly for B2B SaaS — **server-side HTTP clients on older base images**
began failing certificate verification. Symptoms were the usual `certificate verify failed` /
`unable to get local issuer certificate`, appearing simultaneously across unrelated integrations.

### Transferable lessons

1. **Certificate expiries are scheduled outages you're allowed to prepare for.** Root and
   intermediate CA expiry dates are public years ahead. So are your own certs'.
2. **Monitor certificate expiry as a first-class alert** — your own certs (30/14/7 days), *and*
   the certs of critical dependencies. Automated renewal that silently stops renewing is the same
   failure as GitLab's silently failing backups.
3. **Your TLS client stack is a dependency with a version.** Old base images ship old OpenSSL.
   "It works on my machine" and "it works in the container built two years ago" are different claims.
4. **Test with the oldest client you support**, not just a current browser. If you have mobile apps,
   IoT devices, or enterprise customers on old runtimes, they are your long tail — and they will all
   break on the same day.

---

## Docker Hub rate limits, from November 2020

**Impact:** CI pipelines and Kubernetes clusters worldwide began failing to pull images —
`toomanyrequests: You have reached your pull rate limit` and `ImagePullBackOff` — with no code
change on anyone's part.

### What happened

Docker Hub introduced pull rate limits: **100 pulls per 6 hours for anonymous users**, **200 for
authenticated free users**. The limits are applied **per source IP** for anonymous pulls.

That last detail is what turned a pricing change into an outage. If your CI runners, Kubernetes
nodes, or developer machines sit **behind NAT or a corporate proxy** — that is, essentially all
of them — they **share a single public IP and therefore a single 100-pull quota**. A moderately
busy CI system exhausts it in minutes. A cluster that autoscales, or a rolling deploy across many
nodes, does it faster.

The failure surfaces at the worst times: during a scale-up (new nodes can't pull), during a deploy
(new pods can't start), during an incident (you can't roll back, because the rollback also needs
to pull an image).

### Transferable lessons

1. **A free tier is not an SLA.** Anything free and critical is a dependency awaiting a pricing
   change. Enumerate yours: container registries, package registries (npm/PyPI/Maven), CDNs, free
   API tiers, public CI minutes, fonts, analytics scripts.
2. **Cache or mirror everything you pull in CI and at deploy time.** A pull-through cache
   (ECR pull-through, Artifactory, Nexus, or a registry mirror) removes an external dependency from
   your deploy path entirely. Deploys and rollbacks should not require the public internet.
3. **Pin by digest, not by tag.** `:latest` is a moving target that can change under you; a digest
   is immutable and reproducible.
4. **Authenticate even when you don't have to** — free authenticated limits are typically higher
   than anonymous, and they're per-account rather than per-IP.
5. **Ask of every deploy-path dependency: "what happens during an incident if this is unavailable?"**
   If the answer is "we cannot roll back," fix it before you need to.

---

## The general shape: third-party single points of failure

For a small SaaS the vendor list *is* the architecture: payments, email, SMS/WhatsApp, auth, CDN,
DNS, object storage, error tracking, analytics, the container registry, the package registry.

**A practical exercise — do this once, it takes an hour:**

For each vendor, write one line: **what breaks, how fast we notice, what we do.**

| Vendor class | If it's down… | Cheap mitigation |
|---|---|---|
| DNS / registrar | total outage, unfixable | own the `.com`; registrar lock; secondary DNS provider; alerts on domain expiry |
| CDN / edge | total outage or severe latency | ability to fail back to origin; DNS TTLs low enough to move |
| Payments | can't take money | queue and retry; never lose the intent; reconcile later |
| Transactional email | users don't get signup/reset mails | a second provider configured and tested, switchable by config |
| SMS / WhatsApp | OTP and notifications fail | fallback channel; don't make OTP the only auth path |
| Auth provider | nobody can log in | session lifetimes long enough to ride out a short outage |
| Object storage | uploads/downloads fail | degrade gracefully; don't block core flows on media |
| Container / package registry | **can't deploy or roll back** | mirror or cache; pin digests |
| Error tracking / analytics | you're blind | must never be in the request path; fail open, never block |

**Three rules that cover most of it:**
1. **Nothing non-essential belongs in the request path.** Analytics, feature flags, and error
   tracking should fail open with a short timeout, never block a user request.
2. **For anything that touches money or auth, have a second provider configured** — even if unused —
   so switching is a config change, not a project.
3. **Your status page and your incident comms must not depend on your own infrastructure.**
   (Compare AWS in 2017, whose Service Health Dashboard was hosted on the S3 that was down.)

## Sources

- [TechCrunch — Notion's hours-long outage was caused by phishing complaints](https://techcrunch.com/2021/02/15/notions-hours-long-outage-was-caused-by-phishing-complaints)
- [TechCrunch — Online workspace startup Notion hit by outage, citing DNS issues](https://techcrunch.com/2021/02/12/notion-outage-dns-domain-issues/)
- [Data Center Dynamics — Phishing complaints cause Notion outage](https://www.datacenterdynamics.com/en/news/phishing-complaints-cause-notion-outage/)
- [Let's Encrypt — DST Root CA X3 Expiration (September 2021)](https://letsencrypt.org/docs/dst-root-ca-x3-expiration-september-2021/) *(primary)*
- [Scott Helme — Let's Encrypt's Root Certificate is expiring!](https://scotthelme.co.uk/lets-encrypt-old-root-expiration/)
- [Docker — Docker Hub rate limits](https://docs.docker.com/docker-hub/usage/) *(primary)*
- [AWS — Advice for customers dealing with Docker Hub rate limits](https://aws.amazon.com/blogs/containers/advice-for-customers-dealing-with-docker-hub-rate-limits-and-a-coming-soon-announcement/)
