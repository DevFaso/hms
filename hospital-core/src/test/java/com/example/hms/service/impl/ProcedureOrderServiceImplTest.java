package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderUpdateDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.PatientSubjectReadGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import java.util.Map;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class ProcedureOrderServiceImplTest {

    @Mock private ProcedureOrderRepository procedureOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;
    @Mock private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    @InjectMocks private ProcedureOrderServiceImpl service;

    /**
     * The real subject guard. These tests set no authentication, so it waves
     * every read through, as it does for any caller that is not patient-only;
     * the patient cases are in PatientSubjectReadGuardTest and the
     * per-service ownership tests.
     */
    @Spy
    private PatientSubjectReadGuard subjectReadGuard =
        new PatientSubjectReadGuard(mock(ControllerAuthUtils.class), mock(PatientRepository.class));

    private UUID patientId, hospitalId, staffId, orderId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId); patient.setFirstName("Jane"); patient.setLastName("Doe");
        hospital = new Hospital(); hospital.setId(hospitalId); hospital.setName("Test Hospital");
        staff = new Staff(); staff.setId(staffId);
    }

    private ProcedureOrder buildOrder(ProcedureOrderStatus status) {
        ProcedureOrder o = ProcedureOrder.builder()
            .patient(patient).hospital(hospital).orderingProvider(staff)
            .procedureName("Appendectomy").status(status).build();
        o.setId(orderId);
        return o;
    }

    @Test void createProcedureOrder_success() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setProcedureName("Appendectomy"); r.setProcedureCode("44970");
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });
        ProcedureOrderResponseDTO result = service.createProcedureOrder(r, staffId);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ProcedureOrderStatus.ORDERED);
        assertThat(result.getProcedureName()).isEqualTo("Appendectomy");
    }

    @Test void createProcedureOrder_patientNotFound() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createProcedureOrder(r, staffId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void createProcedureOrder_atAnotherHospital_answersAsAMissingHospital() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.createProcedureOrder(r, staffId))
            .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                assertThat(e.getMessageKey()).isEqualTo("hospital.notFound");
                assertThat(e.getMessage()).isEqualTo(new ResourceNotFoundException("hospital.notFound", hospitalId).getMessage());
            });
        verify(patientRepository, never()).findById(any());
        verify(procedureOrderRepository, never()).save(any());
    }

    @Test void createProcedureOrder_forAPatientRegisteredElsewhere_answersAsAMissingPatient() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);
        assertThatThrownBy(() -> service.createProcedureOrder(r, staffId))
            .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                assertThat(e.getMessageKey()).isEqualTo("patient.notFound");
                assertThat(e.getMessage()).isEqualTo(new ResourceNotFoundException("patient.notFound", patientId).getMessage());
            });
        verify(patientRepository, never()).findById(any());
        verify(procedureOrderRepository, never()).save(any());
    }

    @Test void createProcedureOrder_atTheActingHospital_forARegisteredPatient_proceeds() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setProcedureName("Appendectomy");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });
        assertThat(service.createProcedureOrder(r, staffId).getHospitalId()).isEqualTo(hospitalId);
    }

    @Test void createProcedureOrder_againstAnotherHospitalsEncounter_answersAsAMissingEncounter() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId); r.setProcedureName("Appendectomy");
        UUID encounterId = UUID.randomUUID();
        r.setEncounterId(encounterId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        com.example.hms.model.Hospital elsewhere = new com.example.hms.model.Hospital();
        elsewhere.setId(UUID.randomUUID());
        com.example.hms.model.Encounter foreign = new com.example.hms.model.Encounter();
        foreign.setId(encounterId); foreign.setHospital(elsewhere); foreign.setPatient(patient);

        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());
        String missing = org.assertj.core.api.Assertions.catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.createProcedureOrder(r, staffId)).getMessage();
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(foreign));
        String refused = org.assertj.core.api.Assertions.catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.createProcedureOrder(r, staffId)).getMessage();

        assertThat(refused).isEqualTo(missing);
        verify(procedureOrderRepository, never()).save(any());
    }

    @Test void createProcedureOrder_againstThePatientsOwnEncounterHere_attachesIt() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId); r.setProcedureName("Appendectomy");
        UUID encounterId = UUID.randomUUID();
        r.setEncounterId(encounterId);
        com.example.hms.model.Encounter own = new com.example.hms.model.Encounter();
        own.setId(encounterId); own.setHospital(hospital); own.setPatient(patient);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(own));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });

        service.createProcedureOrder(r, staffId);
        org.mockito.ArgumentCaptor<ProcedureOrder> saved = org.mockito.ArgumentCaptor.forClass(ProcedureOrder.class);
        verify(procedureOrderRepository).save(saved.capture());
        assertThat(saved.getValue().getEncounter()).isSameAs(own);
    }

    @Test void createProcedureOrder_resolvesTheOrderingClinicianFromTheirUserId() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId); r.setProcedureName("Appendectomy");
        UUID callerUserId = UUID.randomUUID();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(callerUserId)).thenReturn(Optional.empty());
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });

        service.createProcedureOrder(r, callerUserId);

        org.mockito.ArgumentCaptor<ProcedureOrder> saved = org.mockito.ArgumentCaptor.forClass(ProcedureOrder.class);
        verify(procedureOrderRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderingProvider()).isSameAs(staff);
    }

    @Test void getProcedureOrder_success() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        ProcedureOrderResponseDTO result = service.getProcedureOrder(orderId);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(orderId);
    }

    @Test void getProcedureOrder_notFound() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProcedureOrder(orderId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getProcedureOrdersForPatient() {
        when(procedureOrderRepository.findByPatient_IdOrderByOrderedAtDesc(patientId)).thenReturn(List.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        assertThat(service.getProcedureOrdersForPatient(patientId)).hasSize(1);
    }

    @Test void getProcedureOrdersForHospital_withStatus() {
        when(procedureOrderRepository.findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(hospitalId, ProcedureOrderStatus.SCHEDULED))
            .thenReturn(List.of(buildOrder(ProcedureOrderStatus.SCHEDULED)));
        assertThat(service.getProcedureOrdersForHospital(hospitalId, ProcedureOrderStatus.SCHEDULED)).hasSize(1);
    }

    @Test void getProcedureOrdersOrderedBy() {
        when(procedureOrderRepository.findByOrderingProvider_IdOrderByOrderedAtDesc(staffId)).thenReturn(List.of());
        assertThat(service.getProcedureOrdersOrderedBy(staffId)).isEmpty();
    }

    @Test void getProcedureOrdersScheduledBetween() {
        LocalDateTime start = LocalDateTime.now(); LocalDateTime end = start.plusDays(7);
        when(procedureOrderRepository.findByHospital_IdAndScheduledDatetimeBetween(hospitalId, start, end)).thenReturn(List.of());
        assertThat(service.getProcedureOrdersScheduledBetween(hospitalId, start, end)).isEmpty();
    }

    @Test void updateProcedureOrder_success() {
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setStatus(ProcedureOrderStatus.COMPLETED);
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.SCHEDULED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.updateProcedureOrder(orderId, u);
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.COMPLETED);
    }

    @Test void updateProcedureOrder_completedOrder_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.COMPLETED)));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        assertThatThrownBy(() -> service.updateProcedureOrder(orderId, u)).isInstanceOf(BusinessException.class);
    }

    @Test void updateProcedureOrder_cancelledOrder_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.CANCELLED)));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        assertThatThrownBy(() -> service.updateProcedureOrder(orderId, u)).isInstanceOf(BusinessException.class);
    }

    @Test void updateProcedureOrder_scheduledDateSetsStatus() {
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setScheduledDatetime(LocalDateTime.now().plusDays(3));
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.updateProcedureOrder(orderId, u);
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.SCHEDULED);
    }

    /**
     * QA D857: DOCTOR_A rescheduled a consented order to 01/01/2020 09:00; the
     * PUT answered 200, the order read "Planifié" for 2020 and offered
     * "Débuter la procédure". A past date is refused and nothing changes.
     */
    @Test void d857_aRescheduleToAPastDateIsRefusedAndChangesNothing() {
        ProcedureOrder consented = buildOrder(ProcedureOrderStatus.SCHEDULED);
        consented.setConsentObtained(true);
        LocalDateTime planned = LocalDateTime.now().plusDays(2);
        consented.setScheduledDatetime(planned);
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(consented));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setScheduledDatetime(LocalDateTime.of(2020, 1, 1, 9, 0));

        assertThatThrownBy(() -> service.updateProcedureOrder(orderId, u))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessage()).isEqualTo(new BusinessException("procedureOrder.scheduledInPast").getMessage()));
        assertThat(consented.getScheduledDatetime()).isEqualTo(planned);
        assertThat(consented.getStatus()).isEqualTo(ProcedureOrderStatus.SCHEDULED);
        verify(procedureOrderRepository, never()).save(any());
    }

    /** QA D857: the same date re-sent with a consent update is not a reschedule, and passes. */
    @Test void d857_anUnchangedPastDateDoesNotBlockAConsentUpdate() {
        ProcedureOrder order = buildOrder(ProcedureOrderStatus.SCHEDULED);
        LocalDateTime earlier = LocalDateTime.now().minusHours(3);
        order.setScheduledDatetime(earlier);
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderUpdateDTO u = new ProcedureOrderUpdateDTO();
        u.setScheduledDatetime(earlier);
        u.setSiteMarked(true);

        ProcedureOrderResponseDTO r = service.updateProcedureOrder(orderId, u);

        assertThat(r.getSiteMarked()).isTrue();
    }

    /** QA D857: an order placed with a past planned date is refused too. */
    @Test void d857_anOrderPlacedForAPastDateIsRefused() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setProcedureName("Appendectomy");
        r.setScheduledDatetime(LocalDateTime.of(2020, 1, 1, 9, 0));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.createProcedureOrder(r, staffId)).isInstanceOf(BusinessException.class);
        verify(procedureOrderRepository, never()).save(any());
    }

    /**
     * QA D880: a planned date given at creation left the order "Prescrit"
     * (ORDERED); the same date set later by a reschedule makes it "Planifié".
     */
    @Test void d880_aPlannedDateAtCreationMakesTheOrderScheduled() {
        ProcedureOrderRequestDTO r = new ProcedureOrderRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setProcedureName("Appendectomy");
        r.setScheduledDatetime(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> { ProcedureOrder o = i.getArgument(0); o.setId(orderId); return o; });

        ProcedureOrderResponseDTO result = service.createProcedureOrder(r, staffId);

        assertThat(result.getStatus()).isEqualTo(ProcedureOrderStatus.SCHEDULED);
    }

    @Test void cancelProcedureOrder_success() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(procedureOrderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ProcedureOrderResponseDTO r = service.cancelProcedureOrder(orderId, "No longer needed");
        assertThat(r.getStatus()).isEqualTo(ProcedureOrderStatus.CANCELLED);
        assertThat(r.getCancellationReason()).isEqualTo("No longer needed");
    }

    @Test void cancelProcedureOrder_alreadyCompleted_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.COMPLETED)));
        assertThatThrownBy(() -> service.cancelProcedureOrder(orderId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void cancelProcedureOrder_alreadyCancelled_throws() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.CANCELLED)));
        assertThatThrownBy(() -> service.cancelProcedureOrder(orderId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void getPendingConsentOrders() {
        ProcedureOrder o = buildOrder(ProcedureOrderStatus.SCHEDULED);
        o.setConsentObtained(false);
        when(procedureOrderRepository.findByHospital_IdAndStatusAndConsentObtainedFalse(hospitalId, ProcedureOrderStatus.SCHEDULED)).thenReturn(List.of(o));
        assertThat(service.getPendingConsentOrders(hospitalId)).hasSize(1);
    }

    // ── Tenant isolation tests ──

    @Test void getProcedureOrder_crossHospital_throws404() {
        UUID otherHospId = UUID.randomUUID();
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospId);

        assertThatThrownBy(() -> service.getProcedureOrder(orderId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getProcedureOrder_sameHospital_success() {
        when(procedureOrderRepository.findById(orderId)).thenReturn(Optional.of(buildOrder(ProcedureOrderStatus.ORDERED)));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        ProcedureOrderResponseDTO result = service.getProcedureOrder(orderId);
        assertThat(result).isNotNull();
    }

    @Test void getProcedureOrdersForPatient_scopedToTheReadableSet() {
        UUID activeHospId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(activeHospId))).thenReturn(Set.of(activeHospId));
        when(procedureOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, Set.of(activeHospId))).thenReturn(List.of());

        assertThat(service.getProcedureOrdersForPatient(patientId)).isEmpty();
    }

    @Test void getProcedureOrdersForPatient_followsThePatientAndAccountsTheReach() {
        // E9 #59b — an appendectomy ordered at Hôpital B is on the list at
        // Hôpital A when the policy reads B for this patient; accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital(); other.setId(otherHospitalId); other.setName("Hôpital B");
        ProcedureOrder foreign = ProcedureOrder.builder()
            .patient(patient).hospital(other).orderingProvider(staff)
            .procedureName("Appendectomy").status(ProcedureOrderStatus.COMPLETED).build();
        foreign.setId(UUID.randomUUID());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId, otherHospitalId));
        when(procedureOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, Set.of(hospitalId, otherHospitalId)))
            .thenReturn(List.of(buildOrder(ProcedureOrderStatus.ORDERED), foreign));

        assertThat(service.getProcedureOrdersForPatient(patientId)).extracting(ProcedureOrderResponseDTO::getId)
            .containsExactly(orderId, foreign.getId());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    @Test void getProcedureOrdersForHospital_scopedOverridesParam() {
        UUID activeHospId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(procedureOrderRepository.findByHospital_IdOrderByOrderedAtDesc(activeHospId)).thenReturn(List.of());

        assertThat(service.getProcedureOrdersForHospital(hospitalId, null)).isEmpty();
    }

    @Test void getProcedureOrdersOrderedBy_scopedToHospital() {
        UUID activeHospId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(procedureOrderRepository.findByOrderingProvider_IdAndHospital_IdOrderByOrderedAtDesc(staffId, activeHospId)).thenReturn(List.of());

        assertThat(service.getProcedureOrdersOrderedBy(staffId)).isEmpty();
    }

    @Test void getProcedureOrdersScheduledBetween_scopedOverridesParam() {
        UUID activeHospId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.now(); LocalDateTime end = start.plusDays(7);
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(procedureOrderRepository.findByHospital_IdAndScheduledDatetimeBetween(activeHospId, start, end)).thenReturn(List.of());

        assertThat(service.getProcedureOrdersScheduledBetween(hospitalId, start, end)).isEmpty();
    }

    @Test void getPendingConsentOrders_scopedOverridesParam() {
        UUID activeHospId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(procedureOrderRepository.findByHospital_IdAndStatusAndConsentObtainedFalse(activeHospId, ProcedureOrderStatus.SCHEDULED)).thenReturn(List.of());

        assertThat(service.getPendingConsentOrders(hospitalId)).isEmpty();
    }
}
