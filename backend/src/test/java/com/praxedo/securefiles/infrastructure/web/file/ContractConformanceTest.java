package com.praxedo.securefiles.infrastructure.web.file;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.praxedo.securefiles.application.file.model.FileSort;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.ScanResult;
import com.praxedo.securefiles.domain.file.model.StatusReason;
import com.praxedo.securefiles.infrastructure.web.common.error.ErrorCode;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileDetailResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileLinksResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilePageResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileSummaryResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilesSummaryResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.ScanVerdictResponse;
import com.praxedo.securefiles.infrastructure.web.session.dto.LogoutResponse;
import com.praxedo.securefiles.infrastructure.web.session.dto.SessionResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract and the code, compared field by field.
 *
 * <p>{@code contracts/openapi.yaml} is the single source of truth shared with
 * the front-end (rule B-12). This test reads it and fails the build as soon as
 * a response record, an enumeration or an error code drifts from it — in
 * either direction. It is what turns "the contract is authoritative" from a
 * statement into a check.
 *
 * <p>It reads the file itself rather than a copy, so a change to the contract
 * that the code has not followed is caught the moment it is made.
 */
class ContractConformanceTest {

    private static Map<String, Object> contract;

    @BeforeAll
    static void readTheContract() throws IOException {
        Path location = Path.of("..", "contracts", "openapi.yaml");
        try (Reader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
            contract = new Yaml().load(reader);
        }
    }

    // ── enumerations ────────────────────────────────────────────────────

    @Test
    @DisplayName("the published statuses are exactly the contract's FileStatus")
    void public_statuses_match() {
        assertThat(names(PublicStatus.values())).isEqualTo(enumOf("FileStatus"));
    }

    @Test
    void status_reasons_match() {
        assertThat(names(StatusReason.values())).isEqualTo(enumOf("StatusReasonCode"));
    }

    @Test
    void verdict_results_match() {
        Map<String, Object> result = map(map(schema("ScanVerdict").get("properties")).get("result"));
        assertThat(names(ScanResult.values())).isEqualTo(new LinkedHashSet<>(strings(result.get("enum"))));
    }

    @Test
    @DisplayName("every error code the service emits is declared by the contract")
    void emitted_error_codes_are_declared() {
        assertThat(enumOf("ErrorCode")).containsAll(names(ErrorCode.values()));
    }

    @Test
    @DisplayName("the accepted sorts are exactly the contract's allow-list")
    void accepted_sorts_match() {
        Map<String, Object> listFiles = map(map(map(contract.get("paths")).get("/api/v1/files")).get("get"));
        Map<String, Object> sort = parameter(listFiles, "sort");
        assertThat(FileSort.accepted()).containsExactlyInAnyOrderElementsOf(strings(map(sort.get("schema")).get("enum")));
    }

    @Test
    void the_default_page_size_and_cap_match() {
        Map<String, Object> listFiles = map(map(map(contract.get("paths")).get("/api/v1/files")).get("get"));
        Map<String, Object> size = map(parameter(listFiles, "size").get("schema"));
        assertThat(size.get("default")).isEqualTo(com.praxedo.securefiles.application.file.model.PageQuery.DEFAULT_SIZE);
        assertThat(size.get("maximum")).isEqualTo(com.praxedo.securefiles.application.file.model.PageQuery.MAX_SIZE);
    }

    // ── response shapes ─────────────────────────────────────────────────

    @Test
    @DisplayName("FileSummaryResponse has exactly the contract's FileSummary fields")
    void file_summary_matches() {
        assertThat(components(FileSummaryResponse.class)).isEqualTo(properties("FileSummary"));
        assertThat(components(FileSummaryResponse.class)).containsAll(required("FileSummary"));
    }

    @Test
    @DisplayName("FileDetailResponse is FileSummary plus the contract's detail fields")
    void file_detail_matches() {
        Set<String> expected = new LinkedHashSet<>(properties("FileSummary"));
        expected.addAll(allOfProperties("FileDetail"));
        assertThat(components(FileDetailResponse.class)).isEqualTo(expected);
    }

    @Test
    void verdict_matches() {
        assertThat(components(ScanVerdictResponse.class)).isEqualTo(properties("ScanVerdict"));
    }

    @Test
    void links_match() {
        assertThat(components(FileLinksResponse.class)).isEqualTo(properties("FileLinks"));
    }

    @Test
    void page_and_page_metadata_match() {
        assertThat(components(FilePageResponse.class)).isEqualTo(properties("FilePage"));
        assertThat(components(FilePageResponse.PageMetadataResponse.class)).isEqualTo(properties("PageMetadata"));
    }

    @Test
    void summary_matches() {
        assertThat(components(FilesSummaryResponse.class)).isEqualTo(properties("FilesSummary"));
        Map<String, Object> byStatus = map(map(schema("FilesSummary").get("properties")).get("byStatus"));
        assertThat(new LinkedHashSet<>(strings(byStatus.get("required")))).isEqualTo(names(PublicStatus.values()));
    }

    @Test
    @DisplayName("SessionResponse has exactly the contract's Session fields, all required")
    void session_matches() {
        assertThat(components(SessionResponse.class)).isEqualTo(properties("Session"));
        assertThat(components(SessionResponse.class)).isEqualTo(required("Session"));
    }

    @Test
    @DisplayName("LogoutResponse has exactly the contract's Logout fields, all required")
    void logout_matches() {
        assertThat(components(LogoutResponse.class)).isEqualTo(properties("Logout"));
        assertThat(components(LogoutResponse.class)).isEqualTo(required("Logout"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> schema(String name) {
        return map(map(map(contract.get("components")).get("schemas")).get(name));
    }

    private static Set<String> enumOf(String schemaName) {
        return new LinkedHashSet<>(strings(schema(schemaName).get("enum")));
    }

    private static Set<String> properties(String schemaName) {
        return new LinkedHashSet<>(map(schema(schemaName).get("properties")).keySet());
    }

    private static Set<String> required(String schemaName) {
        return new LinkedHashSet<>(strings(schema(schemaName).get("required")));
    }

    /** The inline part of an {@code allOf} — the fields a schema adds to its base. */
    private static Set<String> allOfProperties(String schemaName) {
        Set<String> names = new LinkedHashSet<>();
        for (Object part : (List<?>) schema(schemaName).get("allOf")) {
            Map<String, Object> inline = map(part);
            if (inline.containsKey("properties")) {
                names.addAll(map(inline.get("properties")).keySet());
            }
        }
        return names;
    }

    private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
        for (Object candidate : (List<?>) operation.get("parameters")) {
            Map<String, Object> parameter = map(candidate);
            if (name.equals(parameter.get("name"))) {
                return parameter;
            }
        }
        throw new AssertionError("The contract declares no parameter " + name);
    }

    private static Set<String> components(Class<? extends Record> type) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(type.getRecordComponents()).forEach(component -> names.add(component.getName()));
        return names;
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new LinkedHashSet<>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        assertThat(node).as("expected an object in the contract").isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    private static List<String> strings(Object node) {
        List<String> values = new ArrayList<>();
        for (Object value : (List<?>) node) {
            values.add(String.valueOf(value));
        }
        return values;
    }
}
