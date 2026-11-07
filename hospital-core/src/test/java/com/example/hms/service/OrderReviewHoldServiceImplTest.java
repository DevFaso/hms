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
            prescriptionService, transfusionService, inBasket, audit);
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
        when(roles.getCurrentUserId()).thenReturn(actor);
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
            .contains(hold.getDisputeId().toString()).contains(actor.toString()).contains("Still indicated");
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
        when(prescriptions.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        UUID id = hold.getId();

        OrderReviewDecisionDTO none = OrderReviewDecisionDTO.builder().reason("x").build();
        assertThatThrownBy(() -> service.replaceOrder(id, none))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("replacementOrderId"));

        com.example.hms.model.Patient other = new com.example.hms.model.Patient();
        other.setId(UUID.randomUUID());
        fresh.setPatient(other);
        OrderReviewDecisionDTO toOther = OrderReviewDecisionDTO.builder().reason("x").replacementOrderId(fresh.getId()).build();
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
        when(roles.isSigningPhysician(actor, hospital)).thenReturn(false);
        service.recordAssessment(flag.getId(), OrderReviewDecisionDTO.builder().reason("Stable, continuing").build());
        assertThat(flag.getStatus()).isEqualTo(OrderReviewStatus.ASSESSED);
    }

    @Test
    @DisplayName("a pharmacist does not assess a running treatment")
    void assessmentRole() {
        hold.setOrderType(OrderReviewType.RUNNING_TRANSFUSION);
        hold.setStatus(OrderReviewStatus.ASSESSMENT_DUE);
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
    @DisplayName("a review-only role is not an authorized prescriber: a pharmacist or a nurse decides nothing")
    void reviewOnlyRole() {
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

        service.reviewAgainAfterChange(order.getId());

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
        service.reviewAgainAfterChange(order.getId());
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
}
