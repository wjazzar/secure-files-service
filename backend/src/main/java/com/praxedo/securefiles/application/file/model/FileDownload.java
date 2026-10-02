package com.praxedo.securefiles.application.file.model;

import com.praxedo.securefiles.domain.file.model.StoredFile;

/** What to stream, and what it is. The caller closes it. */
public record FileDownload(StoredFile file, ContentStream content) implements AutoCloseable {

    @Override
    public void close() {
        content.close();
    }
}
