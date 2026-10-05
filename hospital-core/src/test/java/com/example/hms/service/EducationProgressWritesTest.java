package com.example.hms.service;

import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.repository.PatientEducationProgressRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The insert really runs in its own transaction against a schema that carries
 * {@code uk_patient_education_progress_patient_resource}: the second insert
 * for the same (patient, resource) is refused as a
 * {@code DataIntegrityViolationException} the caller can catch, and the row
 * the first one committed is the only one there.
 *
 * <p>Not wrapped in the usual test transaction, so each write commits the way
 * it does in production; the rows are removed after each test.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(EducationProgressWrites.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EducationProgressWritesTest {

    @Autowired
    private EducationProgressWrites writes;

    @Autowired
    private PatientEducationProgressRepository repository;

    private final UUID patientId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @AfterEach
    void removeRows() {
        repository.deleteAll(repository.findByPatientIdAndResourceId(patientId, resourceId));
    }

    @Test
    void createsTheRowWithTheDefaultsTheEntityRequires() {
        writes.createRow(patientId, resourceId, hospitalId, EducationComprehensionStatus.IN_PROGRESS);

        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).singleElement()
            .satisfies(row -> {
                assertThat(row.getHospitalId()).isEqualTo(hospitalId);
                assertThat(row.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.IN_PROGRESS);
                assertThat(row.getProgressPercentage()).isZero();
                assertThat(row.getAccessCount()).isZero();
                assertThat(row.getTimeSpentSeconds()).isZero();
                assertThat(row.getStartedAt()).isNotNull();
            });
    }

    @Test
    void aSecondInsertForTheSamePatientAndResourceIsRefusedAndLeavesOneRow() {
        writes.createRow(patientId, resourceId, hospitalId, EducationComprehensionStatus.NOT_STARTED);

        assertThatThrownBy(() ->
            writes.createRow(patientId, resourceId, hospitalId, EducationComprehensionStatus.NOT_STARTED))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId))
            .extracting(PatientEducationProgress::getComprehensionStatus)
            .containsExactly(EducationComprehensionStatus.NOT_STARTED);
    }
}
