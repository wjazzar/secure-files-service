package com.praxedo.securefiles.domain.file.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.praxedo.securefiles.domain.file.exception.IllegalTransitionException;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * A stored file: its metadata, its state, and the only legal ways to move
 * between states.
 *
 * <p><strong>The invariant lives here, and in two other places.</strong> This
 * class refuses to build an {@code AVAILABLE} file without a clean attestation
 * bound to its own digest; the database refuses the same row through total
 * {@code CHECK} predicates; and the object storage refuses to let the delivery
 * credentials read the quarantine at all. Three independent mechanisms, no
 * shared failure mode.
 *
 * <p><strong>Every instance goes through the constructor.</strong> This class
 * knows nothing of how it is stored: the persistence adapter rebuilds it from
 * a JPA entity or from a row, and both paths call the constructor below. The
 * invariant is therefore checked for every file the service ever handles —
 * created, transitioned or read back — and a row that a manual {@code UPDATE}
 * left inconsistent fails loudly on read instead of flowing into a response.
 *
 * <p><strong>Transitions return a new instance, they never mutate.</strong>
 * Every field is final: a state change is a new value, which the work queue
 * persists through one of its conditional statements — rule B-6, enforced by
 * construction rather than by discipline.
 *
 * <p>{@code version} and {@code statusChangedAt} are assigned on write; the
 * values carried by a freshly transitioned instance are those of the state it
 * came from.
 */
public final class StoredFile {

    private final FileId id;
    private final OwnerId owner;
    private final FileName filename;
    private final ContentType contentType;
    private final long sizeBytes;
    private final Sha256 sha256;
    private final StorageArea area;
    private final FileStatus status;
    private final StatusReason statusReason;
    private final ScanVerdict verdict;
    private final int attempts;
    private final Lease lease;
    private final Instant uploadedAt;
    private final Instant statusChangedAt;
    private final long version;

    public StoredFile(
            FileId id,
            OwnerId owner,
            FileName filename,
            ContentType contentType,
            long sizeBytes,
            Sha256 sha256,
            StorageArea area,
            FileStatus status,
            StatusReason statusReason,
            ScanVerdict verdict,
            int attempts,
            Lease lease,
            Instant uploadedAt,
            Instant statusChangedAt,
            long version) {

        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.filename = Objects.requireNonNull(filename, "filename");
        this.contentType = Objects.requireNonNull(contentType, "content type");
        this.sizeBytes = sizeBytes;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.area = Objects.requireNonNull(area, "storage area");
        this.status = Objects.requireNonNull(status, "status");
        this.statusReason = statusReason;
        this.verdict = verdict;
        this.attempts = attempts;
        this.lease = lease;
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploaded at");
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "status changed at");
        this.version = version;

