package com.example.hms.service.allergy;

import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2220, on H2 with the real backfill, worker, importer, ledger and summary
 * sync: the legacy free-text import runs at most once per patient, durably,
 * whatever it created, and never brings back an allergy a clinician removed.
 *
 * <p>Not transactional: the worker commits each patient in its own
 * {@code REQUIRES_NEW} transaction, as on a real start. The H2 database may be
 * shared with other cached slices, so assertions read this test's patients
 * only, never the pass's global counts.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LegacyAllergyTextBackfill.class, LegacyAllergyTextBackfillWorker.class, LegacyAllergyTextImporter.class,
    LegacyAllergyImportLedger.class, PatientAllergySummarySync.class,
    LegacyAllergyTextBackfillRerunTest.FixedClock.class})
@DisplayName("D2220: the legacy allergy backfill runs once per patient")
class LegacyAllergyTextBackfillRerunTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired private LegacyAllergyTextBackfill backfill;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientAllergyRepository allergyRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("two passes over legacy text create the rows once; the second pass does not touch the patient")
    void twoPassesCreateRowsOnce() {
        Hospital hospital = hospital();
        Patient patient = patient(hospital, "Arachide, latex");

        backfill.pass();
        assertThat(displays(patient)).containsExactlyInAnyOrder("Arachide", "latex");
        assertThat(ledgerOutcome(patient)).isEqualTo("IMPORTED");
        assertThat(ledgerRows(patient)).isEqualTo(2);

        backfill.pass();
        assertThat(displays(patient)).hasSize(2);
        assertThat(candidates()).doesNotContain(patient.getId());
    }

    @Test
    @DisplayName("text that only repeats hand-recorded allergies is NOTHING_NEW, recorded, and not a candidate again")
    void handRecordedAllergiesAreNothingNew() {
        Hospital hospital = hospital();
        Patient patient = patient(hospital, "Arachide");
        allergy(patient, hospital, "Arachide", true, AllergyVerificationStatus.CONFIRMED);

        assertThat(candidates()).contains(patient.getId());
        backfill.pass();

        assertThat(displays(patient)).containsExactly("Arachide");
        assertThat(ledgerOutcome(patient)).isEqualTo("NOTHING_NEW");
        assertThat(ledgerRows(patient)).isZero();
        assertThat(candidates()).doesNotContain(patient.getId());
    }

    @Test
    @DisplayName("an allergy deactivated or marked entered in error after the import is not re-created by a later pass")
    void aRemovedAllergyStaysRemoved() {
        Hospital hospital = hospital();
        Patient patient = patient(hospital, "Arachide, latex");
        backfill.pass();

        // A clinician removes both, but the old text is still in the column
        // (a path that did not refresh the summary, or the text as it was).
        for (PatientAllergy row : allergyRepository.findByPatient_Id(patient.getId())) {
            if ("latex".equals(row.getAllergenDisplay())) {
                row.setActive(false);
            } else {
                row.setVerificationStatus(AllergyVerificationStatus.ENTERED_IN_ERROR);
            }
            allergyRepository.save(row);
        }
        jdbc.update("UPDATE clinical.patients SET allergies = ? WHERE id = ?", "Arachide, latex", patient.getId());

        backfill.pass();
        backfill.pass();

        List<PatientAllergy> rows = allergyRepository.findByPatient_Id(patient.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).noneMatch(r -> r.isActive()
            && r.getVerificationStatus() != AllergyVerificationStatus.ENTERED_IN_ERROR);
        assertThat(ledgerOutcome(patient)).isEqualTo("IMPORTED");
    }

    @Test
    @DisplayName("legacy text naming a hand-recorded allergy that was deactivated or entered in error creates nothing")
    void handRecordedRemovedAllergiesAreNotResurrected() {
        Hospital hospital = hospital();
        Patient patient = patient(hospital, "Latex, Pénicilline");
        allergy(patient, hospital, "latex", false, AllergyVerificationStatus.CONFIRMED);
        allergy(patient, hospital, "Penicilline", true, AllergyVerificationStatus.ENTERED_IN_ERROR);

        backfill.pass();

        List<PatientAllergy> rows = allergyRepository.findByPatient_Id(patient.getId());
        assertThat(rows).hasSize(2);
        assertThat(ledgerOutcome(patient)).isEqualTo("NOTHING_NEW");
    }

    private List<String> displays(Patient patient) {
        return allergyRepository.findByPatient_Id(patient.getId()).stream()
            .map(PatientAllergy::getAllergenDisplay).toList();
    }

    private List<UUID> candidates() {
        return jdbc.query(LegacyAllergyTextBackfill.CANDIDATES_SQL, (rs, i) -> rs.getObject(1, UUID.class));
    }

    private String ledgerOutcome(Patient patient) {
        return jdbc.queryForObject("SELECT outcome FROM clinical.patient_allergy_legacy_imports WHERE patient_id = ?",
            String.class, patient.getId());
    }

    private Integer ledgerRows(Patient patient) {
        return jdbc.queryForObject("SELECT rows_created FROM clinical.patient_allergy_legacy_imports WHERE patient_id = ?",
            Integer.class, patient.getId());
    }

    private Hospital hospital() {
        String n = suffix();
        return hospitalRepository.save(Hospital.builder()
            .name("Legacy " + n).code("LA" + n).address("1 Main St").phoneNumber("555" + n).build());
    }

    private Patient patient(Hospital hospital, String legacyText) {
        String n = suffix();
        User user = userRepository.save(User.builder()
            .username("leg" + n).email("leg" + n + "@example.test").passwordHash("x")
            .phoneNumber("7" + Math.abs(n.hashCode())).isActive(true).build());
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Legacy").lastName("Patient").dateOfBirth(LocalDate.now().minusYears(30))
            .gender("FEMALE").phoneNumberPrimary("6" + Math.abs(n.hashCode())).email("lp" + n + "@example.test")
            .hospitalId(hospital.getId()).user(user).build());
        // The pre-#56 text, written straight to the column as the old code did.
        jdbc.update("UPDATE clinical.patients SET allergies = ? WHERE id = ?", legacyText, saved.getId());
        return saved;
    }

    private void allergy(Patient patient, Hospital hospital, String display, boolean active,
                         AllergyVerificationStatus status) {
        allergyRepository.save(PatientAllergy.builder()
            .patient(patient).hospital(hospital).allergenDisplay(display)
            .verificationStatus(status).active(active).build());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
