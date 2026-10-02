package com.praxedo.securefiles.domain.file.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection that a status filter depends on.
 *
 * <p>A client filters on published statuses; the database knows only internal
 * ones. Getting this translation wrong is silent — the caller simply sees fewer
 * files than exist, with no error anywhere — so it is written down here, by
 * hand, from the contract, and compared with what the code derives.
 */
class PublicStatusTest {

    /**
     * Copied from {@code contracts/README.md} §2.1, deliberately as a literal
     * table. If the production code and this table are both derived from the
     * same source, the test proves nothing.
     */
    private static final Map<PublicStatus, Set<FileStatus>> CONTRACT_PROJECTION = Map.of(
            PublicStatus.PENDING, EnumSet.of(FileStatus.AWAITING_SCAN, FileStatus.RETRY_WAIT),
            PublicStatus.SCANNING, EnumSet.of(FileStatus.SCANNING, FileStatus.PROMOTING),
            PublicStatus.AVAILABLE, EnumSet.of(FileStatus.AVAILABLE),
            PublicStatus.INFECTED, EnumSet.of(FileStatus.INFECTED),
            PublicStatus.UNSCANNABLE, EnumSet.of(FileStatus.UNSCANNABLE),
            PublicStatus.FAILED, EnumSet.of(FileStatus.FAILED_FINAL));

    @ParameterizedTest
    @EnumSource(PublicStatus.class)
    @DisplayName("each published status stands for exactly the internal states the contract says")
    void the_translation_is_the_one_the_contract_describes(PublicStatus published) {
        assertThat(published.internalStates()).isEqualTo(CONTRACT_PROJECTION.get(published));
    }

    @ParameterizedTest
    @EnumSource(FileStatus.class)
    @DisplayName("every internal state is reachable through exactly one published status")
    void no_internal_state_is_lost_or_counted_twice(FileStatus internal) {
        long publishers = Arrays.stream(PublicStatus.values())
                .filter(published -> published.internalStates().contains(internal))
                .count();

        assertThat(publishers)
                .describedAs("a state reachable through none would be invisible to every filter, "
                        + "and one reachable through two would be counted twice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filtering on all six statuses is the same as not filtering at all")
    void the_six_statuses_cover_the_whole_machine() {
        Set<FileStatus> covered = EnumSet.noneOf(FileStatus.class);
        Arrays.stream(PublicStatus.values()).forEach(published -> covered.addAll(published.internalStates()));

        assertThat(covered).isEqualTo(EnumSet.allOf(FileStatus.class));
    }

    @ParameterizedTest
    @EnumSource(PublicStatus.class)
    @DisplayName("no published status translates to nothing, which would match no file")
    void a_published_status_always_means_something(PublicStatus published) {
        assertThat(published.internalStates()).isNotEmpty();
    }

    @Test
    void the_translation_cannot_be_modified_by_a_caller() {
        Set<FileStatus> states = PublicStatus.PENDING.internalStates();

        assertThat(states).isUnmodifiable();
    }
}
