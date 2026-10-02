package com.praxedo.securefiles.infrastructure.storage.file.adapter;

import java.io.InputStream;

import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.infrastructure.storage.common.support.S3Objects;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * The upload path's access to storage, through the {@code ingest} identity:
 * write to the quarantine, delete from it — and nothing else. It cannot read
 * back what it wrote, nor list it.
 */
public final class S3QuarantineWriter implements QuarantineWriter {

    private final S3Client ingest;
    private final String quarantineBucket;

    public S3QuarantineWriter(S3Client ingest, String quarantineBucket) {
        this.ingest = ingest;
        this.quarantineBucket = quarantineBucket;
    }

    @Override
    public void write(ObjectKey key, InputStream content, long sizeBytes) {
        S3Objects.put(ingest, quarantineBucket, key, content, sizeBytes, "quarantine write");
    }

    @Override
    public void discard(ObjectKey key) {
        S3Objects.delete(ingest, quarantineBucket, key, "quarantine discard");
    }
}
