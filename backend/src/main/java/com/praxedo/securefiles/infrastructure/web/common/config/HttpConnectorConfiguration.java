package com.praxedo.securefiles.infrastructure.web.common.config;

import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How the HTTP connector treats a request body the service has not asked for.
 *
 * <p>An upload is refused before its body is read: too large, node full, queue
 * full. Two settings keep that refusal cheap:
 * <ul>
 *   <li>{@code continueResponseTiming=onRead}, here: a client that announced
 *       {@code Expect: 100-continue} receives "100 Continue" only when the
 *       service starts reading the body. A refused upload is answered before
 *       that, and its body is never sent. Tomcat's default answers "100
 *       Continue" at once, which defeats the header;</li>
 *   <li>{@code server.tomcat.max-swallow-size}, in {@code application.yml}: a
 *       client that sends its body anyway is read for at most that much before
 *       the connection is closed.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class HttpConnectorConfiguration {

    @Bean
    TomcatConnectorCustomizer continueOnlyOnceTheBodyIsRead() {
        return connector -> {
            if (!connector.setProperty("continueResponseTiming", "onRead")) {
                throw new IllegalStateException("The HTTP connector does not accept continueResponseTiming");
            }
        };
    }
}
