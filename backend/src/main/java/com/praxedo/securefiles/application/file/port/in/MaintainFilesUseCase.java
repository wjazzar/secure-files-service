package com.praxedo.securefiles.application.file.port.in;

/**
 * The chores that keep the service healing itself: work abandoned by a
 * crashed worker, objects nobody references, idempotency keys past their use.
 *
 * <p>Called by the scheduling adapter. Each chore is a set of conditional
 * transitions: running it on several nodes at once has no side effect.
 */
public interface MaintainFilesUseCase {

    /** @return how many files whose lease expired went back to the queue, or were given up on */
    int reclaimExpiredLeases();

    /** @return how many orphan objects were removed from the quarantine */
    int sweepQuarantine();

    /** @return how many idempotency records were removed */
    int purgeIdempotencyKeys();
}
