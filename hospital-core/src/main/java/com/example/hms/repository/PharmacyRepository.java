package com.example.hms.repository;

import com.example.hms.model.pharmacy.Pharmacy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PharmacyRepository extends JpaRepository<Pharmacy, UUID> {

    Page<Pharmacy> findByHospital_IdAndActiveTrue(UUID hospitalId, Pageable pageable);

    @Query("SELECT p FROM Pharmacy p WHERE p.hospital.id = :hospitalId AND p.active = true " +
           "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(p.city) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Pharmacy> searchByHospital(
            @Param("hospitalId") UUID hospitalId,
            @Param("search") String search,
            Pageable pageable);

    Page<Pharmacy> findByHospital_IdAndTierAndActiveTrue(UUID hospitalId, int tier, Pageable pageable);

    Optional<Pharmacy> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    Optional<Pharmacy> findByLicenseNumberAndHospital_Id(String licenseNumber, UUID hospitalId);
}
