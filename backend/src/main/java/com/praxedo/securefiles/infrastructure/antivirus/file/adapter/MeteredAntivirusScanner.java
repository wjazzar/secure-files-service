package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.praxedo.securefiles.application.file.exception.ScannerUnavailableException;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;

/**
 * The antivirus, measured — a decorator, so that neither the adapter nor the
 * worker has to know that metrics exist.
 *
 * <ul>
 *   <li>{@code praxedo.scan.duration{result}} — how long the engine took, per verdict;</li>
 *   <li>{@code praxedo.scan.verdict{result}} — how many of each;</li>
 *   <li>{@code praxedo.scan.bytes{result}} — how many bytes the engine read to
 *       reach them: its throughput, by size;</li>
 *   <li>{@code praxedo.scan.failures} — calls that ended without a verdict;</li>
 *   <li>{@code praxedo.scan.inflight} — analyses running now: never above the number of analysis loops;</li>
 *   <li>{@code praxedo.antivirus.available} — the health gate's last answer (1 or 0).</li>
 * </ul>
 */
public final class MeteredAntivirusScanner implements AntivirusScanner {

    private final AntivirusScanner engine;
    private final MeterRegistry registry;
    private final Counter failures;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger available = new AtomicInteger();

    public MeteredAntivirusScanner(AntivirusScanner engine, MeterRegistry registry) {
        this.engine = engine;
        this.registry = registry;
        this.failures = Counter.builder("praxedo.scan.failures")
                .description("Analyses that ended without a verdict: outage, timeout, unreadable answer")
                .register(registry);
        Gauge.builder("praxedo.scan.inflight", inFlight, AtomicInteger::get)
                .description("Analyses currently running on this node")
                .register(registry);
        Gauge.builder("praxedo.antivirus.available", available, AtomicInteger::get)
                .description("Last answer of the antivirus health gate: 1 available, 0 not")
                .register(registry);
    }

    @Override
    public ScanOutcome scan(InputStream content, long sizeBytes) {
        inFlight.incrementAndGet();
        try {
            ScanOutcome outcome = engine.scan(content, sizeBytes);
            record(outcome, sizeBytes);
            return outcome;
        } catch (ScannerUnavailableException failure) {
            failures.increment();
            throw failure;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Override
    public boolean isAvailable() {
        boolean answer = engine.isAvailable();
        available.set(answer ? 1 : 0);
        return answer;
    }

    private void record(ScanOutcome outcome, long sizeBytes) {
        String result = outcome.result().name();
        scannedBytes(result).increment(sizeBytes);
        Timer.builder("praxedo.scan.duration")
                .description("Time the engine took to reach a verdict")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(outcome.duration());
        Counter.builder("praxedo.scan.verdict")
                .description("Verdicts reached, per result")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /** Registers every verdict series at zero, so a dashboard shows "none yet" rather than "no data". */
    public MeteredAntivirusScanner withAllResultsRegistered() {
        for (ScanResult result : ScanResult.values()) {
            Counter.builder("praxedo.scan.verdict").tag("result", result.name()).register(registry);
            scannedBytes(result.name());
        }
        return this;
    }

    private Counter scannedBytes(String result) {
        return Counter.builder("praxedo.scan.bytes")
                .description("Bytes the engine read to reach its verdicts: its throughput, by size")
                .baseUnit("bytes")
                .tag("result", result)
                .register(registry);
    }
}
