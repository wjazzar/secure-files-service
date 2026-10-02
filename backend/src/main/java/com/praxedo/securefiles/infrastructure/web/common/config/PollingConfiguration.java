package com.praxedo.securefiles.infrastructure.web.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.praxedo.securefiles.infrastructure.web.file.header.PollingHeaders;

/**
 * The polling hint of the web adapter, built from its settings. It wires no
 * port: it is how this adapter answers, not what the core needs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PollingProperties.class)
class PollingConfiguration {

    @Bean
    PollingHeaders pollingHeaders(PollingProperties polling) {
        return new PollingHeaders(polling);
    }
}
