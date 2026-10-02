package com.praxedo.securefiles.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the tests that need the whole service: PostgreSQL and the
 * object storage, both real, and the API reached over HTTP on a random port.
 */
public abstract class FullStackTest extends PostgresTestcontainer {

    @LocalServerPort
    protected int port;

    /** The actuator's own port — never the API's (audit S-08). */
    @LocalManagementPort
    protected int managementPort;

    /** The API as {@link TestIdentityProvider#USER}: a valid access token on every request. */
    protected HttpApi api;

    /** The actuator, without credentials, as Prometheus and the probes reach it. */
    protected HttpApi management;

    @DynamicPropertySource
    static void objectStorage(DynamicPropertyRegistry registry) {
        registry.add("praxedo.storage.endpoint", SeaweedFsContainer::endpoint);
    }

    @BeforeEach
    void connectToTheService() {
        api = new HttpApi(port).withToken(TestIdentityProvider.accessToken());
        management = new HttpApi(managementPort);
    }
}
