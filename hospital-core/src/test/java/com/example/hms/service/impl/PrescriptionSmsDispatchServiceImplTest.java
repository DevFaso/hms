package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.prescription.PrescriptionTransmission;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionSmsDispatchServiceImpl")
class PrescriptionSmsDispatchServiceImplTest {

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private PrescriptionTransmissionRepository transmissionRepository;
    @Mock private SmsService smsService;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    @InjectMocks private PrescriptionSmsDispatchServiceImpl service;

    private UUID prescriptionId;
    private UUID pharmacyId;
    private UUID hospitalId;
    private Prescription rx;
    private Pharmacy pharmacy;

    @BeforeEach
    void setUp() {
        prescriptionId = UUID.randomUUID();
        pharmacyId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Doe");

        rx = new Prescription();
        rx.setId(prescriptionId);
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setMedicationName("Metformin");
        rx.setDosage("500");
        rx.setDoseUnit("mg");
        rx.setRoute("PO");
        rx.setFrequency("BID");
        rx.setDuration("30 days");
        rx.setInstructions("Take with food");

        pharmacy = new Pharmacy();
        pharmacy.setId(pharmacyId);
        pharmacy.setName("Pharmacie Centrale");
        pharmacy.setPhoneNumber("+22670111222");
        pharmacy.setPharmacyType(PharmacyType.COMMUNITY_PHARMACY);
        pharmacy.setHospital(hospital);
    }

    @Test
    @DisplayName("happy path — sends SMS, persists transmission, updates prescription dispatch fields")
    void dispatch_happyPath() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> {
                    PrescriptionTransmission t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return t;
                });

        PrescriptionSmsDispatchResponseDTO result = service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).note("priority").build());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111222"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .contains("Metformin")
                .contains("500mg")
                .contains("Note: priority");
        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(rx.getDispatchChannel()).isEqualTo("SMS");
        assertThat(rx.getDispatchStatus()).isEqualTo("SENT");
        assertThat(rx.getPharmacyId()).isEqualTo(pharmacyId);
    }

    @Test
    @DisplayName("hospital-dispensary pharmacies are rejected (must dispense in-house)")
    void dispatch_rejectsHospitalDispensary() {
        pharmacy.setPharmacyType(PharmacyType.HOSPITAL_DISPENSARY);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dispensary");

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("cross-hospital pharmacies are rejected")
    void dispatch_rejectsCrossHospital() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        pharmacy.setHospital(otherHospital);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build()))
                .isInstanceOf(AccessDeniedException.class);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("pharmacies without a phone number are rejected with a friendly error")
    void dispatch_requiresPharmacyPhone() {
        pharmacy.setPhoneNumber(null);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("phone number");
    }

    @Test
    @DisplayName("provider failures persist a FAILED transmission and re-raise as BusinessException")
    void dispatch_persistsFailedOnProviderError() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        doThrow(new RuntimeException("twilio offline")).when(smsService).send(anyString(), anyString());

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("twilio offline");

        ArgumentCaptor<PrescriptionTransmission> captor =
                ArgumentCaptor.forClass(PrescriptionTransmission.class);
        verify(transmissionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getStatusReason()).contains("twilio");
    }
}
