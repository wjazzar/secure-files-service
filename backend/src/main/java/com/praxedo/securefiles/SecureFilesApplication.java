package com.praxedo.securefiles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the secure file service. It stays at the root of the packages
 * so that component scanning covers all of them; the composition itself —
 * which implementation answers which port — is in {@code config}.
 *
 * <p><strong>One process.</strong> Ingestion, analysis and delivery run in the
 * same application; there is nothing to start three times.
 *
 * <p>The separation between them is a separation <em>in the code</em>, and the
 * part that matters is the one the object storage enforces: the component that
 * serves content is handed credentials with <strong>no read access at all</strong>
 * to the quarantine area, while the component that accepts uploads gets
 * write-only credentials on it. Neither one can be made to do the other's job
 * by a bug, because the storage itself refuses.
 *
 * <p>Should one part ever need to scale on its own, that becomes a deployment
 * choice — the same artefact, started twice with a different toggle — and not
 * a reason to complicate the code today.
 */
@SpringBootApplication
public class SecureFilesApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureFilesApplication.class, args);
    }
}
