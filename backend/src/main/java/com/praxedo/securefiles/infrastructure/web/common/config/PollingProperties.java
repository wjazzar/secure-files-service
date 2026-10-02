package com.praxedo.securefiles.infrastructure.web.common.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How soon a client is invited to look at a file again while its analysis is
 * not finished: a floor, plus time in proportion to the file's size, capped.
 *
 * @param minimum     the hint for the smallest file, and the floor of every
 *                    hint: never more often than this
 * @param perMebibyte added for each MiB of content, in line with the measured
 *                    throughput of an analysis and its promotion
 * @param maximum     the ceiling: the longest a client may learn of a verdict
 *                    after it was reached
 */
@ConfigurationProperties("praxedo.polling")
public record PollingProperties(Duration minimum, Duration perMebibyte, Duration maximum) {

    public PollingProperties {
        Objects.requireNonNull(minimum, "polling minimum");
        Objects.requireNonNull(perMebibyte, "polling per mebibyte");
        Objects.requireNonNull(maximum, "polling maximum");
        if (minimum.toSeconds() < 1 || perMebibyte.isNegative() || maximum.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    "Polling needs a minimum of at least 1 s, a non-negative rate and a maximum above the minimum");
        }
    }
}
