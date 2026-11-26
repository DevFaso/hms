package com.example.hms.repository;

import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.model.medication.MedicationCatalogVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Go-live D2 (V385): the versions of the medication catalogue. Every read names the hospital it is for
 * ({@code hospitalId} null: the platform's own entries), so a hospital never reads the queue or the history of
 * another.
 */
@Repository
public interface MedicationCatalogVersionRepository extends JpaRepository<MedicationCatalogVersion, UUID> {

    /**
     * One version, only at the hospital it belongs to, locked for the decision: an approval and a withdrawal
     * cannot both land on it.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT v FROM MedicationCatalogVersion v
        WHERE v.id = :id
          AND ((:hospitalId IS NULL AND v.hospitalId IS NULL) OR v.hospitalId = :hospitalId)
        """)
    Optional<MedicationCatalogVersion> findForDecision(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    /** The queue and the decided versions of one hospital, the newest proposal first. */
    @Query(value = """
        SELECT v FROM MedicationCatalogVersion v
        WHERE ((:hospitalId IS NULL AND v.hospitalId IS NULL) OR v.hospitalId = :hospitalId)
          AND (:status IS NULL OR v.status = :status)
          AND (:kind IS NULL OR v.kind = :kind)
        ORDER BY v.proposedAt DESC
        """, countQuery = """
        SELECT COUNT(v) FROM MedicationCatalogVersion v
        WHERE ((:hospitalId IS NULL AND v.hospitalId IS NULL) OR v.hospitalId = :hospitalId)
          AND (:status IS NULL OR v.status = :status)
          AND (:kind IS NULL OR v.kind = :kind)
        """)
    Page<MedicationCatalogVersion> findInScope(@Param("hospitalId") UUID hospitalId,
                                               @Param("status") MedicationCatalogVersionStatus status,
                                               @Param("kind") MedicationCatalogVersionKind kind,
                                               Pageable pageable);

    /** The history of one entry at its hospital, the latest version first. */
    @Query("""
        SELECT v FROM MedicationCatalogVersion v
        WHERE v.catalogItemId = :catalogItemId
          AND ((:hospitalId IS NULL AND v.hospitalId IS NULL) OR v.hospitalId = :hospitalId)
        ORDER BY v.proposedAt DESC
        """)
    List<MedicationCatalogVersion> findHistoryInScope(@Param("catalogItemId") UUID catalogItemId,
                                                      @Param("hospitalId") UUID hospitalId);

    List<MedicationCatalogVersion> findByCatalogItemIdAndKindAndStatus(UUID catalogItemId,
                                                                       MedicationCatalogVersionKind kind,
                                                                       MedicationCatalogVersionStatus status);

    /** The versions awaiting a decision among these entries: one statement for a page of the catalogue. */
    List<MedicationCatalogVersion> findByCatalogItemIdInAndStatus(Collection<UUID> catalogItemIds,
                                                                  MedicationCatalogVersionStatus status);

    @Query("""
        SELECT COALESCE(MAX(v.versionNumber), 0) FROM MedicationCatalogVersion v
        WHERE v.catalogItemId = :catalogItemId AND v.kind = :kind
        """)
    int highestVersionNumber(@Param("catalogItemId") UUID catalogItemId,
                             @Param("kind") MedicationCatalogVersionKind kind);
}
