# What counts as a break

Break Compliance evaluates whether each person took the required rest
during a working day. "Required" is set by the active preset (and the
admin-tunable thresholds layered on top); this page is about the other
half of the question — **how does the engine decide what is, and isn't, a
break?**

If you're an admin trying to explain a finding, this is the right page.
If you're tuning thresholds, see the Active preset chip in the sidebar
instead.

## TL;DR

The engine looks at every time entry on a person's day and bucketises
each into one of three categories:

| Category | What it means | Counts as a break? |
| --- | --- | --- |
| **WORK** | A regular time entry — billable, project work, the default. | No. Adds to the work-minutes total that triggers a break requirement. |
| **BREAK** | An entry with `type=BREAK` (Clockify's built-in break type) or whose project / tag matches a configured break label. | Yes. Adds to the break-minutes total. |
| **IGNORED** | An entry with `type=TIME_OFF` or `type=HOLIDAY`. | Neither. The day is skipped entirely for that person. |

A finding fires when a person crosses the work-minutes threshold without
enough qualifying break minutes underneath it. The interesting cases
below are about what makes a break "qualifying."

## 1. Explicit BREAK entries

The straightforward case. Clockify supports a native `BREAK` time-entry
type on the **BASIC** plan upward. When the engine sees an entry with
`type=BREAK`, the entry's duration goes straight into the break-minutes
total for that day.

- **Tag / project fallback.** If your workspace tracks breaks as a
  regular WORK entry against a "Lunch" project or a `#break` tag instead
  of using `type=BREAK`, the engine classifies those entries as BREAK
  too. The match is governed by `EntryClassifier` and is case-insensitive.
- **Stacking is allowed by default.** Two 15-minute BREAK entries
  generally satisfy a 30-minute requirement — unless the workspace's
  active preset disables the split-break rule (California IWC, for
  example, requires a single uninterrupted 30-minute meal break and
  flips `allowSplitBreaks` to off).
- **Minimum segment length.** A BREAK entry shorter than
  `minBreakSegmentMinutes` (default 5 min) is treated as noise and
  ignored. This keeps a 30-second mis-click from being credited toward
  someone's required break.

## 2. The gap-as-break heuristic (opt-in)

Many workspaces don't log breaks at all — people just stop the timer
when they step away and start a new entry when they come back. If your
workspace is set up that way, turn on **Fallback detection** under
**Workspace Settings → Add-ons → Break Compliance → ⋯ → Settings**
(field name: `fallbackDetectionEnabled`, default off).

With fallback detection on, the engine looks at wall-clock gaps between
two consecutive WORK entries on the same calendar day for the same
person. A gap is credited as a synthesised break when **both** of these
are true:

- the gap is **at least** `minBreakSegmentMinutes` long (default 5 min)
- the gap is **at most** `MAX_GAP_AS_BREAK_MINUTES` long (hardcoded 120 min)

The synthesised break minutes are recorded under
`evidence.syntheticBreakMinutes` on any finding and surfaced in the
sidebar as `Break: 30m · 30m detected` — the second number is the
synthesised portion.

Two boundary cases are deliberately excluded:

- **Gaps shorter than the minimum segment** are noise (someone refreshing
  Clockify, two entries back-to-back). They're not credited.
- **Gaps longer than 2 hours** are too long to be a workplace break.
  They represent a new shift, a long lunch home, or a sick day — none of
  which should count toward "did this person rest enough mid-shift?"
  The 120-minute ceiling is the cutoff.

A gap **between a WORK entry and an IGNORED entry** (or vice versa) is
never synthesised — TIME_OFF / HOLIDAY entries break the heuristic's
chain because they already mean "this person isn't working." Same for
gaps between WORK and an explicit BREAK entry: the BREAK entry is the
canonical record, so we don't double-count.

## 3. TIME_OFF and HOLIDAY entries

When the engine sees an entry with `type=TIME_OFF` or `type=HOLIDAY` on
a person's day, the **entire day** is skipped for that person — no
work-minutes accumulated, no break requirement evaluated, no finding
produced.

The reason is simple: a person on PTO isn't on shift, so asking "did
they take their meal break?" is meaningless. Without this exclusion,
the engine would generate spurious `MISSING_REQUIRED_BREAK` findings
on every PTO day (since the work-minutes total would be zero but no
break would be recorded either).

This is why PTO and statutory holidays should be recorded as their
canonical Clockify type rather than as a regular WORK entry against a
"PTO" project — the type-based classification is what triggers the skip.

## 4. The `minBreakSegmentMinutes` floor

Both real BREAK entries and synthesised gap-breaks are filtered through
the same minimum-segment threshold. Default: **5 minutes**.

Why have it at all? Two reasons:

1. **Noise filtering.** Someone clocking out at 12:00:00 and clocking
   back in at 12:00:03 isn't taking a break. Without a floor, the
   engine would credit those three seconds toward the requirement.
2. **Compliance intent.** Statutory rest breaks generally have a
   minimum duration. A 90-second screen break doesn't satisfy a "10
   minute rest period every 4 hours" rule no matter how often it
   happens.

Workspaces with stricter regimes (e.g. some EU jurisdictions require
breaks of at least 15 minutes to count) can raise the floor in the
structured-settings page.

## 5. The `MAX_GAP_AS_BREAK_MINUTES = 120` ceiling

This one isn't tunable from the UI — it's a hardcoded constant in
`BreakRuleEngine.java:52`. The rationale:

- A 4-hour gap between two WORK entries is almost certainly a long
  absence (sick leave, off-site meeting, end-of-shift home break) — not
  a workplace rest break the engine should credit toward "did this
  person rest enough mid-shift?"
- Allowing arbitrarily long gaps to count would mean a person clocking
  9 AM, taking lunch home from 12-4 PM, and clocking back in 4-7 PM
  would auto-satisfy a 30-minute meal break with a 4-hour synthesised
  gap. That's surprising behaviour and a compliance reviewer would
  question it.
- 120 minutes is comfortably above any common statutory meal break
  (30 min in the US, 60 min in many EU regimes) but below the duration
  of typical out-of-office activities.

If a workspace genuinely needs to credit longer gaps (24-hour-cycle
schedules, split shifts with explicit unpaid breaks), the right answer
is to **log the break explicitly as a BREAK time entry** rather than
relying on the heuristic. The sidebar's "Pending refresh · webhook X
ago" pill will pick up the new entry on the next ingest.

## Where this lives in the code

| Concept | File |
| --- | --- |
| WORK / BREAK / IGNORED classification | `src/main/java/me/apet97/breakcompliance/domain/EntryClassifier.java` |
| Gap-as-break heuristic + `MAX_GAP_AS_BREAK_MINUTES` | `src/main/java/me/apet97/breakcompliance/domain/BreakRuleEngine.java` (`evaluateSegments`) |
| Workspace settings — fallback detection, segment floor, split-break flag | `src/main/java/me/apet97/breakcompliance/persistence/entities/WorkspaceSettings.java` |

If you find a case where the documented behaviour doesn't match what the
engine actually does, that's a bug — open an issue with a CSV export
(see `/api/findings/export?format=csv`) and the date range, and we'll
trace it.
