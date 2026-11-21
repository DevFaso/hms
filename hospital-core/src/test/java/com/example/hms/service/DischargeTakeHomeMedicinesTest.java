package com.example.hms.service;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.discharge.MedicationReconciliationEntry;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D2553 (round 65): a stay's take-home orders (« Traitement de sortie ») reach its discharge
 * summary whenever they are signed: on the stay, or within 24 h after the discharge on an
 * outpatient anchor. A finalized summary is never changed; an order is listed once.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DischargeTakeHomeMedicinesTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AdmissionRepository admissionRepository;

    private DischargeTakeHomeMedicines carrier;
    private Patient patient;
    private Hospital hospital;
    private Admission stay;
    private Encounter stayEncounter;
    private DischargeSummary summary;
    private final LocalDateTime discharged = LocalDateTime.of(2026, 11, 19, 10, 0);

    @BeforeEach
    void setUp() {
        carrier = new DischargeTakeHomeMedicines(prescriptionRepository, dischargeSummaryRepository,
            encounterRepository, admissionRepository);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setPatient(patient);
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(discharged.minusDays(3));
        stay.setActualDischargeDateTime(discharged);
        stayEncounter = new Encounter();
        stayEncounter.setId(UUID.randomUUID());
        stayEncounter.setAdmissionId(stay.getId());
        stayEncounter.setPatient(patient);
        stayEncounter.setHospital(hospital);
        summary = new DischargeSummary();
        summary.setEncounter(stayEncounter);
        summary.setMedicationReconciliation(new ArrayList<>());
        when(admissionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(stay));
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(stay.getId()))
            .thenReturn(Optional.of(stayEncounter));
        when(dischargeSummaryRepository.findByEncounter_Id(stayEncounter.getId())).thenReturn(Optional.of(summary));
        when(prescriptionRepository.findByEncounter_Id(eq(stayEncounter.getId()), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of());
    }

    private Prescription rx(String name, boolean takeHome, PrescriptionStatus status, Encounter on, LocalDateTime written) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName(name);
        rx.setDosage("500 mg");
        rx.setFrequency("3 fois par jour");
        rx.setTakeHome(takeHome);
        rx.setStatus(status);
        rx.setPatient(patient);
        rx.setHospital(hospital);
        rx.setEncounter(on);
        rx.setCreatedAt(written);
        return rx;
    }

    private Encounter anchor() {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        return e;
    }

    private List<String> listed() {
        return summary.getMedicationReconciliation().stream().map(MedicationReconciliationEntry::getMedicationName).toList();
    }

    @Test
    void d2553_aTakeHomeOrderSignedAfterTheDischargeJoinsTheStaysSummary() {
        Prescription paracetamol = rx("Paracétamol", true, PrescriptionStatus.SIGNED, anchor(), discharged.plusHours(2));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(paracetamol));

        carrier.onSigned(paracetamol);

        assertThat(listed()).containsExactly("Paracétamol");
        assertThat(summary.getMedicationReconciliation().get(0).getPrescriptionId()).isEqualTo(paracetamol.getId());
        assertThat(summary.getMedicationReconciliation().get(0).getContinueAtDischarge()).isTrue();
        verify(dischargeSummaryRepository).save(summary);
    }

    @Test
    void aTakeHomeOrderSignedOnTheStayJoinsASummaryWrittenBeforeIt() {
        Prescription amox = rx("Amoxicilline", true, PrescriptionStatus.SIGNED, stayEncounter, discharged.minusHours(5));
        Prescription ward = rx("Ceftriaxone", false, PrescriptionStatus.SIGNED, stayEncounter, discharged.minusDays(2));
        when(prescriptionRepository.findByEncounter_Id(eq(stayEncounter.getId()), any()))
            .thenReturn(new PageImpl<>(List.of(amox, ward)));

        carrier.onSigned(amox);
        carrier.onSigned(amox);

        assertThat(listed()).containsExactly("Amoxicilline");
    }

    @Test
    void anOrderLongAfterTheDischargeOrDuringALaterStayIsNotTheStays() {
        Prescription late = rx("Paracétamol", true, PrescriptionStatus.SIGNED, anchor(), discharged.plusHours(30));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(late));
        carrier.onSigned(late);
        assertThat(listed()).isEmpty();

        Admission readmitted = new Admission();
        readmitted.setId(UUID.randomUUID());
        readmitted.setAdmissionDateTime(discharged.plusHours(1));
        when(admissionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(stay, readmitted));
        Prescription duringNewStay = rx("Ibuprofène", true, PrescriptionStatus.SIGNED, anchor(), discharged.plusHours(3));
        carrier.onSigned(duringNewStay);
        assertThat(listed()).isEmpty();
        verify(dischargeSummaryRepository, never()).save(any());
    }

    @Test
    void aFinalizedSummaryAWardOrderAndAnUnsignedOrderAreLeftAlone() {
        Prescription draft = rx("Brouillon", true, PrescriptionStatus.DRAFT, anchor(), discharged.plusHours(1));
        Prescription ward = rx("Ceftriaxone", false, PrescriptionStatus.SIGNED, anchor(), discharged.plusHours(1));
        Prescription stopped = rx("Arrêté", true, PrescriptionStatus.DISCONTINUED, anchor(), discharged.plusHours(1));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(draft, ward, stopped));
        carrier.onSigned(ward);
        carrier.onSigned(draft);
        carrier.onSigned(stopped);
        assertThat(listed()).isEmpty();

        Prescription home = rx("Paracétamol", true, PrescriptionStatus.SIGNED, anchor(), discharged.plusHours(1));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId()))
            .thenReturn(List.of(home));
        summary.setIsFinalized(true);
        carrier.onSigned(home);
        assertThat(listed()).isEmpty();
        verify(dischargeSummaryRepository, never()).save(any());
    }

    @Test
    void anEntryTypedByHandWithTheSameDrugIsNotListedTwice() {
        summary.addMedicationReconciliation(MedicationReconciliationEntry.builder().medicationName("paracétamol ").build());
        Prescription home = rx("Paracétamol", true, PrescriptionStatus.SIGNED, stayEncounter, discharged.minusHours(1));
        when(prescriptionRepository.findByEncounter_Id(eq(stayEncounter.getId()), any()))
            .thenReturn(new PageImpl<>(List.of(home)));

        assertThat(carrier.carryInto(summary)).isFalse();
        assertThat(summary.getMedicationReconciliation()).hasSize(1);
    }
}
