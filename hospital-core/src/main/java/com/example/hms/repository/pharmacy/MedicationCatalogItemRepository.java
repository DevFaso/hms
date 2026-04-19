package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.MedicationCatalogItem;
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

    Page<MedicationCatalogItem> findByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    @Query("SELECT m FROM MedicationCatalogItem m WHERE m.hospital.id = :hospitalId " +
           "AND m.active = true " +
           "AND (LOWER(m.nameFr) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(m.genericName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(m.code) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<MedicationCatalogItem> searchByHospital(
        @Param("hospitalId") UUID hospitalId,
        @Param("search") String search,
        Pageable pageable);

    Optional<MedicationCatalogItem> findByHospitalIdAndCode(UUID hospitalId, String code);
}
