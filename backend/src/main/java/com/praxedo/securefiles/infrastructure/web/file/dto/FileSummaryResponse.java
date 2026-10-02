package com.praxedo.securefiles.infrastructure.web.file.dto;

import java.time.Instant;
import java.util.UUID;

import com.praxedo.securefiles.domain.file.model.PublicStatus;

/**
 * A file as shown in a list — the contract's {@code FileSummary}.
 *
 * <p>{@code downloadable} and {@code terminal} are computed by the server and
 * are the only fields a client may branch on. They are published as data rather
 * than left to be derived from {@code status} so that adding an internal state
 * tomorrow cannot turn a correct client into a wrong one.
 */
public record FileSummaryResponse(
        UUID id,
        String filename,
        long sizeBytes,
        String contentType,
        PublicStatus status,
        boolean downloadable,
        boolean terminal,
        Instant uploadedAt,
        Instant statusChangedAt) {
}
