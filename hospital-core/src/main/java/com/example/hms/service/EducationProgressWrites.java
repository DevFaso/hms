package com.example.hms.service;

import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.repository.PatientEducationProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The insert of a patient's progress row on an education resource, in its own
 * transaction. Since V176 there is one row per (patient, resource)
 * ({@code uk_patient_education_progress_patient_resource}); when two first
 * requests race, the loser's insert meets that key, rolls back only this
 * inner transaction and reaches the caller as a
 * {@code DataIntegrityViolationException}, and the caller reads and updates
 * the winner's row. Done in the caller's transaction instead, the failed
 * flush would mark it rollback-only and the request would end as a 500.
 */
@Component
@RequiredArgsConstructor
public class EducationProgressWrites {

    private final PatientEducationProgressRepository progressRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRow(UUID patientId, UUID resourceId, UUID hospitalId,
                          EducationComprehensionStatus initialStatus) {
        PatientEducationProgress row = new PatientEducationProgress();
        row.setPatientId(patientId);
        row.setResourceId(resourceId);
        row.setHospitalId(hospitalId);
        row.setStartedAt(LocalDateTime.now());
        row.setAccessCount(0);
        row.setTimeSpentSeconds(0L);
        row.setComprehensionStatus(initialStatus);
        progressRepository.saveAndFlush(row);
    }
}
