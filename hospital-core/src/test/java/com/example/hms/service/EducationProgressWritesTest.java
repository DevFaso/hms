package com.example.hms.service;

import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.repository.PatientEducationProgressRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The insert joins the caller's transaction, against a schema that carries
 * {@code uk_patient_education_progress_patient_resource}: the row exists only
 * if the caller commits, and a second insert for the same (patient, resource)
 * is a no-op that leaves the caller's transaction usable.
 *
 * <p>Not wrapped in the usual test transaction: each test drives its own with
 * a TransactionTemplate, so commits and rollbacks are real. Rows are removed
 * after each test.
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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID patientId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @AfterEach
    void removeRows() {
        repository.deleteAll(repository.findByPatientIdAndResourceId(patientId, resourceId));
    }

    @Test
    void createsTheRowWithTheDefaultsTheEntityRequires() {
        Boolean created = inTransaction().execute(status ->
            writes.insertIfAbsent(patientId, resourceId, hospitalId, EducationComprehensionStatus.IN_PROGRESS));

        assertThat(created).isTrue();
        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).singleElement()
            .satisfies(row -> {
                assertThat(row.getHospitalId()).isEqualTo(hospitalId);
                assertThat(row.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.IN_PROGRESS);
                assertThat(row.getProgressPercentage()).isZero();
                assertThat(row.getAccessCount()).isZero();
                assertThat(row.getTimeSpentSeconds()).isZero();
                assertThat(row.getStartedAt()).isNotNull();
                assertThat(row.getCreatedAt()).isNotNull();
            });
    }

    @Test
    void whenTheRequestFailsAfterTheInsert_noRowIsLeftBehind() {
        TransactionTemplate tx = inTransaction();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writes.insertIfAbsent(patientId, resourceId, hospitalId, EducationComprehensionStatus.NOT_STARTED);
            throw new IllegalStateException("a later step of the request failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).isEmpty();
    }

    @Test
    void whenAnotherRequestCreatedTheRowFirst_theInsertIsANoOpAndTheTransactionStaysUsable() {
        // The winner's request has committed its row.
        inTransaction().executeWithoutResult(status ->
            writes.insertIfAbsent(patientId, resourceId, hospitalId, EducationComprehensionStatus.NOT_STARTED));

        // The loser saw no row, inserts, meets the key, and carries on in the
        // same transaction: it reads the winner's row and updates it.
        inTransaction().executeWithoutResult(status -> {
            assertThat(writes.insertIfAbsent(patientId, resourceId, hospitalId,
                EducationComprehensionStatus.IN_PROGRESS)).isFalse();
            PatientEducationProgress winner = repository.findByPatientIdAndResourceId(patientId, resourceId)
                .getFirst();
            winner.setProgressPercentage(30);
            repository.saveAndFlush(winner);
        });

        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).singleElement()
            .satisfies(row -> {
                assertThat(row.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.NOT_STARTED);
                assertThat(row.getProgressPercentage()).isEqualTo(30);
            });
    }

    @Test
    void aFailureOtherThanTheKeyIsNotSwallowed() {
        // hospital_id is NOT NULL: a refusal that is not the unique key.
        TransactionTemplate tx = inTransaction();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
            writes.insertIfAbsent(patientId, resourceId, null, EducationComprehensionStatus.NOT_STARTED)))
            .isInstanceOf(RuntimeException.class);
        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).isEmpty();
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() ->
            writes.insertIfAbsent(patientId, resourceId, hospitalId, EducationComprehensionStatus.NOT_STARTED))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }
}
