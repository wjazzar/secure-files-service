package com.praxedo.securefiles.infrastructure.web.file.dto;

import java.time.Instant;

import com.praxedo.securefiles.domain.file.model.ScanResult;

/**
 * The antivirus verdict as published — the contract's {@code ScanVerdict}.
 *
 * <p>{@code signatureVersion} is exposed on purpose: "clean according to which
 * signature database, and when?" is the first question asked after an incident,
 * and the only cheap moment to answer it is while the answer is still recorded.
 */
public record ScanVerdictResponse(
        ScanResult result,
        String threatName,
        String engine,
        String engineVersion,
        String signatureVersion,
        Instant scannedAt,
        long durationMs) {
}
