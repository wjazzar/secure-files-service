package com.praxedo.securefiles.application.common.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * A stream that refuses to be read past a deadline — the bound on a whole
 * transfer that socket timeouts do not give.
 *
 * <p>A socket's read timeout measures <em>silence</em>: the longest wait for
 * the next packet. A peer that trickles — a few bytes, a pause just under the
 * timeout, a few more — never trips it, and a 500 MB transfer at that pace
 * never ends. Whatever it holds meanwhile stays held: an upload permit, an
 * analysis slot, a thread. This stream checks the clock before every read and
 * fails once the deadline has passed.
 *
 * <p>The two bounds are complementary. A read already blocked is not
 * interrupted here: a peer gone completely silent is the socket timeout's job.
 * One that never quite goes silent is this stream's.
 *
 * <p>The failure is an {@link IOException}, as for any broken transfer, and
 * whoever reads the stream may wrap it in its own exception; {@link #expired()}
 * is how the owner of the stream tells a deadline from any other failure.
 */
public final class DeadlineInputStream extends FilterInputStream {

    private final Instant deadline;
    private final Clock clock;
    private volatile boolean expired;

    public DeadlineInputStream(InputStream in, Instant deadline, Clock clock) {
        super(Objects.requireNonNull(in, "stream"));
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int read() throws IOException {
        checkDeadline();
        return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkDeadline();
        return super.read(buffer, offset, length);
    }

    @Override
    public long skip(long wanted) throws IOException {
        checkDeadline();
        return super.skip(wanted);
    }

    /** Whether a read was refused because the deadline had passed. */
    public boolean expired() {
        return expired;
    }

    private void checkDeadline() throws IOException {
        if (!clock.instant().isBefore(deadline)) {
            expired = true;
            throw new IOException("The transfer outlasted its deadline of " + deadline);
        }
    }
}
