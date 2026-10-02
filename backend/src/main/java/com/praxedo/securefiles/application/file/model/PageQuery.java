package com.praxedo.securefiles.application.file.model;

import java.util.Objects;

/**
 * One page worth of a request: which page, how big, in what order.
 *
 * <p>Deliberately <strong>not</strong> the framework's own pagination type. The
 * application layer says what it wants; the persistence adapter decides how to
 * ask for it. That is also what keeps this type free of any framework, which
 * the architecture rules verify at build time.
 *
 * <p>Page numbering starts at zero, as in the contract.
 */
public record PageQuery(int number, int size, FileSort sort) {

    /** What the contract applies when the caller says nothing. */
    public static final int DEFAULT_SIZE = 20;

    /**
     * The cap the contract announces. It is a server-side limit, not a
     * suggestion: a caller asking for ten thousand rows gets a hundred.
     */
    public static final int MAX_SIZE = 100;

    /**
     * How many rows a page may skip at most (audit S-19). An {@code OFFSET}
     * reads and throws away every row before the page, plus a count per page:
     * without a bound, a caller looping on deep pages makes the database work
     * for nothing. Ten thousand rows is a hundred full pages — no one reads
     * further by paging; they search or filter.
     */
    public static final int MAX_DEPTH = 10_000;

    public PageQuery {
        Objects.requireNonNull(sort, "sort");
        if (number < 0) {
            throw new IllegalArgumentException("A page number starts at 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("A page holds between 1 and " + MAX_SIZE + " items");
        }
        if (tooDeep(number, size)) {
            throw new IllegalArgumentException("A page starts at most " + MAX_DEPTH + " items deep");
        }
    }

    /** Whether a page would skip more than {@link #MAX_DEPTH} rows. */
    public static boolean tooDeep(int number, int size) {
        return (long) number * size > MAX_DEPTH;
    }

    /** Applies the cap, so the bound lives in one place rather than at every caller. */
    public static PageQuery of(int number, int size, FileSort sort) {
        return new PageQuery(number, capped(size), sort);
    }

    /** A size above {@link #MAX_SIZE} is served at {@link #MAX_SIZE}, as the contract says. */
    public static int capped(int size) {
        return Math.min(size, MAX_SIZE);
    }
}
