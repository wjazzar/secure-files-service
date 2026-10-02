package com.praxedo.securefiles.infrastructure.web.file.controller;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.praxedo.securefiles.application.file.model.FileSort;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.port.in.QueryFilesUseCase;
import com.praxedo.securefiles.domain.file.model.PublicStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.infrastructure.web.common.exception.InvalidParameterException;
import com.praxedo.securefiles.infrastructure.web.common.identity.CurrentOwner;
import com.praxedo.securefiles.infrastructure.web.file.dto.FileDetailResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilePageResponse;
import com.praxedo.securefiles.infrastructure.web.file.dto.FilesSummaryResponse;
import com.praxedo.securefiles.infrastructure.web.file.exception.UnknownFileException;
import com.praxedo.securefiles.infrastructure.web.file.header.PollingHeaders;
import com.praxedo.securefiles.infrastructure.web.file.mapper.FileIdMapper;
import com.praxedo.securefiles.infrastructure.web.file.mapper.FileResponseMapper;

/**
 * The three read operations of the contract: list, detail, counters.
 *
 * <p>Its job is narrow and worth stating, because everything else belongs
 * elsewhere: it validates what a caller wrote, hands typed values to the
 * application layer, and shapes the answer. It holds no rule of its own —
 * the owner comes from {@link CurrentOwner}, the status translation from the
 * domain, the counters from the use case.
 *
 * <p><strong>Every parameter is validated before anything reaches the
 * database.</strong> In particular the sort: it is matched against an enum and
 * rejected otherwise, so no fragment a caller wrote can ever end up in a query.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileQueryController {

    /** The contract's cap on the search term. */
    private static final int MAX_SEARCH_LENGTH = 100;

    private final QueryFilesUseCase filesUseCase;
    private final CurrentOwner currentOwner;
    private final PollingHeaders polling;

    FileQueryController(QueryFilesUseCase filesUseCase, CurrentOwner currentOwner, PollingHeaders polling) {
        this.filesUseCase = filesUseCase;
        this.currentOwner = currentOwner;
        this.polling = polling;
    }

    @GetMapping
    ResponseEntity<FilePageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String q) {

        int pageSize = PageQuery.capped(pageSize(size));
        PageQuery pageQuery = PageQuery.of(pageNumber(page, pageSize), pageSize, sortOrDefault(sort));
        PageResult<StoredFile> found =
                filesUseCase.list(owner(), publicStatuses(status), search(q), pageQuery);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(FileResponseMapper.page(found));
    }

    /**
     * Mapped before {@code /{fileId}} by the framework's own preference for a
     * literal path over a template — a UUID could never collide with it anyway.
     */
    @GetMapping("/summary")
    ResponseEntity<FilesSummaryResponse> summary(@RequestParam(required = false) String q) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(FileResponseMapper.counters(filesUseCase.count(owner(), search(q))));
    }

    /**
     * Built for polling: the answer carries a weak {@code ETag} over the row
     * version, which changes at every transition. A client that sends it back
     * gets a bodyless {@code 304} for as long as nothing moved, and
     * {@code Retry-After} tells it when to ask again, sized to the file — until
     * the file is terminal, at which point there is nothing left to wait for.
     *
     * <p>The hint rides on the {@code 304} too: a client that revalidates
     * receives little else, and would otherwise see it once per transition.
     */
    @GetMapping("/{fileId}")
    ResponseEntity<FileDetailResponse> detail(
            @PathVariable String fileId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        StoredFile file = filesUseCase.find(FileIdMapper.fromPath(fileId), owner()).orElseThrow(UnknownFileException::new);
        String etag = etagOf(file);

        if (unchanged(ifNoneMatch, etag)) {
            return withPollingHint(ResponseEntity.status(HttpStatus.NOT_MODIFIED), file, etag).build();
        }
        return withPollingHint(ResponseEntity.ok(), file, etag).body(FileResponseMapper.detail(file));
    }

    /** The headers of every answer about one file: its version, and when to look again while it moves. */
    private ResponseEntity.BodyBuilder withPollingHint(ResponseEntity.BodyBuilder answer, StoredFile file,
                                                       String etag) {
        answer.eTag(etag).cacheControl(CacheControl.noCache());
        if (!file.isTerminal()) {
            answer.header(HttpHeaders.RETRY_AFTER, polling.retryAfterSeconds(file.sizeBytes()));
        }
        return answer;
    }

    // ── parameters ──────────────────────────────────────────────────────

    private OwnerId owner() {
        return currentOwner.resolve();
    }

    /**
     * A malformed identifier is answered {@code 404}, not {@code 400}: it names
     * a file that cannot exist, which is exactly what "not found" means. It also
     * keeps the answer identical to the one an unknown — or someone else's —
     * file gets, so nothing can be learnt by comparing them.
     */

    /** A page that would skip more than {@link PageQuery#MAX_DEPTH} files is refused (audit S-19). */
    private static int pageNumber(int page, int pageSize) {
        if (page < 0) {
            throw new InvalidParameterException("The parameter 'page' starts at 0.");
        }
        if (PageQuery.tooDeep(page, pageSize)) {
            throw new InvalidParameterException("A page may start at most " + PageQuery.MAX_DEPTH
                    + " files deep: narrow the list with the search or the status filter.");
        }
        return page;
    }

    /**
     * A size above the cap is brought back to it rather than rejected: the
     * contract announces the maximum, and a client asking for more is asking for
     * everything, not making a mistake. A size below one, on the other hand,
     * describes no page at all.
     */
    private static int pageSize(int size) {
        if (size < 1) {
            throw new InvalidParameterException("The parameter 'size' must be at least 1.");
        }
        return size;
    }

    private static FileSort sortOrDefault(String sort) {
        if (sort == null || sort.isBlank()) {
            return FileSort.DEFAULT;
        }
        return FileSort.parse(sort).orElseThrow(() -> new InvalidParameterException(
                "The parameter 'sort' accepts only: " + String.join(", ", FileSort.accepted()) + "."));
    }

    /** The statuses a caller may filter on are the published ones, never the internal ones. */
    private static Set<PublicStatus> publicStatuses(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        Set<PublicStatus> statuses = EnumSet.noneOf(PublicStatus.class);
        for (String candidate : requested) {
            statuses.add(publicStatus(candidate));
        }
        return statuses;
    }

    private static PublicStatus publicStatus(String candidate) {
        try {
            return PublicStatus.valueOf(candidate.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidParameterException("The parameter 'status' accepts only: "
                    + String.join(", ", EnumSet.allOf(PublicStatus.class).stream().map(Enum::name).toList()) + ".");
        }
    }

    private static String search(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String term = q.strip();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidParameterException(
                    "The parameter 'q' is limited to " + MAX_SEARCH_LENGTH + " characters.");
        }
        return term;
    }

    // ── conditional request ─────────────────────────────────────────────

    /** The row version moves at every transition, so it is the whole entity tag. */
    private static String etagOf(StoredFile file) {
        return "W/\"" + file.id() + "-" + file.version() + "\"";
    }

    /**
     * Weak comparison, as RFC 9110 requires for {@code If-None-Match}: the tag
     * is weak, and a client may legitimately send it back with or without its
     * {@code W/} prefix, alone or among others.
     */
    private static boolean unchanged(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String offered = candidate.strip();
            if ("*".equals(offered) || withoutWeakPrefix(offered).equals(withoutWeakPrefix(etag))) {
                return true;
            }
        }
        return false;
    }

    private static String withoutWeakPrefix(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }
}
