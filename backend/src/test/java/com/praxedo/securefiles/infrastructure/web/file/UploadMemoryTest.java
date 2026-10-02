package com.praxedo.securefiles.infrastructure.web.file;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.TestContent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules B-1 and B-4, measured: a 500 MB upload through a JVM whose whole heap
 * is 256 MB.
 *
 * <p>This test runs in its own surefire execution, with {@code -Xmx256m} — half
 * the size of the file, and shared with the Spring context, Hibernate and the
 * HTTP client. If anything on the way — a {@code MultipartFile}, a
 * {@code byte[]}, a framework converter, an SDK retry buffer — held the body,
 * the JVM would run out of memory and the test would fail. Passing is the proof
 * that the body flows from the socket to the storage without being held.
 */
@Tag("memory")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadMemoryTest extends FullStackTest {

    private static final long FIVE_HUNDRED_MEGABYTES = 500L * 1024 * 1024;

    @Test
    @DisplayName("500 MB are accepted, stored intact, through a 256 MB heap")
    void a_file_twice_the_heap_streams_through() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long maxHeap = memory.getHeapMemoryUsage().getMax();
        assertThat(maxHeap)
                .as("the proof only holds if the heap is smaller than the file")
                .isLessThan(FIVE_HUNDRED_MEGABYTES);

        Instant start = Instant.now();
        HttpApi.Response response = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(FIVE_HUNDRED_MEGABYTES)),
                        FIVE_HUNDRED_MEGABYTES),
                "X-File-Name", "five-hundred-megabytes.bin",
                "Content-Type", "application/octet-stream");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.json().path("sizeBytes").asLong()).isEqualTo(FIVE_HUNDRED_MEGABYTES);
        assertThat(response.json().path("sha256").asString())
                .isEqualTo(TestContent.digestOfGenerated(FIVE_HUNDRED_MEGABYTES));

        System.out.printf("MEMORY PROOF: 500 MB uploaded in %d ms with a %d MB heap%n",
                elapsed.toMillis(), maxHeap / (1024 * 1024));
    }
}
