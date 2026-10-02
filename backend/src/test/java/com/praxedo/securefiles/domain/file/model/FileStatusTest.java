package com.praxedo.securefiles.domain.file.model;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classification of every state, checked exhaustively.
 *
 * <p>These tests are not about the eight states that exist today — they are
 * about the ninth. Adding a state already forces a decision at compile time
 * (the enum constructor takes the classification); these tests make sure the
 * decision is <em>coherent</em>, which the compiler cannot check.
 */
class FileStatusTest {

    @Test
    @DisplayName("exactly one state is downloadable, and it is AVAILABLE")
    void only_available_is_downloadable() {
        assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isDownloadable))
                .containsExactly(FileStatus.AVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    @DisplayName("every state publishes a status")
    void every_state_projects_to_a_public_status(FileStatus status) {
        assertThat(status.publicStatus()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    @DisplayName("a downloadable state is terminal: nothing may turn a served file back")
    void downloadable_implies_terminal(FileStatus status) {
        if (status.isDownloadable()) {
            assertThat(status.isTerminal()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    @DisplayName("a state that holds a lease is neither terminal nor claimable")
    void a_lease_means_work_in_progress(FileStatus status) {
        if (status.holdsLease()) {
            assertThat(status.isTerminal()).isFalse();
            assertThat(status.isClaimable()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    @DisplayName("a claimable state holds no lease and is not terminal")
    void claimable_states_are_free_and_unfinished(FileStatus status) {
        if (status.isClaimable()) {
            assertThat(status.holdsLease()).isFalse();
            assertThat(status.isTerminal()).isFalse();
        }
    }

    @Test
    @DisplayName("the claim query targets exactly the two waiting states")
    void claimable_states_are_the_two_waiting_ones() {
        assertThat(Arrays.stream(FileStatus.values()).filter(FileStatus::isClaimable))
                .containsExactlyInAnyOrder(FileStatus.AWAITING_SCAN, FileStatus.RETRY_WAIT);
    }

    @Test
    @DisplayName("a retry and a final failure are distinct states")
    void retry_and_final_failure_are_not_the_same_thing() {
        assertThat(FileStatus.RETRY_WAIT.isTerminal()).isFalse();
        assertThat(FileStatus.FAILED_FINAL.isTerminal()).isTrue();
        assertThat(FileStatus.RETRY_WAIT.publicStatus()).isEqualTo(PublicStatus.PENDING);
        assertThat(FileStatus.FAILED_FINAL.publicStatus()).isEqualTo(PublicStatus.FAILED);
    }

    @Test
    @DisplayName("promotion is not yet availability")
    void promoting_is_not_downloadable() {
        assertThat(FileStatus.PROMOTING.isDownloadable()).isFalse();
        assertThat(FileStatus.PROMOTING.publicStatus()).isEqualTo(PublicStatus.SCANNING);
    }
}
