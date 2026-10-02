package com.praxedo.securefiles.application.file.exception;

/**
 * The object storage could not be reached, or refused in a way that says
 * nothing about the object itself. Retryable, and never a verdict.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
