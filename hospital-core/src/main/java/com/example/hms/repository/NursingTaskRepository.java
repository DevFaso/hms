package com.example.hms.repository;

import com.example.hms.enums.NursingTaskStatus;
import com.example.hms.model.NursingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NursingTaskRepository extends JpaRepository<NursingTask, UUID> {

    List<NursingTask> findByHospital_IdOrderByDueAtAsc(UUID hospitalId);

    List<NursingTask> findByHospital_IdAndStatusOrderByDueAtAsc(UUID hospitalId, NursingTaskStatus status);

    List<NursingTask> findByHospital_IdAndStatusNotOrderByDueAtAsc(UUID hospitalId, NursingTaskStatus status);

    List<NursingTask> findByPatient_IdAndHospital_IdOrderByDueAtAsc(UUID patientId, UUID hospitalId);

    Optional<NursingTask> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    long countByHospital_IdAndStatusAndDueAtBefore(UUID hospitalId, NursingTaskStatus status, LocalDateTime now);

    /** Tasks assigned to a specific staff member. */
    List<NursingTask> findByAssignedToStaff_IdAndStatusNotOrderByDueAtAsc(UUID staffId, NursingTaskStatus excludeStatus);

    /** Overdue tasks eligible for escalation. */
    List<NursingTask> findByHospital_IdAndStatusAndSlaDeadlineBefore(UUID hospitalId, NursingTaskStatus status, LocalDateTime now);

    /** Tasks by focus (e.g., all tasks linked to a specific prescription). */
    List<NursingTask> findByFocusTypeAndFocusId(String focusType, UUID focusId);
}
