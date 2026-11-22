package com.ithaka.batchinspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Read-only Spring Batch execution viewer.
 *
 * The default single DataSource auto-configuration is disabled on purpose: the application
 * must start (and render its UI) with zero database connections. Pools are created lazily,
 * one per configured database, by {@code DataSourceRegistry}.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class BatchInspectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatchInspectorApplication.class, args);
    }
}
