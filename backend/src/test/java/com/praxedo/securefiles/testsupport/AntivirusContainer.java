package com.praxedo.securefiles.testsupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/**
 * The real antivirus, built from {@code infra/antivirus/Dockerfile} — the same
 * derived image as {@code docker-compose.yml}, with {@code AlertExceedsMax}
 * forced and the same limits.
 *
 * <p>It starts in about twenty seconds (the signatures ship inside the image)
 * and needs one to two gigabytes of memory, which is why only the pipeline test
 * uses it. Every failure mode of the engine is tested against a simulation; this
 * container exists for what a simulation cannot prove: that a real engine,
 * configured as the service configures it, really blocks EICAR — and really
 * reads archives to the end, or says it could not.
 */
public final class AntivirusContainer {

    @SuppressWarnings("resource")
    public static final GenericContainer<?> INSTANCE = new GenericContainer<>(
            new ImageFromDockerfile("praxedo-antivirus-test", false)
                    .withFileFromPath(".", Path.of("..", "infra", "antivirus")))
            .withEnv("MAX_FILE_SIZE", "2000M")
            .withEnv("MAX_SCAN_SIZE", "1024M")
            .withEnv("MAX_RECURSION", "16")
            .withEnv("MAX_FILES", "10000")
            // Copied with the bundled signatures into the engine's database at start-up.
            .withCopyToContainer(Transferable.of(probeSignature()), "/var/lib/clamav/praxedo-probe.ndb")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/").forPort(9000).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        INSTANCE.start();
    }

    private AntivirusContainer() {
    }

    /**
     * A harmless marker the test engine — and only the test engine — reports as
     * a threat, wherever it sits in a file.
     *
     * <p>EICAR cannot answer the question the limit tests ask: its signature
     * only matches at the very start of a file, so it cannot say whether the
     * <em>end</em> of a large archive entry was read. This marker can.
     */
    public static final String PROBE_MARKER = "PRAXEDO-PROBE-MARKER-7f3a9c21e5b8d604";

    /** The name the engine gives the marker. */
    public static final String PROBE_SIGNATURE_NAME = "Praxedo.Probe.Marker";

    public static String baseUrl() {
        return "http://" + INSTANCE.getHost() + ":" + INSTANCE.getMappedPort(9000);
    }

    /** ClamAV extended signature format: {@code name:target type:offset:hex}, any type, anywhere. */
    private static String probeSignature() {
        return PROBE_SIGNATURE_NAME + ":0:*:"
                + HexFormat.of().formatHex(PROBE_MARKER.getBytes(StandardCharsets.US_ASCII)) + "\n";
    }

    /**
     * The EICAR test string, assembled at run time.
     *
     * <p>Written whole in the repository, it would get the repository
     * quarantined by the antivirus of whoever clones it. And the pieces are
     * joined by a method call on purpose: adjacent string literals are folded
     * into one constant by the compiler, which would put the whole signature
     * back into the {@code .class} file.
     */
    public static String eicar() {
        return String.join("", List.of("X5O!P%@AP[4\\PZX54(P^)7CC)7}", "$EICAR-STANDARD-", "ANTIVIRUS-TEST-FILE!", "$H+H*"));
    }
}
