package com.praxedo.securefiles.infrastructure.storage.common.support;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import software.amazon.awssdk.http.ContentStreamProvider;

/**
 * Hands the SDK a body that can be read exactly once.
 *
 * <p>An upload streams straight from the client socket: there is no second
 * copy to replay. If the SDK ever asked for the content twice — to retry a
 * failed attempt — the honest answer is a failure, not a silently truncated or
 * empty second body. This provider makes that explicit instead of relying on
 * how a generic input-stream body happens to behave.
 *
 * <p>The clients that write through it do not let the SDK retry
 * ({@code S3Clients.Retries.BY_CALLER}), so the refusal below is a tripwire,
 * not a path: {@code StorageRetriesTest} fails if a wiring ever reaches it.
 */
public final class SingleUseContent implements ContentStreamProvider {

    private final InputStream content;
    private final AtomicBoolean handedOut = new AtomicBoolean();

    public SingleUseContent(InputStream content) {
        this.content = content;
    }

    @Override
    public InputStream newStream() {
        if (!handedOut.compareAndSet(false, true)) {
            throw new IllegalStateException("A streamed body cannot be replayed");
        }
        return content;
    }
}
