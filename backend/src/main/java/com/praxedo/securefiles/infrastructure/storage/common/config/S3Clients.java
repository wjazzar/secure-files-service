package com.praxedo.securefiles.infrastructure.storage.common.config;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import com.praxedo.securefiles.infrastructure.storage.common.support.SingleUseContent;

/**
 * Builds the S3 clients — one per identity — with the settings measurement
 * proved necessary.
 *
 * <p><strong>One setting here is not tuning, it is correctness.</strong> Three
 * configurations were measured against SeaweedFS (see {@code docs/prompts/B-005}):
 * <ol>
 *   <li><em>SDK defaults</em> — a 1 MiB upload is stored as 1 049 503 bytes: the
 *       trailing checksum of the aws-chunked encoding ends up <strong>inside</strong>
 *       the object, and the {@code PUT} reports success. Silent corruption;</li>
 *   <li><em>chunked encoding disabled</em> — exact bytes, but over plain HTTP the
 *       SDK must then sign a hash of the whole payload before sending it, so it
 *       reads the body <strong>twice</strong>. A test body regenerated on demand
 *       hid it; a real upload, read once from a socket, cannot be replayed —
 *       {@link SingleUseContent} caught it at the first test;</li>
 *   <li><em>chunked encoding kept, checksums only when required</em> — exact
 *       bytes, signed chunk by chunk as they flow: <strong>one pass</strong>.
 *       This is the configuration below.</li>
 * </ol>
 * The integration tests re-check both properties on every build: the stored
 * digest equals the sent one, and the body is read exactly once.
 *
 * <p>Even if a storage corrupted an object anyway, the service would not serve
 * it: the worker hashes what it analyses, and the domain refuses a verdict
 * about bytes whose digest differs from the upload's.
 *
 * <p><strong>Retries are decided per identity, never left to the SDK
 * default.</strong> The default retried every call, streamed writes included:
 * under the saturation campaign ({@code docs/capacity-planning}) a promotion
 * copy refused with {@code 503} was attempted again, asked for its body a
 * second time, and died on {@link SingleUseContent}'s refusal — a failure no
 * port promises, so its file waited out the promotion lease instead of going
 * back to the queue. See {@link Retries}.
 */
public final class S3Clients {

    /** Who retries a call the storage failed. */
    public enum Retries {

        /**
         * Each call is attempted once; the layer above retries. For the
         * identities that stream a body read once from a socket — the SDK could
         * not replay it. The uploading client retries with its idempotency key,
         * the worker's queue with its persisted back-off: a second, in-memory
         * policy underneath would only multiply the load on a storage that is
         * already refusing it.
         */
        BY_CALLER,

        /**
         * The SDK retries, in its standard mode: three attempts, back-off with
         * jitter. For reads only — reading again replays nothing, and nothing
         * above the download path would retry within the request.
         */
        BY_SDK
    }

    private S3Clients() {
    }

    public static S3Client forIdentity(StorageProperties properties, StorageProperties.Credentials credentials,
                                       Retries retries) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey())))
                // Path-style addressing: required by SeaweedFS, harmless on AWS.
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(override -> {
                    switch (retries) {
                        case BY_CALLER -> override.retryStrategy(AwsRetryStrategy.doNotRetry());
                        case BY_SDK -> override.retryStrategy(RetryMode.STANDARD);
                    }
                })
                // Rule B-7: connection AND read timeouts, both written down.
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.readTimeout()))
                .build();
    }
}
