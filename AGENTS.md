# Agent Guide

## Project

This is a read-only Spring Batch 5.x execution viewer built with Java 21, Spring Boot 3.3, Spring JDBC/HikariCP, Thymeleaf, vanilla JavaScript, and Maven.
The Maven build targets Java 21 bytecode (`java.version=21`); use JDK 21 or newer to build and run it. The current Spring Boot version is 3.3.5.

It supports MySQL and Oracle metadata databases. It is a viewer only: it does not launch, stop, restart, or modify batch jobs.

## Spring Batch tables

The table prefix is configurable in `src/main/resources/application.yml`. It can contain any validated prefix required by the deployment.

- `<prefix>JOB_INSTANCE`
- `<prefix>JOB_EXECUTION`
- `<prefix>JOB_EXECUTION_PARAMS`
- `<prefix>STEP_EXECUTION`
- `<prefix>JOB_EXECUTION_CONTEXT`
- `<prefix>STEP_EXECUTION_CONTEXT`

Set `dashboard.table-prefix` or `BATCH_TABLE_PREFIX`, and use the per-database `table-prefix` override when applicable.

If a schema/owner is required, set `DBn_SCHEMA` and keep the generated identifier safe. Table prefix and schema identifiers are validated in `Tables.of(...)`; do not bypass that validation or concatenate untrusted identifiers into SQL.

The repository must assume the Spring Batch 5.x column layouts documented in `BatchMetadataRepository` and `README.md`. Do not add support for the 4.x parameter layout or silently change column names.

For displayed job names, `<prefix>JOB_EXECUTION_PARAMS.PARAMETER_NAME = 'JobName'` is authoritative. Read its `PARAMETER_VALUE` for the same `JOB_EXECUTION_ID` for result cards, details, and the job-name filter. Do not fall back to `JOB_INSTANCE.JOB_NAME`; if the parameter is missing, the displayed name is blank. Keep this lookup compatible with both MySQL and Oracle and return at most one name per execution.

## Read-only invariants

Preserve these guarantees in every change:

- `spring-batch-core` must remain absent; there must be no Spring Batch schema initialization or job execution.
- All controller endpoints must remain `GET`-only. Do not add POST, PUT, DELETE, or mutation endpoints.
- Repository code must contain only `SELECT` statements.
- Bind all user values with `NamedParameterJdbcTemplate`/`MapSqlParameterSource`.
- Concatenate only validated table/schema identifiers.
- Keep database calls inside `DbAccess.tx()` and its read-only transaction.
- Use JDBC and application credentials with read-only access only.
- Never log credentials, JDBC URLs, parameter values, or full SQL containing bound values.
- Never deserialize execution or step context objects. Context values are text/JSON for display only.
- Keep size limits and sensitive-value masking for parameters and context.

The data-source registry creates Hikari pools lazily and opens no pool until a database operation is requested.

## Architecture

- `config/`: externalized dashboard properties and lazy per-database data sources.
- `controller/`: GET-only Thymeleaf endpoints and detail fragments.
- `service/`: filtering, detail assembly, timelines, masking, and friendly database errors.
- `repository/`: dialect-aware SELECT SQL for the six metadata tables.
- `dto/` and `model/`: records, enums, filters, table definitions, and search criteria.
- `util/`: presentation and time formatting.
- `resources/templates/`: server-rendered UI.
- `resources/static/`: CSS and vanilla JavaScript.

Do not move SQL into controllers or add JPA/Spring Batch repositories.

## Development commands


```bash
mvn -DskipTests package
mvn spring-boot:run
```

Build changes with `mvn -DskipTests package`. No test sources are currently included; get developer confirmation before running tests if tests are added.
The application normally runs on port `8080`; `SERVER_PORT` overrides it.

Open `http://localhost:<SERVER_PORT>/`.

No test sources are currently included. Use the smallest relevant build/test command and manually verify database behavior through "Test connection", search, detail, and context views.

## Configuration

Copy or export variables from `.env.example`. The project is currently configured for up to three database slots:

- `DB1_*`
- `DB2_*`
- `DB3_*`

Blank database URLs are ignored. Configuration must never expose credentials in the UI or `Database.toString()`.

Important settings:

- `BATCH_TABLE_PREFIX`: configurable table prefix
- `DASHBOARD_ZONE`: time zone of the JVM that ran the batch jobs
- `DBn_SCHEMA`: optional table owner/schema
- `DBn_TABLE_PREFIX`: optional database-specific prefix
- `DBn_POOL_MAX`: Hikari maximum pool size
- `DEFAULT_DB`: default database ID

Spring Batch stores local timestamps without a zone. Use `DASHBOARD_ZONE` only for display; do not infer timezone conversions when adding filters or timelines.

## Dialects

Keep MySQL and Oracle behavior in `DatabaseDialect` or narrowly dialect-specific repository code.

- MySQL: `LIMIT :lim`, MySQL read-only connection mode.
- Oracle: row limiting with `ROWNUM` and read-only transaction enforcement.
- Oracle ping SQL uses `SELECT 1 FROM DUAL`.

Execution search uses `COALESCE(START_TIME, CREATE_TIME)` for time filtering. An execution-ID filter ignores the time range.

## UI and data handling

- Preserve live elapsed time and timeline behavior for running executions.
- Keep status counts correct before result limits.
- Unknown or future status values belong in the UNKNOWN bucket.
- Mask parameter names matching `dashboard.sensitive-parameter-patterns`.
- Mask sensitive-looking keys in contexts and cap context text at `dashboard.max-context-chars`.
- Escape JSON before embedding it in HTML/JavaScript.
- Keep real-estate of the UI use as much as possible to display as much information as possible for developer info/debug.

## Change guidelines

- Follow existing record, enum, service, repository, and Thymeleaf patterns.
- Make the smallest complete change; do not combine unrelated fixes.
- Before finalizing implementation changes, present a before/after diff covering every changed file and the exact edits made.
- Wait for explicit developer approval of the proposed diff before applying the final changes.
- After approval, apply only the approved edits and verify the smallest relevant build or syntax check.
- Update `README.md` and `.env.example` when configuration or supported table names change.
- When touching SQL, cover both MySQL and Oracle behavior or clearly document why one dialect is affected.
- Before finishing, run at least `mvn -DskipTests package` for Java/resource changes. Use `mvn spring-boot:run` or an existing database only when live behavior is relevant.
