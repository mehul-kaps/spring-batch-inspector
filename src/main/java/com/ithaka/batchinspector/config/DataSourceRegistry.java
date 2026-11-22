package com.ithaka.batchinspector.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.ithaka.batchinspector.config.DashboardProperties.Database;
import com.ithaka.batchinspector.config.DashboardProperties.Pool;
import com.ithaka.batchinspector.dto.DatabaseOption;
import com.ithaka.batchinspector.exception.DashboardException;
import com.ithaka.batchinspector.model.DatabaseDialect;
import com.ithaka.batchinspector.model.Tables;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Lazily creates one HikariCP pool per configured database.
 *
 * Nothing is created at startup. A pool object is instantiated on first use and Hikari itself
 * only opens its first physical connection on the first getConnection() call.
 * Every query runs inside a read-only transaction. Oracle additionally uses Spring's enforceReadOnly
 * statement; MySQL relies on the read-only connection and SELECT-only grants.
 */
@Component
public class DataSourceRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    /** Everything needed to run read-only queries against one database. */
    public record DbAccess(Database db, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Tables tables,
                           DatabaseDialect dialect) {
    }

    private final DashboardProperties props;
    private final Map<String, DbAccess> cache = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceRegistry(DashboardProperties props) {
        this.props = props;
    }

    public List<DatabaseOption> options() {
        return props.configuredDatabases().stream()
                .map(d -> new DatabaseOption(d.id(), d.displayName(), d.environment() == null ? "" : d.environment()))
                .toList();
    }

    public DbAccess access(String dbId) {
        Database db = props.find(dbId).orElseThrow(() ->
                new DashboardException(DashboardException.Kind.NOT_FOUND, "Unknown database selection."));
        return cache.computeIfAbsent(db.id(), id -> build(db));
    }

    private DbAccess build(Database db) {
        DatabaseDialect dialect = DatabaseDialect.fromJdbcUrl(db.url());
        Tables tables;
        try {
            tables = Tables.of(db.schema(), props.prefixFor(db));
        } catch (IllegalStateException e) {
            throw new DashboardException(DashboardException.Kind.BAD_REQUEST,
                    "Invalid table prefix/schema configured for database '" + db.displayName() + "'.", e);
        }
        Pool p = db.pool();
        HikariDataSource ds = new HikariDataSource(); // no-arg ctor => pool is started lazily on first getConnection()
        ds.setPoolName("batch-inspector-" + db.id());
        ds.setJdbcUrl(db.url());
        ds.setUsername(db.username());
        ds.setPassword(db.password());
        ds.setDriverClassName(dialect.driverClassName());
        ds.setMaximumPoolSize(Math.max(1, p.maxPoolSize()));
        ds.setMinimumIdle(Math.max(0, Math.min(p.minIdle(), p.maxPoolSize())));
        ds.setConnectionTimeout(Math.max(1000, p.connectionTimeoutMs()));
        ds.setIdleTimeout(Math.max(10_000, p.idleTimeoutMs()));
        ds.setMaxLifetime(Math.max(60_000, p.maxLifetimeMs()));
        ds.setValidationTimeout(Math.max(1000, Math.min(p.validationTimeoutMs(), ds.getConnectionTimeout())));
        // Wait the full connection timeout before reporting "cannot connect" for the very first connection.
        ds.setInitializationFailTimeout(ds.getConnectionTimeout());
        ds.setAutoCommit(true);
        pools.put(db.id(), ds);

        DataSource dataSource = ds;
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(props.queryTimeoutSeconds());
        jdbc.getJdbcTemplate().setFetchSize(200);

        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        // MySQL's driver rejects SET TRANSACTION READ ONLY once the connection is marked read-only.
        tm.setEnforceReadOnly(dialect == DatabaseDialect.ORACLE);
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setReadOnly(true);
        tx.setTimeout(props.queryTimeoutSeconds() + 5);

        log.info("Initialised lazy connection pool for database '{}' (no connection opened yet)", db.id());
        return new DbAccess(db, jdbc, tx, tables, dialect);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }
}
