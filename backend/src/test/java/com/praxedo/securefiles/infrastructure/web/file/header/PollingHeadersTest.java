package com.praxedo.securefiles.infrastructure.web.file.header;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.praxedo.securefiles.infrastructure.web.common.config.PollingProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The polling hint: 2 s, plus 0.05 s per MiB, at most 30 s — the settings of {@code application.yml}. */
class PollingHeadersTest {

    private static final PollingProperties DEFAULTS =
            new PollingProperties(Duration.ofSeconds(2), Duration.ofMillis(50), Duration.ofSeconds(30));

    private final PollingHeaders polling = new PollingHeaders(DEFAULTS);

    @ParameterizedTest(name = "{0} bytes -> Retry-After: {1}")
    @CsvSource({
            "1,           2",   // the floor: never more often than the old fixed value
            "1000000,     2",   // 2.05 s, rounded to the nearest: a small file is not pushed to 3 s
            "50000000,    4",   // 2 + 47.7 MiB x 0.05 s = 4.4 s
            "100000000,   7",   // 6.8 s
            "500000000,   26",  // the largest accepted file: 25.8 s
            "2000000000,  30"   // beyond today's limit: capped
    })
    @DisplayName("sized to the file, rounded to the nearest second, floored and capped")
    void sized_to_the_file(long sizeBytes, String expected) {
        assertThat(polling.retryAfterSeconds(sizeBytes)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a rate of zero gives back the old fixed hint")
    void a_zero_rate_is_the_fixed_hint() {
        PollingHeaders fixed = new PollingHeaders(
                new PollingProperties(Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(30)));

        assertThat(fixed.retryAfterSeconds(500_000_000)).isEqualTo("2");
    }

    @Test
    @DisplayName("settings that would ask for a hint under a second, or a ceiling under the floor, are refused")
    void inconsistent_settings_are_refused() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PollingProperties(Duration.ofMillis(500), Duration.ofMillis(50), Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PollingProperties(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(5)));
    }
}
