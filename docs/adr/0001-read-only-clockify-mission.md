# ADR 0001: Read-Only Clockify Mission

## Status

Accepted

## Context

The add-on is meant for compliance review, not enforcement. Marketplace
installation across many Clockify workspaces makes write scope risk larger than
the product value of automatic correction.

## Decision

The add-on remains read-only:

- no Clockify `_WRITE` scopes,
- no outbound Clockify mutations,
- no user messaging,
- no automatic time-entry creation or editing.

## Consequences

Findings are advisory. Admin workflow features may write only to the add-on's own
database, such as finding review state and audit-log rows.
