package com.praxedo.securefiles.infrastructure.persistence.file;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.application.file.model.FileCounters;
import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.FileSort;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.port.in.QueryFilesUseCase;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.testsupport.FileFixtures;
import com.praxedo.securefiles.testsupport.PostgresTestcontainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Listing, ordering, searching and counting, against the real PostgreSQL.
 *
 * <p>Every property here is a property of the database, not of the Java around
 * it: whether an ordering is total, whether a {@code LIKE} pattern escapes what
 * a caller wrote, whether a {@code group by} on a native enum comes back mapped.
 * A doubled catalogue would prove none of them.
 */
@SpringBootTest
class FileCatalogQueryTest extends PostgresTestcontainer {

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");
    private static final OwnerId ALICE = new OwnerId("alice");
    private static final OwnerId BOB = new OwnerId("bob");

    @Autowired
    FileCatalog catalog;

    @Autowired
    FileWorkQueue workQueue;

    @Autowired
    QueryFilesUseCase queriesUseCase;

    FileFixtures files;

    @BeforeEach
    void setUp() {
        files = new FileFixtures(catalog, workQueue);
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        void a_page_holds_at_most_its_size_and_reports_the_whole_count() {
            insert(25);

            PageResult<StoredFile> page = catalog.findPage(everything(), PageQuery.of(0, 10, FileSort.DEFAULT));

            assertThat(page.content()).hasSize(10);
            assertThat(page.number()).isZero();
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.totalElements()).isEqualTo(25);
            assertThat(page.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("walking every page yields each file exactly once")
        void pages_neither_overlap_nor_skip() {
            insert(25);

            List<FileId> walked = walkEveryPage(10, FileSort.DEFAULT);

            assertThat(walked).hasSize(25).doesNotHaveDuplicates();
        }

        @Test
        void a_page_past_the_last_one_is_empty_but_still_counts() {
            insert(5);

            PageResult<StoredFile> page = catalog.findPage(everything(), PageQuery.of(9, 10, FileSort.DEFAULT));

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(5);
            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty catalogue is zero pages, not one empty page")
        void nothing_to_page_through_is_zero_pages() {
            PageResult<StoredFile> page = catalog.findPage(everything(), PageQuery.of(0, 20, FileSort.DEFAULT));

            assertThat(page.totalElements()).isZero();
            assertThat(page.totalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        void the_default_order_is_the_most_recently_uploaded_first() {
            files.awaitingScan("old.pdf", 10L, T0.minus(Duration.ofHours(2)));
            files.awaitingScan("new.pdf", 10L, T0);
            files.awaitingScan("middle.pdf", 10L, T0.minus(Duration.ofHours(1)));

            assertThat(namesInOrder(FileSort.DEFAULT))
                    .containsExactly("new.pdf", "middle.pdf", "old.pdf");
        }

        @Test
        void files_order_by_upload_date_both_ways() {
            files.awaitingScan("old.pdf", 10L, T0.minus(Duration.ofHours(2)));
            files.awaitingScan("new.pdf", 10L, T0);

            assertThat(namesInOrder(FileSort.UPLOADED_AT_ASC)).containsExactly("old.pdf", "new.pdf");
            assertThat(namesInOrder(FileSort.UPLOADED_AT_DESC)).containsExactly("new.pdf", "old.pdf");
        }

        @Test
        void files_order_by_name_both_ways() {
            files.awaitingScan("charlie.pdf", 10L, T0);
            files.awaitingScan("alpha.pdf", 10L, T0);
            files.awaitingScan("bravo.pdf", 10L, T0);

            assertThat(namesInOrder(FileSort.FILENAME_ASC))
                    .containsExactly("alpha.pdf", "bravo.pdf", "charlie.pdf");
            assertThat(namesInOrder(FileSort.FILENAME_DESC))
                    .containsExactly("charlie.pdf", "bravo.pdf", "alpha.pdf");
        }

        @Test
        void files_order_by_size_both_ways() {
            files.awaitingScan("medium.bin", 500L, T0);
            files.awaitingScan("small.bin", 1L, T0);
            files.awaitingScan("large.bin", 900_000L, T0);

            assertThat(namesInOrder(FileSort.SIZE_BYTES_ASC))
                    .containsExactly("small.bin", "medium.bin", "large.bin");
            assertThat(namesInOrder(FileSort.SIZE_BYTES_DESC))
                    .containsExactly("large.bin", "medium.bin", "small.bin");
        }

        @Test
        @DisplayName("files sharing a sort key are still ordered, so pages cannot overlap")
        void the_identifier_completes_every_ordering() {
            // Same instant, same size, same name: without the tie-break on id
            // the database is free to return these in any order, and page 2
            // could repeat a row of page 1.
            for (int index = 0; index < 20; index++) {
                files.awaitingScan("identical.bin", 4_096L, T0);
            }

            List<FileId> firstWalk = walkEveryPage(7, FileSort.UPLOADED_AT_DESC);
            List<FileId> secondWalk = walkEveryPage(7, FileSort.UPLOADED_AT_DESC);

            assertThat(firstWalk).hasSize(20).doesNotHaveDuplicates();
            assertThat(firstWalk).isEqualTo(secondWalk);
        }
    }

    @Nested
    @DisplayName("searching")
    class Searching {

        @Test
        void the_search_ignores_case_and_matches_anywhere_in_the_name() {
            files.awaitingScan("Rapport-Annuel.PDF");
            files.awaitingScan("notes.txt");

            assertThat(namesMatching("annuel")).containsExactly("Rapport-Annuel.PDF");
            assertThat(namesMatching("RAPPORT")).containsExactly("Rapport-Annuel.PDF");
            assertThat(namesMatching(".pdf")).containsExactly("Rapport-Annuel.PDF");
        }

        @Test
        @DisplayName("the search ignores accents too, both ways: « releve » finds « Relevé », « relevé » finds « RELEVE »")
        void the_search_ignores_accents() {
            files.awaitingScan("Relevé-de-compte.pdf");
            files.awaitingScan("FACTURE-ÉTÉ.pdf");
            files.awaitingScan("RELEVE-2025.csv");

            assertThat(namesMatching("releve")).containsExactlyInAnyOrder("Relevé-de-compte.pdf", "RELEVE-2025.csv");
            assertThat(namesMatching("relevé")).containsExactlyInAnyOrder("Relevé-de-compte.pdf", "RELEVE-2025.csv");
            assertThat(namesMatching("facture-ete")).containsExactly("FACTURE-ÉTÉ.pdf");
        }

        @Test
        @DisplayName("a wildcard typed by the caller is searched for, not obeyed")
        void like_metacharacters_are_escaped() {
            files.awaitingScan("discount-100%.csv");
            files.awaitingScan("invoice.pdf");

            // Unescaped, "%" would match every file and "_" any single character.
            assertThat(namesMatching("100%")).containsExactly("discount-100%.csv");
            assertThat(namesMatching("%")).containsExactly("discount-100%.csv");
            assertThat(namesMatching("invoice")).containsExactly("invoice.pdf");
            assertThat(namesMatching("invoic_")).isEmpty();
        }

        @Test
        void no_search_returns_everything() {
            files.awaitingScan("a.pdf");
            files.awaitingScan("b.pdf");

            assertThat(catalog.findPage(everything(), PageQuery.of(0, 20, FileSort.DEFAULT)).totalElements())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("filtering by status")
    class Filtering {

        @Test
        @DisplayName("PENDING means both of the internal states behind it")
        void a_public_status_matches_every_internal_state_it_stands_for() {
            files.retryWait("retried.bin");
            files.awaitingScan("fresh.bin");

            assertThat(namesWithStatus(PublicStatus.PENDING))
                    .containsExactlyInAnyOrder("retried.bin", "fresh.bin");
        }

        @Test
        @DisplayName("SCANNING covers a promotion in progress too")
        void scanning_covers_promoting() {
            files.promoting("promoting.bin");
            files.scanning("scanning.bin");
            files.awaitingScan("fresh.bin");

            assertThat(namesWithStatus(PublicStatus.SCANNING))
                    .containsExactlyInAnyOrder("promoting.bin", "scanning.bin");
        }

        @Test
        void a_terminal_status_matches_only_itself() {
            files.infected("eicar.txt");
            files.unscannable("locked.zip");
            files.failedFinal("unreadable.bin");
            files.available("clean.pdf");

            assertThat(namesWithStatus(PublicStatus.INFECTED)).containsExactly("eicar.txt");
            assertThat(namesWithStatus(PublicStatus.UNSCANNABLE)).containsExactly("locked.zip");
            assertThat(namesWithStatus(PublicStatus.FAILED)).containsExactly("unreadable.bin");
            assertThat(namesWithStatus(PublicStatus.AVAILABLE)).containsExactly("clean.pdf");
        }

        @Test
        void asking_for_several_statuses_combines_them() {
            files.infected("eicar.txt");
            files.available("clean.pdf");
            files.awaitingScan("fresh.bin");

            FileQuery query = FileQuery.of(FileFixtures.OWNER,
                    Set.of(PublicStatus.INFECTED, PublicStatus.AVAILABLE), null);

            assertThat(names(catalog.findPage(query, PageQuery.of(0, 20, FileSort.DEFAULT))))
                    .containsExactlyInAnyOrder("eicar.txt", "clean.pdf");
        }
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        void counts_come_back_grouped_by_internal_state() {
            files.infected("eicar.txt");
            files.retryWait("retried.bin");
            files.awaitingScan("fresh-1.bin");
            files.awaitingScan("fresh-2.bin");

            Map<FileStatus, Long> counts = catalog.countByStatus(everything());

            assertThat(counts).containsEntry(FileStatus.INFECTED, 1L)
                    .containsEntry(FileStatus.RETRY_WAIT, 1L)
                    .containsEntry(FileStatus.AWAITING_SCAN, 2L);
            assertThat(counts).doesNotContainKey(FileStatus.AVAILABLE);
        }

        @Test
        @DisplayName("the six published counters are always there, at zero when empty")
        void the_service_folds_the_counts_onto_the_published_statuses() {
            // Files that must be driven forward first; the one left claimable last.
            files.promoting("promoting.bin");
            files.retryWait("retried.bin");
            files.awaitingScan("fresh.bin");

            FileCounters counters = queriesUseCase.count(FileFixtures.OWNER, null);

            assertThat(counters.byStatus()).containsOnlyKeys(PublicStatus.values());
            assertThat(counters.byStatus()).containsEntry(PublicStatus.PENDING, 2L);
            assertThat(counters.byStatus()).containsEntry(PublicStatus.SCANNING, 1L);
            assertThat(counters.byStatus()).containsEntry(PublicStatus.AVAILABLE, 0L);
            assertThat(counters.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("the counters honour the same search, so they match what the table shows")
        void the_counters_follow_the_search() {
            files.infected("report-eicar.txt");
            files.awaitingScan("report-draft.pdf");
            files.awaitingScan("unrelated.bin");

            FileCounters counters = queriesUseCase.count(FileFixtures.OWNER, "report");

            assertThat(counters.total()).isEqualTo(2);
            assertThat(counters.byStatus()).containsEntry(PublicStatus.INFECTED, 1L);
            assertThat(counters.byStatus()).containsEntry(PublicStatus.PENDING, 1L);
        }
    }

    @Nested
    @DisplayName("owner partitioning")
    class Partitioning {

        @Test
        void a_page_never_contains_another_owners_file() {
            files.awaitingScan("alice.pdf", 10L, T0, ALICE);
            files.awaitingScan("bob.pdf", 10L, T0, BOB);

            PageResult<StoredFile> page = catalog.findPage(
                    FileQuery.of(ALICE, Set.of(), null), PageQuery.of(0, 20, FileSort.DEFAULT));

            assertThat(names(page)).containsExactly("alice.pdf");
            assertThat(page.totalElements()).isEqualTo(1);
        }

        @Test
        void the_counters_never_count_another_owners_file() {
            files.awaitingScan("alice.pdf", 10L, T0, ALICE);
            files.awaitingScan("bob-1.pdf", 10L, T0, BOB);
            files.awaitingScan("bob-2.pdf", 10L, T0, BOB);

            assertThat(queriesUseCase.count(ALICE, null).total()).isEqualTo(1);
            assertThat(queriesUseCase.count(BOB, null).total()).isEqualTo(2);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void insert(int count) {
        for (int index = 0; index < count; index++) {
            files.awaitingScan("file-%02d.bin".formatted(index), 1_024L + index, T0.plusSeconds(index));
        }
    }

    private static FileQuery everything() {
        return FileQuery.of(FileFixtures.OWNER, Set.of(), null);
    }

    private List<String> namesInOrder(FileSort sort) {
        return names(catalog.findPage(everything(), PageQuery.of(0, 20, sort)));
    }

    private List<String> namesMatching(String search) {
        FileQuery query = FileQuery.of(FileFixtures.OWNER, Set.of(), search);
        return names(catalog.findPage(query, PageQuery.of(0, 20, FileSort.DEFAULT)));
    }

    private List<String> namesWithStatus(PublicStatus status) {
        FileQuery query = FileQuery.of(FileFixtures.OWNER, Set.of(status), null);
        return names(catalog.findPage(query, PageQuery.of(0, 20, FileSort.DEFAULT)));
    }

    private static List<String> names(PageResult<StoredFile> page) {
        return page.content().stream().map(file -> file.filename().value()).toList();
    }

    private List<FileId> walkEveryPage(int size, FileSort sort) {
        List<FileId> seen = new ArrayList<>();
        int pageNumber = 0;
        PageResult<StoredFile> page;
        do {
            page = catalog.findPage(everything(), PageQuery.of(pageNumber, size, sort));
            page.content().forEach(file -> seen.add(file.id()));
            pageNumber++;
        } while (pageNumber < page.totalPages());
        return seen;
    }
}
