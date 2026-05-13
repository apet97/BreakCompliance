package me.apet97.breakcompliance.persistence.repositories;

import java.time.LocalDate;
import java.util.List;
import me.apet97.breakcompliance.persistence.entities.WorkspaceHoliday;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceHolidayRepository extends JpaRepository<WorkspaceHoliday, WorkspaceHoliday.Pk> {

    List<WorkspaceHoliday> findByWorkspaceIdAndDateBetween(
            String workspaceId, LocalDate from, LocalDate to);
}
