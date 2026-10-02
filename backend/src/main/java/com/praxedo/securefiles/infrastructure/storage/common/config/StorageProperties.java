package com.praxedo.securefiles.infrastructure.storage.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the object storage is, and the three identities the service uses on it.
 *
 * <p>Three credential sets, not one with three names: each set is handed to a
 * different component, and the storage itself enforces what each may do. The
 * delivery set has no right at all on the quarantine.
 *
 * @param connectTimeout explicit, as rule B-7 requires for every outgoing call
 * @param readTimeout    sized for a 500 MB object, not for a thumbnail
 */
@ConfigurationProperties("praxedo.storage")
public record StorageProperties(
        URI endpoint,
        String region,
        String quarantineBucket,
        String servableBucket,
        Duration connectTimeout,
        Duration readTimeout,
        Credentials ingest,
        Credentials worker,
        Credentials delivery) {

    public StorageProperties {
        Objects.requireNonNull(endpoint, "praxedo.storage.endpoint");
        Objects.requireNonNull(region, "praxedo.storage.region");
        Objects.requireNonNull(quarantineBucket, "praxedo.storage.quarantine-bucket");
        Objects.requireNonNull(servableBucket, "praxedo.storage.servable-bucket");
        Objects.requireNonNull(connectTimeout, "praxedo.storage.connect-timeout");
        Objects.requireNonNull(readTimeout, "praxedo.storage.read-timeout");
        Objects.requireNonNull(ingest, "praxedo.storage.ingest");
        Objects.requireNonNull(worker, "praxedo.storage.worker");
        Objects.requireNonNull(delivery, "praxedo.storage.delivery");
    }

    /** One identity on the storage. The secret is never logged. */
    public record Credentials(String accessKey, String secretKey) {

        public Credentials {
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("Storage credentials must be configured, never defaulted in code");
            }
        }

        @Override
        public String toString() {
            return "Credentials[" + accessKey + ", ****]";
        }
    }
}
