package com.example.hms.repository;

import com.example.hms.model.medication.MedicationCatalogItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicationCatalogItemRepository extends JpaRepository<MedicationCatalogItem, UUID> {

    Page<MedicationCatalogItem> findByHospital_IdAndActiveTrue(UUID hospitalId, Pageable pageable);

    @Query("SELECT m FROM MedicationCatalogItem m WHERE m.hospital.id = :hospitalId AND m.active = true " +
           "AND (LOWER(m.nameFr) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(m.genericName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(m.brandName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(m.atcCode) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<MedicationCatalogItem> searchByHospital(
            @Param("hospitalId") UUID hospitalId,
            @Param("search") String search,
            Pageable pageable);

    Page<MedicationCatalogItem> findByHospital_IdAndCategoryAndActiveTrue(UUID hospitalId, String category, Pageable pageable);

    Optional<MedicationCatalogItem> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    boolean existsByAtcCodeAndHospital_Id(String atcCode, UUID hospitalId);

    Optional<MedicationCatalogItem> findByHospitalIdAndCode(UUID hospitalId, String code);

    /**
     * Batch lookup used by {@link com.example.hms.cdshooks.rules.CdsRuleEngine}
     * to resolve the RxNorm of every active prescription on a patient
     * in a single query (avoids N+1 over the catalog).
     */
    @Query("SELECT m FROM MedicationCatalogItem m WHERE m.hospital.id = :hospitalId AND m.code IN :codes")
    java.util.List<MedicationCatalogItem> findByHospitalIdAndCodeIn(
            @Param("hospitalId") UUID hospitalId,
            @Param("codes") java.util.Collection<String> codes);

    /**
     * RxNorm-keyed lookup added in V93 for the {@code order-select} and
     * {@code medication-prescribe} CDS hooks. Backed by the partial index
     * {@code idx_med_catalog_rxnorm_active} so it stays O(log n) on the
     * active subset. Returns the first active row whose RxCUI matches —
     * the catalog model does not enforce uniqueness on rxnorm_code (a
     * single drug can have multiple SKUs / strengths sharing a code), so
     * callers are expected to treat the match as a representative entry
     * and rely on the rule engine for SKU-level decisions.
     */
    @Query("SELECT m FROM MedicationCatalogItem m " +
           "WHERE m.hospital.id = :hospitalId AND m.rxnormCode = :rxnormCode AND m.active = true")
    java.util.List<MedicationCatalogItem> findActiveByHospitalIdAndRxnormCode(
            @Param("hospitalId") UUID hospitalId,
            @Param("rxnormCode") String rxnormCode);
}
