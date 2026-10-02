package com.praxedo.securefiles.application.file.exception;

/** The requested byte range starts past the end of the object. */
public class RangeNotSatisfiableException extends RuntimeException {

    private final long totalLength;

    public RangeNotSatisfiableException(long totalLength) {
        super("Range not satisfiable for an object of " + totalLength + " bytes");
        this.totalLength = totalLength;
    }

    public long totalLength() {
        return totalLength;
    }
}
