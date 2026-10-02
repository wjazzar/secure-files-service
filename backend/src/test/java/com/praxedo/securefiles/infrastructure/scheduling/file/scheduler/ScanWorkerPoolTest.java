package com.praxedo.securefiles.infrastructure.scheduling.file.scheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.port.in.ScanFilesUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The analysis loops are the only bound on how many analyses reach the engine
 * at once — the scan service adds none of its own. So the bound is proved here,
 * against a queue that never runs dry.
 */
class ScanWorkerPoolTest {

    private static final int LOOPS = 3;
    private static final Duration POLL = Duration.ofMillis(10);
    private static final Duration DRAIN = Duration.ofSeconds(5);

    private final EndlessWork work = new EndlessWork();
    private ScanWorkerPool pool;

    @AfterEach
    void stopTheLoops() {
        work.finish();
        if (pool != null) {
            pool.stop();
        }
    }

    @Test
    @DisplayName("with work always waiting, no more analyses run at once than there are loops")
    void the_loops_bound_the_analyses() throws InterruptedException {
        pool = new ScanWorkerPool(work, LOOPS, POLL, DRAIN);
        pool.start();

        assertThat(work.allLoopsBusy.await(5, TimeUnit.SECONDS)).as("every loop took a file").isTrue();
        // Work is waiting: a fourth analysis would start now if anything allowed it.
        Thread.sleep(200);
        assertThat(work.running.get()).isEqualTo(LOOPS);

        // Analyses now end at once, and each loop takes the next file straight away.
        work.finish();
        Thread.sleep(100);
        assertThat(work.calls.get()).as("the loops went on working").isGreaterThan(LOOPS);
        assertThat(work.peak.get()).isEqualTo(LOOPS);
    }

    @Test
    @DisplayName("a node without a loop is refused at start-up: not analysing is said with its own switch")
    void a_node_without_a_loop_is_refused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScanWorkerPool(work, 0, POLL, DRAIN))
                .withMessageContaining("praxedo.worker.enabled");
    }

    /** A queue that never runs dry; each analysis holds its loop until {@link #finish()}. */
    private static final class EndlessWork implements ScanFilesUseCase {

        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch allLoopsBusy = new CountDownLatch(LOOPS);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public boolean processNext() {
            calls.incrementAndGet();
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            allLoopsBusy.countDown();
            try {
                finished.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return true;
        }

        void finish() {
            finished.countDown();
        }

        @Override
        public int releaseInFlight() {
            return 0;
        }
    }
}
