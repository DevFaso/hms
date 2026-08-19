package com.example.hms.service.pharmacy;

import com.example.hms.enums.CdsAlertSeverity;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;
import com.example.hms.repository.DrugInteractionRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * P-08 follow-up: unit tests for CdsCheckServiceImpl. Covers the critical
 * severity ladder (CONTRAINDICATED/MAJOR → CRITICAL → requiresOverride),
 * therapeutic-overlap detection, and missing-drug-code defensive behaviour.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CdsCheckServiceImpl")
class CdsCheckServiceImplTest {

    @Mock private DrugInteractionRepository drugInteractionRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private CdsCheckServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
    }

    private Prescription rx(String code, String name) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationCode(code);
        p.setMedicationName(name);
        p.setHospital(hospital);
        p.setStatus(PrescriptionStatus.SIGNED);
        return p;
    }

    private DrugInteraction interaction(String d1Code, String d1Name, String d2Code, String d2Name,
                                        InteractionSeverity sev) {
        DrugInteraction i = new DrugInteraction();
        i.setDrug1Code(d1Code);
        i.setDrug1Name(d1Name);
        i.setDrug2Code(d2Code);
        i.setDrug2Name(d2Name);
        i.setSeverity(sev);
        i.setDescription("test interaction");
        return i;
    }

    @Test
    @DisplayName("returns clear when prescription is null")
    void clearOnNullPrescription() {
        CdsAlertResult result = service.checkAtDispense(null, patientId);
        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.NONE);
        assertThat(result.requiresOverride()).isFalse();
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    @DisplayName("returns INFO (no override) when prescription has no medication code")
    void infoOnMissingDrugCode() {
        Prescription p = rx(null, "Unknown");
        CdsAlertResult result = service.checkAtDispense(p, patientId);
        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.INFO);
        assertThat(result.requiresOverride()).isFalse();
    }

    @Test
    @DisplayName("returns NONE when patient has no other active prescriptions and no interactions")
    void noneWhenNoActivePrescriptions() {
        Prescription p = rx("DRUG-A", "Drug A");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of());
        // codesToCheck.size() < 2 so the interaction repo is never called

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.NONE);
        assertThat(result.requiresOverride()).isFalse();
    }

    @Test
    @DisplayName("flags WARNING for therapeutic overlap (same drug already active)")
    void warningOnTherapeuticOverlap() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription existing = rx("DRUG-A", "Drug A duplicate");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(existing));
        // Note: when prescribed drug code already appears in active codes, the dedup
        // set has size 1 so findInteractionsAmongDrugs is not called — no stub needed.

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.WARNING);
        assertThat(result.requiresOverride()).isFalse();
        assertThat(result.alerts()).anyMatch(a -> a.toLowerCase().contains("chevauchement"));
    }

    @Test
    @DisplayName("flags CRITICAL + requiresOverride for CONTRAINDICATED interaction")
    void criticalOnContraindicated() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription existing = rx("DRUG-B", "Drug B");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(existing));
        when(drugInteractionRepository.findInteractionsAmongDrugs(anyList()))
                .thenReturn(List.of(interaction("DRUG-A", "Drug A", "DRUG-B", "Drug B",
                        InteractionSeverity.CONTRAINDICATED)));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.CRITICAL);
        assertThat(result.requiresOverride()).isTrue();
    }

    @Test
    @DisplayName("MAJOR interaction also escalates to CRITICAL + requiresOverride")
    void criticalOnMajor() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription existing = rx("DRUG-B", "Drug B");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(existing));
        when(drugInteractionRepository.findInteractionsAmongDrugs(anyList()))
                .thenReturn(List.of(interaction("DRUG-A", "Drug A", "DRUG-B", "Drug B",
                        InteractionSeverity.MAJOR)));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.CRITICAL);
        assertThat(result.requiresOverride()).isTrue();
    }

    @Test
    @DisplayName("MODERATE interaction maps to WARNING, not requiresOverride")
    void warningOnModerate() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription existing = rx("DRUG-B", "Drug B");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(existing));
        when(drugInteractionRepository.findInteractionsAmongDrugs(anyList()))
                .thenReturn(List.of(interaction("DRUG-A", "Drug A", "DRUG-B", "Drug B",
                        InteractionSeverity.MODERATE)));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.WARNING);
        assertThat(result.requiresOverride()).isFalse();
    }

    @Test
    @DisplayName("escalates to the highest severity when multiple interactions are present")
    void escalatesToHighestSeverity() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription b = rx("DRUG-B", "Drug B");
        Prescription c = rx("DRUG-C", "Drug C");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(b, c));
        when(drugInteractionRepository.findInteractionsAmongDrugs(anyList()))
                .thenReturn(List.of(
                        interaction("DRUG-A", "Drug A", "DRUG-B", "Drug B", InteractionSeverity.MODERATE),
                        interaction("DRUG-A", "Drug A", "DRUG-C", "Drug C", InteractionSeverity.MAJOR)
                ));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.CRITICAL);
        assertThat(result.alerts()).hasSize(2);
    }

    @Test
    @DisplayName("ignores interactions that don't involve the prescribed drug")
    void ignoresUnrelatedInteractions() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription b = rx("DRUG-B", "Drug B");
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(b));
        // Repo returns an interaction between B and C — but C isn't even in the picture for this dispense
        when(drugInteractionRepository.findInteractionsAmongDrugs(anyList()))
                .thenReturn(List.of(interaction("DRUG-B", "Drug B", "DRUG-C", "Drug C",
                        InteractionSeverity.CONTRAINDICATED)));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.NONE);
        assertThat(result.requiresOverride()).isFalse();
    }

    @Test
    @DisplayName("ignores prescriptions in non-active states (e.g. DRAFT, CANCELLED)")
    void ignoresInactivePrescriptions() {
        Prescription p = rx("DRUG-A", "Drug A");
        Prescription draft = rx("DRUG-A", "Drug A draft");
        draft.setStatus(PrescriptionStatus.DRAFT);
        Prescription cancelled = rx("DRUG-A", "Drug A cancelled");
        cancelled.setStatus(PrescriptionStatus.CANCELLED);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId))
                .thenReturn(List.of(draft, cancelled));

        CdsAlertResult result = service.checkAtDispense(p, patientId);

        // No active overlap, no interactions queried
        assertThat(result.severity()).isEqualTo(CdsAlertSeverity.NONE);
    }
}
