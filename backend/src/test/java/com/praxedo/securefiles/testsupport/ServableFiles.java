package com.praxedo.securefiles.testsupport;

import java.time.Duration;
import java.time.Instant;

import com.praxedo.securefiles.application.file.model.LeaseTerms;
import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.ScanVerdict;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.LeaseToken;

/**
 * Uploaded files made downloadable the way production does it — minus the
 * antivirus, whose verdict is the only thing written here.
 *
 * <p>The verdict is bound to the digest the upload computed, and the promotion
 * is the real {@link FilePromotionService}: the bytes are copied from the
 * quarantine to the servable area and checked against that digest. What the
 * download tests then serve is exactly what a clean scan would have released.
 */
public final class ServableFiles {

    private static final LeaseTerms LEASE = Leases.fixed(Duration.ofMinutes(10));
    private static final int MAX_ATTEMPTS = 5;

    private final FileWorkQueue queue;
    private final FilePromotionService promotion;

    public ServableFiles(FileWorkQueue queue, FilePromotionService promotion) {
        this.queue = queue;
        this.promotion = promotion;
    }

    /** Claims the next due file — the one just uploaded — and releases it as clean. */
    public StoredFile promoteNextDue() {
        StoredFile claimed = queue.claimNextDue("test-worker", LeaseToken.random(), LEASE, MAX_ATTEMPTS)
                .orElseThrow(() -> new IllegalStateException("No uploaded file was waiting"));
        LeaseToken claim = claimed.currentLease().orElseThrow().token();

        StoredFile promoting = claimed.scanned(new ScanVerdict(ScanResult.CLEAN, null, "ClamAV", "1.4.6", "28098",
                claimed.sha256(), Instant.now(), Duration.ofMillis(50)), Instant.now());
        Duration promotionLease = LEASE.forSize(claimed.sizeBytes());
        if (!queue.writeVerdict(promoting, claim, promotionLease)) {
            throw new IllegalStateException("The verdict was not recorded");
        }

        StoredFile available = promotion.promote(promoting, claim, promotionLease);
        if (available.status() != FileStatus.AVAILABLE) {
            throw new IllegalStateException("The promotion did not complete: " + available.status());
        }
        return available;
    }
}
