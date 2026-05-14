package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * P1.1 — cached holiday row populated by {@code HolidayFetcher}. A row
 * applies to a single user when {@link #appliesToUserId} is set; null = the
 * holiday applies to every user in the workspace (national-holiday shape).
 *
 * <p>The composite PK includes {@code date} + {@code appliesToUserId} so a
 * single source holiday that spans multiple days (e.g. a multi-day religious
 * observance) lands as N rows. Engine reads them with a date-only filter
 * for fast (workspaceId, date) lookups.
 */
@Entity
@Table(name = "breakcompliance_workspace_holidays")
@IdClass(WorkspaceHoliday.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceHoliday {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Id
    @Column(name = "applies_to_user_id")
    private String appliesToUserId;

    @Column(name = "name")
    private String name;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String sourceId;
        private LocalDate date;
        private String appliesToUserId;
    }
}
