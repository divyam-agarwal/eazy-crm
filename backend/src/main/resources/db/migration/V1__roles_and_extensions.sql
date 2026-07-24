-- Runtime application role: can log in, but does NOT own tables and has NO BYPASSRLS.
-- Because it is not the table owner, PostgreSQL enforces Row-Level Security against it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'easycrm_app') THEN
        CREATE ROLE easycrm_app LOGIN PASSWORD 'easycrm_app';
    END IF;
END $$;

-- Let the app role use the schema and DML any tables the owner creates later.
GRANT USAGE ON SCHEMA public TO easycrm_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO easycrm_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO easycrm_app;
