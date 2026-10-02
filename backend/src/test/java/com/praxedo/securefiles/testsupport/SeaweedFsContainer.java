package com.praxedo.securefiles.testsupport;

import java.net.URI;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * The object storage the tests run against: the same SeaweedFS image and the
 * same identity file as {@code docker-compose.yml}.
 *
 * <p>That last point is the reason this is a real container and not a double.
 * The isolation between quarantine and servable area is enforced by the
 * storage's own permissions, so only the real storage, loaded with the real
 * identity file, can prove it.
 */
public final class SeaweedFsContainer {

    /**
     * The same pinned image as {@code docker-compose.yml}. Not 3.97, which the
     * project started on: measured, a read-only identity there cannot read at
     * all — every GET is refused unless the identity also holds Write on the
     * bucket. See {@code docs/prompts/B-005}.
     */
    public static final String IMAGE = "chrislusf/seaweedfs:4.47@sha256:ce9e796f1fe6f06968f4c04bdaf8f678dad9c8acdfef3d244133d71bfa6bf882";

    public static final String QUARANTINE = "quarantine";
    public static final String SERVABLE = "servable";

    @SuppressWarnings("resource")
    public static final GenericContainer<?> INSTANCE = new GenericContainer<>(IMAGE)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/seaweedfs/s3-identities.json"),
                    "/etc/seaweedfs/s3-identities.json")
            .withCommand("server", "-dir=/data", "-master.volumeSizeLimitMB=2048", "-volume.max=20",
                    "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3-identities.json")
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/cluster/healthz").forPort(9333).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        INSTANCE.start();
        createBuckets();
    }

    private SeaweedFsContainer() {
    }

    public static String endpoint() {
        return "http://" + INSTANCE.getHost() + ":" + INSTANCE.getMappedPort(8333);
    }

    /** A plain client for one identity — for tests that probe the storage directly. */
    public static S3Client client(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    /**
     * SeaweedFS reports healthy slightly before its S3 gateway accepts writes,
     * so bucket creation is retried for a few seconds.
     */
    private static void createBuckets() {
        try (S3Client admin = client("praxedo-admin", "praxedo-admin-secret")) {
            for (String bucket : new String[] {QUARANTINE, SERVABLE}) {
                for (int attempt = 1; ; attempt++) {
                    try {
                        admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                        break;
                    } catch (BucketAlreadyOwnedByYouException alreadyThere) {
                        break;
                    } catch (RuntimeException notReadyYet) {
                        if (attempt == 40) {
                            throw notReadyYet;
                        }
                        pause();
                    }
                }
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
