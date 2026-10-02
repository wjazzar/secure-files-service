package com.praxedo.securefiles.infrastructure.storage.common.support;

import java.io.InputStream;

import com.praxedo.securefiles.domain.file.valueobject.ContentType;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The object writes the storage adapters share.
 *
 * <p>Each adapter passes <strong>its own</strong> client: the identity — and
 * therefore what the storage allows — stays with the adapter. This class holds
 * no credentials; it only keeps the way an object is written in one place.
 */
public final class S3Objects {

    /** Never the type the client declared: stored objects are opaque. */
    private static final String OPAQUE = ContentType.OCTET_STREAM.value();

    private S3Objects() {
    }

    /** Streams the content to the storage, read exactly once and never held. */
    public static void put(S3Client client, String bucket, ObjectKey key, InputStream content, long sizeBytes,
                           String operation) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key.value())
                            .contentLength(sizeBytes)
                            .contentType(OPAQUE)
                            .build(),
                    RequestBody.fromContentProvider(new SingleUseContent(content), sizeBytes, OPAQUE));
        } catch (SdkException failure) {
            throw S3Failures.translate(failure, operation, key);
        }
    }

    public static void delete(S3Client client, String bucket, ObjectKey key, String operation) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.value()).build());
        } catch (SdkException failure) {
            throw S3Failures.translate(failure, operation, key);
        }
    }
}
