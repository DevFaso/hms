package com.example.hms.repository.integration;

import com.example.hms.model.integration.Dhis2ExportOutbox;
import com.example.hms.model.integration.Dhis2OutboxStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Dhis2ExportOutboxRepository extends JpaRepository<Dhis2ExportOutbox, UUID> {

    List<Dhis2ExportOutbox> findByRun_Id(UUID runId);

    List<Dhis2ExportOutbox> findByRun_IdAndStatus(UUID runId, Dhis2OutboxStatus status);
}
