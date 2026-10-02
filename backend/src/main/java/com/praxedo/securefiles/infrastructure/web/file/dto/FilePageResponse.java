package com.praxedo.securefiles.infrastructure.web.file.dto;

import java.util.List;

/**
 * One page of files — the contract's {@code FilePage}, whose shape is the one
 * Spring Data's {@code PagedModel} serialises, so the interface can use the
 * same table component it would use against any paginated API.
 */
public record FilePageResponse(List<FileSummaryResponse> content, PageMetadataResponse page) {

    /** The contract's {@code PageMetadata}. {@code number} is zero-based. */
    public record PageMetadataResponse(int size, int number, long totalElements, int totalPages) {
    }
}