        validate();
    }

    /**
     * A file that has just been written to quarantine and committed.
     *
     * <p>This is the only entry point into the state machine: there is no way
     * to construct a file that is already available.
     */
    public static StoredFile received(
            FileId id,
            OwnerId owner,
            FileName filename,
            ContentType contentType,
            long sizeBytes,
            Sha256 sha256,
            Instant receivedAt) {

        return new StoredFile(id, owner, filename, contentType, sizeBytes, sha256,
                StorageArea.QUARANTINE, FileStatus.AWAITING_SCAN, null, null,
                0, null, receivedAt, receivedAt, 0L);
    }

    /** The invariant, checked by the constructor — so for every instance, however it was obtained. */
    private void validate() {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("An empty file is refused at admission, not stored");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("Attempts cannot be negative");
        }

        // A lease exists if and only if work is in progress.
        if (status.holdsLease() != (lease != null)) {
            throw new IllegalStateException(
                    status.holdsLease()
                            ? "Status " + status + " requires a lease"
                            : "Status " + status + " must not hold a lease");
        }

        // ── The invariant, in three clauses ──────────────────────────────
        if (status == FileStatus.AVAILABLE) {
            if (verdict == null || !verdict.attestsCleanFor(sha256)) {
                throw new IllegalStateException(
                        "AVAILABLE requires a clean verdict attesting this very content");
            }
            if (area != StorageArea.SERVABLE) {
                throw new IllegalStateException("AVAILABLE requires the content to live in the servable area");
            }
        }
        if (area == StorageArea.SERVABLE && status != FileStatus.AVAILABLE) {
            throw new IllegalStateException("Only an available file lives in the servable area");
        }

        // Terminal non-servable states carry the verdict that explains them.
        if (status == FileStatus.INFECTED && resultIsNot(ScanResult.INFECTED)) {
            throw new IllegalStateException("INFECTED requires the verdict that detected the threat");
        }
        if (status == FileStatus.UNSCANNABLE && resultIsNot(ScanResult.UNSCANNABLE)) {
            throw new IllegalStateException("UNSCANNABLE requires the verdict that reported it");
        }
    }

    private boolean resultIsNot(ScanResult expected) {
        return verdict == null || verdict.result() != expected;
    }

    // ── Transitions — each returns a NEW instance, never mutates ────────

    /**
     * A worker takes the work: {@code AWAITING_SCAN | RETRY_WAIT -> SCANNING}.
     *
     * <p>The attempt is counted here, at the claim, and not at the failure. A
     * worker that dies mid-analysis has still consumed one attempt, which is
     * what bounds a poison file.
     *
     * <p>In service, the claim is a single SQL statement — it must pick, lock
     * and lease in one step ({@code JdbcFileWorkQueue}) — so nothing calls this
     * transition there. It stays because it is the automaton's statement of
     * the claim: the in-memory queue of the service tests applies it, and the
     * persistence tests hold the SQL claim to the same result.
     */
    public StoredFile claimedBy(LeaseToken token, String worker, Instant now, Duration leaseDuration) {
        if (!status.isClaimable()) {
            throw new IllegalTransitionException(status, "claim");
        }
        return copy(area, FileStatus.SCANNING, null, verdict,
                attempts + 1, new Lease(token, worker, now.plus(leaseDuration)), now);
    }

    /**
     * The analysis produced a verdict: {@code SCANNING -> PROMOTING | INFECTED
     * | UNSCANNABLE}.
     *
     * <p>A clean verdict does <em>not</em> make the file available: it makes it
     * promotable. The content still has to be copied, verified, and only then
     * declared servable.
     */
    public StoredFile scanned(ScanVerdict newVerdict, Instant now) {
        Objects.requireNonNull(newVerdict, "verdict");
        if (status != FileStatus.SCANNING) {
            throw new IllegalTransitionException(status, "record a verdict");
        }
        if (!newVerdict.scannedContent().equals(sha256)) {
            throw new IllegalArgumentException(
                    "This verdict was produced for different content and cannot apply to this file");
        }

        return switch (newVerdict.result()) {
            case CLEAN -> copy(area, FileStatus.PROMOTING, null, newVerdict, attempts, lease, now);
            case INFECTED -> copy(area, FileStatus.INFECTED, null, newVerdict, attempts, null, now);
            case UNSCANNABLE -> copy(area, FileStatus.UNSCANNABLE, reasonFor(newVerdict), newVerdict,
                    attempts, null, now);
        };
    }

    /**
     * The verified copy reached the servable area: {@code PROMOTING ->
     * AVAILABLE}.
     *
     * <p>The caller must have re-read the content while copying it and checked
     * both its size and its digest. This method re-checks the attestation
     * anyway — the last gate before a file becomes servable is not a good place
     * to trust a caller.
     */
    public StoredFile promoted(Instant now) {
        if (status != FileStatus.PROMOTING) {
            throw new IllegalTransitionException(status, "promote");
        }
        if (verdict == null || !verdict.attestsCleanFor(sha256)) {
            throw new IllegalStateException(
                    "Refusing to promote content without a clean attestation of its digest");
        }
        return copy(StorageArea.SERVABLE, FileStatus.AVAILABLE, null, verdict, attempts, null, now);
    }

    /**
     * A technical failure — timeout, broken connection, unreadable answer, an
     * interrupted promotion: {@code SCANNING | PROMOTING -> RETRY_WAIT |
     * FAILED_FINAL}.
     *
     * <p>Never a verdict. The file is blocked either way, but the distinction
     * is what tells an operator whether to look at the antivirus or at the
     * file.
     */
    public StoredFile technicalFailure(int maxAttempts, Instant now) {
        if (status != FileStatus.SCANNING && status != FileStatus.PROMOTING) {
            throw new IllegalTransitionException(status, "record a technical failure");
        }
        boolean exhausted = attempts >= maxAttempts;
        return copy(area,
                exhausted ? FileStatus.FAILED_FINAL : FileStatus.RETRY_WAIT,
                exhausted ? StatusReason.SCAN_ATTEMPTS_EXHAUSTED : StatusReason.SCAN_RETRY_SCHEDULED,
                verdict, attempts, null, now);
    }

    /**
     * The node is shutting down cleanly and hands the work back.
     *
     * <p>The attempt is <em>given back</em>: nothing was tried, so nothing
     * should be counted. A crash, by contrast, keeps the attempt — the
     * conservative direction, which keeps a poison file bounded.
     */
    public StoredFile releasedOnShutdown(Instant now) {
        if (!status.holdsLease()) {
            throw new IllegalTransitionException(status, "release a lease");
        }
        return copy(area, FileStatus.AWAITING_SCAN, null, verdict, Math.max(0, attempts - 1), null, now);
    }

    private StoredFile copy(StorageArea newArea, FileStatus newStatus, StatusReason reason,
                            ScanVerdict newVerdict, int newAttempts, Lease newLease, Instant changedAt) {
        return new StoredFile(id, owner, filename, contentType, sizeBytes, sha256,
                newArea, newStatus, reason, newVerdict, newAttempts, newLease, uploadedAt, changedAt, version);
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /** The only question the download path is allowed to ask. */
    public boolean isDownloadable() {
        return status.isDownloadable();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public PublicStatus publicStatus() {
        return status.publicStatus();
    }

    public FileId id() {
        return id;
    }

    public OwnerId owner() {
        return owner;
    }

    public FileName filename() {
        return filename;
    }

    public ContentType contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Sha256 sha256() {
        return sha256;
    }

    public StorageArea area() {
        return area;
    }

    public FileStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public long version() {
        return version;
    }

    /** Where the canonical object lives right now. */
    public ObjectKey objectKey() {
        return ObjectKey.of(id);
    }

    public Optional<ScanVerdict> scan() {
        return Optional.ofNullable(verdict);
    }

    public Optional<Lease> currentLease() {
        return Optional.ofNullable(lease);
    }

    public Optional<StatusReason> reason() {
        return Optional.ofNullable(statusReason);
    }

    private static StatusReason reasonFor(ScanVerdict unscannable) {
        String detail = unscannable.threat().orElse("").toLowerCase(Locale.ROOT);
        if (detail.contains("encrypted")) {
            return StatusReason.ENCRYPTED_ARCHIVE;
        }
        if (detail.contains("size") || detail.contains("max")) {
            return StatusReason.EXCEEDS_SCANNER_SIZE_LIMIT;
        }
        return StatusReason.SCANNER_LIMITS_EXCEEDED;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredFile file && id.equals(file.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "StoredFile[" + id + ", " + status + ", " + sizeBytes + " bytes]";
    }
}
