package com.praxedo.securefiles.application.file.exception;

import java.util.Objects;

import com.praxedo.securefiles.domain.file.model.PublicStatus;

/**
 * Why nothing was served — one subtype per answer the contract describes.
 *
 * <p>Sealed, so that the web layer translates it with a {@code switch} that has
 * no {@code default}: a new reason does not compile until it has its answer.
 */
public abstract sealed class DownloadRefusedException extends RuntimeException {

    DownloadRefusedException(String message) {
        super(message);
    }

    /**
     * {@code 404}: unknown — or belonging to someone else, which must look
     * exactly the same.
     */
    public static final class UnknownFile extends DownloadRefusedException {
        public UnknownFile() {
            super("No such file");
        }
    }

    /**
     * {@code 409}: the file exists but is not servable — not analysed yet,
     * infected, unscannable, or given up on. A verdict, not a refusal of
     * authorisation: never {@code 403}.
     */
    public static final class NotServable extends DownloadRefusedException {
        private final PublicStatus status;

        public NotServable(PublicStatus status) {
            super("The file is not servable in status " + status);
            this.status = Objects.requireNonNull(status, "status");
        }

        public PublicStatus status() {
            return status;
        }
    }

    /**
     * The row says {@code AVAILABLE} but the servable area has no object. It is
     * an <strong>anomaly</strong> — the promotion writes the object before the
     * row changes — and it is answered as an outage, loudly logged, never
     * papered over.
     */
    public static final class ContentMissing extends DownloadRefusedException {
        public ContentMissing() {
            super("An available file has no servable content");
        }
    }
}
