# ADR 0002: Split Settings Surface

## Status

Accepted

## Context

Clockify native structured settings render each field independently and do not
refresh sibling fields after a backend write. A native preset dropdown that
overwrites multiple threshold fields makes the UI look stale until the admin
reloads the settings page.

## Decision

Native structured settings own individual threshold fields. The sidebar owns
preset selection through `GET /api/presets` and `POST /api/presets/apply`.

## Consequences

Preset apply can preview, confirm, validate, audit, and save all threshold fields
atomically. Fine tuning stays in Clockify's native settings chrome.
