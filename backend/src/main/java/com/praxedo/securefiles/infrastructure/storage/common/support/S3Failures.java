package com.praxedo.securefiles.infrastructure.storage.common.support;

import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Translates what the SDK throws into what the ports promise.
 *
 * <p>Only a missing object says something about the object. Everything else —
 * a refused connection, a timeout, a {@code 403} from wrongly configured
 * credentials, a {@code 5xx} — says something about the storage, and becomes a
 * retryable {@link StorageUnavailableException}. None of them is ever mistaken
 * for a statement about the content.
 */
public final class S3Failures {

    private static final int NOT_FOUND = 404;

    private S3Failures() {
    }

    public static RuntimeException translate(SdkException failure, String operation, ObjectKey key) {
        if (failure instanceof NoSuchKeyException
                || (failure instanceof S3Exception s3 && s3.statusCode() == NOT_FOUND)) {
            return new ObjectMissingException(key.value());
        }
        return new StorageUnavailableException("Object storage failed during " + operation, failure);
    }
}
