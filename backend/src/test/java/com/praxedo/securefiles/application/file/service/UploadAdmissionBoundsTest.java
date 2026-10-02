package com.praxedo.securefiles.application.file.service;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.testsupport.SettableClock;
import com.praxedo.securefiles.testsupport.Leases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The two bounds an upload meets before its body is read — and what they cost:
 * nothing, for a node that is full; one count per interval, for the queue.
 */
class UploadAdmissionBoundsTest {

    private static final int PLACES = 2;
    private static final long MAX_PENDING = 10;
    private static final Duration MAX_AGE = Duration.ofMillis(250);

    private final AtomicLong pending = new AtomicLong();
    private final AtomicInteger counts = new AtomicInteger();
    private final SettableClock clock = new SettableClock(Instant.parse("2026-09-28T12:00:00Z"));
    private UploadAdmission admission;

    @BeforeEach
    void admission() {
        FileCatalog catalog = (FileCatalog) Proxy.newProxyInstance(FileCatalog.class.getClassLoader(),
                new Class<?>[] {FileCatalog.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("countPendingFiles")) {
                        counts.incrementAndGet();
                        return pending.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        admission = new UploadAdmission(catalog,
                new UploadLimits(500_000_000, MAX_PENDING, PLACES, MAX_AGE, Duration.ofHours(24),
                        Leases.fixed(Duration.ofHours(1))), clock);
    }

    @Test
    @DisplayName("a full node refuses at once, without counting the queue")
    void a_full_node_refuses_without_the_database() {
        admission.admit();
        admission.admit();
        int countsBefore = counts.get();

        assertThatExceptionOfType(UploadRefusedException.TooManyConcurrentUploads.class)
                .isThrownBy(admission::admit);
        assertThat(counts.get()).isEqualTo(countsBefore);
    }

    @Test
    @DisplayName("an upload that ends gives its place back — once, however many times it is closed")
    void an_ended_upload_frees_its_place() {
        UploadAdmission.UploadPermit first = admission.admit();
        admission.admit();

        first.close();
        first.close();

        admission.admit();
        assertThatExceptionOfType(UploadRefusedException.TooManyConcurrentUploads.class)
                .isThrownBy(admission::admit);
    }

    @Test
    @DisplayName("the queue is counted at most once per interval, however many uploads arrive")
    void the_queue_is_counted_once_per_interval() {
        for (int upload = 0; upload < 50; upload++) {
            admission.admit().close();
        }
        assertThat(counts.get()).isEqualTo(1);

        clock.advance(MAX_AGE);
        admission.admit().close();

        assertThat(counts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a full queue refuses the upload, and the place it had taken is given back")
    void a_full_queue_frees_the_place() {
        pending.set(MAX_PENDING);

        for (int attempt = 0; attempt < PLACES + 1; attempt++) {
            assertThatExceptionOfType(UploadRefusedException.TooManyPending.class).isThrownBy(admission::admit);
        }
    }
}
