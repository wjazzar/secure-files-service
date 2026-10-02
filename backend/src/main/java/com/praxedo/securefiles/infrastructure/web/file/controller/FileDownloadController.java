package com.praxedo.securefiles.infrastructure.web.file.controller;

import java.io.IOException;
import java.io.OutputStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.praxedo.securefiles.application.file.model.ContentStream;
import com.praxedo.securefiles.application.file.model.FileDownload;
import com.praxedo.securefiles.application.file.port.in.DownloadFileUseCase;
import com.praxedo.securefiles.infrastructure.web.common.identity.CurrentOwner;
import com.praxedo.securefiles.infrastructure.web.file.header.DownloadHeaders;
import com.praxedo.securefiles.infrastructure.web.file.mapper.FileIdMapper;

/**
 * The one way out of the service, for browsers and third-party systems alike.
 *
 * <p><strong>The caller's identity is the only credential</strong> (ADR-0013).
 * A browser navigates here natively — its download manager streams the file to
 * disk — and its {@code HttpOnly} session cookie goes along by itself; a
 * third-party system sends its bearer token. No signed link, no second secret.
 *
 * <p><strong>The response is written by hand</strong>, straight to the servlet
 * output stream: no message converter gets a chance to buffer the body, or to
 * handle a {@code Range} header on a stream it cannot rewind. Everything that
 * can fail — identity, state, object — fails <em>before</em> the first header
 * is written, so every refusal is a clean problem document.
 *
 * <p>Whatever the file, the answer is always a download, never a page:
 * {@code application/octet-stream}, {@code attachment}, {@code nosniff}. A file
 * the antivirus found clean can still be harmful once <em>interpreted</em> by a
 * browser — an HTML or SVG page carrying script — so it is never interpreted.
 */
@RestController
class FileDownloadController {

    private static final String CONTENT = "/api/v1/files/{fileId}/content";

    private final DownloadFileUseCase downloadsUseCase;
    private final CurrentOwner currentOwner;
    private final Counter servedBytes;

    FileDownloadController(DownloadFileUseCase downloadsUseCase, CurrentOwner currentOwner, MeterRegistry registry) {
        this.downloadsUseCase = downloadsUseCase;
        this.currentOwner = currentOwner;
        this.servedBytes = Counter.builder("praxedo.download.bytes")
                .description("Bytes served on the download path")
                .baseUnit("bytes")
                .register(registry);
    }

    /**
     * Spring MVC answers {@code HEAD} with the {@code GET} handler: a
     * {@code HEAD} would open the object, read it whole and be audited as a
     * download — up to 500 MB read for an answer without a body. The contract
     * offers no {@code HEAD}: it is refused, before any of that.
     */
    @RequestMapping(path = CONTENT, method = RequestMethod.HEAD)
    ResponseEntity<Void> headContent() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET").build();
    }

    @GetMapping(CONTENT)
    void downloadContent(@PathVariable String fileId,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         HttpServletResponse response) throws IOException {
        try (FileDownload download = downloadsUseCase.open(FileIdMapper.fromPath(fileId), currentOwner.resolve(),
                DownloadHeaders.rangeOf(range))) {
            servedBytes.increment(stream(download, response));
        }
    }

    /** @return how many bytes were written */
    private static long stream(FileDownload download, HttpServletResponse response) throws IOException {
        ContentStream content = download.content();
        response.setStatus(content.isPartial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(content.length());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, DownloadHeaders.attachment(download.file().filename().value()));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        // The content never changes once served: its digest is the strongest possible tag.
        response.setHeader(HttpHeaders.ETAG, "\"" + download.file().sha256().value() + "\"");
        if (content.isPartial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + content.firstByte() + "-" + content.lastByte() + "/" + content.totalLength());
        }
        OutputStream out = response.getOutputStream();
        long written = content.content().transferTo(out);
        out.flush();
        return written;
    }

}
