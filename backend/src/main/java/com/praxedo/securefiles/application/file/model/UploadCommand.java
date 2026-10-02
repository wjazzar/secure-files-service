package com.praxedo.securefiles.application.file.model;

import java.util.Objects;
import java.util.Optional;

import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Everything an upload is known by <em>before</em> its body is read.
 *
 * <p>The name is already sanitised — a {@link FileName} cannot hold anything
 * else — and the length is the declared one, which the upload then checks
 * against the bytes actually received.
 *
 * @param idempotencyKey optional; when present, a retry with the same key
 *                       returns the same file instead of creating a second one
 */
public record UploadCommand(OwnerId owner, FileName filename, long declaredSize, Optional<String> idempotencyKey) {

    public UploadCommand {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(idempotencyKey, "idempotency key");
    }
}
