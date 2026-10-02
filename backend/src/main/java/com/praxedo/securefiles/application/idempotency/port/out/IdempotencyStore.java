package com.praxedo.securefiles.application.idempotency.port.out;

import java.time.Duration;

import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Remembers which upload an {@code Idempotency-Key} produced, so that a client
 * retrying after a timeout gets the same file instead of a second one.
 *
 * <p><strong>The race is settled by a unique key, not by a read.</strong>
 * {@link #reserve} is an insert that either wins or reports what is already
 * there. A read followed by a write would let two concurrent retries both see
 * "nothing yet" and both upload — the very bug idempotency exists to prevent.
 *
 * <p>A concept of its own, not a detail of the file catalogue: its own table,
 * its own lifecycle — reserved, completed or released, then purged — and its
 * own expiry. Its one link to files is the file a completed key replays.
 */
public interface IdempotencyStore {

    /**
     * Tries to claim a key for a new upload.
     *
     * @param fingerprint what identifies the request before its body is read:
     *                    the same key with a different fingerprint is a client
     *                    bug, answered {@code 422}
     * @param ttl         how long the key is remembered
     */
    Reservation reserve(OwnerId owner, String key, String fingerprint, Duration ttl);

    /** The upload finished: from now on the key replays this file. */
    void complete(OwnerId owner, String key, FileId file);

    /** The upload failed: the key becomes usable again immediately. */
    void release(OwnerId owner, String key);

    /**
     * Forgets expired keys, and reservations abandoned by a node that died
     * mid-upload.
     *
     * @return how many records were removed
     */
    int purge(Duration abandonedAfter);

    /** What {@link #reserve} found. */
    sealed interface Reservation {

        /** The key is ours: proceed with the upload. */
        record Granted() implements Reservation {
        }

        /** Already done: answer with this file. */
        record Replay(FileId file) implements Reservation {
        }

        /** Another request with this key is still streaming. */
        record InProgress() implements Reservation {
        }

        /** The key was used for a different request. */
        record Reused() implements Reservation {
        }
    }
}
