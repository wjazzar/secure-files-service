package com.praxedo.securefiles.infrastructure.scheduling.file.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.praxedo.securefiles.application.file.port.in.ScanFilesUseCase;

/**
 * Runs the analysis loops, and stops them cleanly.
 *
 * <p>One virtual thread per slot, each looping on {@link ScanFilesUseCase#processNext()}:
 * work while there is work, wait a short while when there is none. There is no
 * message to receive — the database is the queue — so polling is the whole
 * mechanism, and the partial index on waiting files makes it cheap.
 *
 * <p><strong>The number of loops is the bound on simultaneous analyses.</strong>
 * A loop runs one analysis at a time, and nothing else calls the scan use case:
 * {@code concurrency} loops, at most {@code concurrency} analyses reaching the
 * engine from this node. Virtual threads do not change that. What they undo is
 * the limit a pool size puts on tasks <em>submitted</em> to an executor — each
 * task gets its own thread — not a fixed number of loops that each wait for
 * their own work to finish. Hence no semaphore in front of the engine: it could
 * never be short of permits.
 *
 * <p><strong>Stopping hands the work back.</strong> On shutdown the loops stop
 * taking files, the analyses in progress get a grace period to finish, and
 * whatever is still running after it is released: its file goes back to the
 * queue immediately, without the attempt being counted. A crash, by contrast,
 * keeps the attempt, and the reaper returns the file once its lease expires.
 */
@Component
@ConditionalOnProperty(name = "praxedo.worker.enabled", havingValue = "true", matchIfMissing = true)
class ScanWorkerPool implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ScanWorkerPool.class);

    private final ScanFilesUseCase workerUseCase;
    private final int concurrency;
    private final Duration pollInterval;
    private final Duration drainTimeout;
    private final List<Thread> loops = new ArrayList<>();
    private volatile boolean running;

    ScanWorkerPool(ScanFilesUseCase workerUseCase,
                   @Value("${praxedo.worker.concurrency}") int concurrency,
                   @Value("${praxedo.worker.poll-interval}") Duration pollInterval,
                   @Value("${praxedo.worker.drain-timeout}") Duration drainTimeout) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("praxedo.worker.concurrency must be at least 1; "
                    + "to stop analysing on this node, set praxedo.worker.enabled=false");
        }
        this.workerUseCase = workerUseCase;
        this.concurrency = concurrency;
        this.pollInterval = pollInterval;
        this.drainTimeout = drainTimeout;
    }

    @Override
    public synchronized void start() {
        running = true;
        for (int slot = 0; slot < concurrency; slot++) {
            loops.add(Thread.ofVirtual().name("scan-worker-" + slot).start(this::loop));
        }
        LOG.info("Started {} analysis loop(s)", concurrency);
    }

    private void loop() {
        while (running) {
            boolean worked;
            try {
                worked = workerUseCase.processNext();
            } catch (RuntimeException unexpected) {
                LOG.error("Analysis loop failed on a file; continuing", unexpected);
                worked = false;
            }
            if (!worked && running) {
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        for (Thread loop : loops) {
            long remaining = deadline - System.nanoTime();
            join(loop, Math.max(remaining, 0));
        }
        loops.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        int released = workerUseCase.releaseInFlight();
        if (released > 0) {
            LOG.info("Handed {} file(s) in progress back to the queue", released);
        }
        loops.clear();
    }

    private static void join(Thread loop, long nanos) {
        try {
            loop.join(Duration.ofNanos(nanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
