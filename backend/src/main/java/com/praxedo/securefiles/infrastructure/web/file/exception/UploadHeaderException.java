package com.praxedo.securefiles.infrastructure.web.file.exception;

/**
 * An upload refused on its headers alone — before the use case is even asked.
 *
 * <p>These are HTTP concerns (a length the protocol did not announce, a header
 * that does not decode), which is why they live here and not beside the
 * refusals of the use case.
 */
public abstract sealed class UploadHeaderException extends RuntimeException {

    UploadHeaderException(String message) {
        super(message);
    }

    /** {@code 411 LENGTH_REQUIRED}. */
    public static final class LengthRequired extends UploadHeaderException {
        public LengthRequired() {
            super("Content-Length is required");
        }
    }

    /** {@code 400 INVALID_FILE_NAME}. */
    public static final class InvalidFileName extends UploadHeaderException {
        public InvalidFileName() {
            super("X-File-Name is missing, malformed, or holds nothing usable");
        }
    }
}
