package com.praxedo.securefiles.application.file.port.out;

import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Who took what, and when — the part of the audit trail no state change
 * records, since serving a file changes nothing about it.
 *
 * <p>Written <em>before</em> the first byte is sent: a download that could not
 * be recorded is not served. The database it writes to is the one the
 * download has just read the file's state from, so this adds no new way for a
 * download to fail.
 */
public interface DownloadAudit {

    void served(StoredFile file, OwnerId owner, ByteRange range);
}
