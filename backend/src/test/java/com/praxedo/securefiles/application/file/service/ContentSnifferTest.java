package com.praxedo.securefiles.application.file.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSnifferTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            %PDF-1.7                  | application/pdf
            GIF89a                    | image/gif
            <?xml version="1.0"?>     | application/xml
            {\\rtf1\\ansi             | application/rtf
            plain words, nothing else | text/plain
            """)
    void textual_signatures_are_recognised(String head, String expected) {
        assertThat(ContentSniffer.typeOf(head.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
    }

    @Test
    void binary_signatures_are_recognised() {
        assertThat(ContentSniffer.typeOf(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).isEqualTo("image/png");
        assertThat(ContentSniffer.typeOf(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(ContentSniffer.typeOf(bytes('P', 'K', 0x03, 0x04))).isEqualTo("application/zip");
        assertThat(ContentSniffer.typeOf(bytes(0x1F, 0x8B, 0x08))).isEqualTo("application/gzip");
        assertThat(ContentSniffer.typeOf(bytes(0x7F, 'E', 'L', 'F'))).isEqualTo("application/x-executable");
        assertThat(ContentSniffer.typeOf(bytes('M', 'Z', 0x90, 0))).isEqualTo("application/x-msdownload");
    }

    @Test
    @DisplayName("anything unrecognised, or binary, is opaque")
    void unknown_content_is_octet_stream() {
        assertThat(ContentSniffer.typeOf(bytes(0x00, 0x01, 0x02, 0x03))).isEqualTo("application/octet-stream");
        assertThat(ContentSniffer.typeOf(new byte[0])).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("sniffing peeks and puts the bytes back: the stream still starts at its first byte")
    void sniffing_leaves_the_stream_untouched() throws IOException {
        BufferedInputStream content = new BufferedInputStream(
                new ByteArrayInputStream("%PDF-1.7 and the rest".getBytes(StandardCharsets.US_ASCII)));

        assertThat(ContentSniffer.detect(content).value()).isEqualTo("application/pdf");
        assertThat(new String(content.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7 and the rest");
    }

    @Test
    @DisplayName("the type the client declared plays no part: only the bytes speak")
    void a_disguised_executable_is_seen_for_what_it_is() {
        // Named invoice.pdf, declared application/pdf by the client — and yet:
        assertThat(ContentSniffer.typeOf(bytes('M', 'Z', 0x90, 0x00, 0x03))).isEqualTo("application/x-msdownload");
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
