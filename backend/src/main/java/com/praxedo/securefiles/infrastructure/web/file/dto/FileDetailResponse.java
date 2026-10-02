package com.praxedo.securefiles.infrastructure.web.file.dto;

import java.time.Instant;
import java.util.UUID;

import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.StatusReason;

/**
 * A file with its full analysis information — the contract's
 * {@code FileDetail}, which is {@code FileSummary} plus five fields.
 *
 * <p>The summary fields are repeated here rather than nested and unwrapped: the
 * contract describes one flat object, and spelling it out is what makes the
 * conformance test able to compare field for field.
 *
 * <p>{@code scan} and {@code statusReason} are required by the contract and
 * nullable: they are serialised as {@code null}, never omitted. A client can
 * then tell "no verdict" from "field I do not know about".
 */
public record FileDetailResponse(
        UUID id,
        String filename,
        long sizeBytes,
        String contentType,
        PublicStatus status,
        boolean downloadable,
        boolean terminal,
        Instant uploadedAt,
        Instant statusChangedAt,
        String sha256,
        int scanAttempts,
        ScanVerdictResponse scan,
        StatusReason statusReason,
        FileLinksResponse links) {
}
