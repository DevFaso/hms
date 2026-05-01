package com.example.hms.repository.integration;

import com.example.hms.model.integration.Dhis2ExportRun;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Dhis2ExportRunRepository extends JpaRepository<Dhis2ExportRun, UUID> {

    Page<Dhis2ExportRun> findByHospital_IdOrderByStartedAtDesc(UUID hospitalId, Pageable pageable);
}
