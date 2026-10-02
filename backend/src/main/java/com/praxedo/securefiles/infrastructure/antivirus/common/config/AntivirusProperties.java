package com.praxedo.securefiles.infrastructure.antivirus.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the antivirus API is, and how long the service is prepared to wait.
 *
 * @param connectTimeout  explicit, as rule B-7 requires
 * @param readTimeout     must exceed the engine's time to analyse a 500 MB file:
 *                        a client that gives up first leaves the engine working
 *                        for nobody, and the retry doubles the load
 * @param healthTimeout   short: the health gate must answer fast or not at all
 * @param versionCacheTtl how long the engine and signature versions are reused
 *                        before being asked again
 */
@ConfigurationProperties("praxedo.antivirus")
public record AntivirusProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout,
                                  Duration healthTimeout, Duration versionCacheTtl) {

    public AntivirusProperties {
        Objects.requireNonNull(baseUrl, "praxedo.antivirus.base-url");
        Objects.requireNonNull(connectTimeout, "praxedo.antivirus.connect-timeout");
        Objects.requireNonNull(readTimeout, "praxedo.antivirus.read-timeout");
        Objects.requireNonNull(healthTimeout, "praxedo.antivirus.health-timeout");
        Objects.requireNonNull(versionCacheTtl, "praxedo.antivirus.version-cache-ttl");
    }
}
