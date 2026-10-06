ALTER TABLE sites ADD COLUMN project_id UUID REFERENCES projects(id) ON DELETE SET NULL;
ALTER TABLE projects ALTER COLUMN site_id DROP NOT NULL;

UPDATE sites s
SET project_id = p.id
FROM projects p
WHERE p.site_id = s.id AND s.project_id IS NULL;

CREATE INDEX sites_project_idx ON sites(project_id, created_at DESC);
