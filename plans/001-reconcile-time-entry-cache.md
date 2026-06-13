# Plan 001: Reconcile Deleted Time Entries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected result before moving to the next step. If anything in the "STOP conditions" section occurs, stop and report. Do not improvise.
>
> **Drift check (run first)**:
>
> ```sh
> git diff --stat 2c969bb..HEAD -- \
>   src/main/java/me/apet97/breakcompliance/api/IngestionService.java \
>   src/main/java/me/apet97/breakcompliance/api/TimeEntryUpserter.java \
>   src/main/java/me/apet97/breakcompliance/persistence/repositories/TimeEntryRepository.java \
>   src/test/java/me/apet97/breakcompliance/api/IngestionControllerTest.java
> ```
>
> If any in-scope file changed since this plan was written, compare the "Current state" excerpts against the live code before proceeding. On a mismatch, treat it as a STOP condition.

**Goal:** A successful ingest replaces the cached time-entry rows for the refreshed date range, so Clockify-deleted or moved entries no longer affect findings.

**Architecture:** Keep the Detailed Report as the source of truth. After a successful fetch and inside the existing finalize transaction, delete cached `TimeEntry` rows whose `startAt` falls inside the refreshed UTC date range, then upsert the entries returned by the current report. If the upsert fails, the transaction rolls back the delete too.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, PostgreSQL, Maven, JUnit 5, Mockito, Testcontainers.

