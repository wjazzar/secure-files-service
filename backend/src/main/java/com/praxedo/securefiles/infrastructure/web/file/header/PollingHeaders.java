package com.praxedo.securefiles.infrastructure.web.file.header;

import java.time.Duration;
import java.util.Objects;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.infrastructure.web.common.config.PollingProperties;

/**
 * The {@code Retry-After} a client receives while its file is still moving —
 * one rule for the upload's answer and for the detail's, so the two cannot
 * drift apart.
 *
 * <p><strong>Sized to the file.</strong> A 1 MB file is concluded in about a
 * second, a 500 MB one in tens of seconds: the analysis runs at some 20 MB/s
 * and the promotion copies the bytes once more (capacity campaigns). Asking
 * every 2 s whatever the size made a third-party system poll a large file
 * about twenty times, and each poll, even answered {@code 304}, still reads
 * the row from the database — the {@code ETag} is its version — on the API's
 * small connection pool, after checking the caller's token.
 *
 * <p>With the default settings — 2 s, plus 0.05 s per MiB, at most 30 s — a
 * large file is looked at about twice while it is processed:
 *
 * <pre>
 *     1 MB  →  2 s        50 MB  →  4 s        500 MB  →  26 s
 * </pre>
 *
 * <p>The size is not the whole wait: under load, the queue is. It does not
 * make the hint worse than a fixed one — the floor is the old fixed value, so
 * no client is ever asked to poll more often than before — and the ceiling
 * bounds how late a client can learn a verdict. The queue depth is not used on
 * purpose: one node does not know how many workers the cluster runs.
 *
 * <p>The interface does not read this hint: it polls the list, one request for
 * every visible file, at its own pace. It is for the clients of the API.
 */
public final class PollingHeaders {

    private static final long MILLIS_PER_SECOND = 1_000;

    private final PollingProperties polling;
    /** A floor plus time per MiB — the rule leases and transfers already follow. */
    private final LeaseTerms proportional;

    public PollingHeaders(PollingProperties polling) {
        this.polling = Objects.requireNonNull(polling, "polling");
        this.proportional = new LeaseTerms(polling.minimum(), polling.perMebibyte());
    }

    /**
     * Seconds before the next look at a file of this size whose analysis is
     * not finished — rounded to the nearest second, so that a small file keeps
     * the floor instead of being pushed to the next second.
     */
    public String retryAfterSeconds(long sizeBytes) {
        Duration hint = proportional.forSize(Math.max(sizeBytes, 0));
        if (hint.compareTo(polling.maximum()) > 0) {
            hint = polling.maximum();
        }
        long seconds = Math.round((double) hint.toMillis() / MILLIS_PER_SECOND);
        return String.valueOf(Math.max(seconds, polling.minimum().toSeconds()));
    }
}
