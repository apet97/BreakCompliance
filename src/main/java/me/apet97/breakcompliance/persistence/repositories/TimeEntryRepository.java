package me.apet97.breakcompliance.persistence.repositories;

import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, TimeEntry.Pk> {

    List<TimeEntry> findByWorkspaceIdAndStartAtBetween(String workspaceId, Instant from, Instant to);

    List<TimeEntry> findByWorkspaceIdAndUserId(String workspaceId, String userId);

    @Modifying
    @Transactional
    @Query("delete from TimeEntry t where t.workspaceId = :workspaceId "
            + "and t.startAt >= :fromInclusive and t.startAt < :toExclusive")
    int deleteByWorkspaceIdAndStartAtGreaterThanEqualAndStartAtLessThan(
            @Param("workspaceId") String workspaceId,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive);

    /**
     * P2.3 — refresh the cached userName column whenever the workspace's
     * directory has a different name for the same userId. Bulk update is
     * fine here; the values are non-PII display labels and the engine
     * only reads userName for rendering, never for evaluation.
     */
    @Modifying
    @Transactional
    @Query("update TimeEntry t set t.userName = :newName "
            + "where t.workspaceId = :workspaceId and t.userId = :userId "
            + "and (t.userName is null or t.userName <> :newName)")
    int updateUserNameForUser(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("newName") String newName);
}
