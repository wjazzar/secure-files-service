package com.praxedo.securefiles.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/**
 * Files in every state, built through the real ports.
 *
 * <p>Nothing here writes a status directly: each file reaches its state by the
 * same transitions production uses. A fixture that inserted an
 * {@code AVAILABLE} row by hand would be testing a situation the service cannot
 * produce — and would quietly stop matching it the day a transition changes.
 *
 * <p><strong>Build order matters.</strong> The queue hands out the next due
 * file, not a chosen one, so a file that must be driven forward has to be the
 * only claimable one at that moment. Create the files that end up claimable —
 * {@link #awaitingScan} — last. The helper refuses loudly rather than silently
 * transitioning the wrong file.
 */
public final class FileFixtures {

    /** Whose files these are, unless a test says otherwise: the user the API tests call as. */
    public static final OwnerId OWNER = TestIdentityProvider.USER;

    private static final LeaseTerms LEASE = Leases.fixed(Duration.ofMinutes(10));
    private static final Duration PROMOTION_LEASE = Duration.ofMinutes(15);
    private static final Duration LONG_RETRY = Duration.ofMinutes(30);
    private static final int MAX_ATTEMPTS = 3;

    private final FileCatalog catalog;
    private final FileWorkQueue queue;

    public FileFixtures(FileCatalog catalog, FileWorkQueue queue) {
        this.catalog = catalog;
        this.queue = queue;
    }

    // ── the eight states ────────────────────────────────────────────────

    public StoredFile awaitingScan(String name) {
        return awaitingScan(name, 2_048L, Instant.now(), OWNER);
    }

    public StoredFile awaitingScan(String name, long sizeBytes, Instant uploadedAt) {
        return awaitingScan(name, sizeBytes, uploadedAt, OWNER);
    }

    public StoredFile awaitingScan(String name, long sizeBytes, Instant uploadedAt, OwnerId owner) {
        return catalog.insert(StoredFile.received(FileId.random(), owner, FileName.sanitised(name),
                ContentType.OCTET_STREAM, sizeBytes, randomDigest(), uploadedAt));
    }

    public StoredFile scanning(String name) {
        return claimed(awaitingScan(name));
    }

    public StoredFile promoting(String name) {
        StoredFile claimed = claimed(awaitingScan(name));
        queue.writeVerdict(claimed.scanned(clean(claimed), Instant.now()), claim(claimed), PROMOTION_LEASE);
        return reload(claimed);
    }

    public StoredFile available(String name) {
        return available(name, 2_048L, Instant.now());
    }

    public StoredFile available(String name, long sizeBytes, Instant uploadedAt) {
        StoredFile claimed = claimed(awaitingScan(name, sizeBytes, uploadedAt));
        queue.writeVerdict(claimed.scanned(clean(claimed), Instant.now()), claim(claimed), PROMOTION_LEASE);
        StoredFile promoting = reload(claimed);
        queue.markAvailable(promoting.promoted(Instant.now()), claim(promoting));
        return reload(claimed);
    }

    public StoredFile infected(String name) {
        StoredFile claimed = claimed(awaitingScan(name));
        queue.writeVerdict(claimed.scanned(infected(claimed), Instant.now()), claim(claimed), PROMOTION_LEASE);
        return reload(claimed);
    }

    public StoredFile unscannable(String name) {
        StoredFile claimed = claimed(awaitingScan(name));
        queue.writeVerdict(claimed.scanned(unscannable(claimed), Instant.now()), claim(claimed), PROMOTION_LEASE);
        return reload(claimed);
    }

    /**
     * A technical failure with attempts left. The retry is scheduled far enough
     * ahead that the file stays out of the way of the next fixture.
     */
    public StoredFile retryWait(String name) {
        StoredFile claimed = claimed(awaitingScan(name));
        queue.writeTechnicalFailure(claimed.technicalFailure(MAX_ATTEMPTS, Instant.now()),
                claim(claimed), LONG_RETRY, "antivirus timeout");
        return reload(claimed);
    }

    /** The same failure, with the attempts spent — which makes it final. */
    public StoredFile failedFinal(String name) {
        StoredFile claimed = claimed(awaitingScan(name));
        queue.writeTechnicalFailure(claimed.technicalFailure(1, Instant.now()),
                claim(claimed), LONG_RETRY, "antivirus timeout");
        return reload(claimed);
    }

    public StoredFile reload(StoredFile file) {
        return catalog.findById(file.id()).orElseThrow();
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private StoredFile claimed(StoredFile expected) {
        StoredFile claimed = queue.claimNextDue("fixture", LeaseToken.random(), LEASE, MAX_ATTEMPTS)
                .orElseThrow(() -> new IllegalStateException("Nothing was claimable: " + expected.filename()));
        if (!claimed.id().equals(expected.id())) {
            throw new IllegalStateException(
                    "The queue handed back another file. Fixtures that stay claimable must be created last.");
        }
        return claimed;
    }

    private static LeaseToken claim(StoredFile leased) {
        return leased.currentLease().orElseThrow().token();
    }

    private static ScanVerdict clean(StoredFile file) {
        return new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                file.sha256(), Instant.now(), Duration.ofMillis(120));
    }

    private static ScanVerdict infected(StoredFile file) {
        return new ScanVerdict(ScanResult.INFECTED, "Eicar-Test-Signature", "ClamAV", "1.4.6", "28098",
                file.sha256(), Instant.now(), Duration.ofMillis(95));
    }

    private static ScanVerdict unscannable(StoredFile file) {
        return new ScanVerdict(ScanResult.UNSCANNABLE, "Heuristics.Encrypted.Zip", "ClamAV", "1.4.6", "28098",
                file.sha256(), Instant.now(), Duration.ofMillis(75));
    }

    private static Sha256 randomDigest() {
        StringBuilder digest = new StringBuilder(64);
        for (int character = 0; character < 64; character++) {
            digest.append("0123456789abcdef".charAt(ThreadLocalRandom.current().nextInt(16)));
        }
        return new Sha256(digest.toString());
    }
}
