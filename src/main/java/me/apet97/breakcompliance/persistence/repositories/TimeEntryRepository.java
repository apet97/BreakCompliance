package me.apet97.breakcompliance.persistence.repositories;

import java.time.Instant;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.TimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, TimeEntry.Pk> {

    List<TimeEntry> findByWorkspaceIdAndStartAtBetween(String workspaceId, Instant from, Instant to);

    List<TimeEntry> findByWorkspaceIdAndUserId(String workspaceId, String userId);
}
