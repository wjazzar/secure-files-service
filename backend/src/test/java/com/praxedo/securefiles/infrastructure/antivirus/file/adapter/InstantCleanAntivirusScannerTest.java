package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.ByteArrayInputStream;
import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.domain.file.model.ScanResult;

import static org.assertj.core.api.Assertions.assertThat;

class InstantCleanAntivirusScannerTest {

    private final InstantCleanAntivirusScanner scanner = new InstantCleanAntivirusScanner(Clock.systemUTC());

    @Test
    @DisplayName("the capacity stub consumes the complete stream before returning clean")
    void consumes_the_complete_stream() {
        ByteArrayInputStream content = new ByteArrayInputStream(new byte[8_192]);

        var outcome = scanner.scan(content, 8_192);

        assertThat(content.available()).isZero();
        assertThat(outcome.result()).isEqualTo(ScanResult.CLEAN);
        assertThat(outcome.engine()).isEqualTo("capacity-stub");
        assertThat(outcome.signatureVersion()).isEqualTo("instant-clean-v1");
        assertThat(scanner.isAvailable()).isTrue();
    }
}
