package com.praxedo.securefiles.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.praxedo.securefiles.application.file.port.out.QuarantineWriter;
import com.praxedo.securefiles.application.file.port.out.ServableReader;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.infrastructure.storage.common.config.S3Clients;
import com.praxedo.securefiles.infrastructure.storage.common.config.S3Clients.Retries;
import com.praxedo.securefiles.infrastructure.storage.common.config.StorageProperties;
import com.praxedo.securefiles.infrastructure.storage.file.adapter.S3QuarantineWriter;
import com.praxedo.securefiles.infrastructure.storage.file.adapter.S3ServableReader;
import com.praxedo.securefiles.infrastructure.storage.file.adapter.S3WorkerStorage;

/**
 * Three identities, three clients, three ports — wired here and nowhere else.
 *
 * <p>The S3 clients are deliberately <strong>not</strong> beans. If they were,
 * any component could ask Spring for "an {@code S3Client}" and receive one with
 * the wrong rights. Built inside each adapter, the worker's client cannot be
 * injected into the download path, even by mistake: the separation is a matter
 * of what can be referenced, not of naming discipline.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

    @Bean
    QuarantineWriter quarantineWriter(StorageProperties storage) {
        return new S3QuarantineWriter(S3Clients.forIdentity(storage, storage.ingest(), Retries.BY_CALLER),
                storage.quarantineBucket());
    }

    @Bean
    ServableReader servableReader(StorageProperties storage) {
        return new S3ServableReader(S3Clients.forIdentity(storage, storage.delivery(), Retries.BY_SDK),
                storage.servableBucket());
    }

    @Bean
    WorkerStorage workerStorage(StorageProperties storage) {
        return new S3WorkerStorage(S3Clients.forIdentity(storage, storage.worker(), Retries.BY_CALLER),
                storage.quarantineBucket(), storage.servableBucket());
    }
}
