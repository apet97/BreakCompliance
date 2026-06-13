# Marketplace Listing — Break Compliance

Source-of-truth copy for the Clockify Marketplace submission. Update
here first, then mirror into the dev portal listing form.

## One-liner (≤ 80 chars)

> Catches missed breaks and over-long shifts in your team's Clockify time entries.

## Short description (≤ 140 chars, used in tile)

> Advisory break-compliance review for Clockify workspaces. German ArbZG and California IWC presets plus per-workspace custom thresholds.

## Long description

Break Compliance is a sidebar add-on for Clockify workspaces that
reviews whether users took the breaks your policy requires. It reads
time entries via the Detailed Report API, evaluates them against a
configurable rule template, and surfaces findings to admins through a
read-only sidebar — never modifying entries, never starting or
stopping timers, never enforcing a rule against a worker.

The engine ships with three starter templates:

- **Custom (basic)** — the default. 4-hour work threshold, 15-minute
  required break, 5-minute minimum segment. Tune to your policy.
- **Germany (ArbZG §3 & §4)** — 6-hour shift = 30-minute break,
  9-hour shift = 45-minute break, segments ≥ 15 min, no split breaks.
- **California (IWC Meal & Rest)** — 5-hour shift = 30-minute meal
  break, single uninterrupted segment.

The add-on never claims legal compliance with any specific law. See
`docs/LEGAL_NOTICES.md` for the full advisory disclaimer.

## What admins see

- **Settings tab** (Workspace Settings → Add-ons → Break Compliance):
  ten threshold fields plus a "Detect missing break entries" toggle
  for workspaces that record breaks by stopping the timer rather than
  logging a dedicated BREAK entry.
- **Sidebar** (workspace-wide, admin-visible): preset chooser with
  preview, "Customized — Reset to <preset>?" indicator, last
  successful refresh time, findings list (severity, code, message,
  evidence), and a "Refresh" button that re-pulls the Detailed
  Report and re-evaluates.
- **Webhook-driven refresh**: when time entries change in Clockify,
  the add-on debounces (≤ 30s) and re-ingests automatically — admins
  don't have to remember to click Refresh.

## Feature bullets (for the marketplace tile)

- Advisory only — never modifies time entries, never enforces a rule.
- Ten configurable thresholds covering work, break, minimum segment,
  max continuous work, grace period, split-break policy, two-tier
  thresholds, timezone strategy, and fallback gap detection.
- Three starter presets (Custom, ArbZG, California). Switch presets
  with a preview-and-confirm flow; "Reset to preset" is one click.
- Active webhook → debounce → ingest loop. New / updated / deleted
  time entries trigger an automatic re-evaluation within 30 seconds.
- Read-only iframe — never asks for write permission, never POSTs to
  Clockify on behalf of a user. Required scopes:
  `TIME_ENTRY_READ`, `USER_READ`, `REPORTS_READ`.
- Encrypted installation tokens (AES-GCM-256), webhook signature
  verification, JWT replay-protection, full data wipe on uninstall.
- Prometheus metrics endpoint for operators (`/actuator/prometheus`).

## Setup instructions for admins

1. From the Clockify Marketplace listing, click **Install** on the
   workspace where you want compliance review.
2. Open Workspace Settings → Add-ons → Break Compliance → ⋯ →
   Settings. Tweak any of the ten threshold fields, or open the
   sidebar (left nav) to pick a preset.
3. Click **Refresh** in the sidebar to pull the last 7 days of time
   entries and produce the first findings list. Subsequent edits in
   the workspace trigger refreshes automatically.

## Required scopes

| Scope | Why |
|---|---|
| `TIME_ENTRY_READ` | Listing time entries via the Detailed Report. |
| `USER_READ` | Resolving user names so findings show "Alex Smith" instead of `u_abc123`. |
| `REPORTS_READ` | Calling the Detailed Report endpoint. |

`WORKSPACE_READ` was deliberately not requested — no workspace
metadata is consumed anywhere in the codebase. See `docs/SECURITY.md`
for full threat model.

## Pricing

Free, BASIC plan. Open-source under the operator's repository; no
seats, no usage metering.

## Support contact

`petkovic.aleksandar037@gmail.com` — see `docs/SUPPORT.md` for the
full support contract.

## Screenshots (to attach to the marketplace listing)

- `docs/evidence/clockify-installed-screen.png` — Add-on list with
  Break Compliance installed.
- `docs/evidence/v0.2.0-sidebar-active-findings.png` — Sidebar with
  active findings. *(Pending fresh v0.2.0 capture; skipped on
  2026-06-14 because no live Clockify install/workspace credentials were
  available in this thread.)*
- `docs/evidence/v0.2.0-sidebar-preset-chooser.png` — Preset chooser
  preview cards. *(Pending fresh v0.2.0 capture; same 2026-06-14
  live-access skip.)*
- `docs/evidence/v0.2.0-structured-settings.png` — Native Clockify
  structured settings tab with the ten threshold fields. *(Pending
  fresh v0.2.0 capture; same 2026-06-14 live-access skip.)*

## Disclaimer block (required by Clockify marketplace review)

> Break Compliance produces **advisory findings** about whether
> ingested time entries meet a configurable break policy. The presets
> named "Germany (ArbZG §3 & §4)" and "California (IWC Meal & Rest)"
> are starter templates inspired by the structure of those statutes;
> they are **not claims of legal compliance** with any specific law,
> regulation, or contract. The add-on never modifies time entries,
> never enforces a rule against a worker, and is **not** a
> workforce-management or payroll system. Workspace admins are
> responsible for tuning thresholds to match their policy,
> jurisdiction, and worker contracts; verify with qualified counsel
> before relying on findings for any regulated decision.
