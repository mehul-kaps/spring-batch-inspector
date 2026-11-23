# Batch Inspector

A **strictly read-only** debugging dashboard for **Spring Batch 5.x** metadata stored in **MySQL or Oracle**.
Built for developers investigating production job runs: find an execution, see why a step failed (with a readable
stack-trace viewer), and understand *when* every step actually ran on an interactive timeline.

* Java 21 bytecode target · Spring Boot 3.3.5 · Spring JDBC + HikariCP · Thymeleaf + vanilla JS · Maven
* Tables used: configure the prefix in `src/main/resources/application.yml`; table names follow
  `<prefix>JOB_INSTANCE`, `<prefix>JOB_EXECUTION`, `<prefix>JOB_EXECUTION_PARAMS`, `<prefix>STEP_EXECUTION`,
  `<prefix>JOB_EXECUTION_CONTEXT`, and `<prefix>STEP_EXECUTION_CONTEXT`
* No test sources are currently included. Use `mvn -DskipTests package` to compile and package; run tests only when test sources exist and their execution is approved.

> **Database support:** SQL dialect and JDBC driver are selected from each configured JDBC URL. The project does not
> include live database integration tests; verify connectivity and metadata-table access with **Test connection**.

## Features

| Area | What you get |
|---|---|
| Filters | Database dropdown, time-range chips (15 min … 30 days, all, custom), comma- or newline-separated Execution IDs / Instance IDs, case-insensitive *Job name contains*, Maximum results (database-side limit), Reset |
| Status strip | All / Completed / Failed / Started / Starting / Stopping / Stopped / Abandoned / Unknown with **real counts of every match (before the display limit)** and a share bar |
| Results | Colour-coded cards: job name from the execution's `JobName` parameter, per-job colour avatar, status badge + glyph, ids, start → end, live elapsed time for running jobs, exit code, exit message preview, step / failed-step chips, relative duration bar |
| Detail | Overview (KPIs, **failure spotlight** with stack-trace viewer, timeline, summary), Steps table (all counters, throughput, slowest flag, expandable exit messages), Parameters (masked if sensitive), optional Context (text only, masked, size-limited) |
| Timeline | Real time axis from persisted timestamps, one row per step (so overlaps are visible), idle-gap markers, "now" line for running jobs, zoom, hover tooltip, click-to-inspect, slowest-step ranking, hatched bars where an end time was never recorded |
| Dev comforts | Light/dark theme, env badge (PROD gets a red banner), keyboard shortcuts (`/` `j` `k` `Enter` `1-4` `r` `t` `?`), copy buttons, auto-refresh, "Test connection" check |

## Prerequisites

