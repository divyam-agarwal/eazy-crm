# 07 — Security, supply chain & credentials

Two incidents at small-to-mid developer-tooling companies. Both share the shape that makes this
class so dangerous for startups: **the initial compromise was small and ordinary; the blast radius
was everything the company held on behalf of its customers.**

If your product holds customer credentials — a CI system, a deploy tool, an integration platform, a
CRM with connected accounts — you are a **credential aggregator**, and your threat model is not
"someone attacks us" but "someone attacks our customers *through* us."

---

## Codecov, January–April 2021 — a credential in a Docker image layer

**Company:** Codecov (code-coverage tooling; small company, **~23,000 customers**).
**Impact: a supply-chain compromise that ran undetected for two months** and exfiltrated CI secrets
from customer build environments. Downstream victims publicly included **Twilio, HashiCorp, Rapid7
and Confluent.**

### What happened

1. **The leak.** Codecov published a public **Docker image** for its self-hosted product. Their
   image-creation process was misconfigured such that an **HMAC key for a Google Cloud Storage
   service account was left in an intermediate layer** of that public image.

   The nuance that makes this so easy to do: **deleting a file in a later Dockerfile layer does not
   remove it from the image.** Every layer is retained and independently extractable. A `COPY` of a
   secret followed by a `RUN rm` looks clean in the final filesystem and is trivially recoverable
   from the layer history.

2. **The pivot.** The attacker extracted that key and used it to **modify the Bash Uploader script
   stored in Google Cloud Storage** — the script tens of thousands of CI pipelines download and
   execute on every build, typically via `curl | bash`.

3. **The payload.** The modified uploader exfiltrated the **environment variables of the CI job it
   ran in**: AWS IAM keys, deploy keys, API keys, service-account credentials, tokens, passwords —
   whatever each customer's pipeline happened to hold.

4. **The window.** The script was malicious from **31 January to 1 April 2021** — roughly two
   months. It was found by **a customer who checked the shasum** of the downloaded script against
   the published one. Not by Codecov's monitoring.

### Remediations

- All public images **squashed and/or converted to multi-stage builds** so no intermediate layer
  retains secrets.
- The Bash Uploader was replaced with a **signed, SHASUM-verifiable binary**, and later sunset entirely.

### Transferable lessons

1. **Never `COPY` a secret into an image, at any stage.** Use build secrets (`--mount=type=secret`),
   multi-stage builds that never carry the secret to the final stage, or runtime injection. Then
   **scan your published images for secrets** — the tools are free and take minutes to wire in.
2. **`curl | bash` from a mutable URL is remote code execution with extra steps.** If you ship one,
   ship a signature or checksum and tell customers to verify it. If you *consume* one, pin and verify.
3. **CI environment variables are the crown jewels.** They are, by design, a concentrated pile of
   production credentials sitting in an environment that executes third-party code on every commit.
   Treat CI as production for security purposes: short-lived OIDC credentials instead of static keys,
   least privilege per pipeline, no shared org-wide secrets.
4. **Two months of undetected modification** is the real finding. Integrity monitoring on artifacts
   you publish — does the file in the bucket still match what we built? — is cheap and was absent.

---

## CircleCI, December 2022 – January 2023 — one laptop, every customer's secrets

**Company:** CircleCI (CI/CD platform). **Impact:** customer **environment variables, tokens and
keys** exfiltrated; every customer instructed to **rotate all secrets** stored in the platform.

### The timeline

| Date | Event |
|---|---|
| **16 Dec 2022** | An engineer's laptop is compromised with malware that **evades antivirus** |
| | The malware performs **session cookie theft** — stealing a **2FA-backed SSO session cookie** |
| **19 Dec** | Attacker performs reconnaissance, impersonating the engineer from a remote location |
| **22 Dec** | **Data exfiltration** from a subset of production databases and stores |
| **29 Dec** | **A customer reports suspicious GitHub OAuth activity** — the first signal |
| **4 Jan 2023** | CircleCI publicly discloses and tells all customers to rotate every secret |

### Why the defences didn't hold

- **2FA was in place and was bypassed.** Not broken — *bypassed*. The malware stole the session
  cookie **after** authentication succeeded. **A valid session is a bearer token, and MFA protects
  the login, not the session.**
- **The engineer's privileges were legitimate.** They could generate production access tokens as
  part of their normal duties. The attacker inherited exactly that — no privilege escalation
  exploit needed, just impersonation.
