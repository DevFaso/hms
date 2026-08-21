package com.example.hms.repository.scheduling;

import com.example.hms.model.scheduling.VisitType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitTypeRepository extends JpaRepository<VisitType, UUID> {

    List<VisitType> findByHospital_IdAndActiveTrueOrderByNameAsc(UUID hospitalId);

    Optional<VisitType> findByHospital_IdAndCodeIgnoreCase(UUID hospitalId, String code);
}
