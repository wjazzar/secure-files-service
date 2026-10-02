/**
 * The composition root: which implementation answers which port.
 *
 * <p>Open this package to know how the hexagon is assembled — nothing else
 * builds a use case or an adapter behind a port.
 *
 * <ul>
 *   <li>{@link com.praxedo.securefiles.config.UseCaseConfiguration} — the
 *       services, each published as its port in, and the ports out they
 *       receive;</li>
 *   <li>{@link com.praxedo.securefiles.config.StorageConfiguration} — the three
 *       storage ports, one per identity: {@code QuarantineWriter} (ingest),
 *       {@code ServableReader} (delivery), {@code WorkerStorage} (worker);</li>
 *   <li>{@link com.praxedo.securefiles.config.AntivirusConfiguration} — the
 *       {@code AntivirusScanner} port: the engine's HTTP API, measured;</li>
 *   <li>{@link com.praxedo.securefiles.config.ServiceProperties} — every bound
 *       the use cases work within.</li>
 * </ul>
 *
 * <p><strong>The persistence ports are the exception, deliberately.</strong>
 * {@code FileCatalog}, {@code FileWorkQueue}, {@code IdempotencyStore},
 * {@code DownloadAudit}, {@code OperationalReadings} and
 * {@code TransactionRunner} each have exactly one implementation, with nothing
 * to choose between: they declare themselves with {@code @Repository} or
 * {@code @Component} in {@code infrastructure.persistence}, which also gives
 * them Spring's exception translation. Everything that needs a decision — an
 * identity, a mode, a decorator — is decided here, and the build checks it
 * ({@code HexagonalArchitectureTest}).
 *
 * <p>What stays in each adapter's {@code common/config} is technical
 * configuration that wires no port: connection pools, the security filter
 * chains, the HTTP connector, the typed properties and the client builders.
 */
package com.praxedo.securefiles.config;
