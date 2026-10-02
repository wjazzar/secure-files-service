package com.praxedo.securefiles.infrastructure.storage.file.adapter;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.infrastructure.storage.common.support.S3Failures;
import com.praxedo.securefiles.infrastructure.storage.common.support.S3Objects;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The worker's access to storage, through the {@code worker} identity — the
 * only one that can read the quarantine and write the servable area.
 */
public final class S3WorkerStorage implements WorkerStorage {

    private final S3Client worker;
    private final String quarantineBucket;
    private final String servableBucket;

    public S3WorkerStorage(S3Client worker, String quarantineBucket, String servableBucket) {
        this.worker = worker;
        this.quarantineBucket = quarantineBucket;
        this.servableBucket = servableBucket;
    }

    @Override
    public InputStream openQuarantined(ObjectKey key) {
        try {
            return worker.getObject(GetObjectRequest.builder().bucket(quarantineBucket).key(key.value()).build());
        } catch (SdkException failure) {
            throw S3Failures.translate(failure, "quarantine read", key);
        }
    }

    @Override
    public void writeServable(ObjectKey key, InputStream content, long sizeBytes) {
        S3Objects.put(worker, servableBucket, key, content, sizeBytes, "servable write");
    }

    @Override
    public void deleteQuarantined(ObjectKey key) {
        S3Objects.delete(worker, quarantineBucket, key, "quarantine delete");
    }

    @Override
    public void deleteServable(ObjectKey key) {
        S3Objects.delete(worker, servableBucket, key, "servable delete");
    }

    @Override
    public List<ObjectKey> listQuarantinedBefore(Instant olderThan, ObjectKey after, int limit) {
        List<ObjectKey> found = new ArrayList<>();
        String continuation = null;
        try {
            do {
                // S3 lists in key order; startAfter applies to the first page, the token to the next ones.
                ListObjectsV2Response page = worker.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(quarantineBucket)
                        .startAfter(continuation == null && after != null ? after.value() : null)
                        .continuationToken(continuation)
                        .maxKeys(Math.min(1_000, limit))
                        .build());
                for (S3Object object : page.contents()) {
                    if (object.lastModified().isBefore(olderThan)) {
                        found.add(new ObjectKey(object.key()));
                        if (found.size() >= limit) {
                            return found;
                        }
                    }
                }
                continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (continuation != null);
            return found;
        } catch (SdkException failure) {
            throw S3Failures.translate(failure, "quarantine listing", new ObjectKey(quarantineBucket));
        }
    }
}
