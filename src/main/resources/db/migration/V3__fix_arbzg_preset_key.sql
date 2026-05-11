-- Fix a typo in the German preset key: the law is the Arbeitszeitgesetz
-- (ArbZG, with z), not "ArbG". Existing rows seeded under the typo'd
-- key are migrated in-place so workspaces don't lose their default
-- template selection or recorded findings.

UPDATE breakcompliance_rule_templates
   SET id         = REPLACE(id, 'germany-arbg-style', 'germany-arbzg-style'),
       key        = 'germany-arbzg-style',
       preset_key = 'germany-arbzg-style'
 WHERE preset_key = 'germany-arbg-style';

UPDATE breakcompliance_workspace_settings
   SET default_template_id = REPLACE(default_template_id, 'germany-arbg-style', 'germany-arbzg-style')
 WHERE default_template_id LIKE '%germany-arbg-style%';

UPDATE breakcompliance_template_assignments
   SET template_id = REPLACE(template_id, 'germany-arbg-style', 'germany-arbzg-style')
 WHERE template_id LIKE '%germany-arbg-style%';

UPDATE breakcompliance_findings
   SET template_id = REPLACE(template_id, 'germany-arbg-style', 'germany-arbzg-style')
 WHERE template_id LIKE '%germany-arbg-style%';
