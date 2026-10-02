package com.praxedo.securefiles.infrastructure.web.common.error;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.praxedo.securefiles.application.file.exception.DownloadRefusedException;
import com.praxedo.securefiles.application.file.exception.RangeNotSatisfiableException;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.infrastructure.web.common.exception.InvalidParameterException;
import com.praxedo.securefiles.infrastructure.web.file.exception.UnknownFileException;
import com.praxedo.securefiles.infrastructure.web.file.exception.UploadHeaderException;

/**
 * The one place errors become responses.
 *
 * <p>Every answer is an RFC 9457 problem document
 * ({@code application/problem+json}) carrying the contract's stable
 * {@code code}. Clients branch on that code; the title and the detail are for
 * humans and may be reworded.
 *
 * <p><strong>No detail ever leaks.</strong> A statement, a stack trace, a
 * constraint name or a file system path in {@code detail} would be a gift to
 * whoever is probing the service — so the messages are written here, by hand,
 * and an unforeseen failure says nothing at all beyond its identifier in the
 * logs.
 *
 * <p><strong>What is deliberately not handled:</strong> the framework's own
 * protocol errors — an unsupported method, an unacceptable media type. The
 * contract has no {@code code} for them, and inventing one would be worse than
 * letting the framework answer with the right status and no code. They are not
 * responses the contract describes.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** How long a client is told to wait when a dependency is down. */
    private static final int UNAVAILABLE_RETRY_SECONDS = 5;

    /** How long before retrying an upload the service turned away for load. */
    private static final int ADMISSION_RETRY_SECONDS = 30;

    /** How long before retrying an upload refused because this node was full: places free up in moments. */
    private static final int CONCURRENT_UPLOADS_RETRY_SECONDS = 1;

    /** How long before retrying a request whose twin is still streaming. */
    private static final int IDEMPOTENCY_RETRY_SECONDS = 2;

    /** How long before asking again for a file still being analysed. */
    private static final int NOT_READY_RETRY_SECONDS = 5;

    /** Back-pressure made visible: every upload turned away because the queue is full. */
    private final Counter admissionRejected;

    /** Every upload turned away because this node was already receiving as many as it accepts. */
    private final Counter concurrentUploadsRejected;

    ApiExceptionHandler(MeterRegistry registry) {
        this.admissionRejected = Counter.builder("praxedo.admission.rejected")
                .description("Uploads refused with 429 to protect the service")
                .tag("reason", "too_many_pending_files")
                .register(registry);
        this.concurrentUploadsRejected = Counter.builder("praxedo.admission.rejected")
                .description("Uploads refused with 429 to protect the service")
                .tag("reason", "too_many_concurrent_uploads")
                .register(registry);
    }

    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail invalidParameter(InvalidParameterException rejected) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER,
                "Invalid parameter", rejected.getMessage());
    }

    /**
     * A parameter that is not even of the declared type — {@code ?page=abc}.
     * Without this it would surface as an internal error, which would be both
     * wrong and alarming.
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail malformedParameter(Exception rejected) {
        String parameter = rejected instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ((MissingServletRequestParameterException) rejected).getParameterName();
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PARAMETER,
                "Invalid parameter", "The parameter '" + parameter + "' is missing or malformed.");
    }

    /**
     * Every refusal of an upload. The {@code switch} has no {@code default}:
     * a new kind of refusal does not compile until it has been given its answer.
     */
    @ExceptionHandler(UploadRefusedException.class)
    ResponseEntity<ProblemDetail> uploadRefused(UploadRefusedException refused) {
        return switch (refused) {
            case UploadRefusedException.EmptyFile empty -> answer(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, ErrorCode.EMPTY_FILE, "Empty file",
                            "The file is empty: there is nothing to analyse."));
            case UploadRefusedException.TooLarge tooLarge -> {
                ProblemDetail body = problem(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.FILE_TOO_LARGE,
                        "File too large", "The file exceeds the accepted maximum size.");
                body.setProperty("maxFileSizeBytes", tooLarge.maxSizeBytes());
                yield answer(HttpStatus.CONTENT_TOO_LARGE, body);
            }
            case UploadRefusedException.LengthMismatch mismatch -> answer(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, ErrorCode.CONTENT_LENGTH_MISMATCH, "Length mismatch",
                            "The body did not have the length announced by Content-Length. Nothing was kept."));
            case UploadRefusedException.TooSlow tooSlow -> answer(HttpStatus.REQUEST_TIMEOUT,
                    problem(HttpStatus.REQUEST_TIMEOUT, ErrorCode.UPLOAD_TOO_SLOW, "Upload too slow",
                            "The body did not arrive within the time allowed for its size. Nothing was kept."));
            case UploadRefusedException.TooManyPending busy -> admissionRefused();
            case UploadRefusedException.TooManyConcurrentUploads full -> {
                concurrentUploadsRejected.increment();
                yield retryLater(HttpStatus.TOO_MANY_REQUESTS,
                        problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_CONCURRENT_UPLOADS,
                                "Too many concurrent uploads",
                                "This node is already receiving as many files as it accepts at once. "
                                        + "Please retry in a moment."),
                        CONCURRENT_UPLOADS_RETRY_SECONDS);
            }
            case UploadRefusedException.InProgress inProgress -> retryLater(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Request in progress",
                            "A request with this idempotency key is still being processed."),
                    IDEMPOTENCY_RETRY_SECONDS);
            case UploadRefusedException.KeyReused reused -> answer(HttpStatus.UNPROCESSABLE_CONTENT,
                    problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused",
                            "This idempotency key was already used for a different request."));
        };
    }

    private ResponseEntity<ProblemDetail> admissionRefused() {
        admissionRejected.increment();
        return retryLater(HttpStatus.TOO_MANY_REQUESTS,
                problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_PENDING_FILES, "Too many pending files",
                        "Too many files are waiting for analysis. Please retry later."),
                ADMISSION_RETRY_SECONDS);
    }

    @ExceptionHandler(UploadHeaderException.class)
    ResponseEntity<ProblemDetail> uploadHeader(UploadHeaderException rejected) {
        return switch (rejected) {
            case UploadHeaderException.LengthRequired missing -> answer(HttpStatus.LENGTH_REQUIRED,
                    problem(HttpStatus.LENGTH_REQUIRED, ErrorCode.LENGTH_REQUIRED, "Length required",
                            "Content-Length is required: send the file with its exact size."));
            case UploadHeaderException.InvalidFileName invalid -> answer(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FILE_NAME, "Invalid file name",
                            "X-File-Name must hold the percent-encoded UTF-8 name of the file."));
        };
    }

    /**
     * Every refusal of a download. As for uploads, no {@code default}: a new
     * reason has to be given its answer before the code compiles.
     */
    @ExceptionHandler(DownloadRefusedException.class)
    ResponseEntity<ProblemDetail> downloadRefused(DownloadRefusedException refused) {
        return switch (refused) {
            case DownloadRefusedException.UnknownFile unknown -> answer(HttpStatus.NOT_FOUND,
                    problem(HttpStatus.NOT_FOUND, ErrorCode.FILE_NOT_FOUND, "File not found", "No such file."));
            case DownloadRefusedException.NotServable notServable -> notServable(notServable.status());
            case DownloadRefusedException.ContentMissing missing -> retryLater(HttpStatus.SERVICE_UNAVAILABLE,
                    problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "Service unavailable",
                            "The content cannot be served at the moment. Please retry."),
                    UNAVAILABLE_RETRY_SECONDS);
        };
    }

    /**
     * {@code 409}, never {@code 403}: the caller may see the file, the file is
     * simply not servable. Only a file still in analysis is worth coming back
     * for; the three others are final, and say so by having no
     * {@code Retry-After}.
     */
    private static ResponseEntity<ProblemDetail> notServable(PublicStatus status) {
        ResponseEntity<ProblemDetail> answer = switch (status) {
            case PENDING, SCANNING -> retryLater(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, ErrorCode.FILE_NOT_READY, "File not ready",
                            "The file is still being analysed."),
                    NOT_READY_RETRY_SECONDS);
            case INFECTED -> answer(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, ErrorCode.FILE_INFECTED, "File infected",
                            "A threat was detected in this file. It will never be served."));
            case UNSCANNABLE -> answer(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, ErrorCode.FILE_UNSCANNABLE, "File unscannable",
                            "The antivirus could not analyse this file. It will never be served."));
            case FAILED -> answer(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, ErrorCode.FILE_SCAN_FAILED, "Analysis failed",
                            "The analysis of this file failed after every attempt. It will never be served."));
            // A servable status is never refused: reaching this line is a defect, not an answer.
            case AVAILABLE -> throw new IllegalStateException("An available file was refused for download");
        };
        answer.getBody().setProperty("fileStatus", status.name());
        return answer;
    }

    /**
     * A range starting past the end. The answer says how long the file really
     * is, so that a client resuming a download can correct itself.
     */
    @ExceptionHandler(RangeNotSatisfiableException.class)
    ResponseEntity<ProblemDetail> rangeNotSatisfiable(RangeNotSatisfiableException rejected) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + rejected.totalLength())
                .body(problem(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, ErrorCode.RANGE_NOT_SATISFIABLE,
                        "Range not satisfiable", "The requested range starts past the end of the file."));
    }

    /** The object storage is down or refusing: a dependency failure, retryable. */
    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<ProblemDetail> storageUnavailable(StorageUnavailableException failure) {
        LOG.error("The object storage is unavailable", failure);
        return retryLater(HttpStatus.SERVICE_UNAVAILABLE,
                problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, "Service unavailable",
                        "A dependency is unavailable. Please retry."),
                UNAVAILABLE_RETRY_SECONDS);
    }

    @ExceptionHandler(UnknownFileException.class)
    ProblemDetail unknownFile(UnknownFileException unknown) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.FILE_NOT_FOUND,
                "File not found", "No such file.");
    }

    /**
     * The database is unreachable or refusing work. It is a dependency failure,
     * not a client mistake, so the answer says "come back" rather than "you are
     * wrong". A transaction that cannot even begin — no connection to be had —
     * is the same failure, reported by Spring outside of
     * {@link DataAccessException}.
     */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> dependencyUnavailable(NestedRuntimeException failure) {
        LOG.error("A dependency required to answer this request is unavailable", failure);
        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                "Service unavailable", "A dependency is unavailable. Please retry.");
        body.setProperty("retryAfterSeconds", UNAVAILABLE_RETRY_SECONDS);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(UNAVAILABLE_RETRY_SECONDS))
                .body(body);
    }

    /** The backstop. It logs everything and publishes nothing. */
    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException failure) {
        LOG.error("Unhandled failure while answering a request", failure);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Internal error", "The request could not be processed.");
    }

    private static ResponseEntity<ProblemDetail> answer(HttpStatus status, ProblemDetail body) {
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<ProblemDetail> retryLater(HttpStatus status, ProblemDetail body, int seconds) {
        body.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds)).body(body);
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
