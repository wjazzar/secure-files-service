package com.praxedo.securefiles.domain.file.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.domain.file.exception.IllegalTransitionException;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.CONTENT;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.LEASE;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.MAX_ATTEMPTS;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.OTHER_CONTENT;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.T0;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.available;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.clean;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.infected;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.promoting;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.received;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.scanning;
import static com.praxedo.securefiles.domain.file.model.StoredFileFixture.unscannable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class StoredFileTest {

    @Nested
    @DisplayName("the nominal path")
    class NominalPath {

        @Test
        void a_received_file_is_in_quarantine_and_not_downloadable() {
            StoredFile file = received();

            assertThat(file.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(file.area()).isEqualTo(StorageArea.QUARANTINE);
            assertThat(file.isDownloadable()).isFalse();
            assertThat(file.attempts()).isZero();
            assertThat(file.scan()).isEmpty();
        }

        @Test
        void claiming_counts_the_attempt_and_takes_a_lease() {
            StoredFile claimed = received().claimedBy(LeaseToken.random(), "worker-1", T0, LEASE);

            assertThat(claimed.status()).isEqualTo(FileStatus.SCANNING);
            assertThat(claimed.attempts()).isEqualTo(1);
            assertThat(claimed.currentLease()).isPresent();
            assertThat(claimed.currentLease().orElseThrow().expiresAt()).isEqualTo(T0.plus(LEASE));
        }

        @Test
        @DisplayName("a clean verdict makes the file promotable, NOT available")
        void a_clean_verdict_does_not_make_the_file_available() {
            StoredFile promotable = scanning().scanned(clean(CONTENT), T0.plusSeconds(3));

            assertThat(promotable.status()).isEqualTo(FileStatus.PROMOTING);
            assertThat(promotable.isDownloadable()).isFalse();
            assertThat(promotable.area()).isEqualTo(StorageArea.QUARANTINE);
            // The lease is kept: the same claim goes on to copy the content.
            assertThat(promotable.currentLease()).isPresent();
        }

        @Test
        void promotion_makes_the_file_available_in_the_servable_area() {
            StoredFile file = available();

            assertThat(file.status()).isEqualTo(FileStatus.AVAILABLE);
            assertThat(file.area()).isEqualTo(StorageArea.SERVABLE);
            assertThat(file.isDownloadable()).isTrue();
            assertThat(file.isTerminal()).isTrue();
            assertThat(file.currentLease()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the blocked paths")
    class BlockedPaths {

        @Test
        void a_threat_blocks_the_file_for_good_and_releases_the_lease() {
            StoredFile file = scanning().scanned(infected(CONTENT), T0.plusSeconds(3));

            assertThat(file.status()).isEqualTo(FileStatus.INFECTED);
            assertThat(file.isDownloadable()).isFalse();
            assertThat(file.isTerminal()).isTrue();
            assertThat(file.currentLease()).isEmpty();
            assertThat(file.scan().orElseThrow().threat()).contains("Eicar-Test-Signature");
        }

        @Test
        void an_unscannable_verdict_explains_itself() {
            StoredFile file = scanning()
                    .scanned(unscannable(CONTENT, "Heuristics.Encrypted.Zip"), T0.plusSeconds(3));

            assertThat(file.status()).isEqualTo(FileStatus.UNSCANNABLE);
            assertThat(file.reason()).contains(StatusReason.ENCRYPTED_ARCHIVE);
        }

        @Test
        @DisplayName("a technical failure schedules a retry, it is not a verdict")
        void a_technical_failure_schedules_a_retry() {
            StoredFile file = scanning().technicalFailure(MAX_ATTEMPTS, T0.plusSeconds(3));

            assertThat(file.status()).isEqualTo(FileStatus.RETRY_WAIT);
            assertThat(file.isTerminal()).isFalse();
            assertThat(file.publicStatus()).isEqualTo(PublicStatus.PENDING);
            assertThat(file.reason()).contains(StatusReason.SCAN_RETRY_SCHEDULED);
            assertThat(file.scan()).isEmpty();
        }

        @Test
        void the_last_attempt_ends_the_work_instead_of_looping_forever() {
            StoredFile file = received();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                file = file.claimedBy(LeaseToken.random(), "worker-1", T0, LEASE)
                        .technicalFailure(MAX_ATTEMPTS, T0.plusSeconds(1));
            }

            assertThat(file.attempts()).isEqualTo(MAX_ATTEMPTS);
            assertThat(file.status()).isEqualTo(FileStatus.FAILED_FINAL);
            assertThat(file.reason()).contains(StatusReason.SCAN_ATTEMPTS_EXHAUSTED);
            assertThat(file.status().isClaimable()).isFalse();
        }

        @Test
        @DisplayName("a clean shutdown hands the attempt back, a crash does not")
        void a_clean_shutdown_does_not_consume_an_attempt() {
            StoredFile claimed = scanning();
            assertThat(claimed.attempts()).isEqualTo(1);

            StoredFile released = claimed.releasedOnShutdown(T0.plusSeconds(1));

            assertThat(released.status()).isEqualTo(FileStatus.AWAITING_SCAN);
            assertThat(released.attempts()).isZero();
            assertThat(released.currentLease()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the transitions the machine refuses")
    class RefusedTransitions {

        @Test
        void a_file_being_analysed_cannot_be_claimed_again() {
            StoredFile claimed = scanning();

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> claimed.claimedBy(LeaseToken.random(), "worker-2", T0, LEASE));
        }

        @Test
        void a_terminal_file_cannot_be_claimed() {
            StoredFile blocked = scanning().scanned(infected(CONTENT), T0.plusSeconds(3));

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> blocked.claimedBy(LeaseToken.random(), "worker-1", T0, LEASE));
        }

        @Test
        void a_file_cannot_be_promoted_before_it_has_been_scanned() {
            StoredFile claimed = scanning();

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> claimed.promoted(T0.plusSeconds(4)));
        }

        @Test
        void a_verdict_cannot_be_recorded_on_a_file_nobody_is_analysing() {
            StoredFile waiting = received();

            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> waiting.scanned(clean(CONTENT), T0.plusSeconds(3)));
        }

        @Test
        @DisplayName("a verdict obtained for other content is refused")
        void a_verdict_for_other_content_is_refused() {
            StoredFile claimed = scanning();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> claimed.scanned(clean(OTHER_CONTENT), T0.plusSeconds(3)))
                    .withMessageContaining("different content");
        }

        @Test
        void a_file_that_holds_no_lease_cannot_release_one() {
            assertThatExceptionOfType(IllegalTransitionException.class)
                    .isThrownBy(() -> received().releasedOnShutdown(T0));
        }
    }

    @Nested
    @DisplayName("the invariant, checked at construction")
    class Invariant {

        @Test
        @DisplayName("no available file without a clean attestation")
        void available_without_a_verdict_cannot_be_built() {
            StoredFile promotable = promoting();

            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    promotable.id(), promotable.owner(), promotable.filename(), promotable.contentType(),
                    promotable.sizeBytes(), promotable.sha256(), StorageArea.SERVABLE, FileStatus.AVAILABLE,
                    null, null, promotable.attempts(), null,
                    promotable.uploadedAt(), promotable.statusChangedAt(), promotable.version()));
        }

        @Test
        @DisplayName("no available file whose attestation is about other bytes")
        void available_with_an_attestation_for_other_content_cannot_be_built() {
            StoredFile promotable = promoting();

            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    promotable.id(), promotable.owner(), promotable.filename(), promotable.contentType(),
                    promotable.sizeBytes(), promotable.sha256(), StorageArea.SERVABLE, FileStatus.AVAILABLE,
                    null, clean(OTHER_CONTENT), promotable.attempts(), null,
                    promotable.uploadedAt(), promotable.statusChangedAt(), promotable.version()));
        }

        @Test
        @DisplayName("nothing but an available file lives in the servable area")
        void a_non_available_file_cannot_live_in_the_servable_area() {
            StoredFile waiting = received();

            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    waiting.id(), waiting.owner(), waiting.filename(), waiting.contentType(),
                    waiting.sizeBytes(), waiting.sha256(), StorageArea.SERVABLE, FileStatus.AWAITING_SCAN,
                    null, null, 0, null, T0, T0, 0L));
        }

        @Test
        void a_lease_and_the_status_cannot_disagree() {
            StoredFile waiting = received();
            Lease lease = new Lease(LeaseToken.random(), "worker-1", T0.plusSeconds(600));

            // A waiting file holding a lease...
            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    waiting.id(), waiting.owner(), waiting.filename(), waiting.contentType(),
                    waiting.sizeBytes(), waiting.sha256(), StorageArea.QUARANTINE, FileStatus.AWAITING_SCAN,
                    null, null, 0, lease, T0, T0, 0L));

            // ...and a file being analysed without one.
            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    waiting.id(), waiting.owner(), waiting.filename(), waiting.contentType(),
                    waiting.sizeBytes(), waiting.sha256(), StorageArea.QUARANTINE, FileStatus.SCANNING,
                    null, null, 1, null, T0, T0, 0L));
        }

        @Test
        void an_infected_status_requires_the_verdict_that_justifies_it() {
            StoredFile waiting = received();

            assertThatIllegalStateException().isThrownBy(() -> new StoredFile(
                    waiting.id(), waiting.owner(), waiting.filename(), waiting.contentType(),
                    waiting.sizeBytes(), waiting.sha256(), StorageArea.QUARANTINE, FileStatus.INFECTED,
                    null, clean(CONTENT), 1, null, T0, T0, 0L));
        }

        @Test
        void an_empty_file_is_never_stored() {
            StoredFile waiting = received();

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new StoredFile(
                    waiting.id(), waiting.owner(), waiting.filename(), waiting.contentType(),
                    0L, waiting.sha256(), StorageArea.QUARANTINE, FileStatus.AWAITING_SCAN,
                    null, null, 0, null, T0, T0, 0L));
        }
    }

    @Nested
    @DisplayName("what the rest of the system reads")
    class Queries {

        @Test
        void the_storage_key_is_the_generated_id_never_the_supplied_name() {
            StoredFile file = received();

            assertThat(file.objectKey().value()).isEqualTo(file.id().value().toString());
            assertThat(file.objectKey().value()).doesNotContain("rapport");
        }

        @Test
        void the_public_projection_hides_the_internal_states() {
            assertThat(received().publicStatus()).isEqualTo(PublicStatus.PENDING);
            assertThat(scanning().publicStatus()).isEqualTo(PublicStatus.SCANNING);
            assertThat(promoting().publicStatus()).isEqualTo(PublicStatus.SCANNING);
            assertThat(available().publicStatus()).isEqualTo(PublicStatus.AVAILABLE);
        }
    }
}
