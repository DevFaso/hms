package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.Pharmacy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PharmacyRepository extends JpaRepository<Pharmacy, UUID> {

    Page<Pharmacy> findByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    List<Pharmacy> findByHospitalIdAndActiveTrue(UUID hospitalId);

    List<Pharmacy> findByHospitalIdAndActiveTrueOrderByNameAsc(UUID hospitalId);
}
