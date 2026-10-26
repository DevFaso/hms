package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.DischargeDisposition;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.AdmissionDischargeRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.AdmissionRequestDTO;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.AdmissionUpdateRequestDTO;
import com.example.hms.repository.AdmissionOrderSetRepository;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.EncounterAutoCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import java.util.Map;
import java.util.Set;
import com.example.hms.enums.SensitivityCategory;

@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class AdmissionServiceImplTest {

    @Mock private AdmissionRepository admissionRepository;
    @Mock private AdmissionOrderSetRepository orderSetRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private com.example.hms.repository.PatientHospitalRegistrationRepository registrationRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EncounterAutoCompletionService encounterAutoCompletion;
    @Mock private com.example.hms.service.AdmissionStayEncounterService stayEncounters;
    @Mock private com.example.hms.service.BedAssignmentService bedAssignmentService;
    @Mock private AdmissionMapper admissionMapper;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.SensitivityClassifier sensitivityClassifier;
    @Mock private com.example.hms.service.recordaccess.BreakGlassGate breakGlassGate;
    @Mock private com.example.hms.repository.BillingInvoiceRepository billingInvoiceRepository;

    @InjectMocks
    private AdmissionServiceImpl service;

    private UUID patientId, hospitalId, staffId, admissionId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        admissionId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
        staff = new Staff(); staff.setId(staffId);
        com.example.hms.model.User staffUser = new com.example.hms.model.User();
        staffUser.setId(UUID.randomUUID());
        staff.setUser(staffUser);
        com.example.hms.security.CallerIdentityTestSupport.actAs(staff, hospital);
        // The admitting provider is a doctor unless a test says otherwise.
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(staffUser.getId(), hospitalId)).thenReturn(true);
        // QA D262: the patient is registered at the admitting hospital unless a test says otherwise.
        org.mockito.Mockito.lenient().when(registrationRepository.existsByPatientIdAndHospitalId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @org.junit.jupiter.api.AfterEach
    void clearCallerIdentity() {
        com.example.hms.security.CallerIdentityTestSupport.clear();
    }

    @Test
    void admitPatient_refusesANurseAsAdmittingProvider() {
        // QA D84: a nurse was accepted as admitting physician.
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);
        when(roleValidator.isDoctor(staff.getUser().getId(), hospitalId)).thenReturn(false);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.admitPatient(request))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasMessageContaining("The admitting and attending providers must be physicians at this hospital.");
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void admitPatient_refusesASecondOpenAdmissionWith409() {
        // QA D304: POST /api/admissions twice for the same patient answered 201 twice.
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.existsOpenAdmissionForPatient(patientId)).thenReturn(true);

        assertThatThrownBy(() -> service.admitPatient(request))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("already has an open admission");
        // The check runs under the patient row lock, so two concurrent admits serialise.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(patientRepository, admissionRepository);
        order.verify(patientRepository).lockByIdUnscoped(patientId);
        order.verify(admissionRepository).existsOpenAdmissionForPatient(patientId);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void admitPatient_success() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);

        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(any(Admission.class))).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.admitPatient(request)).isEqualTo(response);
        // QA D98/D135: every admission opens its own INPATIENT encounter.
        verify(stayEncounters).openStayEncounter(admission);
        // QA D712: the visit that led to the stay is closed with the admission.
        verify(encounterAutoCompletion).completeVisitsThatLedToAdmission(admission);
    }

    /**
     * QA D764: every registration said "Statut du séjour: Admis", outpatients
     * included, because the registration was born ADMITTED and nothing moved
     * it. A registration is now REGISTERED; the admission moves it to
     * ADMITTED, a discharge to DISCHARGED, a cancellation back to REGISTERED.
     */
    @Test
    void qaD764_newRegistrationIsNotAdmitted() {
        assertThat(com.example.hms.model.PatientHospitalRegistration.builder().build().getStayStatus())
            .isEqualTo(com.example.hms.enums.PatientStayStatus.REGISTERED);
        assertThat(new com.example.hms.model.PatientHospitalRegistration(patient, hospital).getStayStatus())
            .isEqualTo(com.example.hms.enums.PatientStayStatus.REGISTERED);
    }

    @Test
    void qaD764_admittingMovesTheRegistrationToAdmitted() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);
        com.example.hms.model.PatientHospitalRegistration registration =
            com.example.hms.model.PatientHospitalRegistration.builder().build();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(any(Admission.class))).thenAnswer(inv -> inv.getArgument(0));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.of(registration));

        service.admitPatient(request);

        assertThat(registration.getStayStatus()).isEqualTo(com.example.hms.enums.PatientStayStatus.ADMITTED);
        verify(registrationRepository).save(registration);
    }

    @Test
    void qaD764_cancellingTheStayReturnsTheRegistrationToRegistered() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        com.example.hms.model.PatientHospitalRegistration registration =
            com.example.hms.model.PatientHospitalRegistration.builder()
                .stayStatus(com.example.hms.enums.PatientStayStatus.ADMITTED).build();
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.of(registration));

        service.cancelAdmission(admissionId);

        assertThat(registration.getStayStatus()).isEqualTo(com.example.hms.enums.PatientStayStatus.REGISTERED);
    }

    @Test
    void admitPatient_patientNotFound() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.admitPatient(request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void admitPatient_usesUnscopedPatientLookup() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);

        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(any(Admission.class))).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.admitPatient(request)).isEqualTo(response);
        verify(patientRepository).findByIdUnscoped(patientId);
    }

    @Test
    void getAdmission_found() {
        Admission admission = new Admission();
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);
        assertThat(service.getAdmission(admissionId)).isEqualTo(response);
    }

    @Test
    void getAdmission_notFound() {
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAdmission(admissionId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAdmission_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionUpdateRequestDTO request = new AdmissionUpdateRequestDTO();
        request.setRoomBed("Room 101");
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.updateAdmission(admissionId, request)).isEqualTo(response);
        assertThat(admission.getRoomBed()).isEqualTo("Room 101");
    }

    @Test
    void d804_updateAdmissionToAnotherDepartmentMovesTheStayEncounter() {
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setHospital(hospital);
        com.example.hms.model.Department cardiology = new com.example.hms.model.Department();
        cardiology.setId(UUID.randomUUID());
        cardiology.setHospital(hospital);
        AdmissionUpdateRequestDTO request = new AdmissionUpdateRequestDTO();
        request.setDepartmentId(cardiology.getId());
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(departmentRepository.findById(cardiology.getId())).thenReturn(Optional.of(cardiology));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(new AdmissionResponseDTO());

        service.updateAdmission(admissionId, request);

        assertThat(admission.getDepartment()).isSameAs(cardiology);
        verify(stayEncounters).followStayDepartment(admission);
    }

    @Test
    void d804_anUpdateThatKeepsTheDepartmentDoesNotTouchTheStayEncounter() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionUpdateRequestDTO request = new AdmissionUpdateRequestDTO();
        request.setRoomBed("Room 102");
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(new AdmissionResponseDTO());

        service.updateAdmission(admissionId, request);

        verify(stayEncounters, never()).followStayDepartment(any());
    }

    @Test
    void cancelAdmission_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));

        service.cancelAdmission(admissionId);

        verify(admissionRepository).save(admission);
    }

    /** QA D603: DELETE /api/admissions/{id} on a discharged (closed) stay is refused and changes nothing. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = AdmissionStatus.class,
        names = {"DISCHARGED", "AWAITING_DISCHARGE", "TRANSFERRED", "DECEASED", "CANCELLED"})
    void d603_cancelAdmission_refusesAClosedStay(AdmissionStatus status) {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(status);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));

        assertThatThrownBy(() -> service.cancelAdmission(admissionId))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("admission.cancel.notInProgress");
        assertThat(admission.getStatus()).isEqualTo(status);
        verify(bedAssignmentService, never()).releaseBed(any());
        verify(admissionRepository, never()).save(any());
    }

    /** QA D603: a stay with a live invoice on its encounter is not cancelled either. */
    @Test
    void d603_cancelAdmission_refusesABilledStay() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(billingInvoiceRepository.existsByEncounter_AdmissionIdAndStatusNot(admissionId,
            com.example.hms.enums.InvoiceStatus.CANCELLED)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelAdmission(admissionId))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("admission.cancel.billed");
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void cancelAdmission_notFound() {
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelAdmission(admissionId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAdmissionsByPatient_returnsList() {
        Admission admission = new Admission();
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(admission));
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        List<AdmissionResponseDTO> result = service.getAdmissionsByPatient(patientId);
        assertThat(result).hasSize(1);
    }

    /**
     * QA D800: the re-test read "active adms" from GET /admissions?patientId=X,
     * which ignored patientId and listed every patient's stays: another
     * patient's ACTIVE stay in Pediatrics read as a second stay of X's.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("QA D800: GET /admissions?patientId= lists only that patient's stays at the acting hospital")
    void getAllAdmissions_patientId_narrowsToThatPatientAtTheActingHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        Admission discharged = new Admission();
        discharged.setStatus(AdmissionStatus.DISCHARGED);
        discharged.setAdmissionDateTime(java.time.LocalDateTime.of(2026, 10, 25, 8, 0));
        Admission active = new Admission();
        active.setStatus(AdmissionStatus.ACTIVE);
        active.setAdmissionDateTime(java.time.LocalDateTime.of(2026, 10, 25, 9, 0));
        AdmissionResponseDTO activeDto = new AdmissionResponseDTO();
        when(admissionRepository.findByPatient_IdAndHospital_IdInOrderByAdmissionDateTimeDesc(patientId, java.util.Set.of(hospitalId)))
            .thenReturn(List.of(active, discharged));
        org.mockito.Mockito.lenient().when(admissionMapper.toResponseDTO(active)).thenReturn(activeDto);
        org.mockito.Mockito.lenient().when(admissionMapper.toResponseDTO(discharged)).thenReturn(new AdmissionResponseDTO());

        assertThat(service.getAllAdmissions(null, null, null, patientId)).hasSize(2);
        assertThat(service.getAllAdmissions("active", null, null, patientId)).containsExactly(activeDto);
        // The hospital-wide list (every patient) is never read for a patient query.
        verify(admissionRepository, never()).findByHospitalIdOrderByAdmissionDateTimeDesc(any());
        verify(admissionRepository, never()).findByPatientIdOrderByAdmissionDateTimeDesc(any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("QA D800 class: startDate/endDate narrow the list instead of being dropped")
    void getAdmissionsByHospital_dateWindowIsApplied() {
        Admission early = new Admission();
        early.setAdmissionDateTime(java.time.LocalDateTime.of(2026, 1, 1, 8, 0));
        Admission inside = new Admission();
        inside.setAdmissionDateTime(java.time.LocalDateTime.of(2026, 1, 15, 8, 0));
        Admission undated = new Admission();
        AdmissionResponseDTO insideDto = new AdmissionResponseDTO();
        when(admissionRepository.findByHospitalIdOrderByAdmissionDateTimeDesc(hospitalId))
            .thenReturn(List.of(early, inside, undated));
        when(admissionMapper.toResponseDTO(inside)).thenReturn(insideDto);

        assertThat(service.getAdmissionsByHospital(hospitalId, null,
            java.time.LocalDateTime.of(2026, 1, 10, 0, 0), java.time.LocalDateTime.of(2026, 1, 31, 23, 59)))
            .containsExactly(insideDto);
        assertThat(AdmissionServiceImpl.admittedWithin(inside, null, null)).isTrue();
        assertThat(AdmissionServiceImpl.admittedWithin(undated, null, null)).isTrue();
        assertThat(AdmissionServiceImpl.admittedWithin(inside, inside.getAdmissionDateTime(), inside.getAdmissionDateTime())).isTrue();
    }

    @Test
    void getAdmissionsByHospital_withStatus_filtersCorrectly() {
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.ACTIVE))
                .thenReturn(List.of());
        assertThat(service.getAdmissionsByHospital(hospitalId, "ACTIVE", null, null)).isEmpty();
    }

    @Test
    void getAdmissionsByHospital_withoutStatus_returnsAll() {
        when(admissionRepository.findByHospitalIdOrderByAdmissionDateTimeDesc(hospitalId)).thenReturn(List.of());
        assertThat(service.getAdmissionsByHospital(hospitalId, null, null, null)).isEmpty();
    }

    @Test
    void getCurrentAdmissionForPatient_noActive_returnsNull() {
        when(admissionRepository.findCurrentAdmissionByPatient(patientId)).thenReturn(Optional.empty());
        assertThat(service.getCurrentAdmissionForPatient(patientId)).isNull();
    }

    @Test
    void getOrderSet_found() {
        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        AdmissionOrderSetResponseDTO response = new AdmissionOrderSetResponseDTO();
        when(orderSetRepository.findById(any())).thenReturn(Optional.of(orderSet));
        when(admissionMapper.toOrderSetResponseDTO(orderSet)).thenReturn(response);
        assertThat(service.getOrderSet(UUID.randomUUID())).isEqualTo(response);
    }

    @Test
    void getOrderSet_notFound() {
        UUID id = UUID.randomUUID();
        when(orderSetRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrderSet(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateOrderSet_success() {
        UUID orderSetId = UUID.randomUUID();
        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        orderSet.setActive(true);
        when(orderSetRepository.findById(orderSetId)).thenReturn(Optional.of(orderSet));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        service.deactivateOrderSet(orderSetId, "No longer needed", staffId);

        verify(orderSetRepository).save(orderSet);
    }

    @Test
    void dischargePatient_refusesANurseAsDischargingProvider() {
        // QA D176: the discharge picker offered nurses and the server took them.
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        when(roleValidator.isDoctor(staff.getUser().getId(), hospitalId)).thenReturn(false);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.dischargePatient(admissionId, request))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .extracting(e -> ((com.example.hms.exception.BusinessException) e).getMessageKey())
            .isEqualTo("admission.dischargingProvider.physician.required");
        verify(admissionRepository, never()).save(any());
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
    }

    @Test
    void dischargePatient_aSecondDischargeIsAConflictAndChangesNothing_D267() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        AdmissionDischargeRequestDTO first = new AdmissionDischargeRequestDTO();
        first.setDischargingProviderId(staffId);
        first.setDischargeDisposition(DischargeDisposition.HOME);
        first.setDischargeSummary("Full summary");
        first.setDischargeInstructions("Rest");
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        service.dischargePatient(admissionId, first);
        java.time.LocalDateTime dischargedAt = admission.getActualDischargeDateTime();

        AdmissionDischargeRequestDTO again = new AdmissionDischargeRequestDTO();
        again.setDischargingProviderId(staffId);
        again.setDischargeDisposition(DischargeDisposition.HOME);
        again.setDischargeSummary("again");

        assertThatThrownBy(() -> service.dischargePatient(admissionId, again))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessage("admission.discharge.alreadyClosed");
        assertThat(admission.getDischargeSummary()).isEqualTo("Full summary");
        assertThat(admission.getDischargeInstructions()).isEqualTo("Rest");
        assertThat(admission.getActualDischargeDateTime()).isEqualTo(dischargedAt);
        verify(admissionRepository, times(1)).save(admission);
    }

    @Test
    void dischargePatient_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        request.setDischargeSummary("Recovered");
        request.setDischargeInstructions("Rest");
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.dischargePatient(admissionId, request)).isEqualTo(response);
    }

    /** QA D764: discharging the stay marks the registration DISCHARGED (it stays active). */
    @Test
    void qaD764_dischargingMovesTheRegistrationToDischarged() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        request.setDischargeSummary("Recovered");
        request.setDischargeInstructions("Rest");
        com.example.hms.model.PatientHospitalRegistration registration =
            com.example.hms.model.PatientHospitalRegistration.builder()
                .stayStatus(com.example.hms.enums.PatientStayStatus.ADMITTED).active(true).build();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(Optional.of(registration));

        service.dischargePatient(admissionId, request);

        assertThat(registration.getStayStatus()).isEqualTo(com.example.hms.enums.PatientStayStatus.DISCHARGED);
        assertThat(registration.isActive()).isTrue();
    }

    @Test
    void dischargePatient_inAColleaguesName_isRefusedAndChangesNothing_D503() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        // QA r8 round 2: a doctor named another doctor as the discharging provider.
        UUID colleagueId = UUID.randomUUID();
        Staff colleague = new Staff(); colleague.setId(colleagueId); colleague.setHospital(hospital);
        com.example.hms.model.User colleagueUser = new com.example.hms.model.User();
        colleagueUser.setId(UUID.randomUUID());
        colleague.setUser(colleagueUser);
        when(roleValidator.isDoctor(colleagueUser.getId(), hospitalId)).thenReturn(true);
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(colleagueId);
        request.setDischargeDisposition(DischargeDisposition.HOME);

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(colleagueId)).thenReturn(Optional.of(colleague));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dischargePatient(admissionId, request))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void dischargePatient_delegatesEncounterCompletion() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);

        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        request.setDischargeSummary("Recovered");
        request.setDischargeInstructions("Rest");

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(new AdmissionResponseDTO());

        service.dischargePatient(admissionId, request);

        // QA D134: the stay's own encounters, never the patient-wide sweep.
        verify(stayEncounters).recordDischarge(admission, null);
        verify(encounterAutoCompletion).completeAdmissionEncounters(admissionId);
        // QA D760: the walk-in that led to the stay is closed at discharge too.
        verify(encounterAutoCompletion).completeVisitsThatLedToAdmission(admission);
    }

    /* ── P0 #4: structured bed assignment ─────────────────────────────── */

    @Test
    void dischargePatient_releasesBed() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);

        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(new AdmissionResponseDTO());

        service.dischargePatient(admissionId, request);

        verify(bedAssignmentService).releaseBed(admission);
    }

    @Test
    void cancelAdmission_releasesBed() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));

        service.cancelAdmission(admissionId);

        verify(bedAssignmentService).releaseBed(admission);
        verify(admissionRepository).save(admission);
    }

    @Test
    void assignBed_delegatesAndSaves() {
        UUID bedId = UUID.randomUUID();
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setHospital(hospital);
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.assignBed(admissionId, bedId)).isEqualTo(response);
        verify(bedAssignmentService).assignBed(admission, bedId);
    }

    @Test
    void assignBed_crossTenantReadsAsNotFound() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setHospital(hospital);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.assignBed(admissionId, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unassignBed_delegatesAndSaves() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setHospital(hospital);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(new AdmissionResponseDTO());

        service.unassignBed(admissionId);

        verify(bedAssignmentService).unassignBed(admission);
    }

    @Test
    void getAdmissionsByPatient_followThePatientAndWithholdAForeignSensitiveAdmission() {
        // E9 #59e — the admission at Hôpital B is on the list at Hôpital A; a
        // foreign admission in a sensitive category (D3) is withheld; accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = Hospital.builder().build(); other.setId(otherHospitalId);
        Admission local = new Admission(); local.setId(UUID.randomUUID()); local.setHospital(hospital);
        Admission foreign = new Admission(); foreign.setId(UUID.randomUUID()); foreign.setHospital(other);
        Admission foreignSensitive = new Admission(); foreignSensitive.setId(UUID.randomUUID()); foreignSensitive.setHospital(other);
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId))).thenReturn(Set.of(hospitalId, otherHospitalId));
        when(admissionRepository.findByPatient_IdAndHospital_IdInOrderByAdmissionDateTimeDesc(patientId, Set.of(hospitalId, otherHospitalId)))
            .thenReturn(List.of(local, foreign, foreignSensitive));
        when(sensitivityClassifier.effectiveCategory(any(Admission.class)))
            .thenAnswer(inv -> inv.getArgument(0) == foreignSensitive ? SensitivityCategory.BEHAVIOURAL_HEALTH : null);
        when(admissionMapper.toResponseDTO(any(Admission.class))).thenReturn(response);

        assertThat(service.getAdmissionsByPatient(patientId)).hasSize(2);
        verify(admissionRepository, never()).findByPatientIdOrderByAdmissionDateTimeDesc(any());
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    // ── QA D262: hospital B may not discharge, edit or cancel hospital A's admission ──

    private Admission activeAdmissionAtHospitalA() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setPatient(patient);
        admission.setHospital(hospital);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        // The caller (DOCTOR_B) acts at hospital B.
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        return admission;
    }

    @Test
    void dischargePatient_fromAnotherHospital_isNotFound_D262() {
        Admission admission = activeAdmissionAtHospitalA();
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        request.setDischargeSummary("x");

        assertThatThrownBy(() -> service.dischargePatient(admissionId, request))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(admissionRepository, never()).save(any());
        verify(bedAssignmentService, never()).releaseBed(any());
        verify(encounterAutoCompletion, never()).completeAdmissionEncounters(any());
    }

    @Test
    void updateAdmission_fromAnotherHospital_isNotFound_D262() {
        Admission admission = activeAdmissionAtHospitalA();
        AdmissionUpdateRequestDTO request = new AdmissionUpdateRequestDTO();
        request.setAcuityLevel(com.example.hms.enums.AcuityLevel.values()[0]);

        assertThatThrownBy(() -> service.updateAdmission(admissionId, request))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(admissionRepository, never()).save(any());
        assertThat(admission.getAcuityLevel()).isNull();
    }

    @Test
    void cancelAdmission_fromAnotherHospital_isNotFound_D262() {
        Admission admission = activeAdmissionAtHospitalA();

        assertThatThrownBy(() -> service.cancelAdmission(admissionId))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        verify(bedAssignmentService, never()).releaseBed(any());
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void admitPatient_atAnotherHospitalThanTheCallers_isNotFound_D262() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.admitPatient(request))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    void admitPatient_notRegisteredAtTheHospital_isNotFound_D262() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> service.admitPatient(request))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(admissionRepository, never()).save(any());
    }
}