1. JDK 21 or newer and Maven 3.9+. The project compiles with Java 21 compatibility (`maven.compiler.release=21` via Spring Boot's `java.version` setting).
2. Network access from the machine running the app to each MySQL or Oracle database
3. A **SELECT-only** account per database – see `docs/mysql-readonly-grants.sql` or `docs/oracle-readonly-grants.sql`
4. The MySQL Connector/J and Oracle JDBC drivers are included as runtime dependencies from Maven Central.

## Configure

Everything is externalised in `src/main/resources/application.yml` using environment variables (see `.env.example`).
Up to three database slots are predefined (`DB1_*`, `DB2_*`, `DB3_*`); slots with a blank URL are ignored. To add more, copy a block
in `application.yml` under `dashboard.databases` with a new `id`.

Key settings:

| Variable | Meaning |
|---|---|
| `BATCH_TABLE_PREFIX` | Configurable table prefix from `dashboard.table-prefix`; the default Spring Batch convention is `BATCH_` |
| `DASHBOARD_ZONE` | Time zone of the JVM that runs your batch jobs (Spring Batch 5 stores local date-times without zone). Default: this server's zone |
| `DBn_SCHEMA` | Optional schema/database qualifier when the read-only user is not the owner |
| `DBn_POOL_MAX` | Hikari max pool size (default 4) |

Credentials are never rendered in the UI and `toString()` of the database config deliberately omits them.

## Run

```bash
export DB1_URL='jdbc:mysql://localhost:3306/batch_db'
export DB1_USER='BATCH_VIEWER'
export DB1_PASSWORD='...'
export DASHBOARD_ZONE='Asia/Kolkata'
mvn spring-boot:run
# open http://localhost:8080 (or the configured SERVER_PORT)
```

For Oracle, use a URL such as `jdbc:oracle:thin:@//dev-host:1521/DEVPDB`.

Or `mvn -DskipTests package && java -jar target/batch-inspector-1.0.0.jar`.

The UI opens with **no database connection**. A pool is created lazily on the first search / detail / "Test connection" for the chosen database;
unrelated databases are never touched. If a database is down you get an error panel and can switch to another one without a restart.

## Read-only guarantees (defence in depth)

1. **Database grants** – the account has `SELECT` on the six metadata tables only (see the MySQL or Oracle grant script). This is the real guarantee.
2. Every query runs in a Spring **read-only transaction**. Oracle additionally uses `enforceReadOnly` (`SET TRANSACTION READ ONLY`); MySQL uses its read-only JDBC connection mode to avoid a Connector/J conflict with that statement.
3. The repository contains only `SELECT` statements; all user input is bound (`NamedParameterJdbcTemplate`); only validated table identifiers are concatenated.
4. Every controller endpoint is a `GET`; there is no write endpoint.
5. `spring-batch-core` is **not a dependency**, so nothing can launch, restart, stop or modify jobs, and schema initialisation can't run. `spring.batch.job.enabled=false` / `initialize-schema=never` are set anyway.
6. No schedulers or background writers exist.

## Spring Batch 5.x schema assumptions

```
JOB_INSTANCE(JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
JOB_EXECUTION(JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED)
JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING)      -- 5.x layout (not the 4.x KEY_NAME/TYPE_CD layout)
STEP_EXECUTION(STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, COMMIT_COUNT, READ_COUNT,
               FILTER_COUNT, WRITE_COUNT, READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED)
JOB_EXECUTION_CONTEXT(JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
STEP_EXECUTION_CONTEXT(STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
```
Each metadata table name uses the configured prefix. If a column is missing, the schema is probably not 5.x.

Semantics worth knowing:

* **Time filter** uses the execution start time (`COALESCE(START_TIME, CREATE_TIME)` so `STARTING` executions still match). An **Execution ID** search ignores the time range.
* **Status counts** are counts of *all* executions matching the sidebar filters, before the *Maximum results* limit; the list then shows the newest N of the selected status.
* **Displayed job name** comes only from the execution parameter named `JobName`. Executions without that parameter have no displayed job name; the job-name filter matches the parameter value.
  The parameter is matched to the specific `JOB_EXECUTION_ID`; `JOB_INSTANCE.JOB_NAME` is not used as a fallback.
* Unrecognised / null status values are folded into the **Unknown** card and displayed with their stored text.
* **Timeline**: bars use stored `START_TIME`/`END_TIME` only. Steps are never assumed sequential; overlaps are flagged, idle gaps are shown only where the timestamps prove nothing ran. A step with no end time on a still-running job extends to "now"; on a finished job it is drawn hatched as *end not recorded* (typically a killed JVM).
* Execution contexts are displayed as **text only** (JSON pretty-printed with Jackson `readTree`, never deserialised into objects), capped at `dashboard.max-context-chars`, with values under sensitive-looking keys masked. Job parameters matching `dashboard.sensitive-parameter-patterns` are masked.

Index suggestions (documentation only): `docs/suggested-indexes.sql`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "No database configured yet" | Set `DB1_URL`/`DB1_USER`/`DB1_PASSWORD` and restart |
| Login / access denied | Check username, password, MySQL host grants, and database privileges |
| Cannot reach database | Check host, port, service/database name, and firewall |
| Table not found / unreadable | Wrong `BATCH_TABLE_PREFIX`, missing `DBn_SCHEMA`, or no SELECT grant. Use **Test connection** – it lists unreadable tables |
| Column not found | Metadata schema isn't Spring Batch 5.x |
| Times look shifted | Set `DASHBOARD_ZONE` to the zone of the JVM that runs the jobs |
| Slow list | See `docs/suggested-indexes.sql`; narrow the time range |
| Fonts look generic | Add JetBrains Mono / Inter `.woff2` files under `static/fonts` and `@font-face` rules; system fonts are used otherwise so the tool works offline |
| Static assets stale after edits | Run with `THYMELEAF_CACHE=false` |

## Layout

```
config/       DashboardProperties (externalised config), DataSourceRegistry (lazy HikariCP pools)
controller/   DashboardController (GET only)
service/      ExecutionQueryService, SearchCriteriaFactory, TimelineBuilder, SensitiveMasker
repository/   BatchMetadataRepository (SELECT-only SQL)
dto/ model/   immutable records / enums
exception/    DashboardException, DbErrorTranslator (friendly, non-sensitive errors)
util/         Formatters (@fmt in Thymeleaf)
resources/    templates (index, fragments/detail), static css/js
```


This is test
