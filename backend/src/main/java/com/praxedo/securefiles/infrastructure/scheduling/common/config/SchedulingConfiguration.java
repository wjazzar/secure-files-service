package com.praxedo.securefiles.infrastructure.scheduling.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background work is on by default and can be switched off as a whole — which
 * the tests do, so that no chore races the situation a test has set up.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "praxedo.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
