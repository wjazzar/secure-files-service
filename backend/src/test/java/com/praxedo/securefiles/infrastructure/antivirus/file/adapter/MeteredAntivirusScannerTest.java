package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.ByteArrayInputStream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.testsupport.ScriptedScanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The engine's throughput, by size: what it read to reach each verdict — and
 * nothing for a call that reached none.
 */
class MeteredAntivirusScannerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ScriptedScanner engine = new ScriptedScanner();
    private final MeteredAntivirusScanner metered = new MeteredAntivirusScanner(engine, registry)
            .withAllResultsRegistered();

    @Test
    @DisplayName("the bytes of each analysis are counted under its verdict")
    void bytes_per_verdict() {
        engine.next = ScanResult.CLEAN;
        metered.scan(new ByteArrayInputStream(new byte[3_000]), 3_000);
        metered.scan(new ByteArrayInputStream(new byte[2_000]), 2_000);
        engine.next = ScanResult.INFECTED;
        metered.scan(new ByteArrayInputStream(new byte[500]), 500);

        assertThat(bytes("CLEAN")).isEqualTo(5_000);
        assertThat(bytes("INFECTED")).isEqualTo(500);
        assertThat(bytes("UNSCANNABLE")).isZero();
    }

    @Test
    @DisplayName("an analysis that reached no verdict adds no bytes: throughput is what was concluded")
    void no_verdict_no_bytes() {
        engine.failing = true;

        assertThatThrownBy(() -> metered.scan(new ByteArrayInputStream(new byte[1_000]), 1_000))
                .isInstanceOf(ScannerUnavailableException.class);

        for (ScanResult result : ScanResult.values()) {
            assertThat(bytes(result.name())).as(result.name()).isZero();
        }
        assertThat(registry.get("praxedo.scan.failures").counter().count()).isEqualTo(1);
    }

    private double bytes(String result) {
        return registry.get("praxedo.scan.bytes").tag("result", result).counter().count();
    }
}
