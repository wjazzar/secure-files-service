package com.praxedo.securefiles.infrastructure.web.file.header;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.praxedo.securefiles.application.file.model.ByteRange;

import static org.assertj.core.api.Assertions.assertThat;

/** The two headers the download path has to get exactly right, without a server. */
class DownloadHeadersTest {

    @Test
    @DisplayName("a plain ASCII name is written as is, twice")
    void ascii_name() {
        assertThat(DownloadHeaders.attachment("report.pdf"))
                .isEqualTo("attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf");
    }

    @Test
    @DisplayName("quotes and backslashes cannot break out of the quoted fallback")
    void header_breaking_characters() {
        assertThat(DownloadHeaders.attachment("a\"b\\c.txt"))
                .startsWith("attachment; filename=\"a_b_c.txt\";")
                .endsWith("filename*=UTF-8''a%22b%5Cc.txt");
    }

    @Test
    @DisplayName("non-ASCII names keep their exact form in filename*, spaces as %20")
    void non_ascii_name() {
        assertThat(DownloadHeaders.attachment("日本 語.txt"))
                .isEqualTo("attachment; filename=\"__ _.txt\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%20%E8%AA%9E.txt");
    }

    @Test
    @DisplayName("filename* holds only what RFC 8187 allows: '*' is encoded too")
    void only_attribute_characters_in_the_exact_name() {
        assertThat(DownloadHeaders.attachment("a*b.txt"))
                .isEqualTo("attachment; filename=\"a*b.txt\"; filename*=UTF-8''a%2Ab.txt");
    }

    @Test
    void no_header_means_the_whole_object() {
        assertThat(DownloadHeaders.rangeOf(null)).isEqualTo(ByteRange.WHOLE_OBJECT);
    }

    @Test
    void a_closed_range() {
        assertThat(DownloadHeaders.rangeOf("bytes=10-19")).isEqualTo(ByteRange.of(10, 19));
    }

    @Test
    void an_open_range() {
        assertThat(DownloadHeaders.rangeOf("bytes=10-")).isEqualTo(ByteRange.from(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=0-1,5-6", "bytes=-500", "items=1-2", "bytes=9-3", "bytes=", "bytes=a-b",
            "bytes=99999999999999999999-"})
    @DisplayName("anything but a single forward range is ignored")
    void ignored(String header) {
        assertThat(DownloadHeaders.rangeOf(header)).isEqualTo(ByteRange.WHOLE_OBJECT);
    }
}
