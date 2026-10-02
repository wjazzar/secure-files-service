package com.praxedo.securefiles.config;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusClients;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusProperties;
import com.praxedo.securefiles.infrastructure.antivirus.file.adapter.InstantCleanAntivirusScanner;
import com.praxedo.securefiles.infrastructure.antivirus.file.adapter.MeteredAntivirusScanner;

/**
 * Who answers the {@link AntivirusScanner} port: the engine's HTTP API, always
 * behind the metering decorator — or, for capacity tests only, a stand-in that
 * finds every file clean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AntivirusProperties.class)
class AntivirusConfiguration {

    @Bean
    @ConditionalOnProperty(name = "praxedo.antivirus.mode", havingValue = "http", matchIfMissing = true)
    AntivirusScanner httpAntivirusScanner(AntivirusProperties antivirus, Clock clock, MeterRegistry registry) {
        return new MeteredAntivirusScanner(AntivirusClients.httpScanner(antivirus, clock), registry)
                .withAllResultsRegistered();
    }

    /**
     * Capacity-test control: keeps the complete storage, hashing, database and
     * promotion pipeline, but removes the external engine from the measurement.
     */
    @Bean
    @Profile("capacity")
    @ConditionalOnProperty(name = "praxedo.antivirus.mode", havingValue = "instant-clean")
    AntivirusScanner instantCleanAntivirusScanner(Clock clock, MeterRegistry registry) {
        return new MeteredAntivirusScanner(new InstantCleanAntivirusScanner(clock), registry)
                .withAllResultsRegistered();
    }
}
