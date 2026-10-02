package com.praxedo.securefiles.application.file.exception;

/** The storage has no object under the requested key. */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException(String key) {
        super("No object under key " + key);
    }
}
