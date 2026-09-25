package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionSmsDispatchServiceImpl")
class PrescriptionSmsDispatchServiceImplTest {

    private static final String REF_TOKEN = "0A1B2C3D";

    /** The offer wording now lives in the bundles (#717), so read it from there. */
    private static final PartnerSmsTemplates TEMPLATES =
            new PartnerSmsTemplates(com.example.hms.i18n.TestMessageSources.bundles());

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

        // requireCallerHospital runs before the pharmacy is even loaded, so
        // every path through dispatch() needs an active hospital on the caller.
        lenient().when(authUtils.currentHospitalId(auth)).thenReturn(hospitalId);
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
                .thenAnswer(inv -> TEMPLATES.prescriptionOffer(REF_TOKEN, inv.getArgument(2), "AD"));
    }

    @Test
    @DisplayName("G1: dispatch records a PENDING PARTNER routing decision and sends the tokenised offer")
    void dispatch_happyPath() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
                // The pharmacy is told which reply to send, reference included.
                .contains("« 1 " + REF_TOKEN + " »")
                .endsWith("« 2 " + REF_TOKEN + " » pour refuser.");

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
        // The note shares its column with the no-show marker, so it is
        // defused here as it is on the routing path: otherwise a note could
        // forge the fact and, once this PENDING decision is superseded, the
        // prescriber's history would read "The partner never delivered".
        assertThat(com.example.hms.service.pharmacy.PartnerNoShowReason
                .isNoShow(decision.getReason())).isFalse();

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
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
                .endsWith("« 2 " + REF_TOKEN + " » pour refuser.");
    }

    @Test
    @DisplayName("round 4: a superseded pharmacy is told its offer is gone, best-effort")
    void dispatch_tellsTheSupersededPharmacy() {
        rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
        Pharmacy previous = new Pharmacy();
        previous.setId(UUID.randomUUID());
        previous.setName("Pharmacie du Nord");
        // A pharmacy that was offered the prescription necessarily has a number.
        previous.setPhoneNumber("+22670555444");
        PrescriptionRoutingDecision stale = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .targetPharmacy(previous)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        stale.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of(stale));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        verify(partnerChannel).sendSuperseded(stale, previous);
    }

    @Test
    @DisplayName("round 4: a failure telling the superseded pharmacy does not undo the dispatch")
    void dispatch_supersededNotificationIsBestEffort() {
        rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
        Pharmacy previous = new Pharmacy();
        previous.setId(UUID.randomUUID());
        previous.setPhoneNumber("+22670555444");
        PrescriptionRoutingDecision stale = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .targetPharmacy(previous)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        stale.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of(stale));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("gateway down"))
                .when(partnerChannel).sendSuperseded(any(), any());

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(stale.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
    }

    @Test
    @DisplayName("round 6: re-sending to the SAME pharmacy replaces its offer without cancelling or scolding it")
    void dispatch_toSamePharmacyKeepsItsOfferAndSaysNothing() {
        rx.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        PrescriptionRoutingDecision itsOwnOffer = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .targetPharmacy(pharmacy)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        itsOwnOffer.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of(itsOwnOffer));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(itsOwnOffer.getStatus())
                .as("its own offer is replaced, not superseded")
                .isEqualTo(RoutingDecisionStatus.PENDING);
        verify(routingDecisionRepository, never()).save(itsOwnOffer);
        verify(partnerChannel, never()).sendSuperseded(any(), any());
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
    }

    @Test
    @DisplayName("round 5: a superseded pharmacy with no number on file is simply not called")
    void dispatch_supersededWithoutPhoneIsNotNotified() {
        rx.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        PrescriptionRoutingDecision stale = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .targetPharmacy(new Pharmacy())
                .status(RoutingDecisionStatus.PENDING)
                .build();
        stale.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of(stale));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(stale.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        verify(partnerChannel, never()).sendSuperseded(any(), any());
    }

    @Test
    @DisplayName("round 5: a prescription a pharmacy has gone quiet on can be sent to another one")
    void dispatch_acceptsSentToPartner() {
        rx.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of());
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(rx.getPharmacyId()).isEqualTo(pharmacyId);
    }

    @Test
    @DisplayName("round 4: a caller with no active hospital is refused unless they are a super-admin")
    void dispatch_rejectsCallerWithNoActiveHospital() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(authUtils.currentHospitalId(auth)).thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pharmacyRepository, never()).findById(any());
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
    }

    @Test
    @DisplayName("round 4: a super-admin in global view is still unscoped")
    void dispatch_allowsSuperAdminInGlobalView() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(authUtils.currentHospitalId(auth)).thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
    }

    @Test
    @DisplayName("round 2: a refused or back-ordered prescription can be dispatched again, superseding the open PARTNER decision only")
    void dispatch_redispatchSupersedesOpenDecision() {
        rx.setStatus(PrescriptionStatus.PARTNER_REJECTED);
        PrescriptionRoutingDecision stale = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.PENDING)
                .reason("first offer")
                .build();
        stale.setId(UUID.randomUUID());
        PrescriptionRoutingDecision closed = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .status(RoutingDecisionStatus.REJECTED)
                .build();
        closed.setId(UUID.randomUUID());
        // Round 4: a back order is not an offer to anybody — it carries the
        // restock date and must survive the hand-off to a pharmacy.
        PrescriptionRoutingDecision backOrder = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.BACKORDER)
                .status(RoutingDecisionStatus.PENDING)
                .estimatedRestockDate(java.time.LocalDate.now().plusDays(10))
                .build();
        backOrder.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId))
                .thenReturn(List.of(stale, closed, backOrder));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(stale.getStatus()).isEqualTo(RoutingDecisionStatus.CANCELLED);
        assertThat(stale.getReason()).startsWith("first offer. Superseded").contains("Pharmacie Centrale");
        assertThat(closed.getStatus()).isEqualTo(RoutingDecisionStatus.REJECTED);
        assertThat(backOrder.getStatus())
                .as("the back order, and the restock date on it, must outlive the hand-off")
                .isEqualTo(RoutingDecisionStatus.PENDING);
        assertThat(backOrder.getEstimatedRestockDate()).isNotNull();
        verify(routingDecisionRepository).save(stale);
        verify(routingDecisionRepository, never()).save(closed);
        verify(routingDecisionRepository, never()).save(backOrder);
    }

    @Test
    @DisplayName("round 2: PENDING_STOCK is dispatchable too")
    void dispatch_acceptsPendingStock() {
        rx.setStatus(PrescriptionStatus.PENDING_STOCK);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
    }

    @Test
    @DisplayName("G1: a prescription that is not SIGNED/TRANSMITTED cannot be dispatched")
    void dispatch_rejectsNonDispatchableStatus() {
        rx.setStatus(PrescriptionStatus.DISPENSED);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dispensary");

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("a prescription awaiting the prescriber's clarification cannot be dispatched")
    void dispatch_refusesWhileAwaitingClarification() {
        rx.setStatus(com.example.hms.enums.PrescriptionStatus.PENDING_CLARIFICATION);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("clarification");

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("inactive pharmacies are rejected")
    void dispatch_rejectsInactivePharmacy() {
        pharmacy.setActive(false);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(AccessDeniedException.class);

        verify(smsService, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("a caller scoped to another hospital gets 404, before the pharmacy is even looked at")
    void dispatch_rejectsCallerFromOtherHospital() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(authUtils.currentHospitalId(auth)).thenReturn(UUID.randomUUID());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pharmacyRepository, never()).findById(any());
        verify(smsService, never()).send(anyString(), anyString());
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SIGNED);
    }

    @Test
    @DisplayName("a caller scoped to the prescription's own hospital passes the tenant check")
    void dispatch_acceptsCallerFromSameHospital() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(authUtils.currentHospitalId(auth)).thenReturn(hospitalId);
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
    }

    @Test
    @DisplayName("pharmacies without a phone number are rejected with a friendly error")
    void dispatch_requiresPharmacyPhone() {
        pharmacy.setPhoneNumber(null);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("phone number");
    }

    @Test
    @DisplayName("an unresolvable caller cannot own the routing decision")
    void dispatch_requiresResolvableUser() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
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
