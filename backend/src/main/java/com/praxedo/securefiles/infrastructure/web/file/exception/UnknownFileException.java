package com.praxedo.securefiles.infrastructure.web.file.exception;

/**
 * No such file — for this caller.
 *
 * <p>It is also what a file owned by somebody else raises. The two are
 * indistinguishable from outside on purpose: answering {@code 403} would confirm
 * that the identifier exists, which is exactly the answer an enumeration attempt
 * is looking for.
 *
 * <p>It carries no identifier in its message. An error document that echoed the
 * requested id back would be one more place where it could end up in a log
 * someone else reads.
 */
public class UnknownFileException extends RuntimeException {

    public UnknownFileException() {
        super("No such file");
    }
}
