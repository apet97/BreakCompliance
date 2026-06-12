-- Workspace-wide holidays use applies_to_user_id = null in the domain model.
-- V13 accidentally placed applies_to_user_id in the primary key, which made
-- Postgres enforce NOT NULL and blocked those rows. Keep the nullable domain
-- column, add a non-null persistence identity for JPA/Postgres, and move the
-- primary key to that identity.

ALTER TABLE breakcompliance_workspace_holidays
  ADD COLUMN IF NOT EXISTS scope_key TEXT;

UPDATE breakcompliance_workspace_holidays
SET scope_key = CASE
  WHEN applies_to_user_id IS NULL OR btrim(applies_to_user_id) = '' THEN '__workspace__'
  ELSE applies_to_user_id
END
WHERE scope_key IS NULL;

ALTER TABLE breakcompliance_workspace_holidays
  ALTER COLUMN scope_key SET NOT NULL;

ALTER TABLE breakcompliance_workspace_holidays
  DROP CONSTRAINT IF EXISTS breakcompliance_workspace_holidays_pkey;

ALTER TABLE breakcompliance_workspace_holidays
  ALTER COLUMN applies_to_user_id DROP NOT NULL;

UPDATE breakcompliance_workspace_holidays
SET applies_to_user_id = NULL
WHERE btrim(applies_to_user_id) = '';

ALTER TABLE breakcompliance_workspace_holidays
  ADD CONSTRAINT breakcompliance_workspace_holidays_pkey
  PRIMARY KEY (workspace_id, source_id, date, scope_key);
