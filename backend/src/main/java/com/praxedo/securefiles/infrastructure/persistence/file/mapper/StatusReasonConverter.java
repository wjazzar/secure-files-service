package com.praxedo.securefiles.infrastructure.persistence.file.mapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.praxedo.securefiles.domain.file.model.StatusReason;

/**
 * The {@code status_reason} column, read the same way by JPA and by the
 * hand-written statements ({@link StoredFileRowMapper}).
 *
 * <p>A reason this version does not know — written by a newer one, or retired
 * since — does not break a read: the reason only explains, the status decides.
 * It reads as no reason at all.
 */
@Converter
public class StatusReasonConverter implements AttributeConverter<StatusReason, String> {

    @Override
    public String convertToDatabaseColumn(StatusReason reason) {
        return reason == null ? null : reason.name();
    }

    @Override
    public StatusReason convertToEntityAttribute(String column) {
        return fromColumn(column);
    }

    static StatusReason fromColumn(String column) {
        if (column == null) {
            return null;
        }
        try {
            return StatusReason.valueOf(column);
        } catch (IllegalArgumentException unknownToThisVersion) {
            return null;
        }
    }
}
