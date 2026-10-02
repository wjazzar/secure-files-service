package com.praxedo.securefiles.domain.file.model;

import java.time.Duration;
import java.time.Instant;

import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.file.valueobject.FileName;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;
import com.praxedo.securefiles.domain.file.valueobject.Sha256;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;

/** Values shared by the domain tests, kept boring on purpose. */
final class StoredFileFixture {

    static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");
    static final Duration LEASE = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 3;

    static final Sha256 CONTENT = Sha256.of("a".repeat(64));
    static final Sha256 OTHER_CONTENT = Sha256.of("b".repeat(64));

    private StoredFileFixture() {
    }

    static StoredFile received() {
        return StoredFile.received(
                FileId.random(), new OwnerId("alice"), FileName.sanitised("rapport.pdf"),
                ContentType.of("application/pdf"), 1_024L, CONTENT, T0);
    }

    static StoredFile scanning() {
        return received().claimedBy(LeaseToken.random(), "worker-1", T0, LEASE);
    }

    static StoredFile promoting() {
        return scanning().scanned(clean(CONTENT), T0.plusSeconds(3));
    }

    static StoredFile available() {
        return promoting().promoted(T0.plusSeconds(5));
    }

    static ScanVerdict clean(Sha256 content) {
        return new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                content, T0.plusSeconds(3), Duration.ofMillis(120));
    }

    static ScanVerdict infected(Sha256 content) {
        return new ScanVerdict(ScanResult.INFECTED, "Eicar-Test-Signature", "ClamAV", "1.4.6", "28098",
                content, T0.plusSeconds(3), Duration.ofMillis(90));
    }

    static ScanVerdict unscannable(Sha256 content, String detail) {
        return new ScanVerdict(ScanResult.UNSCANNABLE, detail, "ClamAV", "1.4.6", "28098",
                content, T0.plusSeconds(3), Duration.ofMillis(75));
    }
}
