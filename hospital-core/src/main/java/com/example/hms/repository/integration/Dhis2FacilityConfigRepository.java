package com.example.hms.repository.integration;

import com.example.hms.model.integration.Dhis2FacilityConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Dhis2FacilityConfigRepository extends JpaRepository<Dhis2FacilityConfig, UUID> {

    Optional<Dhis2FacilityConfig> findByHospital_Id(UUID hospitalId);

    Optional<Dhis2FacilityConfig> findByHospital_IdAndActiveTrue(UUID hospitalId);

    List<Dhis2FacilityConfig> findByActiveTrue();
}
