package com.example.hms.repository;

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

    List<NursingTask> findByHospital_IdAndStatusOrderByDueAtAsc(UUID hospitalId, String status);

    List<NursingTask> findByHospital_IdAndStatusNotOrderByDueAtAsc(UUID hospitalId, String status);

    List<NursingTask> findByPatient_IdAndHospital_IdOrderByDueAtAsc(UUID patientId, UUID hospitalId);

    Optional<NursingTask> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    /** QA D294: the patient's tasks still open when the stay closes. */
    List<NursingTask> findByPatient_IdAndHospital_IdAndStatus(UUID patientId, UUID hospitalId, String status);

    long countByHospital_IdAndStatusAndDueAtBefore(UUID hospitalId, String status, LocalDateTime now);
}
