-- MySQL read-only account for Batch Inspector.
-- Run as a DBA. Replace app-host, batch_db, and the password as appropriate.
-- The application never writes; SELECT-only privileges are the database-level guarantee.
CREATE USER 'BATCH_VIEWER'@'app-host' IDENTIFIED BY '<strong-password>';

-- Replace BATCH_ with the prefix configured in src/main/resources/application.yml.
GRANT SELECT ON `batch_db`.`BATCH_JOB_INSTANCE`           TO 'BATCH_VIEWER'@'app-host';
GRANT SELECT ON `batch_db`.`BATCH_JOB_EXECUTION`          TO 'BATCH_VIEWER'@'app-host';
GRANT SELECT ON `batch_db`.`BATCH_JOB_EXECUTION_PARAMS`   TO 'BATCH_VIEWER'@'app-host';
GRANT SELECT ON `batch_db`.`BATCH_STEP_EXECUTION`         TO 'BATCH_VIEWER'@'app-host';
GRANT SELECT ON `batch_db`.`BATCH_JOB_EXECUTION_CONTEXT`  TO 'BATCH_VIEWER'@'app-host';
GRANT SELECT ON `batch_db`.`BATCH_STEP_EXECUTION_CONTEXT` TO 'BATCH_VIEWER'@'app-host';

-- Set DB1_SCHEMA=batch_db only if the database name in DB1_URL should not be used.
-- Do not grant INSERT, UPDATE, DELETE, EXECUTE, or broader global privileges.
