package com.praxedo.securefiles.application.common.io;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.testsupport.TestContent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class InspectingInputStreamTest {

    @Test
    @DisplayName("the digest and the count are those of the bytes that went through, in one pass")
    void counts_and_hashes_in_one_pass() throws IOException {
        long size = 200_000;
        InspectingInputStream inspected = new InspectingInputStream(TestContent.generated(size), size);

        drain(inspected);

        assertThat(inspected.count()).isEqualTo(size);
        assertThat(inspected.exceeded()).isFalse();
        assertThat(inspected.digest().value()).isEqualTo(TestContent.digestOfGenerated(size));
    }

    @Test
    @DisplayName("a body longer than declared is cut off at the first extra byte")
    void a_body_longer_than_declared_is_refused() {
        InspectingInputStream inspected = new InspectingInputStream(TestContent.generated(1_001), 1_000);

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> drain(inspected));
        assertThat(inspected.exceeded()).isTrue();
    }

    @Test
    @DisplayName("a body shorter than declared ends early, and the count shows it")
    void a_body_shorter_than_declared_is_visible() throws IOException {
        InspectingInputStream inspected = new InspectingInputStream(TestContent.generated(10), 1_000);

        drain(inspected);

        assertThat(inspected.count()).isEqualTo(10);
        assertThat(inspected.exceeded()).isFalse();
    }

    @Test
    @DisplayName("skipped bytes are still hashed: nothing can slip past the digest")
    void skipping_still_hashes() throws IOException {
        long size = 50_000;
        InspectingInputStream inspected = new InspectingInputStream(TestContent.generated(size), size);

        assertThat(inspected.skip(20_000)).isEqualTo(20_000);
        drain(inspected);

        assertThat(inspected.digest().value()).isEqualTo(TestContent.digestOfGenerated(size));
    }

    @Test
    @DisplayName("replaying is refused: it would hash the same bytes twice")
    void replaying_is_refused() {
        InspectingInputStream inspected = new InspectingInputStream(TestContent.generated(10), 10);

        assertThat(inspected.markSupported()).isFalse();
        assertThatExceptionOfType(IOException.class).isThrownBy(inspected::reset);
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        while (in.read(buffer) >= 0) {
            // draining is the point
        }
    }
}
