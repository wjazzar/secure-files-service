package com.praxedo.securefiles.infrastructure.web.file.dto;

import java.util.Map;

import com.praxedo.securefiles.domain.file.model.PublicStatus;

/**
 * Counters per status — the contract's {@code FilesSummary}.
 *
 * <p>The map is an {@code EnumMap} built by the application layer, so the six
 * published statuses are always there, at zero when empty, and always in the
 * same order.
 */
public record FilesSummaryResponse(long total, Map<PublicStatus, Long> byStatus) {
}
