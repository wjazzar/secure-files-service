package com.praxedo.securefiles.domain.file.model;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.domain.file.valueobject.Sha256;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ScanVerdictTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Sha256 CONTENT = Sha256.of("c".repeat(64));
    private static final Sha256 OTHER = Sha256.of("d".repeat(64));

    @Test
    @DisplayName("a clean verdict attests the content it actually inspected, and nothing else")
    void a_clean_verdict_only_attests_its_own_content() {
        ScanVerdict verdict = clean(CONTENT);

        assertThat(verdict.attestsCleanFor(CONTENT)).isTrue();
        assertThat(verdict.attestsCleanFor(OTHER)).isFalse();
    }

    @Test
    void a_threat_never_attests_anything() {
        ScanVerdict verdict = new ScanVerdict(ScanResult.INFECTED, "Eicar-Test-Signature",
                "ClamAV", "1.4.6", "28098", CONTENT, NOW, Duration.ofMillis(10));

        assertThat(verdict.attestsCleanFor(CONTENT)).isFalse();
    }

    @Test
    @DisplayName("a clean verdict without a signature version is refused")
    void a_clean_verdict_must_say_which_signatures_it_used() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", null,
                        CONTENT, NOW, Duration.ofMillis(10)))
                .withMessageContaining("signature database version");
    }

    @Test
    @DisplayName("an unscannable verdict may lack a signature version: nothing was concluded")
    void an_unscannable_verdict_needs_no_signature_version() {
        ScanVerdict verdict = new ScanVerdict(ScanResult.UNSCANNABLE, "Heuristics.Limits.Exceeded",
                "ClamAV", null, null, CONTENT, NOW, Duration.ofMillis(10));

        assertThat(verdict.result()).isEqualTo(ScanResult.UNSCANNABLE);
        assertThat(verdict.signatureVersion()).isNull();
    }

    @Test
    void a_verdict_must_name_the_engine_that_produced_it() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ScanVerdict(ScanResult.CLEAN, null, "  ", "1.4.6", "28098",
                        CONTENT, NOW, Duration.ofMillis(10)));
    }

    private static ScanVerdict clean(Sha256 content) {
        return new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                content, NOW, Duration.ofMillis(10));
    }
}
