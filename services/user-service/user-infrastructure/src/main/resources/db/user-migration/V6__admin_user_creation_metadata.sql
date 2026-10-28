ALTER TABLE admin_user_create_request
    ADD COLUMN source VARCHAR(16) NULL,
    ADD COLUMN remark VARCHAR(200) NULL;
