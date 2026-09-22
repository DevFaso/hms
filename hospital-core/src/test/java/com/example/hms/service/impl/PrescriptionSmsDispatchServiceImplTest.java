package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.model.prescription.PrescriptionTransmission;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.service.SmsService;
import com.example.hms.service.pharmacy.partner.PartnerNotificationChannel;
import com.example.hms.service.pharmacy.partner.PartnerSmsTemplates;
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

    private static final String REF_TOKEN = "0A1B2C3D";

    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private PrescriptionTransmissionRepository transmissionRepository;
    @Mock private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SmsService smsService;
    @Mock private PartnerNotificationChannel partnerChannel;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    @InjectMocks private PrescriptionSmsDispatchServiceImpl service;

    private UUID prescriptionId;
    private UUID pharmacyId;
    private UUID hospitalId;
    private UUID userId;
    private Prescription rx;
    private Pharmacy pharmacy;
    private User user;

    @BeforeEach
    void setUp() {
        prescriptionId = UUID.randomUUID();
        pharmacyId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        userId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = new Patient();
        patient.setFirstName("Alice");
        patient.setLastName("Doe");

        rx = new Prescription();
        rx.setId(prescriptionId);
        rx.setHospital(hospital);
        rx.setPatient(patient);
        rx.setStatus(PrescriptionStatus.SIGNED);
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
        pharmacy.setActive(true);

        user = new User();
        user.setId(userId);
    }

    private PrescriptionSmsDispatchRequestDTO requestForCurrentPharmacy() {
        return PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build();
    }

    /** The stubs every path that reaches the SMS needs: user, decision id, offer template. */
    private void stubHappyPathCollaborators() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(routingDecisionRepository.save(any(PrescriptionRoutingDecision.class)))
                .thenAnswer(inv -> {
                    PrescriptionRoutingDecision d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });
        when(partnerChannel.prescriptionOfferBody(any(), eq(rx), anyString()))
                .thenAnswer(inv -> PartnerSmsTemplates.prescriptionOffer(REF_TOKEN, inv.getArgument(2), "AD"));
    }

    @Test
    @DisplayName("G1: dispatch records a PENDING PARTNER routing decision and sends the tokenised offer")
    void dispatch_happyPath() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
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
                .startsWith("HMS Rx " + REF_TOKEN)
                .contains("Metformin")
                .contains("500mg")
                .contains("Note: priority")
                .contains("pour AD")
                .doesNotContain("Alice")
                .endsWith("2 pour refuser.");

        ArgumentCaptor<PrescriptionRoutingDecision> decisionCaptor =
                ArgumentCaptor.forClass(PrescriptionRoutingDecision.class);
        verify(routingDecisionRepository).save(decisionCaptor.capture());
        PrescriptionRoutingDecision decision = decisionCaptor.getValue();
        assertThat(decision.getRoutingType()).isEqualTo(RoutingType.PARTNER);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        assertThat(decision.getTargetPharmacy()).isSameAs(pharmacy);
        assertThat(decision.getDecidedByUser()).isSameAs(user);
        assertThat(decision.getDecidedForPatient()).isSameAs(rx.getPatient());
        assertThat(decision.getDecidedAt()).isNotNull();
        assertThat(decision.getReason()).contains("COMMUNITY_PHARMACY").contains("priority");

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(rx.getDispatchChannel()).isEqualTo("SMS");
        assertThat(rx.getDispatchStatus()).isEqualTo("SENT");
        assertThat(rx.getPharmacyId()).isEqualTo(pharmacyId);
    }

    @Test
    @DisplayName("G1: the reply instructions survive truncation — the prescribing detail is what gets cut")
    void dispatch_truncatesDetailsNotTheOfferFrame() {
        rx.setInstructions("x".repeat(600));
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(eq("+22670111222"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue())
                .hasSizeLessThanOrEqualTo(480)
                .startsWith("HMS Rx " + REF_TOKEN)
                .endsWith("2 pour refuser.");
    }

    @Test
    @DisplayName("G1: a prescription that is not SIGNED/TRANSMITTED cannot be dispatched")
    void dispatch_rejectsNonDispatchableStatus() {
        rx.setStatus(PrescriptionStatus.DISPENSED);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DISPENSED");

        verify(smsService, never()).send(anyString(), anyString());
        verify(routingDecisionRepository, never()).save(any());
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.DISPENSED);
    }

    @Test
    @DisplayName("hospital-dispensary pharmacies are rejected (must dispense in-house)")
    void dispatch_rejectsHospitalDispensary() {
        pharmacy.setPharmacyType(PharmacyType.HOSPITAL_DISPENSARY);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dispensary");

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("inactive pharmacies are rejected")
    void dispatch_rejectsInactivePharmacy() {
        pharmacy.setActive(false);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("inactive");

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
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(AccessDeniedException.class);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("pharmacies without a phone number are rejected with a friendly error")
    void dispatch_requiresPharmacyPhone() {
        pharmacy.setPhoneNumber(null);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("phone number");
    }

    @Test
    @DisplayName("an unresolvable caller cannot own the routing decision")
    void dispatch_requiresResolvableUser() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.empty());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current user");

        verify(routingDecisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("provider failures persist a FAILED transmission and re-raise as BusinessException")
    void dispatch_persistsFailedOnProviderError() {
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        doThrow(new RuntimeException("twilio offline")).when(smsService).send(anyString(), anyString());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("twilio offline");

        ArgumentCaptor<PrescriptionTransmission> captor =
                ArgumentCaptor.forClass(PrescriptionTransmission.class);
        verify(transmissionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getStatusReason()).contains("twilio");
        // The transaction rolls back; the in-memory row must not have moved either.
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
    }
}
