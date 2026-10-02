package com.praxedo.securefiles.application.file.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The orderings the listing accepts — the whole list of them.
 *
 * <p><strong>This enum is the allow-list.</strong> A sort arrives as a string
 * written by a client ({@code uploadedAt,desc}); it is turned into one of these
 * constants or rejected, and nothing else ever reaches the query. No caller can
 * therefore contribute a fragment of the ordering, which is the only way to be
 * sure none is ever concatenated into SQL.
 *
 * <p>The field names are the contract's names, not the column names: the
 * persistence adapter owns the translation, so an entity attribute renamed
 * tomorrow does not rename an API parameter.
 *
 * <p>Every ordering is completed by the identifier in the same direction. Two
 * files uploaded in the same millisecond would otherwise have no defined order,
 * and page 2 could repeat — or skip — a row of page 1.
 */
public enum FileSort {

    UPLOADED_AT_DESC("uploadedAt", Direction.DESC),
    UPLOADED_AT_ASC("uploadedAt", Direction.ASC),
    FILENAME_ASC("filename", Direction.ASC),
    FILENAME_DESC("filename", Direction.DESC),
    SIZE_BYTES_DESC("sizeBytes", Direction.DESC),
    SIZE_BYTES_ASC("sizeBytes", Direction.ASC);

    /** Sort direction, declared here so the application layer owns no framework type. */
    public enum Direction { ASC, DESC }

    /** What the contract applies when the caller says nothing. */
    public static final FileSort DEFAULT = UPLOADED_AT_DESC;

    private final String field;
    private final Direction direction;

    FileSort(String field, Direction direction) {
        this.field = field;
        this.direction = direction;
    }

    /** The contract's field name, e.g. {@code filename}. */
    public String field() {
        return field;
    }

    public Direction direction() {
        return direction;
    }

    /** The {@code field,direction} form the API speaks. */
    public String wireValue() {
        return field + "," + direction.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the matching ordering, or empty when the caller asked for
     *         something that is not on the list — which the caller answers with
     *         {@code 400 INVALID_PARAMETER}
     */
    public static Optional<FileSort> parse(String raw) {
        Objects.requireNonNull(raw, "sort");
        String candidate = raw.strip();
        return Arrays.stream(values())
                .filter(sort -> sort.wireValue().equalsIgnoreCase(candidate))
                .findFirst();
    }

    /** The accepted values, so a rejected caller is told what it may ask for. */
    public static List<String> accepted() {
        return Arrays.stream(values()).map(FileSort::wireValue).toList();
    }
}
