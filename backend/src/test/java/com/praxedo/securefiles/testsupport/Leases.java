package com.praxedo.securefiles.testsupport;

import java.time.Duration;

import com.praxedo.securefiles.application.file.model.LeaseTerms;

/** Lease terms for tests that do not care about sizes. */
public final class Leases {

    private Leases() {
    }

    /** The same lease whatever the size. */
    public static LeaseTerms fixed(Duration lease) {
        return new LeaseTerms(lease, Duration.ZERO);
    }
}
