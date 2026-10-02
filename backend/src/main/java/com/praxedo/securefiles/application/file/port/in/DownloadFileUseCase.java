package com.praxedo.securefiles.application.file.port.in;

import com.praxedo.securefiles.application.file.exception.DownloadRefusedException;
import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.model.FileDownload;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Take a cleared file out — and nothing else. The file's state is read again
 * at every download: nothing said earlier is a permission.
 *
 * <p>Called by the web adapter: {@code GET /api/v1/files/{id}/content}, the
 * one way out, for browsers and third-party systems alike.
 *
 * @see DownloadRefusedException for every refusal
 */
public interface DownloadFileUseCase {

    /** The caller's identity is the credential; the file must be theirs, and servable now. */
    FileDownload open(FileId id, OwnerId owner, ByteRange range);
}
