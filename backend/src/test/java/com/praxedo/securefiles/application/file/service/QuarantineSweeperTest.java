package com.praxedo.securefiles.application.file.service;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantineSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Duration ORPHAN_AGE = Duration.ofHours(1);

    private final FakeStorage storage = new FakeStorage();
    private final FakeCatalog catalog = new FakeCatalog();
    private final QuarantineSweeper sweeper = sweeper(100);

    @Test
    @DisplayName("an object nobody references is removed")
    void orphans_are_removed() {
        storage.quarantined.add("orphan");

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(storage.deleted).containsExactly("orphan");
    }

    @Test
    @DisplayName("the source of a promoted file is removed: the servable copy is what is served")
    void sources_of_available_files_are_removed() {
        storage.quarantined.add("promoted");
        catalog.statuses.put("promoted", FileStatus.AVAILABLE);

        sweeper.sweep();

        assertThat(storage.deleted).containsExactly("promoted");
    }

    @Test
    @DisplayName("work still ahead, and blocked files kept as evidence, are left alone")
    void everything_else_stays() {
        for (FileStatus status : FileStatus.values()) {
            if (status != FileStatus.AVAILABLE) {
                storage.quarantined.add(status.name());
                catalog.statuses.put(status.name(), status);
            }
        }

        assertThat(sweeper.sweep()).isZero();
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    @DisplayName("⭐ a full batch of files kept as evidence does not hide the orphans behind it")
    void each_sweep_resumes_where_the_previous_one_stopped() {
        QuarantineSweeper twoAtATime = sweeper(2);
        for (String kept : List.of("a-infected", "b-unscannable")) {
            storage.quarantined.add(kept);
            catalog.statuses.put(kept, FileStatus.INFECTED);
        }
        storage.quarantined.add("c-orphan");

        assertThat(twoAtATime.sweep()).isZero();
        assertThat(twoAtATime.sweep()).isEqualTo(1);
        assertThat(storage.deleted).containsExactly("c-orphan");

        // The end was reached: the next sweep starts over from the first key.
        twoAtATime.sweep();
        assertThat(storage.askedAfter).isNull();
    }

    @Test
    @DisplayName("only objects older than the orphan age are even considered — an upload in flight is not an orphan")
    void the_listing_asks_only_for_old_objects() {
        sweeper.sweep();

        assertThat(storage.askedOlderThan).isEqualTo(NOW.minus(ORPHAN_AGE));
    }

    private QuarantineSweeper sweeper(int batchSize) {
        return new QuarantineSweeper(storage, catalog, Clock.fixed(NOW, ZoneOffset.UTC), ORPHAN_AGE, batchSize);
    }

    /** Lists in key order, after the key asked, as S3 does. */
    private static final class FakeStorage implements WorkerStorage {
        final List<String> quarantined = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        Instant askedOlderThan;
        ObjectKey askedAfter;

        @Override
        public List<ObjectKey> listQuarantinedBefore(Instant olderThan, ObjectKey after, int limit) {
            askedOlderThan = olderThan;
            askedAfter = after;
            return quarantined.stream()
                    .sorted()
                    .filter(key -> after == null || key.compareTo(after.value()) > 0)
                    .limit(limit)
                    .map(ObjectKey::new)
                    .toList();
        }

        @Override
        public void deleteQuarantined(ObjectKey key) {
            deleted.add(key.value());
            quarantined.remove(key.value());
        }

        @Override
        public InputStream openQuarantined(ObjectKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeServable(ObjectKey key, InputStream content, long sizeBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteServable(ObjectKey key) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeCatalog implements FileCatalog {
        final Map<String, FileStatus> statuses = new HashMap<>();

        @Override
        public Map<String, FileStatus> statusesByObjectKey(Collection<String> objectKeys) {
            Map<String, FileStatus> found = new HashMap<>();
            objectKeys.forEach(key -> {
                if (statuses.containsKey(key)) {
                    found.put(key, statuses.get(key));
                }
            });
            return found;
        }

        @Override
        public StoredFile insert(StoredFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredFile> findById(FileId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredFile> findOwnedBy(FileId id, OwnerId owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResult<StoredFile> findPage(FileQuery query, PageQuery page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<FileStatus, Long> countByStatus(FileQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countPendingFiles() {
            throw new UnsupportedOperationException();
        }
    }
}
