package com.praxedo.securefiles.domain.file.model;

/**
 * What the antivirus concluded.
 *
 * <p>There is deliberately no {@code FAILED} value here. A technical failure —
 * a timeout, a broken connection, an unreadable answer — is <em>not</em> a
 * verdict: it says nothing about the content. Conflating the two is how a file
 * ends up being treated as inspected when nothing inspected it.
 */
public enum ScanResult {

    /** The engine inspected the content and found nothing. */
    CLEAN,

    /** The engine identified a threat. */
    INFECTED,

    /** The engine could not inspect the content (limits, encryption). */
    UNSCANNABLE
}
