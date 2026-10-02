package com.praxedo.securefiles.infrastructure.persistence.file.projection;

import com.praxedo.securefiles.domain.file.model.FileStatus;

/**
 * One row of {@code group by status}.
 *
 * <p>It exists so the counting query returns something typed instead of an
 * {@code Object[]} unpacked by index. It is public because HQL instantiates it
 * by name, and it goes no further than this package.
 */
public record StatusCount(FileStatus status, long count) {
}
