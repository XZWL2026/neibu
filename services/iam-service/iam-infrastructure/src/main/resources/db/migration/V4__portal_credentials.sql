ALTER TABLE iam_admin DROP CHECK ck_iam_admin_role;
ALTER TABLE iam_admin ADD COLUMN bound_user_id VARCHAR(128) NULL,
  ADD CONSTRAINT ck_iam_admin_role CHECK (role IN ('ADMIN','NORMAL_ADMIN','API_USER','PROMOTER')),
  ADD CONSTRAINT ck_iam_portal_binding CHECK (
    (role IN ('API_USER','PROMOTER') AND bound_user_id IS NOT NULL AND LENGTH(bound_user_id) > 0 AND NOT is_primary)
    OR (role IN ('ADMIN','NORMAL_ADMIN') AND bound_user_id IS NULL)),
  ADD CONSTRAINT uk_iam_portal_owner_role UNIQUE (bound_user_id, role);
