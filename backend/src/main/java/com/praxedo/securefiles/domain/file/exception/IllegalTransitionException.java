package com.praxedo.securefiles.domain.file.exception;

import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;

/**
 * Raised when a transition the state machine does not allow is attempted.
 *
 * <p>It is a programming error, not an expected outcome: every legal path is
 * named in {@link StoredFile}. Concurrency is <em>not</em> handled by catching
 * this — it is handled by the conditional writes in the repository, which
 * simply affect zero rows.
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(FileStatus from, String attempted) {
        super("Illegal transition from " + from + ": " + attempted);
    }
}
