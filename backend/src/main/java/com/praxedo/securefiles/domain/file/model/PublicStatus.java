package com.praxedo.securefiles.domain.file.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The status as published by the API — a stable projection of the richer
 * internal state machine.
 *
 * <p>Clients never see {@code RETRY_WAIT} or {@code PROMOTING}. That is the
 * point: the internal machine can gain states (a rescan state, for instance)
 * without breaking a single client.
 *
 * <p>The projection is declared once, in {@link FileStatus}: each internal
 * state names the public status it publishes. {@link #internalStates()} simply
 * reads that declaration backwards, so a new internal state joins the right
 * filter the day it is written — there is no second table to remember to
 * update, and therefore no way for the two to drift apart.
 *
 * @see FileStatus
 * @see <a href="../../../../../../../../../contracts/openapi.yaml">contracts/openapi.yaml</a>
 */
public enum PublicStatus {

    /** Received, waiting for analysis — or waiting for a scheduled retry. */
    PENDING,

    /** Being analysed, or being made available. */
    SCANNING,

    /** Analysed, clean, and downloadable. */
    AVAILABLE,

    /** A threat was detected. */
    INFECTED,

    /** The antivirus could not analyse it. */
    UNSCANNABLE,

    /** Analysis failed after every attempt. */
    FAILED;

    /**
     * The internal states this public status stands for.
     *
     * <p>This is what a status filter has to be translated into before it
     * reaches the database: a client asking for {@code PENDING} means
     * {@code AWAITING_SCAN} <em>and</em> {@code RETRY_WAIT}.
     *
     * @return an immutable set, never empty for a status the machine can reach
     */
    public Set<FileStatus> internalStates() {
        return Projection.BY_PUBLIC_STATUS.get(this);
    }

    /**
     * Built lazily, in a holder, on purpose: {@link FileStatus} names these
     * constants in its own initialiser, so reading {@code FileStatus.values()}
     * from this enum's static initialiser would be reading a class that is
     * still being initialised.
     */
    private static final class Projection {

        private static final Map<PublicStatus, Set<FileStatus>> BY_PUBLIC_STATUS = invert();

        private static Map<PublicStatus, Set<FileStatus>> invert() {
            Map<PublicStatus, Set<FileStatus>> byPublicStatus = new EnumMap<>(PublicStatus.class);
            for (PublicStatus published : PublicStatus.values()) {
                byPublicStatus.put(published, EnumSet.noneOf(FileStatus.class));
            }
            for (FileStatus internal : FileStatus.values()) {
                byPublicStatus.get(internal.publicStatus()).add(internal);
            }
            byPublicStatus.replaceAll((published, states) -> Collections.unmodifiableSet(states));
            return Collections.unmodifiableMap(byPublicStatus);
        }

        private Projection() {
        }
    }
}
