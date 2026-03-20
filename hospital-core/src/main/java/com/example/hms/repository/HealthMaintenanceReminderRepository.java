package com.example.hms.repository;

import com.example.hms.enums.HealthMaintenanceReminderStatus;
import com.example.hms.model.HealthMaintenanceReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HealthMaintenanceReminderRepository extends JpaRepository<HealthMaintenanceReminder, UUID> {

    List<HealthMaintenanceReminder> findByPatientIdAndActiveTrue(UUID patientId);

    List<HealthMaintenanceReminder> findByPatientIdAndStatusAndActiveTrue(
            UUID patientId, HealthMaintenanceReminderStatus status);

    boolean existsByPatientIdAndId(UUID patientId, UUID id);
}
