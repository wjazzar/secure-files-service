package com.praxedo.securefiles.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The three storage identities of {@code infra/seaweedfs/s3-identities.json},
 * handed to the service the way a deployment would hand them: as properties.
 * {@code application.yml} holds no credential (audit S-02).
 *
 * <p>Apart from {@link SeaweedFsContainer} on purpose: registering them must
 * not start a storage container for the tests that never touch it.
 */
public final class StorageIdentities {

    private StorageIdentities() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("praxedo.storage.ingest.access-key", () -> "praxedo-ingest");
        registry.add("praxedo.storage.ingest.secret-key", () -> "praxedo-ingest-secret");
        registry.add("praxedo.storage.worker.access-key", () -> "praxedo-worker");
        registry.add("praxedo.storage.worker.secret-key", () -> "praxedo-worker-secret");
        registry.add("praxedo.storage.delivery.access-key", () -> "praxedo-delivery");
        registry.add("praxedo.storage.delivery.secret-key", () -> "praxedo-delivery-secret");
    }
}
