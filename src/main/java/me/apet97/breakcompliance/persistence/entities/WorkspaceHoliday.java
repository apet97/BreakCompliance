package me.apet97.breakcompliance.persistence.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * <p>The composite PK includes {@code date} + {@code scopeKey} so a single
 * source holiday that spans multiple days (e.g. a multi-day religious
 * observance) lands as N rows. {@code scopeKey} is persistence-only: it
 * carries a sentinel for workspace-wide rows because JPA/Postgres primary-key
 * columns cannot be null, while {@code appliesToUserId == null} remains the
 * domain meaning for "applies to every user".
 */
@Entity
@Table(name = "breakcompliance_workspace_holidays")
@IdClass(WorkspaceHoliday.Pk.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceHoliday {

    private static final String WORKSPACE_WIDE_SCOPE_KEY = "__workspace__";

    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Id
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "applies_to_user_id")
    private String appliesToUserId;

    @Id
    @Column(name = "scope_key", nullable = false)
    private String scopeKey;

    @Column(name = "name")
    private String name;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    public void setAppliesToUserId(String appliesToUserId) {
        this.appliesToUserId = normalizeAppliesToUserId(appliesToUserId);
        this.scopeKey = scopeKeyFor(this.appliesToUserId);
    }

    @PrePersist
    @PreUpdate
    void normalizeScopeKey() {
        this.appliesToUserId = normalizeAppliesToUserId(this.appliesToUserId);
        this.scopeKey = scopeKeyFor(this.appliesToUserId);
    }

    private static String normalizeAppliesToUserId(String appliesToUserId) {
        return appliesToUserId == null || appliesToUserId.isBlank() ? null : appliesToUserId;
    }

    private static String scopeKeyFor(String appliesToUserId) {
        return appliesToUserId == null
                ? WORKSPACE_WIDE_SCOPE_KEY
                : appliesToUserId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private String workspaceId;
        private String sourceId;
        private LocalDate date;
        private String scopeKey;
    }
}
