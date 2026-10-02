package com.praxedo.securefiles.infrastructure.storage.file.adapter;

import com.praxedo.securefiles.application.file.exception.RangeNotSatisfiableException;
import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.model.ContentStream;
import com.praxedo.securefiles.application.file.port.out.ServableReader;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;
import com.praxedo.securefiles.infrastructure.storage.common.support.S3Failures;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * The download path's access to storage, through the {@code delivery}
 * identity: read-only, servable area only. The storage refuses this identity
 * on the quarantine — and that refusal is tested on every build.
 *
 * <p>A {@code HEAD} precedes each read. It costs one round trip and buys two
 * things: an unsatisfiable range is refused with the object's real size, and a
 * missing object is reported before any response header is written.
 */
public final class S3ServableReader implements ServableReader {

    private final S3Client delivery;
    private final String servableBucket;

    public S3ServableReader(S3Client delivery, String servableBucket) {
        this.delivery = delivery;
        this.servableBucket = servableBucket;
    }

    @Override
    public ContentStream open(ObjectKey key, ByteRange range) {
        try {
            long total = delivery.headObject(
                    HeadObjectRequest.builder().bucket(servableBucket).key(key.value()).build()).contentLength();

            if (range.firstByte() >= total) {
                throw new RangeNotSatisfiableException(total);
            }
            long last = Math.min(range.lastByte().orElse(total - 1), total - 1);
            long length = last - range.firstByte() + 1;

            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(servableBucket).key(key.value());
            if (!range.isWholeObject()) {
                request.range("bytes=" + range.firstByte() + "-" + last);
            }
            ResponseInputStream<GetObjectResponse> content = delivery.getObject(request.build());
            return new ContentStream(content, length, total, range.firstByte());
        } catch (SdkException failure) {
            throw S3Failures.translate(failure, "servable read", key);
        }
    }
}
