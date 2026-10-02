package com.praxedo.securefiles.infrastructure.web.common.exception;

/**
 * A query parameter the API refuses.
 *
 * <p>Its message is written to be shown to the caller: it says what was
 * expected, never what the server tried to do with it.
 */
public class InvalidParameterException extends RuntimeException {

    public InvalidParameterException(String message) {
        super(message);
    }
}
