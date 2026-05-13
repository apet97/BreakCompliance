package me.apet97.breakcompliance.persistence.repositories;

import java.time.LocalDate;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceHolidayRepository extends JpaRepository<WorkspaceHoliday, WorkspaceHoliday.Pk> {

    List<WorkspaceHoliday> findByWorkspaceIdAndDateBetween(
            String workspaceId, LocalDate from, LocalDate to);

    // P6.1 — user-scoped holiday rows for DSAR export.
    List<WorkspaceHoliday> findByWorkspaceIdAndAppliesToUserId(String workspaceId, String userId);

    // Replace-on-refetch so a holiday deleted upstream stops suppressing
    // its date on the next ingest. Range-scoped — rows outside the window
    // stay put.
    @Modifying
    @Transactional
    long deleteByWorkspaceIdAndDateBetween(String workspaceId, LocalDate from, LocalDate to);
}