- **Encryption at rest did not help.** The data was encrypted at rest, but the attacker **obtained
  the encryption keys from running processes**. Encryption at rest defends against stolen disks, not
  against an attacker who is already inside the process boundary. This is worth internalising,
  because "it's encrypted at rest" is frequently offered as though it answers this threat.
- **Detection came from a customer**, seven days after exfiltration and thirteen days after the
  initial compromise.

### Remediations committed to

Automatic OAuth token rotation; migration to **GitHub Apps** (granular, scoped permissions rather
than broad OAuth); expanded monitoring and detection; **additional authentication factors**;
production access restricted to a minimal set of staff; and making it easier for customers to adopt
**OIDC tokens** (short-lived, per-job, no stored secret) and **IP ranges**.

### Transferable lessons

1. **Short-lived, scoped credentials beat long-lived ones — for you and for your customers.** The
   durable fix on both sides is OIDC: the pipeline exchanges an identity assertion for a credential
   that expires in minutes. There is nothing stored to steal.
2. **Bind sessions to something the attacker can't copy.** Device-bound / token-binding sessions,
   IP or ASN change detection, aggressive re-auth for privileged actions. A stolen cookie should not
   be usable from a new device on another continent.
3. **Standing production access is the risk.** Move to just-in-time, approved, time-boxed elevation
   with an audit trail. The question to ask about every engineer: *"if their laptop were compromised
   right now, what could the attacker reach without triggering anything?"*
4. **Assume endpoint compromise.** An engineer's laptop is the softest target you have and the one
   with the most access. Design so that owning it is not the same as owning production.
5. **Have a rotation plan before you need one.** CircleCI's advice to customers — rotate everything —
   is only actionable if you know where your secrets are and can rotate them without a week of
   downtime. Rehearse a full credential rotation once. Most teams discover they can't.
6. **Publish quickly and specifically.** Both companies did, and it's the reason these incidents are
   useful to everyone else. That's a norm worth upholding.

---

## The composite pattern: static credentials

Neither incident above is exotic. The endemic version, which is how most small companies actually
get compromised:

**Where keys leak:** committed to a repo (public *or* private — private repos get cloned, forked,
and eventually made public); embedded in a mobile app bundle or frontend JS; pasted into a CI
config, a Slack message, an issue tracker, or an LLM prompt; left in a Docker image layer (Codecov);
exposed in a public S3 bucket or an unauthenticated `/debug` endpoint.

**What happens next:** automated scanners sweep public repos continuously; keys are typically
found and used **within minutes** of being pushed. Outcomes: crypto-mining across every region
(the bill arrives before the alert), data exfiltration, or bucket ransoming.

**Removing the commit does not help** — the key survives in the reflog, in forks, in clones, in
CI caches, and in the scanner's database. **Only rotation helps.**

**The fixes, in order:**
1. **Eliminate long-lived keys.** Workload identity (IAM roles, IRSA/Pod Identity), SSO for humans,
   **OIDC federation for CI** so there is no stored secret at all.
2. **Secret scanning in pre-commit hooks and in CI**, plus push protection on the host.
3. **Scope every credential** to the minimum resource and action, and give it an expiry.
4. **Alarm on the shape of abuse**: new regions activated, unusual instance launches, spend
   anomalies, API calls from unfamiliar networks.
5. **Know your rotation runbook** and test it.

> The unifying principle across all three cases: **design so that there is no credential to steal,
> and so that stealing one buys the attacker as little as possible.** You cannot prevent every
> compromise; you can bound what a compromise is worth.

## Sources

- [Codecov — Post-Mortem / Root Cause Analysis (April 2021)](https://about.codecov.io/apr-2021-post-mortem/) *(primary)*
- [Codecov — Bash Uploader Security Update](https://about.codecov.io/security-update/) *(primary)*
- [Rapid7 — Analysis of the Codecov supply chain compromise](https://www.rapid7.com/blog/post/2021/04/16/codecov-discloses-supply-chain-compromise/)
- [CircleCI — Incident report for January 4, 2023 security incident](https://circleci.com/blog/jan-4-2023-incident-report) *(primary)*
- [Help Net Security — CircleCI breach post-mortem: attackers got in by stealing an engineer's session cookie](https://www.helpnetsecurity.com/2023/01/16/circleci-breach/)
