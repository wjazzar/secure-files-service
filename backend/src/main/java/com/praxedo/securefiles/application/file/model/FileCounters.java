package com.praxedo.securefiles.application.file.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.praxedo.securefiles.domain.file.model.PublicStatus;

/**
 * How many files sit in each public status — the counters behind the status
 * filter of the interface.
 *
 * <p><strong>Every status is always present, at zero when empty.</strong> A
 * missing key and a zero mean the same thing to a human and different things to
 * a program: the interface would have to decide whether an absent counter means
 * "none" or "not computed". The contract requires the six, and this type is
 * where that promise is kept — before any HTTP concern.
 */
public record FileCounters(long total, Map<PublicStatus, Long> byStatus) {

    public FileCounters {
        Objects.requireNonNull(byStatus, "counters");
        Map<PublicStatus, Long> complete = new EnumMap<>(PublicStatus.class);
        for (PublicStatus published : PublicStatus.values()) {
            complete.put(published, 0L);
        }
        complete.putAll(byStatus);
        byStatus = Collections.unmodifiableMap(complete);
    }

    /** The total is derived, never supplied: it cannot disagree with the counters. */
    public static FileCounters of(Map<PublicStatus, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new FileCounters(total, counts);
    }
}
