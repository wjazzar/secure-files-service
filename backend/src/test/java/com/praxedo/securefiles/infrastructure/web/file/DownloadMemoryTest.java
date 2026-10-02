package com.praxedo.securefiles.infrastructure.web.file;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.praxedo.securefiles.application.file.port.out.FileWorkQueue;
import com.praxedo.securefiles.application.file.service.FilePromotionService;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.testsupport.FullStackTest;
import com.praxedo.securefiles.testsupport.HttpApi;
import com.praxedo.securefiles.testsupport.ServableFiles;
import com.praxedo.securefiles.testsupport.TestContent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule B-1 on the way out: a 500 MB file promoted, then downloaded, through a
 * JVM whose whole heap is 256 MB.
 *
 * <p>Two more places where a file could be held without anyone noticing: the
 * promotion, which reads the quarantined object and writes the servable copy
 * while computing its digest, and the download, which streams the object from
 * the storage to the socket. Either one buffering the file would exhaust the
 * heap. Runs in the {@code bounded-memory} surefire execution, like
 * {@code UploadMemoryTest}.
 */
@Tag("memory")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DownloadMemoryTest extends FullStackTest {

    private static final long FIVE_HUNDRED_MEGABYTES = 500L * 1024 * 1024;

    @Autowired
    FileWorkQueue queue;

    @Autowired
    FilePromotionService promotion;

    @Test
    @DisplayName("500 MB are promoted and downloaded intact, through a 256 MB heap")
    void a_file_twice_the_heap_streams_out() throws IOException {
        long maxHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        assertThat(maxHeap)
                .as("the proof only holds if the heap is smaller than the file")
                .isLessThan(FIVE_HUNDRED_MEGABYTES);

        HttpApi.Response uploaded = api.post("/api/v1/files",
                HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> TestContent.generated(FIVE_HUNDRED_MEGABYTES)),
                        FIVE_HUNDRED_MEGABYTES),
                "X-File-Name", "five-hundred-megabytes.bin",
                "Content-Type", "application/octet-stream");
        assertThat(uploaded.status()).isEqualTo(202);

        Instant promotionStart = Instant.now();
        StoredFile available = new ServableFiles(queue, promotion).promoteNextDue();
        Duration promotionTime = Duration.between(promotionStart, Instant.now());

        Instant downloadStart = Instant.now();
        HttpResponse<InputStream> response = api.getStream("/api/v1/files/" + available.id().value() + "/content");
        String digest;
        try (InputStream body = response.body()) {
            digest = TestContent.digestOf(body);
        }
        Duration downloadTime = Duration.between(downloadStart, Instant.now());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(FIVE_HUNDRED_MEGABYTES);
        assertThat(digest).isEqualTo(TestContent.digestOfGenerated(FIVE_HUNDRED_MEGABYTES));

        System.out.printf("MEMORY PROOF: 500 MB promoted in %d ms and downloaded in %d ms with a %d MB heap%n",
                promotionTime.toMillis(), downloadTime.toMillis(), maxHeap / (1024 * 1024));
    }
}
