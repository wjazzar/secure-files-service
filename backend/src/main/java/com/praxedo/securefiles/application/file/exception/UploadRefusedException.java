package com.praxedo.securefiles.application.file.exception;

/**
 * Why an upload was refused — one subtype per answer the contract describes.
 *
 * <p>A sealed hierarchy rather than a code field: the web layer translates each
 * subtype with a {@code switch} that has no {@code default}, so adding a reason
 * here does not compile until someone has decided which HTTP answer it gets.
 */
public abstract sealed class UploadRefusedException extends RuntimeException {

    UploadRefusedException(String message) {
        super(message);
    }

    /** {@code 400 EMPTY_FILE}: there is nothing to analyse, so nothing to store. */
    public static final class EmptyFile extends UploadRefusedException {
        public EmptyFile() {
            super("The file is empty");
        }
    }

    /**
     * {@code 413 FILE_TOO_LARGE}, decided on the declared length before a single
     * byte is read — and enforced again while reading.
     */
    public static final class TooLarge extends UploadRefusedException {
        private final long maxSizeBytes;

        public TooLarge(long maxSizeBytes) {
            super("The file exceeds the accepted maximum of " + maxSizeBytes + " bytes");
            this.maxSizeBytes = maxSizeBytes;
        }

        public long maxSizeBytes() {
            return maxSizeBytes;
        }
    }

    /**
     * {@code 400 CONTENT_LENGTH_MISMATCH}: the body did not have the length it
     * announced. Nothing is kept — a truncated file analysed as clean would be a
     * clean verdict about a file nobody sent.
     */
    public static final class LengthMismatch extends UploadRefusedException {
        public LengthMismatch() {
            super("The body length does not match its declared Content-Length");
        }
    }

    /**
     * {@code 408 UPLOAD_TOO_SLOW}: the body was still arriving when its
     * transfer deadline passed. Nothing is kept, and the permit it held on the
     * node is free again.
     */
    public static final class TooSlow extends UploadRefusedException {
        public TooSlow() {
            super("The body did not arrive within its transfer deadline");
        }
    }

    /**
     * {@code 429 TOO_MANY_PENDING_FILES}: too much work is already queued.
     * Refusing early and politely beats accepting and collapsing.
     */
    public static final class TooManyPending extends UploadRefusedException {
        public TooManyPending() {
            super("Too many files are waiting for analysis");
        }
    }

    /**
     * {@code 429 TOO_MANY_CONCURRENT_UPLOADS}: this node is already receiving as
     * many files as it accepts at once. Another node — or this one, a moment
     * later — takes it.
     */
    public static final class TooManyConcurrentUploads extends UploadRefusedException {
        public TooManyConcurrentUploads() {
            super("This node is already receiving as many files as it accepts at once");
        }
    }

    /** {@code 409 IDEMPOTENCY_REQUEST_IN_PROGRESS}: the first attempt is still streaming. */
    public static final class InProgress extends UploadRefusedException {
        public InProgress() {
            super("A request with this idempotency key is still being processed");
        }
    }

    /** {@code 422 IDEMPOTENCY_KEY_REUSED}: same key, different request — a client bug. */
    public static final class KeyReused extends UploadRefusedException {
        public KeyReused() {
            super("This idempotency key was already used for a different request");
        }
    }
}
