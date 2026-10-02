package com.praxedo.securefiles.infrastructure.antivirus.file.adapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.praxedo.securefiles.application.file.port.out.AntivirusScanner.ScanOutcome;
import com.praxedo.securefiles.application.file.port.out.AntivirusScanner;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusClients;
import com.praxedo.securefiles.infrastructure.antivirus.common.config.AntivirusProperties;
import com.praxedo.securefiles.testsupport.AntivirusContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the real engine does at its limits — measured, not assumed.
 *
 * <p>The guarantee "never serve what was not analysed" is only as good as the
 * engine's honesty about what it did <em>not</em> analyse. These tests send
 * archives that are a few hundred kilobytes on the wire and hundreds of
 * megabytes once decompressed, with a marker the test engine recognises
 * placed at the <em>end</em> of an entry: if the marker is reported, the end was
 * read; if the limit is reported, the service classes the file unscannable;
 * any plain "clean" is a false negative.
 *
 * <p>The first measurement, with {@code MAX_FILE_SIZE} at 512M, failed the
 * 600 MB case: the entry was cut at the limit without any alert, and the
 * archive reported clean. The configuration now orders the limits so that the
 * one that alerts always gives way first — and the image refuses to start
 * otherwise. See {@code infra/README.md} and {@code docs/prompts/B-008}.
 *
 * <p>Slow on purpose (about two minutes): the engine really decompresses.
 */
class AntivirusEngineLimitsTest {

    private static final int MEGABYTE = 1024 * 1024;

    private final AntivirusScanner scanner = AntivirusClients.httpScanner(
            new AntivirusProperties(URI.create(AntivirusContainer.baseUrl()), Duration.ofSeconds(2), Duration.ofMinutes(10),
                    Duration.ofSeconds(2), Duration.ofMinutes(1)),
            Clock.systemUTC());

    @Test
    @DisplayName("⭐ the end of an entry larger than the old per-file limit is read (600 MB, marker at the end)")
    void the_end_of_a_large_entry_is_analysed() {
        ScanOutcome outcome = scan(zipOfZeros(1, 600, true));

        assertThat(outcome.result()).isEqualTo(ScanResult.INFECTED);
        assertThat(outcome.detail()).startsWith(AntivirusContainer.PROBE_SIGNATURE_NAME);
    }

    @Test
    @DisplayName("⭐ an entry beyond the analysed-volume limit is unscannable — never clean (1100 MB)")
    void an_entry_beyond_the_scan_limit_is_unscannable() {
        ScanOutcome outcome = scan(zipOfZeros(1, 1100, true));

        assertThat(outcome.result()).isEqualTo(ScanResult.UNSCANNABLE);
        assertThat(outcome.detail()).startsWith("Heuristics.Limits.Exceeded");
    }

    @Test
    @DisplayName("⭐ entries adding up beyond the analysed-volume limit are unscannable (3 × 400 MB)")
    void entries_adding_up_beyond_the_scan_limit_are_unscannable() {
        ScanOutcome outcome = scan(zipOfZeros(3, 400, false));

        assertThat(outcome.result()).isEqualTo(ScanResult.UNSCANNABLE);
        assertThat(outcome.detail()).startsWith("Heuristics.Limits.Exceeded");
    }

    @Test
    @DisplayName("control: EICAR in an ordinary compressed archive is detected")
    void eicar_in_an_ordinary_archive_is_detected() {
        ScanOutcome outcome = scan(handMadeZip(AntivirusContainer.eicar().getBytes(StandardCharsets.US_ASCII), false));

        assertThat(outcome.result()).isEqualTo(ScanResult.INFECTED);
        assertThat(outcome.detail()).containsIgnoringCase("eicar");
    }

    /**
     * A characterisation, not a wish: this is what the engine does today, and it
     * is wrong. The archive is valid — any unzip tool extracts it — but the
     * engine does not analyse a compressed entry whose local header uses the
     * zip64 form, and answers "clean". Nothing in the service can catch it
     * short of parsing archives itself; it is documented as a residual risk,
     * with the options that would close it, in the README.
     *
     * <p>If this test starts failing after an engine update, the blind spot is
     * gone: update the documentation, then turn this into a detection test.
     */
    @Test
    @DisplayName("⚠ known engine blind spot: EICAR behind a zip64 local header is reported clean")
    void known_blind_spot_zip64_local_header() {
        ScanOutcome outcome = scan(handMadeZip(AntivirusContainer.eicar().getBytes(StandardCharsets.US_ASCII), true));

        assertThat(outcome.result()).isEqualTo(ScanResult.CLEAN);
    }

    // ── archives ────────────────────────────────────────────────────────

    private ScanOutcome scan(byte[] archive) {
        return scanner.scan(new ByteArrayInputStream(archive), archive.length);
    }

    /** Zeros compress about a thousand to one: a few hundred kilobytes, whatever the entry size. */
    private static byte[] zipOfZeros(int entries, int megabytesEach, boolean markerAtTheEnd) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        byte[] megabyte = new byte[MEGABYTE];
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (int entry = 0; entry < entries; entry++) {
                zip.putNextEntry(new ZipEntry("part-" + entry + ".bin"));
                for (int written = 0; written < megabytesEach; written++) {
                    zip.write(megabyte);
                }
                if (markerAtTheEnd) {
                    zip.write(AntivirusContainer.PROBE_MARKER.getBytes(StandardCharsets.US_ASCII));
                }
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return archive.toByteArray();
    }

    /**
     * One deflated entry, written byte by byte so that the local header's form
     * is chosen, not left to a library: with {@code zip64}, its sizes read
     * {@code 0xFFFFFFFF} and the real ones sit in the zip64 extra field, as the
     * specification (APPNOTE 4.5.3) allows for any entry.
     */
    private static byte[] handMadeZip(byte[] content, boolean zip64) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(content);
        deflater.finish();
        byte[] buffer = new byte[content.length + 64];
        int compressedLength = deflater.deflate(buffer);
        deflater.end();
        CRC32 crc = new CRC32();
        crc.update(content);
        byte[] name = "eicar.com".getBytes(StandardCharsets.US_ASCII);
        short version = (short) (zip64 ? 45 : 20);

        ByteBuffer zip = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        zip.putInt(0x04034b50).putShort(version).putShort((short) 0).putShort((short) 8)
                .putShort((short) 0).putShort((short) 0).putInt((int) crc.getValue())
                .putInt(zip64 ? 0xFFFFFFFF : compressedLength).putInt(zip64 ? 0xFFFFFFFF : content.length)
                .putShort((short) name.length).putShort((short) (zip64 ? 20 : 0)).put(name);
        if (zip64) {
            zip.putShort((short) 0x0001).putShort((short) 16).putLong(content.length).putLong(compressedLength);
        }
        zip.put(buffer, 0, compressedLength);

        int centralDirectoryOffset = zip.position();
        zip.putInt(0x02014b50).putShort(version).putShort(version).putShort((short) 0).putShort((short) 8)
                .putShort((short) 0).putShort((short) 0).putInt((int) crc.getValue())
                .putInt(compressedLength).putInt(content.length).putShort((short) name.length)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putInt(0).putInt(0).put(name);
        int centralDirectoryLength = zip.position() - centralDirectoryOffset;

        zip.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1)
                .putInt(centralDirectoryLength).putInt(centralDirectoryOffset).putShort((short) 0);

        byte[] archive = new byte[zip.position()];
        zip.flip().get(archive);
        return archive;
    }
}
