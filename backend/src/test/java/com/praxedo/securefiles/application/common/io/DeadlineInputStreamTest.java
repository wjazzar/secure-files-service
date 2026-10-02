package com.praxedo.securefiles.application.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.testsupport.SettableClock;
import com.praxedo.securefiles.testsupport.TestContent;
import com.praxedo.securefiles.testsupport.TricklingInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class DeadlineInputStreamTest {

    private static final Instant START = Instant.parse("2026-09-30T12:00:00Z");

    private final SettableClock clock = new SettableClock(START);

    @Test
    @DisplayName("a transfer that ends before its deadline is read whole, and never marked expired")
    void in_time() throws IOException {
        DeadlineInputStream bounded = new DeadlineInputStream(TestContent.generated(10_000),
                START.plusSeconds(60), clock);

        assertThat(TestContent.digestOf(bounded)).isEqualTo(TestContent.digestOfGenerated(10_000));
        assertThat(bounded.expired()).isFalse();
    }

    @Test
    @DisplayName("a peer that trickles is cut at the deadline, although it never went silent")
    void a_trickle_is_cut() {
        // 10 bytes every 5 s: no read ever waits long, yet 1 000 bytes would take 500 s.
        InputStream trickling = new TricklingInputStream(TestContent.generated(1_000), 10, clock,
                Duration.ofSeconds(5));
        DeadlineInputStream bounded = new DeadlineInputStream(trickling, START.plusSeconds(60), clock);

        assertThatIOException().isThrownBy(() -> bounded.transferTo(OutputStream.nullOutputStream()))
                .withMessageContaining("deadline");
        assertThat(bounded.expired()).isTrue();
        assertThat(clock.instant()).isBefore(START.plusSeconds(70));
    }

    @Test
    @DisplayName("the deadline is checked before skipping too: skipped bytes are transferred bytes")
    void skip_is_bounded() {
        DeadlineInputStream bounded = new DeadlineInputStream(TestContent.generated(1_000), START, clock);

        assertThatIOException().isThrownBy(() -> bounded.skip(10));
        assertThat(bounded.expired()).isTrue();
    }
}
