# Support — Break Compliance for Clockify

_Last updated: 2026-05-13._

## Contact

**Email:** `petkovic.aleksandar037@gmail.com`

Use this address for:

- Bug reports (server errors, sidebar rendering issues, unexpected
  findings, install failures).
- Configuration questions (which threshold does what, how presets
  compose with per-field overrides).
- Privacy / security disclosures (see also `docs/PRIVACY.md` and
  `docs/SECURITY.md`).
- Data-subject requests (right-to-know, right-to-erasure, right-to-
  rectification — see `docs/PRIVACY.md` §"How users exercise their
  rights").

## Response expectation

- **Acknowledgement**: within **7 business days** of receipt.
- **Security disclosures**: prioritised — we aim to acknowledge
  within 2 business days and ship a fix or mitigation note within 7
  calendar days.
- **General bug reports / questions**: best-effort. Solo-maintained
  open-source project; expect 1-2 week turnaround for non-critical
  issues.

We do **not** offer:

- A formal SLA, on-call rotation, or guaranteed uptime contract.
- Phone or chat support.
- Custom feature development on commission.

If your organisation needs any of the above, treat Break Compliance
as you would any other community add-on and budget accordingly.

## What to include in a bug report

A good bug report has all of the following — missing items make the
turnaround slower:

1. **Workspace id** (visible in the URL as
   `/workspaces/<id>/...`). We never ask for the workspace name or
   any user identifier — just the id.
2. **Time of the incident** (UTC if you can, or your timezone and
   wall-clock time). Helps us correlate with Railway logs.
3. **What you expected** (e.g. "no finding for Wednesday — the
   policy is a 4-hour threshold and Alex's longest stretch was 3h
   59m") vs **what you observed** (e.g. "a MAX_CONTINUOUS_WORK
   finding fired at 16:42").
4. **The sidebar screenshot** if applicable, with personally
   identifiable workspace member names redacted at your discretion.
5. **The relevant `errorCode`** if the bug surfaced as a
   `503 installation_inactive` or similar — see
   `/api/ingest/runs/{id}` for the full run object.

## What we will need from you for a privacy/erasure request

- Workspace id.
- Whether you want the data deleted (uninstall the add-on yourself —
  the DELETED lifecycle wipes everything automatically; see
  `docs/DATA_RETENTION.md`), or a CSV/JSON export delivered out of
  band first.
- A reply-to email address.

## Status / incident communication

There is no public status page. Production incidents are surfaced
inline:

- The Railway service health is the single source of truth — if
  `/healthz` is non-200 the add-on is degraded; the marketplace iframe
  will surface "Reports unavailable" or a similar inline banner.
- For critical / data-affecting incidents we will email installed
  workspaces' install-time admin contact (the `claims.user` we
  captured at install) within 7 days of detection.

## Reporting a vulnerability

Please email `petkovic.aleksandar037@gmail.com` with subject
`[VULN] Break Compliance — <one-line summary>`. We will acknowledge
within 2 business days, agree a disclosure window with you, and
backport the fix to the deployed Railway service before publishing.

We support **responsible disclosure**: do not exploit the
vulnerability beyond the minimum needed to reproduce it; do not
exfiltrate or alter workspace data; and please do not run automated
scanners against the live deploy without a heads-up.
