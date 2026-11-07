package com.example.hms.service;

import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.OrderReviewHold;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.repository.OrderReviewHoldRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.impl.OrderReviewHoldServiceImpl;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The review of orders held by a disputed death. NOT clinically approved: the clinical lead has to sign it off. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderReviewHoldServiceImplTest {

    @Mock private OrderReviewHoldRepository holds;
    @Mock private PrescriptionRepository prescriptions;
    @Mock private TransfusionRequestRepository requests;
    @Mock private TransfusionAdministrationRepository administrations;
    @Mock private PatientChartAccess chartAccess;
    @Mock private RoleValidator roles;
    @Mock private PrescriptionControlledResolver controlled;
    @Mock private PrescriberReauthenticationService reauthentication;
    @Mock private PrescriptionService prescriptionService;
    @Mock private TransfusionService transfusionService;
    @Mock private InBasketService inBasket;
    @Mock private AuditEventLogService audit;
    @Mock private com.example.hms.service.pharmacy.partner.WithdrawnOrderPartnerHandler partnerOffers;

    private OrderReviewHoldServiceImpl service;
    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Prescription order;
    private OrderReviewHold hold;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        service = new OrderReviewHoldServiceImpl(Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC),
            holds, prescriptions, requests, administrations, chartAccess, roles, controlled, reauthentication,
            prescriptionService, transfusionService, inBasket, audit, partnerOffers);
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.isDoctor(actor, hospital)).thenReturn(true);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(true);
        order = new Prescription();
        order.setId(UUID.randomUUID());
        order.setVersion(4L);
        order.setStatus(PrescriptionStatus.SIGNED);
        order.setMedicationName("Morphine");
        com.example.hms.model.Patient chart = new com.example.hms.model.Patient();
        chart.setId(patient);
        order.setPatient(chart);
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospital);
        order.setHospital(here);
        hold = OrderReviewHold.builder().deathRecordId(UUID.randomUUID()).disputeId(UUID.randomUUID()).hospitalId(hospital).patientId(patient)
            .orderType(OrderReviewType.PRESCRIPTION).orderId(order.getId()).orderVersion(4L)
            .status(OrderReviewStatus.HELD).build();
        hold.setId(UUID.randomUUID());
        when(holds.lockById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.save(any(OrderReviewHold.class))).thenAnswer(call -> call.getArgument(0));
        when(prescriptions.findByIdAndHospitalIdForUpdate(order.getId(), hospital)).thenReturn(Optional.of(order));
        when(prescriptions.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private OrderReviewDecisionDTO reconfirm(long version) {
        return OrderReviewDecisionDTO.builder().reason("Still indicated after assessment").orderVersion(version)
            .disputeId(hold.getDisputeId()).build();
    }

    private DeathDispute dispute() {
        DeathDispute dispute = DeathDispute.builder().deathRecordId(UUID.randomUUID()).patientId(patient).hospitalId(hospital).build();
        dispute.setId(UUID.randomUUID());
        return dispute;
    }

    @Test
    @DisplayName("continue records prescriber, time, reason, order version and dispute, and audits them")
    void continues() {
        var row = service.continueOrder(hold.getId(), reconfirm(4));

        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.CONTINUED);
        assertThat(hold.getDecidedById()).isEqualTo(actor);
        assertThat(hold.getDecidedAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 10, 0));
        assertThat(hold.getReason()).isEqualTo("Still indicated after assessment");
        assertThat(hold.getReviewedOrderVersion()).isEqualTo(4L);
        assertThat(row.getStatus()).isEqualTo(OrderReviewStatus.CONTINUED);
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventDescription()).contains(order.getId().toString()).contains("version 4")
            .contains(hold.getDisputeId().toString()).contains(actor.toString()).doesNotContain("Still indicated");
        assertThat(String.valueOf(event.getValue().getDetails())).as("the audit row is not encrypted: a digest")
            .contains("reasonSha256").doesNotContain("Still indicated");
    }

    @Test
    @DisplayName("no reason, no reconfirmation: the reason given when disputing the death reconfirms nothing")
    void needsItsOwnReason() {
        OrderReviewDecisionDTO blank = OrderReviewDecisionDTO.builder().orderVersion(4L).reason(" ").disputeId(hold.getDisputeId()).build();
        UUID id = hold.getId();

        assertThatThrownBy(() -> service.continueOrder(id, blank))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a reconfirmation of an older version is refused on the version")
    void staleVersion() {
        order.setVersion(5L);
        UUID id = hold.getId();
        OrderReviewDecisionDTO stale = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, stale))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("orderVersion"));
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);

        OrderReviewDecisionDTO fresh = reconfirm(5);
        service.continueOrder(id, fresh);
        assertThat(hold.getReviewedOrderVersion()).as("the version read").isEqualTo(5L);
        assertThat(hold.getOrderVersion()).as("the snapshot stays").isEqualTo(4L);
    }

    @Test
    @DisplayName("the role of signing at this hospital: not the original prescriber, and a nurse or an admin is refused")
    void wrongRole() {
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        when(roles.isHospitalAdmin(actor, hospital)).thenReturn(true);
        when(roles.isNurse(actor, hospital)).thenReturn(true);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.stopOrder(id, decision)).isInstanceOf(AccessDeniedException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("another hospital's row answers as missing, for each decision")
    void otherHospital() {
        when(roles.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.stopOrder(id, decision)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a controlled drug takes the step-up of signing it, on this call")
    void controlledStepUp() {
        when(controlled.isControlled(order)).thenReturn(true);
        when(reauthentication.requireForControlledSigning(actor, order.getId(), "pw", "123"))
            .thenThrow(new BusinessException("prescription.reauth.failed"));
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = OrderReviewDecisionDTO.builder().reason("x").orderVersion(4L).disputeId(hold.getDisputeId())
            .password("pw").mfaCode("123").build();

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(BusinessException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a blood request takes the roles that may request blood; a midwife may")
    void bloodRole() {
        TransfusionRequest request = TransfusionRequest.builder().status(TransfusionRequestStatus.ISSUED).version(2L).build();
        request.setId(hold.getOrderId());
        hold.setOrderType(OrderReviewType.TRANSFUSION_REQUEST);
        when(requests.findById(hold.getOrderId())).thenReturn(Optional.of(request));
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(2);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(AccessDeniedException.class);

        when(roles.isMidwife(actor, hospital)).thenReturn(true);
        service.continueOrder(id, decision);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.CONTINUED);
    }

    @Test
    @DisplayName("a withdrawn order is not continued")
    void withdrawnStaysWithdrawn() {
        order.setStatus(PrescriptionStatus.CANCELLED);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(BusinessException.class);
        verify(prescriptionService, never()).stopHeldOrder(any(), any());
    }

    @Test
    @DisplayName("a decided row is not decided again")
    void decidedOnce() {
        service.continueOrder(hold.getId(), reconfirm(4));
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.stopOrder(id, decision)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("stop goes through the order's own stop path, with its reason")
    void stops() {
        service.stopOrder(hold.getId(), OrderReviewDecisionDTO.builder().reason("No longer indicated").build());

        verify(prescriptionService).stopHeldOrder(order.getId(), "No longer indicated");
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.STOPPED);
    }

    @Test
    @DisplayName("replace closes the held order and links the new one, which must be this patient's, here, and not held")
    void replaces() {
        Prescription fresh = new Prescription();
        fresh.setId(UUID.randomUUID());
        com.example.hms.model.Patient chart = new com.example.hms.model.Patient();
        chart.setId(patient);
        com.example.hms.model.Hospital here = new com.example.hms.model.Hospital();
        here.setId(hospital);
        fresh.setPatient(chart);
        fresh.setHospital(here);
        fresh.setStatus(PrescriptionStatus.SIGNED);
        when(prescriptions.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        UUID id = hold.getId();

        com.example.hms.model.Patient other = new com.example.hms.model.Patient();
        other.setId(UUID.randomUUID());
        fresh.setPatient(other);
        com.example.hms.payload.dto.review.OrderReviewReplacementDTO toOther =
            com.example.hms.payload.dto.review.OrderReviewReplacementDTO.builder().reason("x").replacementOrderId(fresh.getId()).build();
        assertThatThrownBy(() -> service.replaceOrder(id, toOther)).isInstanceOf(FieldBusinessException.class);
        verify(prescriptionService, never()).stopHeldOrder(any(), any());

        fresh.setPatient(chart);
        service.replaceOrder(id, toOther);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.REPLACED);
        assertThat(hold.getReplacementOrderId()).isEqualTo(fresh.getId());
        verify(prescriptionService).stopHeldOrder(eq(order.getId()), any());
    }

    @Test
    @DisplayName("the snapshot holds what exists, not a stay-ended order, and each order once")
    void snapshot() {
        com.example.hms.model.Encounter closed = new com.example.hms.model.Encounter();
        closed.setAdmissionId(UUID.randomUUID());
        closed.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        Prescription ended = new Prescription();
        ended.setId(UUID.randomUUID());
        ended.setEncounter(closed);
        ended.setPatient(order.getPatient());
        ended.setHospital(order.getHospital());
        Prescription already = new Prescription();
        already.setId(UUID.randomUUID());
        already.setPatient(order.getPatient());
        already.setHospital(order.getHospital());
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        Prescription otherHospital = new Prescription();
        otherHospital.setId(UUID.randomUUID());
        otherHospital.setPatient(order.getPatient());
        otherHospital.setHospital(elsewhere);
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(order, ended, already, otherHospital));
        when(holds.findByOrderTypeAndOrderIdAndStatusAndHospitalId(OrderReviewType.PRESCRIPTION, already.getId(), OrderReviewStatus.HELD, hospital))
            .thenReturn(Optional.of(hold));

        service.snapshot(dispute());

        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(saved.getValue().getOrderVersion()).isEqualTo(4L);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("running treatment is not stopped: flagged, with an URGENT item to the treating clinician, until assessed")
    void running() {
        User nurse = new User();
        nurse.setId(UUID.randomUUID());
        Staff staff = new Staff();
        staff.setUser(nurse);
        TransfusionAdministration bag = TransfusionAdministration.builder().administeredBy(staff).hospital(order.getHospital())
            .status(TransfusionAdministrationStatus.IN_PROGRESS).build();
        bag.setId(UUID.randomUUID());
        when(administrations.findByPatient_IdAndStatus(patient, TransfusionAdministrationStatus.IN_PROGRESS)).thenReturn(List.of(bag));

        service.snapshot(dispute());

        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getOrderType()).isEqualTo(OrderReviewType.RUNNING_TRANSFUSION);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderReviewStatus.ASSESSMENT_DUE);
        assertThat(bag.getStatus()).as("nothing stopped or restarted").isEqualTo(TransfusionAdministrationStatus.IN_PROGRESS);
        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItem(item.capture());
        assertThat(item.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(nurse.getId());

        OrderReviewHold flag = saved.getValue();
        flag.setId(UUID.randomUUID());
        when(holds.lockById(flag.getId())).thenReturn(Optional.of(flag));
        when(roles.isNurse(actor, hospital)).thenReturn(true);
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        service.recordAssessment(flag.getId(), OrderReviewDecisionDTO.builder().reason("Stable, continuing").build());
        assertThat(flag.getStatus()).isEqualTo(OrderReviewStatus.ASSESSED);
    }

    @Test
    @DisplayName("a pharmacist does not assess a running treatment")
    void assessmentRole() {
        hold.setOrderType(OrderReviewType.RUNNING_TRANSFUSION);
        hold.setStatus(OrderReviewStatus.ASSESSMENT_DUE);
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = OrderReviewDecisionDTO.builder().reason("x").build();

        assertThatThrownBy(() -> service.recordAssessment(id, decision)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the chart's list answers only at this hospital")
    void list() {
        when(chartAccess.require(patient, hospital)).thenThrow(new ResourceNotFoundException("patient.notFound", patient));

        assertThatThrownBy(() -> service.forPatient(patient)).isInstanceOf(ResourceNotFoundException.class);
        verify(holds, never()).findByPatientIdAndHospitalIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("every decision takes a reason of its own: blank is refused on the field, and the row stays")
    void everyDecisionNeedsAReason() {
        UUID id = hold.getId();
        OrderReviewDecisionDTO blank = OrderReviewDecisionDTO.builder().reason(" ").orderVersion(4L).disputeId(hold.getDisputeId()).build();
        com.example.hms.payload.dto.review.OrderReviewReplacementDTO blankReplacement =
            com.example.hms.payload.dto.review.OrderReviewReplacementDTO.builder().reason(" ").replacementOrderId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.continueOrder(id, blank)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.replaceOrder(id, blankReplacement)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.stopOrder(id, blank)).isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.recordAssessment(id, blank)).isInstanceOf(FieldBusinessException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a review-only role is not an authorized prescriber: a pharmacist or a nurse decides nothing")
    void reviewOnlyRole() {
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        when(roles.hasRole(any(), any(), any())).thenReturn(true);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(AccessDeniedException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a different authorized prescriber may decide: the order's author is not asked for")
    void alternatePrescriber() {
        Staff author = new Staff();
        User other = new User();
        other.setId(UUID.randomUUID());
        author.setUser(other);
        order.setStaff(author);

        service.continueOrder(hold.getId(), reconfirm(4));

        assertThat(hold.getDecidedById()).isEqualTo(actor);
    }

    @Test
    @DisplayName("bound to the dispute: a page read under another dispute is refused")
    void boundToTheDispute() {
        UUID id = hold.getId();
        OrderReviewDecisionDTO other = OrderReviewDecisionDTO.builder().reason("x").orderVersion(4L).disputeId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.continueOrder(id, other))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("disputeId"));
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a stay-ended or discontinued order stays inactive: it is not continued")
    void inactiveStaysInactive() {
        com.example.hms.model.Encounter closed = new com.example.hms.model.Encounter();
        closed.setAdmissionId(UUID.randomUUID());
        closed.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
        order.setEncounter(closed);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);
        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(BusinessException.class);

        order.setEncounter(null);
        order.setStatus(PrescriptionStatus.DISCONTINUED);
        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(BusinessException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a relevant change after a reconfirmation holds the order again; the reconfirmation stays on record")
    void aChangeInvalidates() {
        service.continueOrder(hold.getId(), reconfirm(4));
        when(holds.findByOrderTypeAndOrderIdAndStatus(OrderReviewType.PRESCRIPTION, order.getId(), OrderReviewStatus.CONTINUED))
            .thenReturn(List.of(hold));
        order.setVersion(5L);

        service.reviewAgainAfterChange(OrderReviewType.PRESCRIPTION, order.getId());

        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        OrderReviewHold again = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(again.getStatus()).isEqualTo(OrderReviewStatus.HELD);
        assertThat(again.getOrderVersion()).isEqualTo(5L);
        assertThat(again.getDisputeId()).isEqualTo(hold.getDisputeId());
        assertThat(hold.getStatus()).as("history kept").isEqualTo(OrderReviewStatus.CONTINUED);
    }

    @Test
    @DisplayName("an edit of an order never reconfirmed adds nothing")
    void anEditOfAHeldOrderAddsNothing() {
        service.reviewAgainAfterChange(OrderReviewType.PRESCRIPTION, order.getId());
        verify(holds, never()).save(any());
    }

    @Test
    @DisplayName("at the death event: outstanding orders at every hospital, each its own, with no dispute yet")
    void atTheDeathEvent() {
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        Prescription other = new Prescription();
        other.setId(UUID.randomUUID());
        other.setPatient(order.getPatient());
        other.setHospital(elsewhere);
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(order, other));
        com.example.hms.model.DeathRecord record = com.example.hms.model.DeathRecord.builder().patient(order.getPatient()).build();
        record.setId(UUID.randomUUID());

        service.snapshotAtDeath(record);

        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(OrderReviewHold::getHospitalId).containsExactlyInAnyOrder(hospital, elsewhere.getId());
        assertThat(saved.getAllValues()).allSatisfy(row -> {
            assertThat(row.getDisputeId()).isNull();
            assertThat(row.getDeathRecordId()).isEqualTo(record.getId());
            assertThat(row.getStatus()).isEqualTo(OrderReviewStatus.HELD);
        });
    }

    @Test
    @DisplayName("a dispute binds the standing row from the death event, and adds nothing")
    void aDisputeBindsTheStandingRow() {
        hold.setDisputeId(null);
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(order));
        when(holds.findByOrderTypeAndOrderIdAndStatusAndHospitalId(OrderReviewType.PRESCRIPTION, order.getId(), OrderReviewStatus.HELD, hospital))
            .thenReturn(Optional.of(hold));
        DeathDispute dispute = dispute();

        service.snapshot(dispute);

        assertThat(hold.getDisputeId()).isEqualTo(dispute.getId());
        verify(holds, never()).save(any());
    }

    private Prescription finished(PrescriptionStatus status) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setStatus(status);
        rx.setPatient(order.getPatient());
        rx.setHospital(order.getHospital());
        return rx;
    }

    private com.example.hms.model.Encounter visit(com.example.hms.enums.EncounterStatus status) {
        com.example.hms.model.Encounter visit = new com.example.hms.model.Encounter();
        visit.setStatus(status);
        return visit;
    }

    private List<UUID> heldBySnapshot(Prescription... rows) {
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(rows));
        service.snapshot(dispute());
        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds, org.mockito.Mockito.atLeast(0)).save(saved.capture());
        return saved.getAllValues().stream().map(OrderReviewHold::getOrderId).toList();
    }

    @Test
    @DisplayName("every status a fill can still leave from is asked for: a partner's refusal is back in the in-house queue")
    void theQueryAsksForEveryUsableStatus() {
        service.snapshot(dispute());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<PrescriptionStatus>> asked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(prescriptions).lockUncompletedForReview(eq(patient), asked.capture());
        java.util.Set<PrescriptionStatus> dispensable = java.util.Set.of(PrescriptionStatus.PARTNER_REJECTED, PrescriptionStatus.TRANSMISSION_FAILED,
            PrescriptionStatus.SIGNED, PrescriptionStatus.PARTNER_ACCEPTED, PrescriptionStatus.DISPENSED, PrescriptionStatus.PARTNER_DISPENSED,
            PrescriptionStatus.PRINTED_FOR_PATIENT);
        assertThat(asked.getValue()).containsAll(dispensable).doesNotContain(PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("a partner's refused order is held: the pharmacy could fill it")
    void aRefusedOfferIsHeld() {
        Prescription refused = finished(PrescriptionStatus.PARTNER_REJECTED);
        assertThat(heldBySnapshot(refused)).containsExactly(refused.getId());
    }

    @Test
    @DisplayName("a finished order with a refill left is held at every finished status; without one it is history")
    void aRefillLeftIsStillToBeUsed() {
        Prescription takeHome = finished(PrescriptionStatus.DISPENSED);
        takeHome.setTakeHome(true);
        takeHome.setRefillsRemaining(2);
        Prescription partner = finished(PrescriptionStatus.PARTNER_DISPENSED);
        partner.setRefillsRemaining(1);
        Prescription printed = finished(PrescriptionStatus.PRINTED_FOR_PATIENT);
        printed.setRefillsRemaining(1);
        Prescription spent = finished(PrescriptionStatus.PARTNER_DISPENSED);
        spent.setRefillsRemaining(0);
        Prescription controlled = finished(PrescriptionStatus.PRINTED_FOR_PATIENT);
        controlled.setRefillsRemaining(1);
        controlled.setControlledSubstance(true);

        assertThat(heldBySnapshot(takeHome, partner, printed, spent, controlled))
            .containsExactlyInAnyOrder(takeHome.getId(), partner.getId(), printed.getId());
    }

    @Test
    @DisplayName("a dispense can take a dose only on a visit still open: not for home, not an old closed visit")
    void aDispenseIsHeldOnlyWhileADoseCanBeGiven() {
        Prescription ward = finished(PrescriptionStatus.DISPENSED);
        ward.setEncounter(visit(com.example.hms.enums.EncounterStatus.IN_PROGRESS));
        Prescription oldVisit = finished(PrescriptionStatus.DISPENSED);
        oldVisit.setEncounter(visit(com.example.hms.enums.EncounterStatus.COMPLETED));
        Prescription forHome = finished(PrescriptionStatus.DISPENSED);
        forHome.setTakeHome(true);
        forHome.setEncounter(visit(com.example.hms.enums.EncounterStatus.IN_PROGRESS));

        assertThat(heldBySnapshot(ward, oldVisit, forHome)).containsExactly(ward.getId());
    }

    @Test
    @DisplayName("a newly held order recalls its partner offer; one already standing does not ask again")
    void theOfferIsRecalledOnceOnTheNewHold() {
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(order));
        service.snapshot(dispute());
        verify(partnerOffers).recallForReview(order);

        when(holds.findByOrderTypeAndOrderIdAndStatusAndHospitalId(OrderReviewType.PRESCRIPTION, order.getId(), OrderReviewStatus.HELD, hospital))
            .thenReturn(Optional.of(hold));
        service.snapshot(dispute());
        verify(partnerOffers).recallForReview(order);
    }

    @Test
    @DisplayName("a later dispute takes over a standing row: its reconfirmation cannot be bound to a closed one")
    void aLaterDisputeTakesTheStandingRow() {
        UUID closed = hold.getDisputeId();
        when(prescriptions.lockUncompletedForReview(eq(patient), any())).thenReturn(List.of(order));
        when(holds.findByOrderTypeAndOrderIdAndStatusAndHospitalId(OrderReviewType.PRESCRIPTION, order.getId(), OrderReviewStatus.HELD, hospital))
            .thenReturn(Optional.of(hold));
        DeathDispute later = dispute();

        service.snapshot(later);

        assertThat(hold.getDisputeId()).isEqualTo(later.getId());
        OrderReviewDecisionDTO againstClosed = OrderReviewDecisionDTO.builder().reason("x").orderVersion(4L).disputeId(closed).build();
        UUID id = hold.getId();
        assertThatThrownBy(() -> service.continueOrder(id, againstClosed))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("disputeId"));
    }

    @Test
    @DisplayName("a prescription is reviewed with the authority of signing it: a surgeon's role does not sign one")
    void aSurgeonCannotReconfirmAPrescription() {
        when(roles.isDoctor(actor, hospital)).thenReturn(false);
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(true);
        UUID id = hold.getId();
        OrderReviewDecisionDTO decision = reconfirm(4);

        assertThatThrownBy(() -> service.continueOrder(id, decision)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.stopOrder(id, decision)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.HELD);
    }

    @Test
    @DisplayName("a replacement is new, signed and live: a draft, a stopped order or an older one closes nothing")
    void aReplacementIsNewSignedAndLive() {
        hold.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 0));
        Prescription fresh = finished(PrescriptionStatus.SIGNED);
        fresh.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 30));
        when(prescriptions.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        UUID id = hold.getId();
        var request = com.example.hms.payload.dto.review.OrderReviewReplacementDTO.builder().reason("x").replacementOrderId(fresh.getId()).build();

        for (PrescriptionStatus refused : List.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE,
                PrescriptionStatus.CANCELLED, PrescriptionStatus.DISCONTINUED)) {
            fresh.setStatus(refused);
            assertThatThrownBy(() -> service.replaceOrder(id, request)).as(refused.name()).isInstanceOf(FieldBusinessException.class);
        }
        fresh.setStatus(PrescriptionStatus.SIGNED);
        fresh.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThatThrownBy(() -> service.replaceOrder(id, request)).as("older").isInstanceOf(FieldBusinessException.class);
        verify(prescriptionService, never()).stopHeldOrder(any(), any());

        fresh.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 30));
        service.replaceOrder(id, request);
        assertThat(hold.getStatus()).isEqualTo(OrderReviewStatus.REPLACED);
    }

    @Test
    @DisplayName("the row offers only what the service would accept as a replacement")
    void theRowOffersOnlyLiveReplacements() {
        hold.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 0));
        Prescription signed = finished(PrescriptionStatus.SIGNED);
        signed.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 30));
        Prescription draft = finished(PrescriptionStatus.DRAFT);
        draft.setCreatedAt(LocalDateTime.of(2026, 11, 7, 9, 30));
        when(holds.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patient, hospital)).thenReturn(List.of(hold));
        when(prescriptions.findWrittenSince(patient, hospital, hold.getCreatedAt())).thenReturn(List.of(signed, draft));

        assertThat(service.forPatient(patient).get(0).getReplacementCandidates()).extracting(c -> c.getId()).containsExactly(signed.getId());
    }

    @Test
    @DisplayName("a blood request moved after its reconfirmation is held again")
    void aMovedRequestIsHeldAgain() {
        UUID request = UUID.randomUUID();
        OrderReviewHold approved = OrderReviewHold.builder().deathRecordId(UUID.randomUUID()).disputeId(UUID.randomUUID()).hospitalId(hospital)
            .patientId(patient).orderType(OrderReviewType.TRANSFUSION_REQUEST).orderId(request).orderVersion(1L)
            .status(OrderReviewStatus.CONTINUED).build();
        when(holds.findByOrderTypeAndOrderIdAndStatus(OrderReviewType.TRANSFUSION_REQUEST, request, OrderReviewStatus.CONTINUED))
            .thenReturn(List.of(approved));

        service.reviewAgainAfterChange(OrderReviewType.TRANSFUSION_REQUEST, request);

        ArgumentCaptor<OrderReviewHold> saved = ArgumentCaptor.forClass(OrderReviewHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderReviewStatus.HELD);
        assertThat(saved.getValue().getOrderType()).isEqualTo(OrderReviewType.TRANSFUSION_REQUEST);
    }

    @Test
    @DisplayName("a reason fits the stop it goes on: a blood request takes 500, with what names the replacement")
    void aReasonFitsTheStop() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            String longest = "x".repeat(400);
            assertThat(validator.validate(OrderReviewDecisionDTO.builder().reason(longest).build())).isEmpty();
            assertThat(validator.validate(OrderReviewDecisionDTO.builder().reason(longest + "x").build())).isNotEmpty();
            assertThat(validator.validate(com.example.hms.payload.dto.review.OrderReviewReplacementDTO.builder()
                .reason(longest + "x").replacementOrderId(UUID.randomUUID()).build())).isNotEmpty();
            assertThat(("Replaced by request " + UUID.randomUUID() + ": " + longest).length()).isLessThanOrEqualTo(500);
        }
    }

    @Test
    @DisplayName("a later dispute's flag on a bag reaches the clinician again: each flag files on its own row")
    void eachFlagFilesOnItsOwnRow() {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(UUID.randomUUID());
        com.example.hms.model.Staff nurse = new com.example.hms.model.Staff();
        nurse.setUser(user);
        TransfusionAdministration bag = new TransfusionAdministration();
        bag.setId(UUID.randomUUID());
        bag.setStatus(TransfusionAdministrationStatus.IN_PROGRESS);
        bag.setHospital(order.getHospital());
        bag.setAdministeredBy(nurse);
        when(administrations.findByPatient_IdAndStatus(patient, TransfusionAdministrationStatus.IN_PROGRESS)).thenReturn(List.of(bag));
        UUID flag = UUID.randomUUID();
        when(holds.save(any(OrderReviewHold.class))).thenAnswer(call -> {
            OrderReviewHold row = call.getArgument(0);
            row.setId(flag);
            return row;
        });

        service.snapshot(dispute());

        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        verify(inBasket).createItem(item.capture());
        assertThat(item.getValue().getReferenceId()).isEqualTo(flag);
    }
}
