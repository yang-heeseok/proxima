# Security policy

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Settled. This file discharges `PUB-5` — a security contact exists.

## What this document does not own

| Question | Owner |
| --- | --- |
| What may never enter this repository | `docs/decisions/publication-readiness.md` — `PUB-1`, `PUB-7` |
| What the seed contains and how it is produced | `docs/explanation/domain-model.md` |
| A vulnerability in Spring, Hibernate, or PostgreSQL | Upstream's own tracker — though we would like to hear of it too |
| A finding about how this repository measures things | Not a vulnerability. Open an issue |

## Reporting a vulnerability

Send reports to **gseek@gseek.net**.

- You will receive an acknowledgement **within seven days**.
- Please do not open a public issue for a vulnerability before it has been acknowledged
  and assessed.
- There is no bug bounty. Credit is given in the fix's notes unless you ask otherwise.

## Reporting exposed learner data

**This is a separate and faster path.** If you find what appears to be a real learner
record — an identifier, a behavioural series, anything that describes a specific person
rather than a generated one — in this repository or its history, report it to the address
above with **`[PII]`** in the subject line.

It is treated as an incident rather than a defect. History cannot be erased, so the
response is containment and notification, not deletion — and the sooner it starts the
smaller it is. You do not need to be certain; a false alarm costs an email.

This project's domain is education, so a record here frequently describes a minor. That
is the reason for the separate path, and the reason `PUB-7` blocks a file class rather
than reviewing files one at a time.

## A note on what this repository deliberately contains

Several of the reports under `docs/reports/` describe defects that were **reproduced on
purpose** — a connection pool exhausted under load, a race that loses updates, an
authorisation check that can be walked around. Those are documented failures in this
repository's own history, each paired with the commit that fixed it and the gate that
keeps it fixed.

They are not undisclosed vulnerabilities. If you find one that is described but **not**
fixed, and the report does not say so under *남는 위험 / Remaining risk*, that gap is
itself worth reporting.
