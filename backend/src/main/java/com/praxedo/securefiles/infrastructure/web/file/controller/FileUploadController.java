package com.praxedo.securefiles.infrastructure.web.file.controller;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.praxedo.securefiles.application.file.model.UploadCommand;
import com.praxedo.securefiles.application.file.port.in.UploadFileUseCase;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.infrastructure.web.common.exception.InvalidParameterException;
import com.praxedo.securefiles.infrastructure.web.common.identity.CurrentOwner;
import com.praxedo.securefiles.infrastructure.web.common.io.CountingInputStream;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileDetailResponse;
import com.praxedo.securefiles.infrastructure.web.file.exception.UploadHeaderException;
import com.praxedo.securefiles.infrastructure.web.file.header.PollingHeaders;
import com.praxedo.securefiles.infrastructure.web.file.mapper.FileResponseMapper;

/**
 * {@code POST /api/v1/files} — the body is the raw file, streamed.
 *
 * <p><strong>No multipart, and no {@code @RequestBody}.</strong> The handler
 * takes the servlet input stream itself: nothing in the framework gets a chance
 * to buffer the body, and multipart parsing is disabled altogether (rule B-2).
 * A 500 MB upload goes from the socket to the storage through a 64 KiB buffer.
 *
 * <p>The request's {@code Content-Type} is not constrained: whatever a client
 * declares is ignored by design (rule B-5), so there is nothing to enforce, and
 * a third-party system sending {@code application/pdf} is not wrong.
 *
 * <p>Everything that can be refused on the headers is refused before the first
 * byte of the body is read — a 500 MB upload turned down costs nothing.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileUploadController {

    /** The contract's bounds on the two custom headers. */
    private static final int MAX_FILE_NAME_HEADER = 1024;
    private static final int MIN_IDEMPOTENCY_KEY = 8;
    private static final int MAX_IDEMPOTENCY_KEY = 128;

    private final UploadFileUseCase uploadsUseCase;
    private final CurrentOwner currentOwner;
    private final PollingHeaders polling;
    private final Counter receivedBytes;

    FileUploadController(UploadFileUseCase uploadsUseCase, CurrentOwner currentOwner, PollingHeaders polling,
                         MeterRegistry registry) {
        this.uploadsUseCase = uploadsUseCase;
        this.currentOwner = currentOwner;
        this.polling = polling;
        this.receivedBytes = Counter.builder("praxedo.upload.bytes")
                .description("Bytes received on the upload path, accepted or not")
                .baseUnit("bytes")
                .register(registry);
    }

    @PostMapping
    ResponseEntity<FileDetailResponse> upload(
            @RequestHeader(value = "X-File-Name", required = false) String encodedName,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {

        long declaredLength = request.getContentLengthLong();
        if (declaredLength < 0) {
            // A chunked body has no announced length: without one, neither the
            // size limit nor the length check could be applied before reading.
            throw new UploadHeaderException.LengthRequired();
        }

        UploadCommand command = new UploadCommand(
                currentOwner.resolve(), fileName(encodedName), declaredLength, idempotencyKey(idempotencyKey));
        CountingInputStream body = new CountingInputStream(request.getInputStream());
        StoredFile stored;
        try {
            stored = uploadsUseCase.upload(command, body);
        } finally {
            receivedBytes.increment(body.count());
        }

        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/files/" + stored.id()))
                .header(HttpHeaders.RETRY_AFTER, polling.retryAfterSeconds(stored.sizeBytes()))
                .body(FileResponseMapper.detail(stored));
    }

    /**
     * Decodes the percent-encoded UTF-8 name, then sanitises it. A literal
     * {@code +} is a plus sign here, not a space: {@code encodeURIComponent}
     * never produces one, so a raw {@code +} can only mean itself.
     */
    private static FileName fileName(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_FILE_NAME_HEADER) {
            throw new UploadHeaderException.InvalidFileName();
        }
        try {
            String decoded = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
            return FileName.sanitised(decoded);
        } catch (IllegalArgumentException malformed) {
            throw new UploadHeaderException.InvalidFileName();
        }
    }

    /** Printable ASCII only: the key ends up in logs and in an index. */
    private static Optional<String> idempotencyKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        boolean printable = raw.chars().allMatch(character -> character > 0x20 && character < 0x7F);
        if (!printable || raw.length() < MIN_IDEMPOTENCY_KEY || raw.length() > MAX_IDEMPOTENCY_KEY) {
            throw new InvalidParameterException("The header 'Idempotency-Key' must hold between "
                    + MIN_IDEMPOTENCY_KEY + " and " + MAX_IDEMPOTENCY_KEY + " printable characters.");
        }
        return Optional.of(raw);
    }
}
