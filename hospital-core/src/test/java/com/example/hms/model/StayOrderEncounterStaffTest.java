package com.example.hms.model;

import com.example.hms.enums.EncounterType;
import com.example.hms.enums.LabOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D703 / D736 (r10 re-test): an order on a hospital stay by any treating
 * prescriber of its hospital other than the stay's lead (the admitting
 * doctor, a colleague) passed every service check and then died with a 500
 * in the entity's {@code @PrePersist}: "Encounter.staff must match
 * Prescription.staff". These run the entity callbacks the database save runs.
 */
class StayOrderEncounterStaffTest {

    private Hospital hospital;
    private Patient patient;
    private Staff attending;
    private Staff admitting;
    private UserRoleHospitalAssignment admittingAssignment;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        attending = staff();
        admitting = staff();
        admittingAssignment = new UserRoleHospitalAssignment();
        admittingAssignment.setId(UUID.randomUUID());
        admittingAssignment.setHospital(hospital);
    }

    private Staff staff() {
        Staff s = new Staff();
        s.setId(UUID.randomUUID());
        s.setHospital(hospital);
        return s;
    }

    /** The stay's encounter: led by the attending, linked to the admission. */
    private Encounter stayLedBy(Staff lead) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setHospital(hospital);
        e.setPatient(patient);
        e.setStaff(lead);
        e.setEncounterType(EncounterType.INPATIENT);
        e.setAdmissionId(UUID.randomUUID());
        return e;
    }

    private Encounter visitOf(Staff owner) {
        Encounter e = stayLedBy(owner);
        e.setAdmissionId(null);
        e.setEncounterType(EncounterType.CONSULTATION);
        return e;
    }

    private Prescription prescriptionBy(Staff prescriber, Encounter encounter) {
        Prescription rx = new Prescription();
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setStaff(prescriber);
        rx.setAssignment(admittingAssignment);
        rx.setEncounter(encounter);
        rx.setMedicationName("Artesunate 60 mg");
        return rx;
    }

    private LabOrder labOrderBy(Staff orderer, Encounter encounter) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        LabOrder order = LabOrder.builder()
            .patient(patient)
            .orderingStaff(orderer)
            .assignment(admittingAssignment)
            .hospital(hospital)
            .encounter(encounter)
            .labTestDefinition(definition)
            .status(LabOrderStatus.ORDERED)
            .orderDatetime(LocalDateTime.now())
            .clinicalIndication("Fever")
            .build();
        order.setId(UUID.randomUUID());
        return order;
    }

    @Test
    void theAdmittingDoctorsPrescriptionOnTheAttendingsStayPersists() {
        Prescription rx = prescriptionBy(admitting, stayLedBy(attending));

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(rx, "validate")).doesNotThrowAnyException();
    }

    @Test
    void aPrescriptionOnSomeoneElsesOutpatientVisitIsStillRefused() {
        Prescription rx = prescriptionBy(admitting, visitOf(attending));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(rx, "validate"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Encounter.staff");
    }

    @Test
    void aColleaguesLabOrderOnTheStayPersists() {
        LabOrder order = labOrderBy(admitting, stayLedBy(attending));

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(order, "validate")).doesNotThrowAnyException();
    }

    @Test
    void aLabOrderOnSomeoneElsesOutpatientVisitIsStillRefused() {
        LabOrder order = labOrderBy(admitting, visitOf(attending));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(order, "validate"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Encounter.staff");
    }

    /**
     * Guard: every entity invariant that pins an order to its encounter's
     * lead must waive a stay, or the next order type ordered on a stay by a
     * colleague fails the same way at save time.
     */
    @Test
    void everyEncounterLeadInvariantWaivesAStay() throws IOException {
        Path models = Path.of("src/main/java/com/example/hms/model");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(models)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains("encounter.getStaff().getId()") && !line.contains("getAdmissionId()")
                        && (i == 0 || !lines.get(i - 1).contains("getAdmissionId()"))) {
                        offenders.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders).as("encounter-lead checks without a stay waiver").isEmpty();
    }
}
