-- Break Compliance — initial schema.
-- Native PostgreSQL types throughout (TIMESTAMPTZ, DATE, JSONB, BOOLEAN,
-- BIGINT, BYTEA). Composite primary keys with workspace_id leading every
-- tenant-scoped table so no cross-workspace leakage is possible at the
-- schema level.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Application tables (workspace-scoped)
-- ---------------------------------------------------------------------------

CREATE TABLE breakcompliance_workspace_settings (
    workspace_id                TEXT PRIMARY KEY,
    default_template_id         TEXT,
    timezone_strategy           TEXT NOT NULL DEFAULT 'ENTRY_TIMEZONE'
                                  CHECK (timezone_strategy IN ('ENTRY_TIMEZONE')),
    fallback_detection_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL
);

CREATE TABLE breakcompliance_rule_templates (
    workspace_id                              TEXT NOT NULL,
    id                                        TEXT NOT NULL,
    key                                       TEXT NOT NULL,
    name                                      TEXT NOT NULL,
    description                               TEXT NOT NULL DEFAULT '',
    type                                      TEXT NOT NULL
                                                CHECK (type IN ('BUILT_IN','CUSTOM')),
    preset_key                                TEXT,
    version                                   INT NOT NULL DEFAULT 1,
    enabled                                   BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_valid_break_segment_minutes       INT NOT NULL,
    work_threshold_minutes                    INT NOT NULL,
    required_break_minutes                    INT NOT NULL,
    max_continuous_work_minutes_before_break  INT NOT NULL,
    second_threshold_minutes                  INT,
    second_required_break_minutes             INT,
    allow_split_breaks                        BOOLEAN NOT NULL DEFAULT TRUE,
    grace_period_minutes                      INT NOT NULL,
    created_at                                TIMESTAMPTZ NOT NULL,
    updated_at                                TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, id),
    UNIQUE (workspace_id, key)
);

CREATE TABLE breakcompliance_template_assignments (
    workspace_id  TEXT NOT NULL,
    target_type   TEXT NOT NULL CHECK (target_type IN ('USER','GROUP')),
    target_id     TEXT NOT NULL,
    template_id   TEXT NOT NULL,
    id            TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, target_type, target_id)
);
CREATE INDEX idx_breakcompliance_assignments_by_template
    ON breakcompliance_template_assignments (workspace_id, template_id);

CREATE TABLE breakcompliance_ingestion_runs (
    workspace_id       TEXT NOT NULL,
    id                 TEXT NOT NULL,
    date_range_start   TEXT NOT NULL,
    date_range_end     TEXT NOT NULL,
    status             TEXT NOT NULL CHECK (status IN ('COMPLETED','FAILED')),
    entries_processed  BIGINT NOT NULL DEFAULT 0,
    error_code         TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    completed_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, id)
);
CREATE INDEX idx_breakcompliance_ingestion_runs_by_workspace_created
    ON breakcompliance_ingestion_runs (workspace_id, created_at);

CREATE TABLE breakcompliance_time_entries (
    workspace_id      TEXT NOT NULL,
    source_entry_id   TEXT NOT NULL,
    user_id           TEXT,
    project_id        TEXT,
    task_id           TEXT,
    client_id         TEXT,
    description       TEXT,
    start_at          TIMESTAMPTZ NOT NULL,
    end_at            TIMESTAMPTZ,
    duration_seconds  BIGINT,
    billable          BOOLEAN,
    tags              JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw               JSONB NOT NULL,
    ingested_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, source_entry_id)
);
CREATE INDEX idx_breakcompliance_time_entries_by_workspace_start
    ON breakcompliance_time_entries (workspace_id, start_at);
CREATE INDEX idx_breakcompliance_time_entries_by_workspace_user
    ON breakcompliance_time_entries (workspace_id, user_id);

CREATE TABLE breakcompliance_findings (
    workspace_id  TEXT NOT NULL,
    id            TEXT NOT NULL,
    user_id       TEXT NOT NULL,
    date          DATE NOT NULL,
    template_id   TEXT NOT NULL,
    severity      TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','VIOLATION')),
    code          TEXT NOT NULL,
    message       TEXT NOT NULL,
    evidence      JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, id)
);
CREATE INDEX idx_breakcompliance_findings_by_workspace_date
    ON breakcompliance_findings (workspace_id, date);

CREATE TABLE breakcompliance_refresh_signals (
    workspace_id  TEXT NOT NULL,
    id            TEXT NOT NULL,
    source        TEXT NOT NULL CHECK (source IN ('WEBHOOK')),
    event_type    TEXT NOT NULL,
    entity_id     TEXT,
    date_hint     TEXT,
    received_at   TIMESTAMPTZ NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('PENDING','ACKNOWLEDGED')),
    PRIMARY KEY (workspace_id, id)
);
CREATE INDEX idx_breakcompliance_refresh_signals_by_workspace_received
    ON breakcompliance_refresh_signals (workspace_id, received_at);

CREATE TABLE breakcompliance_group_memberships (
    workspace_id  TEXT NOT NULL,
    group_id      TEXT NOT NULL,
    user_id       TEXT NOT NULL,
    ingested_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, group_id, user_id)
);
CREATE INDEX idx_breakcompliance_group_memberships_by_workspace_user
    ON breakcompliance_group_memberships (workspace_id, user_id);

CREATE TABLE breakcompliance_finding_reviews (
    workspace_id  TEXT NOT NULL,
    finding_id    TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('OPEN','ACKNOWLEDGED','OVERRIDDEN')),
    note          TEXT,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, finding_id)
);

-- ---------------------------------------------------------------------------
-- Installation + webhook auth tokens (encrypted at rest)
-- ---------------------------------------------------------------------------

-- addon_id is the Clockify-generated ObjectId from claims.addonId, NOT the
-- manifest key — looking up installations by manifest key silently misses.
CREATE TABLE breakcompliance_installations (
    workspace_id       TEXT NOT NULL,
    addon_id           TEXT NOT NULL,
    auth_token_cipher  BYTEA NOT NULL,
    auth_token_key_id  TEXT NOT NULL,
    backend_url        TEXT NOT NULL,
    reports_url        TEXT,
    installer_user_id  TEXT,
    status             TEXT NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE','INACTIVE')),
    installed_at       TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, addon_id)
);
CREATE UNIQUE INDEX idx_breakcompliance_installations_by_workspace
    ON breakcompliance_installations (workspace_id);
CREATE INDEX idx_breakcompliance_installations_by_addon
    ON breakcompliance_installations (addon_id);

-- path is the normalized pathname (slashes collapsed) so webhook lookup is
-- deterministic regardless of how Clockify formats the URL in INSTALLED.
CREATE TABLE breakcompliance_webhook_auth_tokens (
    workspace_id       TEXT NOT NULL,
    addon_id           TEXT NOT NULL,
    path               TEXT NOT NULL,
    event_type         TEXT NOT NULL,
    auth_token_cipher  BYTEA NOT NULL,
    auth_token_key_id  TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, addon_id, path),
    FOREIGN KEY (workspace_id, addon_id)
        REFERENCES breakcompliance_installations (workspace_id, addon_id)
        ON DELETE CASCADE
);

CREATE TABLE breakcompliance_audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  TEXT,
    actor         TEXT,
    action        TEXT NOT NULL,
    entity_type   TEXT,
    entity_id     TEXT,
    details       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_breakcompliance_audit_logs_by_workspace_created
    ON breakcompliance_audit_logs (workspace_id, created_at DESC);
