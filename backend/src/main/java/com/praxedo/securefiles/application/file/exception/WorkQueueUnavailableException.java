package com.praxedo.securefiles.application.file.exception;

/**
 * The database holding the work queue could not be reached in time — no
 * connection available, connection lost, query timed out. Transient by
 * nature, and never a statement about the file.
 */
public class WorkQueueUnavailableException extends RuntimeException {

    public WorkQueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
