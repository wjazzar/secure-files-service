package com.praxedo.securefiles.infrastructure.persistence.file.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.praxedo.securefiles.domain.file.model.FileStatus;
import com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue;
import com.praxedo.securefiles.infrastructure.persistence.file.entity.StoredFileEntity;
import com.praxedo.securefiles.infrastructure.persistence.file.projection.ObjectKeyStatus;
import com.praxedo.securefiles.infrastructure.persistence.file.projection.StatusCount;

/**
 * Spring Data over the {@code stored_file} rows — the readable 80 % of the data
 * access.
 *
 * <p><strong>Read only, by construction.</strong> The interface extends
 * {@link Repository}, the marker, rather than {@code JpaRepository}: it offers
 * no inherited {@code save}, {@code delete} or {@code flush}, only the queries
 * declared below. Every state transition goes through {@link JdbcFileWorkQueue},
 * whose statements are conditional, and the one insert goes through the
 * catalogue's entity manager. Adding a mutating method here would open the
 * door that rule B-6 closes.
 *
 * <p>Two things about the search query are deliberate.
 *
 * <p><strong>{@code immutable_unaccent(lower(originalFilename)) like
 * immutable_unaccent(lower(:pattern))}</strong> ignores case and accents
 * ("releve" finds "Relevé.pdf"), and is written that way because that is the
 * exact expression the trigram index of migration {@code V9} is built on. Any
 * other spelling — an {@code ilike}, a comparison on the raw column — would
 * silently stop using it and turn every search into a table scan.
 *
 * <p><strong>The pattern is never null.</strong> "No search" is passed as
 * {@code %}, which matches every row, instead of adding a
 * {@code :pattern is null or …} branch. One code path, one plan shape, and no
 * parameter whose SQL type has to be inferred from a null.
 */
public interface StoredFileJpaRepository extends Repository<StoredFileEntity, UUID> {

    Optional<StoredFileEntity> findById(UUID id);

    Optional<StoredFileEntity> findByIdAndOwnerId(UUID id, String ownerId);

    @Query(value = """
            select f from StoredFileEntity f
             where f.ownerId = :owner
               and f.status in :statuses
               and function('immutable_unaccent' as String, lower(f.originalFilename)) like function('immutable_unaccent' as String, lower(:pattern)) escape '\\'
            """,
            countQuery = """
            select count(f) from StoredFileEntity f
             where f.ownerId = :owner
               and f.status in :statuses
               and function('immutable_unaccent' as String, lower(f.originalFilename)) like function('immutable_unaccent' as String, lower(:pattern)) escape '\\'
            """)
    Page<StoredFileEntity> search(@Param("owner") String owner,
                            @Param("statuses") Collection<FileStatus> statuses,
                            @Param("pattern") String pattern,
                            Pageable pageable);

    @Query("""
            select new com.praxedo.securefiles.infrastructure.persistence.file.projection.StatusCount(f.status, count(f))
              from StoredFileEntity f
             where f.ownerId = :owner
               and f.status in :statuses
               and function('immutable_unaccent' as String, lower(f.originalFilename)) like function('immutable_unaccent' as String, lower(:pattern)) escape '\\'
             group by f.status
            """)
    List<StatusCount> countByStatus(@Param("owner") String owner,
                                    @Param("statuses") Collection<FileStatus> statuses,
                                    @Param("pattern") String pattern);

    long countByStatusIn(Collection<FileStatus> statuses);

    @Query("""
            select new com.praxedo.securefiles.infrastructure.persistence.file.projection.ObjectKeyStatus(f.objectKey, f.status)
              from StoredFileEntity f
             where f.objectKey in :keys
            """)
    List<ObjectKeyStatus> findStatusesByObjectKeys(@Param("keys") Collection<String> keys);
}
