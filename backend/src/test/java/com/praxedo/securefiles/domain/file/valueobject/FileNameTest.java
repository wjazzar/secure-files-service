package com.praxedo.securefiles.domain.file.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FileNameTest {

    @ParameterizedTest
    @DisplayName("a path never survives sanitisation")
    @CsvSource(delimiter = '|', textBlock = """
            ../../etc/passwd          | passwd
            /etc/shadow               | shadow
            C:\\Windows\\system32\\x.dll | x.dll
            folder/sub/report.pdf     | report.pdf
            ..                        | unnamed
            .                         | unnamed
            """)
    void paths_are_reduced_to_their_last_segment(String raw, String expected) {
        assertThat(FileName.sanitised(raw).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("control characters are removed — they truncate strings and break logs")
    void control_characters_are_removed() {
        assertThat(FileName.sanitised("rap\u0000port\nfinal\t.pdf").value()).isEqualTo("rapportfinal.pdf");
    }

    @Test
    @DisplayName("a bidirectional override is removed: 'photo<RLO>gnp.exe' must not read as a PNG")
    void bidirectional_overrides_are_removed() {
        String disguised = "photo\u202Egnp.exe";

        FileName sanitised = FileName.sanitised(disguised);

        assertThat(sanitised.value()).isEqualTo("photognp.exe");
        assertThat(sanitised.value()).doesNotContain("\u202E");
    }

    @Test
    void a_long_name_is_truncated_but_keeps_its_extension() {
        String raw = "a".repeat(400) + ".pdf";

        FileName sanitised = FileName.sanitised(raw);

        assertThat(sanitised.value()).hasSize(FileName.MAX_LENGTH);
        assertThat(sanitised.value()).endsWith(".pdf");
    }

    @Test
    void accents_and_spaces_are_kept_as_they_are() {
        assertThat(FileName.sanitised("Rapport annuel — été 2026.pdf").value())
                .isEqualTo("Rapport annuel — été 2026.pdf");
    }

    @Test
    void a_name_that_holds_nothing_usable_is_refused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> FileName.sanitised("\u0000\u0001  "));
    }

    @Test
    @DisplayName("a FileName cannot be built from an unsanitised string")
    void the_type_itself_refuses_a_dangerous_value() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new FileName("../etc/passwd"));
    }
}
