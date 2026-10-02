package com.praxedo.securefiles.application.file.service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import com.praxedo.securefiles.application.common.io.DeadlineInputStream;
import com.praxedo.securefiles.application.common.io.InspectingInputStream;
import com.praxedo.securefiles.application.common.port.out.TransactionRunner;
import com.praxedo.securefiles.application.file.exception.UploadRefusedException;
import com.praxedo.securefiles.application.file.model.UploadCommand;
import com.praxedo.securefiles.application.file.model.UploadLimits;
import com.praxedo.securefiles.application.file.port.in.UploadFileUseCase;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore.Reservation;
import com.praxedo.securefiles.application.idempotency.port.out.IdempotencyStore;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * Receives a file: into the quarantine first, into the catalogue second.
 *
 * <p><strong>The order is the guarantee.</strong>
 * <ol>
 *   <li>stream the object into the quarantine, counting, hashing and sniffing
 *       it on the way;</li>
 *   <li>commit, in one transaction, the file row — which is also its work item
 *       — and the completed idempotency key.</li>
 * </ol>
 * A crash between the two leaves an <em>orphan</em>: an object nobody
 * references, invisible, swept later. The opposite order would leave a
 * <em>broken reference</em>: a file shown as waiting for an analysis that can
 * never happen, because its bytes do not exist. Under partial failure, the
 * invisible orphan is always the better outcome.
 *
 * <p>Nothing here is ever held in memory: the body flows from the socket to
 * the storage through a 64 KiB buffer, whatever its size.
 *
 * <p><strong>Nor for ever.</strong> The body must arrive within a deadline
 * sized to its declared length. A socket timeout only measures silence: a
 * client that trickles its body would otherwise keep its permit on the node,
 * and a handful of them would close the node to everyone else.
 */
public class UploadFileService implements UploadFileUseCase {

    /**
     * The buffer the body flows through. It is also what lets the sniffer peek
     * at the first bytes and put them back — no second read of the body.
     */
    private static final int STREAM_BUFFER = 64 * 1024;

    private final FileCatalog catalog;
    private final QuarantineWriter quarantine;
    private final IdempotencyStore idempotency;
    private final TransactionRunner transactions;
    private final UploadLimits limits;
    private final UploadAdmission admission;
    private final Clock clock;

    public UploadFileService(FileCatalog catalog, QuarantineWriter quarantine, IdempotencyStore idempotency,
                             TransactionRunner transactions, UploadLimits limits, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = new UploadAdmission(catalog, limits, clock);
    }

    /**
     * @return the stored file — or, for a replayed idempotency key, the file
     *         the first attempt produced, in its current state
     * @throws UploadRefusedException for every refusal the contract describes
     */
    @Override
    public StoredFile upload(UploadCommand command, InputStream body) {
        // Checks that need no byte of the body come first: a 500 MB upload
        // refused on its headers costs nothing.
        if (command.declaredSize() <= 0) {
            throw new UploadRefusedException.EmptyFile();
        }
        if (command.declaredSize() > limits.maxSizeBytes()) {
            throw new UploadRefusedException.TooLarge(limits.maxSizeBytes());
        }
        // Still before the body: a place on this node, then room in the queue.
        try (UploadAdmission.UploadPermit admitted = admission.admit()) {
            return admitted(command, body);
        }
    }

    private StoredFile admitted(UploadCommand command, InputStream body) {
        Optional<String> key = command.idempotencyKey();
        if (key.isPresent()) {
            Optional<StoredFile> replayed = reserve(command, key.get());
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        try {
            return store(command, body);
        } catch (RuntimeException failure) {
            // Give the key back: the client's retry must be able to succeed.
            key.ifPresent(k -> idempotency.release(command.owner(), k));
            throw failure;
        }
    }

    private Optional<StoredFile> reserve(UploadCommand command, String key) {
        Reservation reservation = idempotency.reserve(
                command.owner(), key, fingerprint(command), limits.idempotencyTtl());

        return switch (reservation) {
            case Reservation.Granted granted -> Optional.empty();
            case Reservation.Replay replay -> Optional.of(catalog.findById(replay.file()).orElseThrow(
                    () -> new IllegalStateException("An idempotency key points to a file that does not exist")));
            case Reservation.InProgress inProgress -> throw new UploadRefusedException.InProgress();
            case Reservation.Reused reused -> throw new UploadRefusedException.KeyReused();
        };
    }

    private StoredFile store(UploadCommand command, InputStream body) {
        FileId id = FileId.random();
        ObjectKey objectKey = ObjectKey.of(id);

        DeadlineInputStream bounded = new DeadlineInputStream(body,
                clock.instant().plus(limits.transferDeadline().forSize(command.declaredSize())), clock);
        InspectingInputStream inspected = new InspectingInputStream(bounded, command.declaredSize());
        BufferedInputStream buffered = new BufferedInputStream(inspected, STREAM_BUFFER);

        // ── 1. the bytes, into the quarantine ───────────────────────────
        // Sniffing and writing sit under the same guard: a client that dies
        // during the first 512 bytes has sent a truncated body exactly like one
        // that dies at 400 MB, and must get exactly the same answer.
        ContentType detected;
        try {
            detected = ContentSniffer.detect(buffered);
            quarantine.write(objectKey, buffered, command.declaredSize());
        } catch (RuntimeException failure) {
            if (bounded.expired()) {
                discardQuietly(objectKey);
                throw new UploadRefusedException.TooSlow();
            }
            if (bodyWasTheProblem(inspected, command)) {
                discardQuietly(objectKey);
                throw new UploadRefusedException.LengthMismatch();
            }
            throw failure;
        }
        if (bodyWasTheProblem(inspected, command)) {
            discardQuietly(objectKey);
            throw new UploadRefusedException.LengthMismatch();
        }

        // ── 2. the row and the key, together ────────────────────────────
        StoredFile received = StoredFile.received(id, command.owner(), command.filename(), detected,
                command.declaredSize(), inspected.digest(), clock.instant());
        try {
            return transactions.inTransaction(() -> {
                StoredFile saved = catalog.insert(received);
                command.idempotencyKey().ifPresent(k -> idempotency.complete(command.owner(), k, id));
                return saved;
            });
        } catch (RuntimeException failure) {
            // The object exists but nothing references it. Removing it now is a
            // courtesy; the orphan sweep would remove it anyway.
            discardQuietly(objectKey);
            throw failure;
        }
    }

    /**
     * Whether a failed or suspicious write is the client's doing: a body that
     * ended early, or tried to run past its declared length.
     */
    private static boolean bodyWasTheProblem(InspectingInputStream inspected, UploadCommand command) {
        return inspected.exceeded() || inspected.count() != command.declaredSize();
    }

    private void discardQuietly(ObjectKey objectKey) {
        try {
            quarantine.discard(objectKey);
        } catch (RuntimeException ignored) {
            // The sweep will find it.
        }
    }

    /**
     * What identifies a request before its body is read: its name and its
     * length. A retry with the same key must carry both unchanged; a different
     * content of the same name and length cannot be told apart without reading
     * the whole body — an accepted limit, documented.
     */
    static String fingerprint(UploadCommand command) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String identity = command.filename().value() + '\n' + command.declaredSize();
            return HexFormat.of().formatHex(sha256.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
