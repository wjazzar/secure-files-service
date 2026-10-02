package com.praxedo.securefiles.infrastructure.web.file.mapper;

import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.infrastructure.web.file.exception.UnknownFileException;

/** The {@code {fileId}} of a path, read the same way by every controller. */
public final class FileIdMapper {

    private FileIdMapper() {
    }

    /**
     * A segment that is not an identifier names no file: {@code 404}, as for an
     * unknown one — a {@code 400} would tell a caller which guesses are well formed.
     */
    public static FileId fromPath(String raw) {
        try {
            return FileId.of(raw);
        } catch (IllegalArgumentException notAnIdentifier) {
            throw new UnknownFileException();
        }
    }
}
