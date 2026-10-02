package com.praxedo.securefiles.application.file.exception;

/**
 * The antivirus could not produce a conclusion: unreachable, too slow, or
 * answering something that cannot be read.
 *
 * <p>It says nothing about the content, and must never be turned into a
 * verdict. The file goes back to the queue, with a delay, and is eventually
 * given up on only after every attempt failed — as {@code FAILED}, never as
 * {@code INFECTED} and certainly never as clean.
 */
public class ScannerUnavailableException extends RuntimeException {

    public ScannerUnavailableException(String message) {
        super(message);
    }

    public ScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
