package com.praxedo.securefiles.infrastructure.web.file.mapper;

import com.praxedo.securefiles.application.file.model.FileCounters;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileDetailResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileLinksResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilePageResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileSummaryResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilesSummaryResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.ScanVerdictResponse;

/**
 * Turns domain objects into the contract's representations.
 *
 * <p>Written by hand, with no mapping library. On this volume the gain would
 * not pay for the opacity, and this is the one place where a field name becomes
 * part of a published contract — it deserves to be readable.
 */
public final class FileResponseMapper {

    /** Relative on purpose: the service does not guess its own public address. */
    private static final String FILE_PATH = "/api/v1/files/";

    private FileResponseMapper() {
    }

    public static FileSummaryResponse summary(StoredFile file) {
        return new FileSummaryResponse(
                file.id().value(),
                file.filename().value(),
                file.sizeBytes(),
                file.contentType().value(),
                file.publicStatus(),
                file.isDownloadable(),
                file.isTerminal(),
                file.uploadedAt(),
                file.statusChangedAt());
    }

    public static FileDetailResponse detail(StoredFile file) {
        return new FileDetailResponse(
                file.id().value(),
                file.filename().value(),
                file.sizeBytes(),
                file.contentType().value(),
                file.publicStatus(),
                file.isDownloadable(),
                file.isTerminal(),
                file.uploadedAt(),
                file.statusChangedAt(),
                file.sha256().value(),
                file.attempts(),
                publishedVerdict(file),
                file.reason().orElse(null),
                links(file));
    }

    public static FilePageResponse page(PageResult<StoredFile> result) {
        return new FilePageResponse(
                result.content().stream().map(FileResponseMapper::summary).toList(),
                new FilePageResponse.PageMetadataResponse(
                        result.size(), result.number(), result.totalElements(), result.totalPages()));
    }

    public static FilesSummaryResponse counters(FileCounters counters) {
        return new FilesSummaryResponse(counters.total(), counters.byStatus());
    }

    /**
     * The verdict is published only for the states it explains.
     *
     * <p>The row keeps every verdict it ever received — that history is worth
     * keeping. But a file whose promotion failed technically still carries the
     * clean verdict of its last analysis, and publishing it next to a
     * {@code PENDING} or {@code FAILED} status would contradict the status. The
     * contract says it plainly: {@code scan} is {@code null} while no analysis
     * has completed, and for {@code FAILED} files.
     */
    private static ScanVerdictResponse publishedVerdict(StoredFile file) {
        return switch (file.status()) {
            case PROMOTING, AVAILABLE, INFECTED, UNSCANNABLE -> file.scan().map(FileResponseMapper::verdict).orElse(null);
            case AWAITING_SCAN, SCANNING, RETRY_WAIT, FAILED_FINAL -> null;
        };
    }

    private static ScanVerdictResponse verdict(ScanVerdict verdict) {
        return new ScanVerdictResponse(
                verdict.result(),
                verdict.threatName(),
                verdict.engine(),
                verdict.engineVersion(),
                verdict.signatureVersion(),
                verdict.scannedAt(),
                verdict.duration().toMillis());
    }

    private static FileLinksResponse links(StoredFile file) {
        String self = FILE_PATH + file.id();
        // The content link appears only for a file that can actually be served:
        // publishing it otherwise would advertise a 409 as if it were a download.
        return new FileLinksResponse(self, file.isDownloadable() ? self + "/content" : null);
    }
}
