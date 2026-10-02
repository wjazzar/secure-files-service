package com.praxedo.securefiles.application.file.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.testsupport.SettableClock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The count admission reads: fresh enough, counted once per interval — and,
 * above all, never waited for.
 */
class PendingFileCountCacheTest {

    private static final Duration MAX_AGE = Duration.ofMillis(250);

    private final SettableClock clock = new SettableClock(Instant.parse("2026-09-30T12:00:00Z"));
    private final AtomicLong pendingInDatabase = new AtomicLong(5);
    private final AtomicInteger counts = new AtomicInteger();

    @Test
    @DisplayName("a fresh count is served as is; a stale one is counted again")
    void a_fresh_count_is_reused() {
        PendingFileCountCache cache = new PendingFileCountCache(this::count, MAX_AGE, clock);

        assertThat(cache.current()).isEqualTo(5);
        pendingInDatabase.set(9);
        clock.advance(MAX_AGE.minusMillis(1));
        assertThat(cache.current()).isEqualTo(5);

        clock.advance(Duration.ofMillis(1));
        assertThat(cache.current()).isEqualTo(9);
        assertThat(counts.get()).isEqualTo(2);
    }

    /**
     * The scenario the cache exists for: the database is slow to count, a burst
     * of uploads arrives. One request pays for the refresh; every other one
     * answers at once with the previous count instead of queueing for a
     * connection.
     */
    @Test
    @DisplayName("while one request refreshes a stale count, the others answer at once with the previous one")
    void nobody_waits_for_the_refresh() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch databaseAnswers = new CountDownLatch(1);
        PendingFileCountCache cache = new PendingFileCountCache(() -> {
            if (counts.get() == 1) {          // the refresh, not the first measure
                refreshStarted.countDown();
                awaitQuietly(databaseAnswers);
            }
            return count();
        }, MAX_AGE, clock);

        assertThat(cache.current()).isEqualTo(5);
        clock.advance(MAX_AGE);
        pendingInDatabase.set(8);

        try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Long> refreshing = requests.submit(cache::current);
            assertThat(refreshStarted.await(5, SECONDS)).isTrue();

            List<Future<Long>> others = IntStream.range(0, 20)
                    .mapToObj(request -> requests.submit(cache::current))
                    .toList();
            for (Future<Long> other : others) {
                // A request that waited for the refresh would time out here.
                assertThat(other.get(2, SECONDS)).isEqualTo(5);
            }
            assertThat(refreshing).isNotDone();

            databaseAnswers.countDown();
            assertThat(refreshing.get(5, SECONDS)).isEqualTo(8);
        }

        assertThat(cache.current()).isEqualTo(8);
        assertThat(counts.get()).as("one refresh, however many requests arrived").isEqualTo(2);
    }

    @Test
    @DisplayName("a count that fails keeps nothing: the next request counts again")
    void a_failed_count_is_retried() {
        AtomicInteger failuresLeft = new AtomicInteger(1);
        PendingFileCountCache cache = new PendingFileCountCache(() -> {
            if (counts.get() == 1 && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("database out of reach");
            }
            return count();
        }, MAX_AGE, clock);
        cache.current();
        clock.advance(MAX_AGE);
        pendingInDatabase.set(6);

        assertThatIllegalStateException().isThrownBy(cache::current);

        assertThat(cache.current()).isEqualTo(6);
    }

    private long count() {
        counts.incrementAndGet();
        return pendingInDatabase.get();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException("The test never let the database answer");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
