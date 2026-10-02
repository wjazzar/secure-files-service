package com.praxedo.securefiles.infrastructure.persistence.file.adapter;

import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.praxedo.securefiles.application.file.port.out.OperationalReadings;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.FileStatusSql;

/**
 * The questions operations ask the database, and nothing else does.
 *
 * <p>Read by the metrics and the health indicator on every scrape: both are
 * single aggregates over partial indexes, cheap enough to be asked every few
 * seconds.
 */
@Repository
public class JdbcOperationalReadings implements OperationalReadings {

    private final JdbcClient jdbc;

    public JdbcOperationalReadings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code ARCHITECTURE.md} §6.4: rows whose status and storage area disagree.
     * The {@code CHECK} constraints make this impossible; the query exists to
     * prove it continuously, and to be the first to know if it ever stops
     * being true. It must always return zero.
     */
    @Override
    public long invariantViolations() {
        return jdbc.sql("""
                SELECT count(*) FROM stored_file
                 WHERE (status = 'AVAILABLE') IS DISTINCT FROM (storage_area = 'SERVABLE')
                """).query(Long.class).single();
    }

    /**
     * How long the oldest file with work ahead has been waiting — the alerting
     * metric: a queue that grows is load, a queue that stops moving is an
     * outage. Measured on the database clock, like every lease.
     *
     * @return empty when nothing is waiting
     */
    @Override
    public Optional<Duration> oldestPendingAge() {
        Double seconds = jdbc.sql("""
                SELECT extract(epoch FROM clock_timestamp() - min(uploaded_at))
                  FROM stored_file
                 WHERE status IN (%s)
                """.formatted(FileStatusSql.PENDING)).query(Double.class).optional().orElse(null);
        return seconds == null ? Optional.empty() : Optional.of(Duration.ofMillis((long) (seconds * 1000)));
    }

    /**
     * The lag in bytes: what the workers still have to read. Same states, and
     * the same partial index, as {@link #oldestPendingAge()} — the rows with
     * work ahead are few, whatever the history.
     */
    @Override
    public long pendingBytes() {
        return jdbc.sql("""
                SELECT coalesce(sum(size_bytes), 0)
                  FROM stored_file
                 WHERE status IN (%s)
                """.formatted(FileStatusSql.PENDING)).query(Long.class).single();
    }
}
