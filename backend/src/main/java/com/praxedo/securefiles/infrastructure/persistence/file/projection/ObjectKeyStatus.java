package com.praxedo.securefiles.infrastructure.persistence.file.projection;

import com.praxedo.securefiles.domain.file.model.FileStatus;

/**
 * One row of the orphan-sweep lookup: which state the file stored under a key
 * is in. Public only because HQL instantiates it by name.
 */
public record ObjectKeyStatus(String objectKey, FileStatus status) {
}
