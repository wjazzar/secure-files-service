package com.praxedo.securefiles.domain.file.model;

/**
 * The internal state of a stored file.
 *
 * <p><strong>Every state classifies itself.</strong> Downloadability, the
 * public projection, whether work is still expected and whether a lease is
 * held are constructor arguments, not a {@code switch} somewhere else. Adding
 * a state therefore cannot compile until someone has answered the only
 * question that matters — <em>can this one be served?</em> — which is exactly
 * the mistake this design is meant to make impossible.
 *
 * <p>Two distinctions are deliberate and were paid for in analysis:
 * <ul>
 *   <li>{@code CLEAN} is an antivirus <em>verdict</em>, {@link #AVAILABLE} is a
 *       business <em>state</em>. Merging them would make "verdict obtained,
 *       copy not finished" inexpressible — and therefore unrecoverable after a
 *       crash during promotion.</li>
 *   <li>{@link #RETRY_WAIT} (transient) and {@link #FAILED_FINAL} (terminal)
 *       are distinct. Without that split, the claim query picks up dead work
 *       forever.</li>
 * </ul>
 */
public enum FileStatus {

    /** Stored in quarantine, waiting to be picked up. */
    AWAITING_SCAN(PublicStatus.PENDING, Servable.NO, Terminal.NO, Lease.NONE, Work.EXPECTED),

    /** Being analysed by the antivirus; a lease is held by one worker. */
    SCANNING(PublicStatus.SCANNING, Servable.NO, Terminal.NO, Lease.HELD, Work.IN_PROGRESS),

    /** A technical failure occurred; the work is scheduled for another attempt. */
    RETRY_WAIT(PublicStatus.PENDING, Servable.NO, Terminal.NO, Lease.NONE, Work.EXPECTED),

    /** Verdict is clean; the content is being copied to the servable area. */
    PROMOTING(PublicStatus.SCANNING, Servable.NO, Terminal.NO, Lease.HELD, Work.IN_PROGRESS),

    /** Clean, promoted, and downloadable. The only servable state. */
    AVAILABLE(PublicStatus.AVAILABLE, Servable.YES, Terminal.YES, Lease.NONE, Work.NONE),

    /** A threat was detected. Final. */
    INFECTED(PublicStatus.INFECTED, Servable.NO, Terminal.YES, Lease.NONE, Work.NONE),

    /** The antivirus could not analyse the content (limits, encrypted archive). Final. */
    UNSCANNABLE(PublicStatus.UNSCANNABLE, Servable.NO, Terminal.YES, Lease.NONE, Work.NONE),

    /** Analysis failed after every attempt. Final. */
    FAILED_FINAL(PublicStatus.FAILED, Servable.NO, Terminal.YES, Lease.NONE, Work.NONE);

    private enum Servable { YES, NO }

    private enum Terminal { YES, NO }

    private enum Lease { HELD, NONE }

    private enum Work { EXPECTED, IN_PROGRESS, NONE }

    private final PublicStatus publicStatus;
    private final Servable servable;
    private final Terminal terminal;
    private final Lease lease;
    private final Work work;

    FileStatus(PublicStatus publicStatus, Servable servable, Terminal terminal, Lease lease, Work work) {
        this.publicStatus = publicStatus;
        this.servable = servable;
        this.terminal = terminal;
        this.lease = lease;
        this.work = work;
    }

    /** What the API publishes for this state. */
    public PublicStatus publicStatus() {
        return publicStatus;
    }

    /**
     * Whether content in this state may be served. Exactly one state answers
     * {@code true}; every other answer is a deny by default.
     */
    public boolean isDownloadable() {
        return servable == Servable.YES;
    }

    /** Whether no further automatic change is expected. Clients stop polling. */
    public boolean isTerminal() {
        return terminal == Terminal.YES;
    }

    /** Whether a worker holds a lease on the file while it is in this state. */
    public boolean holdsLease() {
        return lease == Lease.HELD;
    }

    /** Whether the claim query may pick this file up. */
    public boolean isClaimable() {
        return work == Work.EXPECTED;
    }
}
