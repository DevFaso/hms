package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacySale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PharmacySaleRepository extends JpaRepository<PharmacySale, UUID> {

    Page<PharmacySale> findByHospital_Id(UUID hospitalId, Pageable pageable);

    Page<PharmacySale> findByPharmacy_IdAndHospital_Id(UUID pharmacyId, UUID hospitalId, Pageable pageable);

    Page<PharmacySale> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);
}
