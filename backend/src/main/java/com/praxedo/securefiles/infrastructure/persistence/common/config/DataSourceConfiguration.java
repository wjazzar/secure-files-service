package com.praxedo.securefiles.infrastructure.persistence.common.config;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two connection pools to the same database: one for the API, one for the
 * work queue.
 *
 * <p>A bulkhead, like the fixed number of analysis loops and the cap on
 * concurrent uploads. With virtual threads nothing bounds the requests the API serves —
 * reads above all: the interface polls every file until it is final — and a
 * shared pool drained by them starves the workers, the only ones that empty
 * the queue. The capacity campaigns saw it: up to 211 requests waiting for a
 * connection, and useful throughput halved.
 *
 * <p>The split is clean because the queue's statements never run inside a
 * transaction: each is one conditional statement, committed on its own. JPA,
 * Flyway, the sessions and the gauges stay on the API's pool, which Spring
 * Boot's own {@code spring.datasource.hikari.*} settings describe.
 *
 * <p>Each pool names its sessions ({@code praxedo-api}, {@code praxedo-queue}):
 * in {@code pg_stat_activity}, what waits and on what is read per pool. A
 * leak-detection threshold set for the API's pool
 * ({@code spring.datasource.hikari.leak-detection-threshold}) applies to the
 * queue's too: a connection held longer is logged with where it was taken.
 *
 * <p>Both pools reach the database Spring Boot would have: the
 * {@code spring.datasource} settings, or the {@link JdbcConnectionDetails} a
 * service connection provides — a test container, for instance. Both wait for
 * it within the same {@link DatabaseTimeouts}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseTimeouts.class)
public class DataSourceConfiguration {

    /**
     * Qualifies the work queue's pool. There is deliberately no {@code JdbcClient}
     * bean on it: one would make Spring Boot withdraw its own, and every other
     * adapter — the idempotency store, which writes inside the upload's
     * transaction — would silently move to this pool, outside that transaction.
     */
    public static final String QUEUE = "queue";

    /**
     * Each worker holds at most one connection, briefly; two more for the
     * reaper and a promotion finishing while a worker claims.
     */
    private static final int QUEUE_CONNECTIONS_BEYOND_WORKERS = 2;

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection,
                                DatabaseTimeouts timeouts) {
        return pool(properties, connection, timeouts);
    }

    @Bean(QUEUE)
    @Qualifier(QUEUE)
    HikariDataSource queueDataSource(DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection,
                                     DatabaseTimeouts timeouts,
                                     @Value("${praxedo.worker.concurrency}") int workers,
                                     @Value("${spring.datasource.hikari.connection-timeout}") long connectionTimeoutMillis,
                                     @Value("${spring.datasource.hikari.leak-detection-threshold:0}") long leakDetectionMillis) {
        HikariDataSource queue = pool(properties, connection, timeouts);
        queue.setPoolName(QUEUE);
        queue.addDataSourceProperty("ApplicationName", "praxedo-" + QUEUE);
        queue.setLeakDetectionThreshold(leakDetectionMillis);
        queue.setMaximumPoolSize(workers + QUEUE_CONNECTIONS_BEYOND_WORKERS);
        queue.setConnectionTimeout(connectionTimeoutMillis);
        return queue;
    }

    private static HikariDataSource pool(DataSourceProperties properties,
                                         ObjectProvider<JdbcConnectionDetails> connection,
                                         DatabaseTimeouts timeouts) {
        DataSourceBuilder<HikariDataSource> builder = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class);
        connection.ifAvailable(details -> builder
                .url(details.getJdbcUrl())
                .username(details.getUsername())
                .password(details.getPassword())
                .driverClassName(details.getDriverClassName()));
        HikariDataSource pool = builder.build();
        // pgjdbc counts these two in seconds.
        pool.addDataSourceProperty("connectTimeout", String.valueOf(timeouts.connect().toSeconds()));
        pool.addDataSourceProperty("socketTimeout", String.valueOf(timeouts.socket().toSeconds()));
        // Set on the server at connection: PostgreSQL cancels the statement, the connection survives.
        pool.addDataSourceProperty("options", "-c statement_timeout=" + timeouts.statement().toMillis());
        return pool;
    }
}
