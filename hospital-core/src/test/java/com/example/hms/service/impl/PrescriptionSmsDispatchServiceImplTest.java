package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.PrescriptionDispatchFailedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.model.prescription.PrescriptionTransmission;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.SmsService;
import com.example.hms.service.pharmacy.PrescriberPharmacyNotifier;
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
import org.springframework.transaction.annotation.Transactional;

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
import static org.mockito.Mockito.times;
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
    @Mock private PrescriberPharmacyNotifier prescriberNotifier;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private com.example.hms.repository.pharmacy.DispenseRepository dispenseRepository;
    @Mock private Authentication auth;

    /** G13: the work-queue claim; exit-path releases are verified where they matter. */
    @Mock private com.example.hms.service.pharmacy.PrescriptionQueueClaimService queueClaimService;

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = PrescriptionStatus.class,
            names = {"SIGNED", "TRANSMISSION_FAILED"})
    @DisplayName("G15 AC-10: no dispatch (first send or retry) while a fill is prepared: 409, nothing sent")
    void dispatch_refusedWhileAFillIsPrepared(PrescriptionStatus status) {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        rx.setStatus(status);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(dispenseRepository.existsByPrescription_IdAndStatus(prescriptionId,
                com.example.hms.enums.DispenseStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId,
                PrescriptionSmsDispatchRequestDTO.builder().pharmacyId(pharmacyId).build()))
                .isInstanceOf(com.example.hms.exception.ConflictException.class);
        org.mockito.Mockito.verifyNoInteractions(smsService, routingDecisionRepository, transmissionRepository);
        assertThat(rx.getStatus()).isEqualTo(status);
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
        // A dispatch is never a no-show: that fact has its own column (V167)
        // and only the no-show endpoint sets it.
        assertThat(decision.isPartnerNoShow()).isFalse();
        assertThat(decision.getNoShowReason()).isNull();

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(rx.getDispatchChannel()).isEqualTo("SMS");
        assertThat(rx.getDispatchStatus()).isEqualTo("SENT");
        assertThat(rx.getPharmacyId()).isEqualTo(pharmacyId);
        // G13 AC-12: a successful dispatch ends the claim, DISPATCHED, by the
        // resolveUserId actor; no queue role on this caller -> a plain release
        verify(queueClaimService).releaseOnExit(rx,
                com.example.hms.enums.QueueClaimReleaseReason.DISPATCHED, userId,
                com.example.hms.enums.QueueClaimExitActor.OTHER);
    }

    private void stubSuccessfulDispatch() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> {
                    PrescriptionTransmission t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return t;
                });
    }

    @Test
    @DisplayName("G13 AC-12: a PHARMACIST's dispatch is a queue-role exit (a take-over when another holds the claim)")
    void dispatch_byAPharmacistIsAQueueRoleExit() {
        stubSuccessfulDispatch();
        lenient().when(authUtils.hasAuthority(eq(auth), anyString())).thenReturn(false);
        when(authUtils.hasAuthority(auth, "ROLE_PHARMACIST")).thenReturn(true);

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        verify(queueClaimService).releaseOnExit(rx,
                com.example.hms.enums.QueueClaimReleaseReason.DISPATCHED, userId,
                com.example.hms.enums.QueueClaimExitActor.QUEUE_ROLE);
    }

    @Test
    @DisplayName("G13 AC-12: a DOCTOR's dispatch is never a take-over: OTHER, a plain release")
    void dispatch_byADoctorIsAPlainRelease() {
        stubSuccessfulDispatch();
        lenient().when(authUtils.hasAuthority(eq(auth), anyString())).thenReturn(false);
        lenient().when(authUtils.hasAuthority(auth, "ROLE_DOCTOR")).thenReturn(true);

        service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        verify(queueClaimService).releaseOnExit(rx,
                com.example.hms.enums.QueueClaimReleaseReason.DISPATCHED, userId,
                com.example.hms.enums.QueueClaimExitActor.OTHER);
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
    @DisplayName("provider failure: FAILED row, cancelled offer, TRANSMISSION_FAILED, prescriber told, caller still gets the error, no number anywhere")
    void dispatch_recordsFailureAndCommitsIt() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        // Providers do quote the destination back; none of it may be stored or shown.
        doThrow(new IllegalStateException("twilio offline: +22670111222 unreachable"))
                .when(smsService).send(anyString(), anyString());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(PrescriptionDispatchFailedException.class)
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("+22670111222")
                .hasMessageNotContaining("twilio");

        ArgumentCaptor<PrescriptionTransmission> captor =
                ArgumentCaptor.forClass(PrescriptionTransmission.class);
        verify(transmissionRepository).save(captor.capture());
        PrescriptionTransmission failed = captor.getValue();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getStatusReason())
                .isEqualTo("SMS provider refused the message (IllegalStateException)")
                .doesNotContain("+22670111222");

        ArgumentCaptor<PrescriptionRoutingDecision> decisions =
                ArgumentCaptor.forClass(PrescriptionRoutingDecision.class);
        verify(routingDecisionRepository, times(2)).save(decisions.capture());
        assertThat(decisions.getValue().getStatus())
                .as("the pharmacy never got the offer, so its reference must not be answerable")
                .isEqualTo(RoutingDecisionStatus.CANCELLED);

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.TRANSMISSION_FAILED);
        assertThat(rx.getDispatchStatus()).isEqualTo("FAILED");
        assertThat(rx.getPharmacyId()).as("the order is with no pharmacy").isNull();
        verify(prescriptionRepository).save(rx);
        verify(prescriberNotifier).notifyPrescriber(rx, PrescriptionStatus.TRANSMISSION_FAILED);
        // G13 AC-12 / decision D7: a failed dispatch keeps the claim
        org.mockito.Mockito.verifyNoInteractions(queueClaimService);
    }

    @Test
    @DisplayName("the committed failure is audited with its actor (a 400 escapes the write-audit interceptor), ids only")
    void dispatch_auditsTheRecordedFailure() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        doThrow(new IllegalStateException("twilio offline: +22670111222 unreachable"))
                .when(smsService).send(anyString(), anyString());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(PrescriptionDispatchFailedException.class);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        AuditEventRequestDTO event = audit.getValue();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.PRESCRIPTION_SENT_TO_PARTNER);
        assertThat(event.getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getResourceId()).isEqualTo(prescriptionId.toString());
        assertThat(event.getEntityType()).isEqualTo("PRESCRIPTION");
        assertThat(event.getEventDescription())
                .contains(prescriptionId.toString(), pharmacyId.toString(), "TRANSMISSION_FAILED",
                        "IllegalStateException")
                .doesNotContain("+22670111222", "twilio", "Metformin", "Alice", "Doe", "Pharmacie Centrale");
    }

    @Test
    @DisplayName("an audit-service failure never turns the recorded failure into something else")
    void dispatch_auditFailureIsBestEffort() {
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());
        doThrow(new RuntimeException("audit store down")).when(auditEventLogService).logEvent(any());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(PrescriptionDispatchFailedException.class);
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.TRANSMISSION_FAILED);
    }

    @Test
    @DisplayName("the failure record commits: the dispatch transaction does not roll back on the provider failure, and only on it")
    void dispatch_doesNotRollBackTheFailureRecord() throws NoSuchMethodException {
        Transactional tx = PrescriptionSmsDispatchServiceImpl.class
                .getMethod("dispatch", Authentication.class, UUID.class, PrescriptionSmsDispatchRequestDTO.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).containsExactly(PrescriptionDispatchFailedException.class);
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    @Test
    @DisplayName("a failed re-send while another pharmacy holds an open offer leaves that offer, and the status, alone")
    void dispatch_failureKeepsTheOrderWithThePharmacyThatHasIt() {
        rx.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        Pharmacy previous = new Pharmacy();
        previous.setId(UUID.randomUUID());
        previous.setPhoneNumber("+22670555444");
        PrescriptionRoutingDecision live = PrescriptionRoutingDecision.builder()
                .prescription(rx)
                .routingType(RoutingType.PARTNER)
                .targetPharmacy(previous)
                .status(RoutingDecisionStatus.PENDING)
                .build();
        live.setId(UUID.randomUUID());
        when(routingDecisionRepository.findByPrescriptionId(prescriptionId)).thenReturn(List.of(live));
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(PrescriptionDispatchFailedException.class);

        assertThat(live.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        verify(routingDecisionRepository, never()).save(live);
        verify(partnerChannel, never()).sendSuperseded(any(), any());
        assertThat(rx.getStatus())
                .as("the previous pharmacy can still accept: the counter must not be able to fill it too")
                .isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        verify(prescriptionRepository, never()).save(rx);
        verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        ArgumentCaptor<PrescriptionTransmission> captor =
                ArgumentCaptor.forClass(PrescriptionTransmission.class);
        verify(transmissionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a prescriber who dispatched it themselves saw the error; they are not also notified")
    void dispatch_failureByThePrescriberIsNotNotified() {
        Staff staff = new Staff();
        staff.setUser(user);
        rx.setStaff(staff);
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        doThrow(new RuntimeException("gateway down")).when(smsService).send(anyString(), anyString());
        PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

        assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                .isInstanceOf(PrescriptionDispatchFailedException.class);

        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.TRANSMISSION_FAILED);
        verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
    }

    @Test
    @DisplayName("TRANSMISSION_FAILED is dispatchable: sending again is the retry")
    void dispatch_retriesFromTransmissionFailed() {
        rx.setStatus(PrescriptionStatus.TRANSMISSION_FAILED);
        rx.setDispatchStatus("FAILED");
        when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
        when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
        stubHappyPathCollaborators();
        when(transmissionRepository.save(any(PrescriptionTransmission.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PrescriptionSmsDispatchResponseDTO result =
                service.dispatch(auth, prescriptionId, requestForCurrentPharmacy());

        assertThat(result.getStatus()).isEqualTo("SENT");
        assertThat(rx.getStatus()).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(rx.getDispatchStatus()).isEqualTo("SENT");
        assertThat(rx.getPharmacyId()).isEqualTo(pharmacyId);
    }

    @Test
    @DisplayName("a withdrawn order is never sent, never recorded as failed, and keeps its status")
    void dispatch_refusesWithdrawnOrders() {
        for (PrescriptionStatus withdrawn : List.of(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED)) {
            rx.setStatus(withdrawn);
            when(prescriptionRepository.findByIdForUpdate(prescriptionId)).thenReturn(Optional.of(rx));
            when(pharmacyRepository.findById(pharmacyId)).thenReturn(Optional.of(pharmacy));
            PrescriptionSmsDispatchRequestDTO req = requestForCurrentPharmacy();

            assertThatThrownBy(() -> service.dispatch(auth, prescriptionId, req))
                    .isInstanceOf(BusinessException.class)
                    .isNotInstanceOf(PrescriptionDispatchFailedException.class)
                    .hasMessageContaining(withdrawn.name());
            assertThat(rx.getStatus()).isEqualTo(withdrawn);
        }
        assertThat(PrescriptionSmsDispatchServiceImpl.DISPATCHABLE_STATUSES)
                .doesNotContain(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED);
        verify(smsService, never()).send(anyString(), anyString());
        verify(transmissionRepository, never()).save(any());
        verify(routingDecisionRepository, never()).save(any());
        verify(prescriberNotifier, never()).notifyPrescriber(any(), any());
        verify(auditEventLogService, never()).logEvent(any());
    }
}
