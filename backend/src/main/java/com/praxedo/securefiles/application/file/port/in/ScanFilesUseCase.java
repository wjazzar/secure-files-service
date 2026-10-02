package com.praxedo.securefiles.application.file.port.in;

/**
 * Analyse the files waiting in the queue, one unit of work at a time, and
 * release them as clean — or not.
 *
 * <p>Called by the scheduling adapter, whose loops pace the work — and bound it:
 * a call is one analysis, so there are as many analyses at once as there are
 * callers at once. The service adds no limit of its own.
 */
public interface ScanFilesUseCase {

    /**
     * Claims the next due file, has it analysed, records the verdict and, when
     * clean, promotes it.
     *
     * @return {@code true} when a file was handled — the caller may loop at
     *         once; {@code false} when there was nothing to do or the engine is down
     */
    boolean processNext();

    /**
     * Hands back every claim still in progress — the node is stopping — without
     * consuming the attempt.
     *
     * @return how many files went back to the queue
     */
    int releaseInFlight();
}
