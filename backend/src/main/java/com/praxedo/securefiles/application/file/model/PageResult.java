package com.praxedo.securefiles.application.file.model;

import java.util.List;
import java.util.Objects;

/**
 * One page of results, carrying what a paginated table needs to draw itself.
 *
 * <p>The total count is part of the answer because the interface is a table
 * with page numbers, not an infinite scroll. It is also what makes this
 * pagination more expensive than a keyset cursor would be on a very large
 * table — a trade-off taken knowingly, and documented as an improvement track.
 */
public record PageResult<T>(List<T> content, int number, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        if (number < 0 || size < 1 || totalElements < 0) {
            throw new IllegalArgumentException("A page result must describe a page that could exist");
        }
    }

    /** Zero when there is nothing to page through: an empty set is not "page 1 of 1". */
    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }
}
