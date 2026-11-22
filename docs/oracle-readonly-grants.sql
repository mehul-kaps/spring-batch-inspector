-- =====================================================================
-- Read-only Oracle account for Batch Inspector.
-- Run as a DBA / the schema owner. The application never writes, but database-level
-- permissions are the real guarantee - application flags alone are not enough.
-- Replace BATCH_OWNER with the schema that owns the configured-prefix tables.
-- =====================================================================
CREATE USER BATCH_VIEWER IDENTIFIED BY "<strong-password>"
  DEFAULT TABLESPACE USERS QUOTA 0 ON USERS;       -- no quota => cannot create segments

GRANT CREATE SESSION TO BATCH_VIEWER;              -- the ONLY system privilege

-- Replace BATCH_ below with the prefix configured in src/main/resources/application.yml.
GRANT SELECT ON BATCH_OWNER.BATCH_JOB_INSTANCE           TO BATCH_VIEWER;
GRANT SELECT ON BATCH_OWNER.BATCH_JOB_EXECUTION          TO BATCH_VIEWER;
GRANT SELECT ON BATCH_OWNER.BATCH_JOB_EXECUTION_PARAMS   TO BATCH_VIEWER;
GRANT SELECT ON BATCH_OWNER.BATCH_STEP_EXECUTION         TO BATCH_VIEWER;
GRANT SELECT ON BATCH_OWNER.BATCH_JOB_EXECUTION_CONTEXT  TO BATCH_VIEWER;
GRANT SELECT ON BATCH_OWNER.BATCH_STEP_EXECUTION_CONTEXT TO BATCH_VIEWER;

-- Do NOT grant: INSERT/UPDATE/DELETE, EXECUTE, SELECT ANY TABLE, any role such as RESOURCE/CONNECT-with-extras/DBA.
-- Optional safety net:
-- ALTER USER BATCH_VIEWER PROFILE <profile-with-limited-sessions>;

-- Then set DBn_SCHEMA=BATCH_OWNER so queries use BATCH_OWNER.<configured-prefix>JOB_EXECUTION.
-- (or create private synonyms in BATCH_VIEWER's schema and leave DBn_SCHEMA blank).
