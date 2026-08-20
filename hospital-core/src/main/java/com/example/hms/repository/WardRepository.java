package com.example.hms.repository;

import com.example.hms.model.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WardRepository extends JpaRepository<Ward, UUID> {

    List<Ward> findByHospital_Id(UUID hospitalId);

    List<Ward> findByHospital_IdAndActiveTrue(UUID hospitalId);

    /** Tenant guard — single-row lookups go through id + hospital. */
    Optional<Ward> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    boolean existsByHospital_IdAndCodeIgnoreCase(UUID hospitalId, String code);

    boolean existsByHospital_IdAndCodeIgnoreCaseAndIdNot(UUID hospitalId, String code, UUID id);
}