---

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2c969bb`, 2026-06-13

## Why this matters

The add-on promises that new, updated, and deleted Clockify time entries trigger automatic re-evaluation. Today the webhook and ingest run happen, but a deleted entry can remain in `breakcompliance_time_entries` because finalize only upserts rows returned by the Detailed Report. Evaluation then reads the stale cache and can continue producing findings from work that no longer exists, or credit stale breaks that should no longer count.

## Current state

- `src/main/java/me/apet97/breakcompliance/addon/webhook/WebhookController.java` registers the deleted-entry webhook:

```java
// WebhookController.java:45
@PostMapping(value = {"/new-time-entry", "/time-entry-updated", "/time-entry-deleted"}, consumes = MediaType.APPLICATION_JSON_VALUE)
```

- `src/main/java/me/apet97/breakcompliance/addon/webhook/RefreshSignalConsumer.java` re-ingests a fallback window when delete payloads do not carry a date hint:

```java
// RefreshSignalConsumer.java:302-310
/**
 * Smallest covering window for the group's date hints, with two
 * safety rails:
 *
 * <ul>
 *   <li>If no signal carries a hint (TIME_ENTRY_DELETED, or pre-V10
 *       rows), use {@code [today - fallbackWindowDays, today]}.</li>
```

- `src/main/java/me/apet97/breakcompliance/api/IngestionService.java` only upserts current report rows:

```java
// IngestionService.java:371-393
private IngestionRun finalizeRun(
        String workspaceId, String runId, List<DetailedReportEntry> entries) {
    IngestionRun run = runRepo
            .findById(new IngestionRun.Pk(workspaceId, runId))
            .orElseThrow(() -> new IllegalStateException(
                    "ingestion run vanished between prepare and finalize: " + runId));
    int processed = 0;
    int batchCounter = 0;
    Instant ingestedAt = Instant.now();
    for (DetailedReportEntry raw : entries) {
        if (timeEntryUpserter.upsert(workspaceId, raw, ingestedAt)) {
            processed++;
        }
        if (++batchCounter >= FINALIZE_FLUSH_BATCH) {
            timeEntryUpserter.flush();
            batchCounter = 0;
        }
    }
    run.setEntriesProcessed(processed);
    return runRepo.saveAndFlush(run);
}
```

- `src/main/java/me/apet97/breakcompliance/api/TimeEntryUpserter.java` only saves one source row at a time:

```java
// TimeEntryUpserter.java:18-42
boolean upsert(String workspaceId, DetailedReportEntry raw, Instant ingestedAt) {
    String sourceEntryId = raw.sourceEntryId();
    if (sourceEntryId == null) {
        return false;
    }
    TimeEntry entry = timeEntryRepo
            .findById(new TimeEntry.Pk(workspaceId, sourceEntryId))
            .orElseGet(TimeEntry::new);
    // fields copied from DetailedReportEntry...
    timeEntryRepo.save(entry);
    return true;
}
```

- `src/main/java/me/apet97/breakcompliance/persistence/repositories/TimeEntryRepository.java` has no range-delete method:

```java
// TimeEntryRepository.java:12-16
public interface TimeEntryRepository extends JpaRepository<TimeEntry, TimeEntry.Pk> {

    List<TimeEntry> findByWorkspaceIdAndStartAtBetween(String workspaceId, Instant from, Instant to);

    List<TimeEntry> findByWorkspaceIdAndUserId(String workspaceId, String userId);
```

- `src/main/java/me/apet97/breakcompliance/api/FindingsService.java` evaluates whatever is currently cached:

```java
// FindingsService.java:69-71
Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
List<TimeEntry> entries = entriesRepo.findByWorkspaceIdAndStartAtBetween(workspaceId, fromInstant, toInstant);
```

Repo constraints to preserve:

- Read-only mission: never add Clockify write scopes or outbound write calls.
- Detailed Report remains the source of truth for break evaluation.
- `IngestionRun.status=COMPLETED` must still be written only after detailed-report entries are persisted and supplemental refresh attempts return.
- Keep the three-phase ingest split: do not hold a DB transaction open during the Clockify HTTP call.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Static JS syntax | `find src/main/resources/static -name '*.js' -print0 \| xargs -0 -n1 node --check` | exit 0, no output |
| Focused ingest test | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test -Dtest='IngestionControllerTest'` | exit 0, all tests pass |
| Full suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -B -ntp test` | exit 0; baseline before this plan was 362 tests passing |

## Scope

**In scope**:

- `src/main/java/me/apet97/breakcompliance/api/IngestionService.java`
- `src/main/java/me/apet97/breakcompliance/api/TimeEntryUpserter.java`
- `src/main/java/me/apet97/breakcompliance/persistence/repositories/TimeEntryRepository.java`
- `src/test/java/me/apet97/breakcompliance/api/IngestionControllerTest.java`

**Out of scope**:

- Flyway migrations: the existing table can support this with a repository delete.
- `FindingsService`: it should keep reading cached entries; this plan fixes cache correctness upstream.
- Clockify webhook auth, idempotency, or refresh-signal window rules.
- Any new Clockify API endpoint or write scope.

## Git workflow

- Branch: `codex/001-reconcile-time-entry-cache`
- Commit message style: conventional commits, for example `fix(ingest): reconcile cached time entries`
- Do not push or open a PR unless the operator explicitly asks.

## Steps

### Step 1: Add a failing ingest regression test

- [ ] Modify `src/test/java/me/apet97/breakcompliance/api/IngestionControllerTest.java`.
- [ ] Add an import for `me.apet97.breakcompliance.persistence.entities.TimeEntry`.
- [ ] Add an import for `me.apet97.breakcompliance.persistence.repositories.TimeEntryRepository`.
- [ ] Autowire the repository near the existing `runRepo` field:

```java
@Autowired
TimeEntryRepository timeEntryRepo;
```

- [ ] In `cleanState()`, delete time entries before deleting installations:

```java
timeEntryRepo.deleteAll();
runRepo.deleteAll();
installationRepo.deleteAll();
seedInstallation();
```

- [ ] Add this test method. It seeds a stale cached row, mocks the current report as empty for the same range, runs ingest, and asserts the stale row is gone:

```java
@Test
void detailedReport_emptyCurrentReportRemovesCachedEntriesInRange() throws Exception {
    TimeEntry stale = new TimeEntry();
    stale.setWorkspaceId(TestJwtForger.DEFAULT_WORKSPACE_ID);
    stale.setSourceEntryId("deleted-entry-1");
    stale.setUserId("user-a");
    stale.setUserName("User A");
    stale.setStartAt(Instant.parse("2026-05-03T09:00:00Z"));
    stale.setEndAt(Instant.parse("2026-05-03T14:00:00Z"));
    stale.setDurationSeconds(18_000L);
    stale.setBillable(false);
    stale.setTags(List.of());
    stale.setRaw(Map.of("type", "REGULAR"));
    stale.setIngestedAt(Instant.parse("2026-05-01T00:00:00Z"));
    timeEntryRepo.saveAndFlush(stale);

    Mockito.when(fetcher.fetch(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
            .thenReturn(List.of());

    mockMvc.perform(post("/api/ingest/detailed-report")
                    .header("X-Addon-Token", TestJwtForger.forgeInstalledToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateRangeStart\":\"2026-05-01\",\"dateRangeEnd\":\"2026-05-07\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.run.status").value("COMPLETED"));

    org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findByWorkspaceIdAndStartAtBetween(
                    TestJwtForger.DEFAULT_WORKSPACE_ID,
                    Instant.parse("2026-05-01T00:00:00Z"),
                    Instant.parse("2026-05-08T00:00:00Z")))
            .isEmpty();
}
```

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='IngestionControllerTest#detailedReport_emptyCurrentReportRemovesCachedEntriesInRange'
```

Expected before implementation: the new test fails because the stale row remains in `timeEntryRepo`.

### Step 2: Add a range delete repository method

- [ ] Modify `src/main/java/me/apet97/breakcompliance/persistence/repositories/TimeEntryRepository.java`.
- [ ] Keep the existing finder methods.
- [ ] Add this method below `findByWorkspaceIdAndUserId`:

```java
@Modifying
@Transactional
@Query("delete from TimeEntry t where t.workspaceId = :workspaceId "
        + "and t.startAt >= :fromInclusive and t.startAt < :toExclusive")
int deleteByWorkspaceIdAndStartAtGreaterThanEqualAndStartAtLessThan(
        @Param("workspaceId") String workspaceId,
        @Param("fromInclusive") Instant fromInclusive,
        @Param("toExclusive") Instant toExclusive);
```

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='IngestionControllerTest#detailedReport_emptyCurrentReportRemovesCachedEntriesInRange'
```

Expected: still fails, because the method exists but is not called.

### Step 3: Replace the refreshed range inside finalize

- [ ] Modify `src/main/java/me/apet97/breakcompliance/api/TimeEntryUpserter.java`.
- [ ] Add imports:

```java
import java.time.LocalDate;
import java.time.ZoneOffset;
```

- [ ] Add this method below the constructor:

```java
int deleteRange(String workspaceId, LocalDate from, LocalDate to) {
    Instant fromInclusive = from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return timeEntryRepo.deleteByWorkspaceIdAndStartAtGreaterThanEqualAndStartAtLessThan(
            workspaceId, fromInclusive, toExclusive);
}
```

- [ ] Modify `src/main/java/me/apet97/breakcompliance/api/IngestionService.java`.
- [ ] Change both finalize callers:

```java
tx.execute(status -> finalizeRun(workspaceId, prepared.runId(), entries, from, to));
```

and

```java
tx.execute(status -> finalizeRun(workspaceId, runId, entries, from, to));
```

- [ ] Change the finalize signature and delete the range before the upsert loop:

```java
private IngestionRun finalizeRun(
        String workspaceId,
        String runId,
        List<DetailedReportEntry> entries,
        LocalDate from,
        LocalDate to) {
    IngestionRun run = runRepo
            .findById(new IngestionRun.Pk(workspaceId, runId))
            .orElseThrow(() -> new IllegalStateException(
                    "ingestion run vanished between prepare and finalize: " + runId));
    int deleted = timeEntryUpserter.deleteRange(workspaceId, from, to);
    if (deleted > 0) {
        log.info("ingestion.cache.reconciled workspace={} runId={} deleted={}", workspaceId, runId, deleted);
    }
    timeEntryUpserter.flush();
    int processed = 0;
    int batchCounter = 0;
    Instant ingestedAt = Instant.now();
    for (DetailedReportEntry raw : entries) {
        if (timeEntryUpserter.upsert(workspaceId, raw, ingestedAt)) {
            processed++;
        }
        if (++batchCounter >= FINALIZE_FLUSH_BATCH) {
            timeEntryUpserter.flush();
            batchCounter = 0;
        }
    }
    run.setEntriesProcessed(processed);
    return runRepo.saveAndFlush(run);
}
```

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='IngestionControllerTest#detailedReport_emptyCurrentReportRemovesCachedEntriesInRange'
```

Expected: the new test passes.

### Step 4: Add a guard that rows outside the refreshed range survive

- [ ] In `IngestionControllerTest`, add a second regression test. It should seed two rows: one inside `2026-05-01..2026-05-07`, one outside the range on `2026-04-30T09:00:00Z`. Mock the report as empty and run the same ingest request.
- [ ] Assert the inside row is gone and the outside row remains. Use this assertion shape:

```java
org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findById(
                new TimeEntry.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, "outside-entry")))
        .isPresent();
org.assertj.core.api.Assertions.assertThat(timeEntryRepo.findById(
                new TimeEntry.Pk(TestJwtForger.DEFAULT_WORKSPACE_ID, "inside-entry")))
        .isEmpty();
```

**Verify**:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test -Dtest='IngestionControllerTest'
```

Expected: all `IngestionControllerTest` tests pass, including the two new reconciliation tests.

### Step 5: Run the full verification gates

- [ ] Run static JS syntax even though this plan should not modify JS:

```sh
find src/main/resources/static -name '*.js' -print0 | xargs -0 -n1 node --check
```

Expected: exit 0, no output.

- [ ] Run the full suite:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH \
  mvn -B -ntp test
```

Expected: exit 0. The test count should be baseline `362` plus the new tests added in this plan.

## Test plan

- New `IngestionControllerTest#detailedReport_emptyCurrentReportRemovesCachedEntriesInRange`.
- New `IngestionControllerTest` case proving rows outside the refreshed date range remain.
- Existing ingestion tests must still pass, especially failed-run behavior and duplicate RUNNING-run detection.
- Full `mvn -B -ntp test` must pass after focused tests pass.

## Done criteria

- [ ] A successful ingest deletes stale cached `TimeEntry` rows inside the refreshed UTC date range before upserting current report entries.
- [ ] Rows outside the refreshed range remain untouched.
- [ ] A finalize failure still marks the run `FAILED` and rolls back cache deletion.
- [ ] No Clockify write scope or outbound write call is added.
- [ ] Static JS check exits 0.
- [ ] Full Maven test suite exits 0.
- [ ] `git status --short` shows only in-scope source/test changes plus plan status updates.
- [ ] `plans/README.md` status row for Plan 001 is updated.

## STOP conditions

Stop and report back if:

- `IngestionService.finalizeRun` no longer runs inside `tx.execute(...)`.
- The Detailed Report fetcher no longer returns a complete authoritative range.
- The fix appears to require changing webhook idempotency or refresh-signal window semantics.
- Deleting the range causes an existing test to fail because another code path intentionally stores entries in `breakcompliance_time_entries` that do not come from the Detailed Report.
- Docker/Testcontainers cannot start after one retry; record the environment error and do not claim the full suite is green.

## Maintenance notes

- The delete-then-upsert approach avoids a large SQL `NOT IN` list for 100k-entry reports.
- Reviewers should scrutinize the UTC range math: `from` is inclusive at `00:00:00Z`, `to.plusDays(1)` is exclusive.
- If future code supports incremental report pages or partial fetches, revisit this plan. Range replacement is correct only when the Detailed Report response covers the requested date range completely.
