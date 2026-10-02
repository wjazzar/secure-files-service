package com.praxedo.securefiles.infrastructure.persistence.file.adapter;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.praxedo.securefiles.application.file.model.FileQuery;
import com.praxedo.securefiles.application.file.model.FileSort;
import com.praxedo.securefiles.application.file.model.PageQuery;
import com.praxedo.securefiles.application.file.model.PageResult;
import com.praxedo.securefiles.application.file.port.out.FileCatalog;
import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.domain.file.model.StoredFile;
import com.praxedo.securefiles.domain.file.valueobject.FileId;
import com.praxedo.securefiles.domain.owner.valueobject.OwnerId;
import com.praxedo.securefiles.infrastructure.persistence.file.entity.StoredFileEntity;
import com.praxedo.securefiles.infrastructure.persistence.file.mapper.StoredFileEntityMapper;
import com.praxedo.securefiles.infrastructure.persistence.file.repository.StoredFileJpaRepository;

/**
 * The catalogue, backed by JPA.
 *
 * <p>JPA sees {@link StoredFileEntity} rows only; the application sees the
 * domain's {@link StoredFile}. Every answer is converted by
 * {@link StoredFileEntityMapper} before it leaves this class, so the aggregate
 * stays free of any persistence concern and each file read is re-validated by
 * its constructor.
 *
 * <p>⚠️ <strong>Never mix this with {@link JdbcFileWorkQueue} inside one
 * transaction.</strong> The work queue writes through plain JDBC, which does
 * not trigger a Hibernate flush and does not refresh the persistence context:
 * an entity loaded here and a statement executed there would disagree. The two
 * are used by different paths on purpose — the API reads through JPA, the
 * worker claims and writes through SQL — and each method below is its own
 * transaction.
 *
 * <p>This adapter is also the only place that knows the entity attribute names.
 * A sort arrives as a {@link FileSort} constant — a value the application
 * already validated — and is translated here into attributes. Nothing a caller
 * wrote ever becomes part of a query.
 */
@Repository
public class JpaFileCatalog implements FileCatalog {

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Contract field name → entity attribute. The contract's {@code filename}
     * is the entity's {@code originalFilename}; keeping the two apart is what
     * lets either be renamed without touching the other.
     */
    private static final Map<String, String> SORTABLE_ATTRIBUTES = Map.of(
            "uploadedAt", "uploadedAt",
            "filename", "originalFilename",
            "sizeBytes", "sizeBytes");

    /** Escapes the characters {@code LIKE} would otherwise read as wildcards. */
    private static final String LIKE_ESCAPE = "\\";

    private final StoredFileJpaRepository storedFileRepository;
    private final EntityManager entityManager;

    JpaFileCatalog(StoredFileJpaRepository storedFileRepository, EntityManager entityManager) {
        this.storedFileRepository = storedFileRepository;
        this.entityManager = entityManager;
    }

    /**
     * Always an insert, never a merge.
     *
     * <p>{@code save()} would decide between the two from the entity itself —
     * and with a primitive {@code @Version} and an identifier assigned by the
     * domain, it cannot tell a new file from a detached one, so it merges: a
     * {@code SELECT} before every upload. A file is inserted once, by the upload,
     * so the intent is stated rather than guessed — and the repository does not
     * even offer {@code save()}.
     *
     * <p>Flushed at once, on purpose: the upload completes its idempotency key
     * in the same transaction through plain JDBC, and that row references this
     * one. JDBC does not trigger a Hibernate flush, so without this the foreign
     * key would point at a row not yet written.
     */
    @Override
    @Transactional
    public StoredFile insert(StoredFile file) {
        StoredFileEntity row = StoredFileEntityMapper.toEntity(file);
        try {
            entityManager.persist(row);
            entityManager.flush();
        } catch (PersistenceException | DataIntegrityViolationException failure) {
            if (isUniqueViolation(failure)) {
                throw new DuplicateObjectKeyException(
                        "An object is already stored under this key: " + file.objectKey(), failure);
            }
            // Anything else — a lost connection, a CHECK refusing the row — is not a duplicate:
            // it leaves as it came, and the repository's translation makes it a DataAccessException.
            throw failure;
        }
        return StoredFileEntityMapper.toDomain(row);
    }

    /** PostgreSQL's {@code unique_violation}, wherever the driver's exception sits in the chain. */
    static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFile> findById(FileId id) {
        return storedFileRepository.findById(id.value()).map(StoredFileEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFile> findOwnedBy(FileId id, OwnerId owner) {
        return storedFileRepository.findByIdAndOwnerId(id.value(), owner.value())
                .map(StoredFileEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StoredFile> findPage(FileQuery query, PageQuery page) {
        Page<StoredFileEntity> found = storedFileRepository.search(
                query.owner().value(),
                query.statuses(),
                containsPattern(query.nameContains()),
                PageRequest.of(page.number(), page.size(), orderOf(page.sort())));

        return new PageResult<>(
                found.getContent().stream().map(StoredFileEntityMapper::toDomain).toList(),
                page.number(), page.size(), found.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<FileStatus, Long> countByStatus(FileQuery query) {
        Map<FileStatus, Long> counts = new EnumMap<>(FileStatus.class);
        storedFileRepository.countByStatus(query.owner().value(), query.statuses(), containsPattern(query.nameContains()))
                .forEach(row -> counts.put(row.status(), row.count()));
        return counts;
    }

    /**
     * The requested ordering, always completed by the identifier in the same
     * direction: without a tie-break the order is partial, and two files sharing
     * a sort key can appear on two pages — or on none.
     */
    private static Sort orderOf(FileSort sort) {
        Sort.Direction direction = sort.direction() == FileSort.Direction.ASC
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        String attribute = SORTABLE_ATTRIBUTES.get(sort.field());
        if (attribute == null) {
            // Unreachable while FileSort and this map agree; it fails loudly
            // rather than silently dropping the ordering if they ever do not.
            throw new IllegalStateException("No entity attribute maps the sortable field " + sort.field());
        }
        return Sort.by(direction, attribute).and(Sort.by(direction, "id"));
    }

    /**
     * Turns a search term into a {@code LIKE} pattern, escaping what the caller
     * would otherwise be able to use as a wildcard: {@code 100%} must search for
     * the two characters, not for everything.
     *
     * @return {@code %} when there is no search — it matches every row, which
     *         keeps one query instead of two
     */
    private static String containsPattern(String search) {
        if (search == null) {
            return "%";
        }
        String escaped = search
                .replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingFiles() {
        return storedFileRepository.countByStatusIn(PENDING);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, FileStatus> statusesByObjectKey(Collection<String> objectKeys) {
        Map<String, FileStatus> statuses = new HashMap<>();
        if (objectKeys.isEmpty()) {
            return statuses;
        }
        storedFileRepository.findStatusesByObjectKeys(objectKeys).forEach(row -> statuses.put(row.objectKey(), row.status()));
        return statuses;
    }

    /** Every state in which a file is pending: not yet terminal. */
    private static final Set<FileStatus> PENDING = Arrays.stream(FileStatus.values())
            .filter(status -> !status.isTerminal())
            .collect(Collectors.toUnmodifiableSet());
}
