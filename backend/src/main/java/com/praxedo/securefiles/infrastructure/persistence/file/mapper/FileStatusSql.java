package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.praxedo.securefiles.domain.file.model.FileStatus;

/**
 * The sets of states the hand-written statements filter on, derived from the
 * automaton — never a second table typed by hand that could drift from it.
 *
 * <p>Written into the SQL as literals, not bound as parameters: PostgreSQL uses
 * a partial index only when it can prove, at planning time, that the query's
 * condition implies the index's — and it cannot with a parameter. The partial
 * indexes of {@code V3__indexes.sql} are written on these same sets; a test
 * holds the two together.
 */
public final class FileStatusSql {

    /** What the claim may pick up: {@code 'AWAITING_SCAN', 'RETRY_WAIT'}. */
    public static final String CLAIMABLE = literals(FileStatus::isClaimable);

    /** The states in which a worker holds a lease: {@code 'SCANNING', 'PROMOTING'}. */
    public static final String LEASED = literals(FileStatus::holdsLease);

    /** Work still ahead: every state that is not terminal. */
    public static final String PENDING = literals(status -> !status.isTerminal());

    private FileStatusSql() {
    }

    private static String literals(Predicate<FileStatus> selected) {
        return Arrays.stream(FileStatus.values())
                .filter(selected)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
