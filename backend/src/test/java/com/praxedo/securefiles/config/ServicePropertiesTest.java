package com.praxedo.securefiles.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.model.WorkerSettings;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ServicePropertiesTest {

    private static final long MAX_SIZE = 500L * 1024 * 1024;
    /** 60 s + 500 × 8 s: 4 060 s, about 68 minutes for the largest upload. */
    private static final LeaseTerms TRANSFER = new LeaseTerms(Duration.ofSeconds(60), Duration.ofSeconds(8));

    @Test
    @DisplayName("⭐ an orphan age shorter than the slowest upload does not boot: the sweep would take a file still arriving")
    void the_orphan_age_must_exceed_the_slowest_upload() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(Duration.ofHours(1), Duration.ofHours(2)))
                .withMessageContaining("67 min");
    }

    @Test
    @DisplayName("an idempotency reservation purged during its own upload: refused as well")
    void the_abandoned_upload_delay_must_exceed_the_slowest_upload() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(Duration.ofHours(2), Duration.ofHours(1)));
    }

    @Test
    void the_delivered_settings_hold() {
        assertThatNoException().isThrownBy(() -> properties(Duration.ofHours(2), Duration.ofHours(2)));
    }

    private static ServiceProperties properties(Duration orphanAge, Duration abandonedUploadAfter) {
        LeaseTerms lease = new LeaseTerms(Duration.ofSeconds(30), Duration.ofMillis(1200));
        return new ServiceProperties(
                new UploadLimits(MAX_SIZE, 500, 50, Duration.ofMillis(250), Duration.ofHours(24), TRANSFER),
                new WorkerSettings(lease, lease, 5, Duration.ofSeconds(10), Duration.ofMinutes(15)),
                new ServiceProperties.Maintenance(orphanAge, 500, abandonedUploadAfter));
    }
}
