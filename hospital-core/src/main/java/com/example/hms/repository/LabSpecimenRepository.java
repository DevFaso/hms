package com.example.hms.repository;

import com.example.hms.model.LabSpecimen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabSpecimenRepository extends JpaRepository<LabSpecimen, UUID> {

    List<LabSpecimen> findByLabOrder_Id(UUID labOrderId);

    boolean existsByAccessionNumber(String accessionNumber);

    Optional<LabSpecimen> findByAccessionNumber(String accessionNumber);
}
