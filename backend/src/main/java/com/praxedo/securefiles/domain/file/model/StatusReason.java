package com.praxedo.securefiles.domain.file.model;

/**
 * Machine-readable explanation of a non-nominal status, as published by the
 * API. The interface maps each code to a localized sentence; the server never
 * ships prose it would then have to translate.
 */
public enum StatusReason {

    /** A technical failure occurred; another attempt is scheduled. */
    SCAN_RETRY_SCHEDULED,

    /** The content is larger than the antivirus can analyse. */
    EXCEEDS_SCANNER_SIZE_LIMIT,

    /** An encrypted archive cannot be inspected. */
    ENCRYPTED_ARCHIVE,

    /** Recursion, file count or decompressed size exceeded the engine limits. */
    SCANNER_LIMITS_EXCEEDED,

    /**
     * Every attempt failed; the file will not be analysed again. An engine
     * that answers anything but a verdict is a failure like any other: it is
     * retried, and ends here.
     */
    SCAN_ATTEMPTS_EXHAUSTED
}
