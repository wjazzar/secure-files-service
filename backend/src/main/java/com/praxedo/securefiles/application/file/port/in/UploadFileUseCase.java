package com.praxedo.securefiles.application.file.port.in;

import java.io.InputStream;

import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.application.file.model.UploadCommand;
import com.praxedo.securefiles.domain.file.model.StoredFile;

/**
 * Deposit a file: its content is streamed to the quarantine, and it waits for
 * its analysis.
 *
 * <p>Called by the web adapter for {@code POST /api/v1/files}.
 */
public interface UploadFileUseCase {

    /**
     * @param body the raw content, read once, never held in memory
     * @return the stored file — or, for a replayed idempotency key, the file the
     *         first request stored
     * @throws UploadRefusedException for every refusal the contract describes
     */
    StoredFile upload(UploadCommand command, InputStream body);
}
