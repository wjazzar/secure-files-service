package com.praxedo.securefiles.infrastructure.web.common.error;

/**
 * The stable, machine-readable codes the API puts in every problem document.
 *
 * <p>Clients branch on these, never on {@code title} or {@code detail} — which
 * is why the wording of those two may change and these may not.
 *
 * <p>Only the codes the service can actually produce are listed, so that this
 * enum keeps saying something true about what the service does.
 * {@code ContractConformanceTest} checks that every constant here is declared
 * in the contract's {@code ErrorCode} enumeration.
 */
public enum ErrorCode {

    /** A query parameter or header the API cannot honour: unknown sort, malformed page, bad key. */
    INVALID_PARAMETER,

    /** {@code X-File-Name} is missing, does not decode, or holds nothing usable once sanitised. */
    INVALID_FILE_NAME,

    /** A declared length of zero: there is nothing to analyse, so nothing to store. */
    EMPTY_FILE,

    /** No {@code Content-Length}: neither the size limit nor the length check could apply. */
    LENGTH_REQUIRED,

    /** The body did not have the length it announced. Nothing is kept. */
    CONTENT_LENGTH_MISMATCH,

    /** Larger than the accepted maximum, refused before the body is read. */
    FILE_TOO_LARGE,

    /** The body was still arriving when its transfer deadline passed. Nothing is kept. */
    UPLOAD_TOO_SLOW,

    /** Another request with the same {@code Idempotency-Key} is still streaming. */
    IDEMPOTENCY_REQUEST_IN_PROGRESS,

    /** The {@code Idempotency-Key} was already used for a different request. */
    IDEMPOTENCY_KEY_REUSED,

    /** Too much work already queued: admission refused to protect the service. */
    TOO_MANY_PENDING_FILES,

    /** This node already receives as many files as it accepts at once: admission refused before the body. */
    TOO_MANY_CONCURRENT_UPLOADS,

    /** No session cookie nor access token, or one that failed a check. Which check is not said. */
    UNAUTHENTICATED,

    /**
     * A write authenticated by the session cookie that does not echo
     * the CSRF token. The only {@code 403} of the API — there are no roles.
     */
    CSRF_TOKEN_INVALID,

    /**
     * Unknown file — and also a file belonging to someone else, so that the API
     * never reveals its existence.
     */
    FILE_NOT_FOUND,

    /** Analysis not finished yet: retryable, with {@code Retry-After}. */
    FILE_NOT_READY,

    /** The antivirus found a threat. Final. */
    FILE_INFECTED,

    /** The antivirus could not analyse the file — too deep an archive, encrypted. Final. */
    FILE_UNSCANNABLE,

    /** The analysis failed after every attempt. Final. */
    FILE_SCAN_FAILED,

    /** The requested byte range starts past the end of the file. */
    RANGE_NOT_SATISFIABLE,

    /** A dependency this operation needs is unavailable. Retryable. */
    SERVICE_UNAVAILABLE,

    /** Anything unforeseen. The client learns nothing about what went wrong. */
    INTERNAL_ERROR
}
