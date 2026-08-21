package com.example.hms.service.impl;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralReferralServiceImplTest {

    @Mock
    private GeneralReferralRepository referralRepository;
    @Mock
    private com.example.hms.repository.AppointmentRepository appointmentRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private com.example.hms.utility.RoleValidator roleValidator;
    @Mock
    private com.example.hms.service.ReferralEventRecorder eventRecorder;
    @Mock
    private com.example.hms.repository.ReferralEventRepository eventRepository;

    @InjectMocks
    private GeneralReferralServiceImpl generalReferralService;

    @Test
    void createReferral_populatesEntitiesAndCalculatesPriorityScore() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID referringProviderId = UUID.randomUUID();
        UUID receivingProviderId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID receivingHospitalId = UUID.randomUUID();
        UUID sourceDepartmentId = UUID.randomUUID();

        GeneralReferralRequestDTO request = new GeneralReferralRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setReferringProviderId(referringProviderId);
        request.setReceivingProviderId(receivingProviderId);
        request.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        request.setTargetDepartmentId(departmentId);
        request.setTargetFacilityName("Cardiology Institute");
        request.setReferralType(ReferralType.CONSULTATION);
        request.setUrgency(ReferralUrgency.EMERGENCY);
        request.setReferralReason("Chest pain evaluation");
        request.setClinicalIndication("Abnormal stress test");
        request.setClinicalSummary("Patient with unstable angina");
        request.setCurrentMedications(List.of(Map.of("name", "Aspirin")));
        request.setDiagnoses(List.of(Map.of("code", "I20.0")));
        request.setClinicalQuestion("Is cath indicated?");
        request.setAnticipatedTreatment("Cardiac cath");
        request.setInsuranceAuthNumber("AUTH-123");
        request.setMetadata(Map.of("source", "ED"));
        request.setReceivingHospitalId(receivingHospitalId);
        request.setSourceDepartmentId(sourceDepartmentId);

        Patient patient = buildPatient(patientId, "Alice", "Smith");
        Hospital hospital = buildHospital(hospitalId, "Metro Hospital");
        Hospital receivingHospital = buildHospital(receivingHospitalId, "Cardiology Institute Hospital");
        Staff referringProvider = buildStaff(referringProviderId, "Dr. Referrer");
        Staff receivingProvider = buildStaff(receivingProviderId, "Dr. Receiver");
        Department department = buildDepartment(departmentId, "Cardiology");
        Department sourceDept = buildDepartment(sourceDepartmentId, "Emergency");

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.findById(receivingHospitalId)).thenReturn(Optional.of(receivingHospital));
        when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
        when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.of(receivingProvider));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(departmentRepository.findById(sourceDepartmentId)).thenReturn(Optional.of(sourceDept));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(invocation -> {
            GeneralReferral referral = invocation.getArgument(0);
            referral.setId(referralId);
            referral.setCreatedAt(LocalDateTime.now());
            referral.setUpdatedAt(LocalDateTime.now());
            return referral;
        });

        GeneralReferralResponseDTO response = generalReferralService.createReferral(request);

        assertNotNull(response);
        assertEquals(referralId, response.getId());
        assertEquals(ReferralStatus.DRAFT, response.getStatus());
        assertEquals(Integer.valueOf(100), response.getPriorityScore());
        assertEquals("Alice Smith", response.getPatientName());
        assertEquals("Dr. Referrer", response.getReferringProviderName());
        assertEquals("Dr. Receiver", response.getReceivingProviderName());
        assertEquals("Cardiology", response.getTargetDepartmentName());
        assertThat(response.getCurrentMedications()).containsExactly(Map.of("name", "Aspirin"));
        assertEquals(receivingHospitalId, response.getReceivingHospitalId());
        assertEquals("Cardiology Institute Hospital", response.getReceivingHospitalName());
        assertEquals(sourceDepartmentId, response.getSourceDepartmentId());
        assertEquals("Emergency", response.getSourceDepartmentName());

        ArgumentCaptor<GeneralReferral> captor = ArgumentCaptor.forClass(GeneralReferral.class);
        verify(referralRepository).save(captor.capture());
        GeneralReferral saved = captor.getValue();
        assertEquals(ReferralUrgency.EMERGENCY, saved.getUrgency());
        assertEquals("Chest pain evaluation", saved.getReferralReason());
        assertEquals("Cardiac cath", saved.getAnticipatedTreatment());
        assertEquals("AUTH-123", saved.getInsuranceAuthNumber());
        assertNotNull(saved.getReceivingHospital());
        assertEquals(receivingHospitalId, saved.getReceivingHospital().getId());
        assertNotNull(saved.getSourceDepartment());
        assertEquals(sourceDepartmentId, saved.getSourceDepartment().getId());
    }

    @Test
    void createReferral_usesUnscopedPatientLookup() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID referringProviderId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();

        GeneralReferralRequestDTO request = new GeneralReferralRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setReferringProviderId(referringProviderId);
        request.setReferralType(ReferralType.CONSULTATION);
        request.setUrgency(ReferralUrgency.ROUTINE);
        request.setReferralReason("Cross-hospital referral");

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(buildPatient(patientId, "Cross", "Hospital")));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(buildHospital(hospitalId, "Hospital A")));
        when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(buildStaff(referringProviderId, "Dr. Cross")));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(invocation -> {
            GeneralReferral ref = invocation.getArgument(0);
            ref.setId(referralId);
            ref.setCreatedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            return ref;
        });

        GeneralReferralResponseDTO response = generalReferralService.createReferral(request);

        assertNotNull(response);
        verify(patientRepository).findByIdUnscoped(patientId);
        verify(patientRepository, never()).findById(patientId);
    }

    @Test
    void createReferral_withoutOptionalHospitalAndDepartment_leavesFieldsNull() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID referringProviderId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();

        GeneralReferralRequestDTO request = new GeneralReferralRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setReferringProviderId(referringProviderId);
        request.setReferralType(ReferralType.CONSULTATION);
        request.setUrgency(ReferralUrgency.ROUTINE);
        request.setReferralReason("Follow-up care");
        // receivingHospitalId and sourceDepartmentId deliberately omitted

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(buildPatient(patientId, "Bob", "Jones")));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(buildHospital(hospitalId, "Main Hospital")));
        when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(buildStaff(referringProviderId, "Dr. Provider")));
        when(referralRepository.save(any(GeneralReferral.class))).thenAnswer(invocation -> {
            GeneralReferral ref = invocation.getArgument(0);
            ref.setId(referralId);
            ref.setCreatedAt(LocalDateTime.now());
            ref.setUpdatedAt(LocalDateTime.now());
            return ref;
        });

        GeneralReferralResponseDTO response = generalReferralService.createReferral(request);

        assertNotNull(response);
        assertThat(response.getReceivingHospitalId()).isNull();
        assertThat(response.getReceivingHospitalName()).isNull();
        assertThat(response.getSourceDepartmentId()).isNull();
        assertThat(response.getSourceDepartmentName()).isNull();

        ArgumentCaptor<GeneralReferral> captor = ArgumentCaptor.forClass(GeneralReferral.class);
        verify(referralRepository).save(captor.capture());
        GeneralReferral saved = captor.getValue();
        assertThat(saved.getReceivingHospital()).isNull();
        assertThat(saved.getSourceDepartment()).isNull();
    }

    @Test
    void submitReferral_transitionsReferralToSubmittedAndSetsTimestamps() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setUrgency(ReferralUrgency.URGENT);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response = generalReferralService.submitReferral(referralId);

        assertEquals(ReferralStatus.SUBMITTED, response.getStatus());
        assertNotNull(referral.getSubmittedAt());
        assertNotNull(referral.getSlaDueAt());
        verify(referralRepository).save(referral);
    }

    @Test
    void acknowledgeReferral_withUnknownReceivingProvider_throws() {
        UUID referralId = UUID.randomUUID();
        UUID receivingProviderId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
            generalReferralService.acknowledgeReferral(referralId, "Ready", receivingProviderId));
        verifyNoInteractions(departmentRepository);
    }

    @Test
    void completeReferral_updatesCompletionMetadata() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response = generalReferralService.completeReferral(referralId, "Specialist concluded care", "Follow up with PCP");

        assertEquals(ReferralStatus.COMPLETED, response.getStatus());
        assertEquals("Specialist concluded care", response.getCompletionSummary());
        assertEquals("Follow up with PCP", response.getFollowUpRecommendations());
        assertNotNull(referral.getCompletedAt());
        verify(referralRepository).save(referral);
    }

    @Test
    void scheduleReferral_setsScheduledStatusAndAppointmentFields() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);
        LocalDateTime appointmentTime = LocalDateTime.now().plusDays(5);

        com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO request =
            com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO.builder()
                .appointmentTime(appointmentTime)
                .location("Clinic 7 — Room 12")
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response = generalReferralService.scheduleReferral(referralId, request);

        assertEquals(ReferralStatus.SCHEDULED, response.getStatus());
        assertEquals(appointmentTime, referral.getScheduledAppointmentAt());
        assertEquals("Clinic 7 — Room 12", referral.getAppointmentLocation());
        verify(referralRepository).save(referral);
    }

    @Test
    void scheduleReferral_unknownReferral_throwsNotFound() {
        UUID referralId = UUID.randomUUID();
        com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO request =
            com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO.builder()
                .appointmentTime(LocalDateTime.now().plusDays(1))
                .location("X")
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> generalReferralService.scheduleReferral(referralId, request));
        verify(referralRepository, never()).save(any(GeneralReferral.class));
    }

    @Test
    void startReferral_transitionsToInProgressAndStampsStartedAt() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.SCHEDULED);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response = generalReferralService.startReferral(referralId);

        assertEquals(ReferralStatus.IN_PROGRESS, response.getStatus());
        assertNotNull(referral.getStartedAt());
        assertNotNull(response.getStartedAt());
        verify(referralRepository).save(referral);
    }

    @Test
    void rejectReferral_setsRejectedStatusAndStoresReason() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.SUBMITTED);
        com.example.hms.payload.dto.referral.RejectReferralRequestDTO request =
            com.example.hms.payload.dto.referral.RejectReferralRequestDTO.builder()
                .reason("Out of scope for our service")
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response = generalReferralService.rejectReferral(referralId, request);

        assertEquals(ReferralStatus.REJECTED, response.getStatus());
        assertEquals("Out of scope for our service", referral.getCancellationReason());
        verify(referralRepository).save(referral);
    }

    @Test
    void cancelReferral_marksReferralCancelledAndPersistsReason() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        generalReferralService.cancelReferral(referralId, "Patient opted for another facility");

        assertEquals(ReferralStatus.CANCELLED, referral.getStatus());
        assertEquals("Patient opted for another facility", referral.getCancellationReason());
        verify(referralRepository).save(referral);
    }

    @Test
    void getReferralsByPatient_returnsDTOsWithMappedNames() {
        UUID patientId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(UUID.randomUUID());
        referral.getPatient().setId(patientId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId)).thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> responses = generalReferralService.getReferralsByPatient(patientId);

        assertEquals(1, responses.size());
        assertEquals(patientId, responses.get(0).getPatientId());
        assertEquals(ReferralStatus.ACKNOWLEDGED, responses.get(0).getStatus());
        verify(referralRepository).findByPatientIdOrderByCreatedAtDesc(patientId);
    }

    @Test
    void getReferralsByReferringProvider_returnsDescendingList() {
        UUID providerId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(UUID.randomUUID());
        referral.getReferringProvider().setId(providerId);

        when(referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(providerId)).thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> responses = generalReferralService.getReferralsByReferringProvider(providerId);

        assertEquals(1, responses.size());
        assertEquals(providerId, responses.get(0).getReferringProviderId());
        verify(referralRepository).findByReferringProviderIdOrderByCreatedAtDesc(providerId);
    }

    @Test
    void getReferralsByReceivingProvider_returnsMappedList() {
        UUID providerId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(UUID.randomUUID());
        Staff receivingProvider = buildStaff(providerId, "Dr. Receiving");
        referral.setReceivingProvider(receivingProvider);

        when(referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(providerId)).thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> responses = generalReferralService.getReferralsByReceivingProvider(providerId);

        assertEquals(1, responses.size());
        assertEquals(providerId, responses.get(0).getReceivingProviderId());
        assertEquals("Dr. Receiving", responses.get(0).getReceivingProviderName());
        verify(referralRepository).findByReceivingProviderIdOrderByCreatedAtDesc(providerId);
    }

    @Test
    void getReferralsByHospital_withStatusFilterUsesProperRepository() {
        UUID hospitalId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(UUID.randomUUID());
        referral.setHospital(buildHospital(hospitalId, "Metro Hospital"));
        referral.setStatus(ReferralStatus.SUBMITTED);

        when(referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED))
            .thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> responses = generalReferralService.getReferralsByHospital(hospitalId, "submitted");

        assertEquals(1, responses.size());
        assertEquals(ReferralStatus.SUBMITTED, responses.get(0).getStatus());
        verify(referralRepository).findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED);
    }

    @Test
    void getOverdueReferrals_delegatesToRepositoryAndMapsResults() {
        GeneralReferral overdue = buildReferral(UUID.randomUUID());
        overdue.submit();
        overdue.setSlaDueAt(LocalDateTime.now().minusDays(1));

        when(referralRepository.findOverdueReferrals(any(LocalDateTime.class))).thenReturn(List.of(overdue));

        List<GeneralReferralResponseDTO> responses = generalReferralService.getOverdueReferrals();

        assertEquals(1, responses.size());
        assertEquals(overdue.getId(), responses.get(0).getId());
        assertEquals(Boolean.TRUE, responses.get(0).getIsOverdue());
    }

    private Patient buildPatient(UUID id, String firstName, String lastName) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setEmail(firstName.toLowerCase() + "." + lastName.toLowerCase() + "@example.com");
        patient.setPhoneNumberPrimary("555-1234");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        return patient;
    }

    private Hospital buildHospital(UUID id, String name) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        hospital.setName(name);
        hospital.setCode("HOSP-" + id.toString().substring(0, 8));
        hospital.setPhoneNumber("555-0000");
        return hospital;
    }

    private Staff buildStaff(UUID id, String name) {
        Staff staff = new Staff();
        staff.setId(id);
        staff.setName(name);
        return staff;
    }

    private Department buildDepartment(UUID id, String name) {
        Department department = new Department();
        department.setId(id);
        department.setName(name);
        return department;
    }

    private GeneralReferral buildReferral(UUID referralId) {
        GeneralReferral referral = new GeneralReferral();
        referral.setId(referralId);
        referral.setPatient(buildPatient(UUID.randomUUID(), "John", "Doe"));
        referral.setHospital(buildHospital(UUID.randomUUID(), "Central Hospital"));
        referral.setReferringProvider(buildStaff(UUID.randomUUID(), "Dr. Referrer"));
        referral.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        referral.setReferralType(ReferralType.CONSULTATION);
        referral.setStatus(ReferralStatus.DRAFT);
        referral.setUrgency(ReferralUrgency.PRIORITY);
        referral.setReferralReason("Follow-up care");
        return referral;
    }

    @Test
    void toResponse_mapsReceivingHospitalAndSourceDepartment() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        UUID receivingHospId = UUID.randomUUID();
        UUID sourceDeptId = UUID.randomUUID();
        referral.setReceivingHospital(buildHospital(receivingHospId, "Destination Hospital"));
        referral.setSourceDepartment(buildDepartment(sourceDeptId, "ICU"));
        referral.setCreatedAt(LocalDateTime.now());
        referral.setUpdatedAt(LocalDateTime.now());

        when(referralRepository.findByPatientIdOrderByCreatedAtDesc(referral.getPatient().getId()))
            .thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> results =
            generalReferralService.getReferralsByPatient(referral.getPatient().getId());

        assertEquals(1, results.size());
        GeneralReferralResponseDTO dto = results.get(0);
        assertEquals(receivingHospId, dto.getReceivingHospitalId());
        assertEquals("Destination Hospital", dto.getReceivingHospitalName());
        assertEquals(sourceDeptId, dto.getSourceDepartmentId());
        assertEquals("ICU", dto.getSourceDepartmentName());
    }

    // ── Tenant isolation tests ──

    @Test
    void getReferralsByPatient_scopedToHospital() {
        UUID patientId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, activeHospId))
            .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = generalReferralService.getReferralsByPatient(patientId);
        assertThat(result).isEmpty();
        verify(referralRepository).findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, activeHospId);
    }

    @Test
    void getReferralsByReferringProvider_scopedToHospital() {
        UUID providerId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospId))
            .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = generalReferralService.getReferralsByReferringProvider(providerId);
        assertThat(result).isEmpty();
        verify(referralRepository).findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospId);
    }

    @Test
    void getReferralsByReceivingProvider_scopedToHospital() {
        UUID providerId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospId))
            .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = generalReferralService.getReferralsByReceivingProvider(providerId);
        assertThat(result).isEmpty();
        verify(referralRepository).findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospId);
    }

    @Test
    void getReferralsByHospital_scopedOverridesHospitalParam() {
        UUID paramHospitalId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(activeHospId))
            .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = generalReferralService.getReferralsByHospital(paramHospitalId, null);
        assertThat(result).isEmpty();
        verify(referralRepository).findByHospitalIdOrderByCreatedAtDesc(activeHospId);
    }

    @Test
    void getReferralsByHospital_scopedWithStatus() {
        UUID activeHospId = UUID.randomUUID();        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(activeHospId, ReferralStatus.SUBMITTED))
            .thenReturn(List.of());

        List<GeneralReferralResponseDTO> result = generalReferralService.getReferralsByHospital(UUID.randomUUID(), "submitted");
        assertThat(result).isEmpty();
        verify(referralRepository).findByHospitalIdAndStatusOrderByCreatedAtDesc(activeHospId, ReferralStatus.SUBMITTED);
    }

    @Test
    void getReferralsByHospital_includesIncomingReferrals() {
        UUID hospitalId = UUID.randomUUID();
        UUID incomingReferralId = UUID.randomUUID();

        GeneralReferral incoming = buildReferral(incomingReferralId);
        incoming.setStatus(ReferralStatus.SUBMITTED);
        incoming.setReceivingHospital(buildHospital(hospitalId, "My Hospital"));

        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of(incoming));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(hospitalId, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(incomingReferralId);
        verify(referralRepository).findByReceivingHospitalIdOrderByCreatedAtDesc(hospitalId);
    }

    @Test
    void getReferralsByHospital_deduplicatesReferralPresentInBothOutgoingAndIncoming() {
        UUID hospitalId = UUID.randomUUID();
        UUID sharedReferralId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(sharedReferralId);
        referral.setStatus(ReferralStatus.SUBMITTED);
        referral.setCreatedAt(LocalDateTime.now());

        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of(referral));
        when(referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(hospitalId, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(sharedReferralId);
    }

    @Test
    void getReferralsByHospital_scopedIncludesIncomingViaReceivingHospitalQuery() {
        UUID activeHospId = UUID.randomUUID();
        UUID incomingReferralId = UUID.randomUUID();

        GeneralReferral incoming = buildReferral(incomingReferralId);
        incoming.setStatus(ReferralStatus.SUBMITTED);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(activeHospId)).thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(activeHospId)).thenReturn(List.of(incoming));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(UUID.randomUUID(), null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(incomingReferralId);
        verify(referralRepository).findByReceivingHospitalIdOrderByCreatedAtDesc(activeHospId);
    }

    @Test
    void getReferralsByHospital_excludesDraftIncomingReferrals() {
        UUID hospitalId = UUID.randomUUID();
        UUID draftIncomingId = UUID.randomUUID();

        GeneralReferral draftIncoming = buildReferral(draftIncomingId);
        // status defaults to DRAFT — should be filtered from incoming

        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(hospitalId)).thenReturn(List.of(draftIncoming));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(hospitalId, null);

        assertThat(results).isEmpty();
    }

    // ── getReferralsByHospital incoming + status ─────────────────────────

    @Test
    void getReferralsByHospital_statusFilter_includesIncomingReferrals() {
        UUID hospitalId = UUID.randomUUID();
        UUID incomingId = UUID.randomUUID();

        GeneralReferral incoming = buildReferral(incomingId);
        incoming.setStatus(ReferralStatus.SUBMITTED);

        when(referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED))
            .thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED))
            .thenReturn(List.of(incoming));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(hospitalId, "submitted");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(incomingId);
        verify(referralRepository).findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, ReferralStatus.SUBMITTED);
    }

    @Test
    void getReferralsByHospital_scopedStatusFilter_includesIncomingReferrals() {
        UUID activeHospId = UUID.randomUUID();
        UUID incomingId = UUID.randomUUID();

        GeneralReferral incoming = buildReferral(incomingId);
        incoming.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(activeHospId, ReferralStatus.ACKNOWLEDGED))
            .thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(activeHospId, ReferralStatus.ACKNOWLEDGED))
            .thenReturn(List.of(incoming));

        List<GeneralReferralResponseDTO> results = generalReferralService.getReferralsByHospital(UUID.randomUUID(), "acknowledged");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(incomingId);
        verify(referralRepository).findByReceivingHospitalIdAndStatusOrderByCreatedAtDesc(activeHospId, ReferralStatus.ACKNOWLEDGED);
    }

    // ── getAllReferrals ───────────────────────────────────────────────────

    @Test
    void getAllReferrals_unscopedNoStatus_delegatesToFindAll() {
        GeneralReferral referral = buildReferral(UUID.randomUUID());

        when(referralRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> results = generalReferralService.getAllReferrals(null);

        assertThat(results).hasSize(1);
        verify(referralRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void getAllReferrals_unscopedWithStatus_delegatesToFindByStatus() {
        GeneralReferral referral = buildReferral(UUID.randomUUID());
        referral.setStatus(ReferralStatus.SUBMITTED);

        when(referralRepository.findByStatusOrderByCreatedAtDesc(ReferralStatus.SUBMITTED))
            .thenReturn(List.of(referral));

        List<GeneralReferralResponseDTO> results = generalReferralService.getAllReferrals("submitted");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(ReferralStatus.SUBMITTED);
        verify(referralRepository).findByStatusOrderByCreatedAtDesc(ReferralStatus.SUBMITTED);
    }

    @Test
    void getAllReferrals_scoped_delegatesToGetReferralsByHospital() {
        UUID activeHospId = UUID.randomUUID();

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findByHospitalIdOrderByCreatedAtDesc(activeHospId)).thenReturn(List.of());
        when(referralRepository.findByReceivingHospitalIdOrderByCreatedAtDesc(activeHospId)).thenReturn(List.of());

        List<GeneralReferralResponseDTO> results = generalReferralService.getAllReferrals(null);

        assertThat(results).isEmpty();
        verify(referralRepository).findByHospitalIdOrderByCreatedAtDesc(activeHospId);
    }

    // ── getOverdueReferrals scoped ───────────────────────────────────────

    @Test
    void getOverdueReferrals_scopedDelegatesToHospitalRepository() {
        UUID activeHospId = UUID.randomUUID();
        GeneralReferral overdue = buildReferral(UUID.randomUUID());
        overdue.submit();
        overdue.setSlaDueAt(LocalDateTime.now().minusDays(1));

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findOverdueReferralsByHospital(any(UUID.class), any(LocalDateTime.class)))
            .thenReturn(List.of(overdue));

        List<GeneralReferralResponseDTO> results = generalReferralService.getOverdueReferrals();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getIsOverdue()).isTrue();
        verify(referralRepository).findOverdueReferralsByHospital(any(UUID.class), any(LocalDateTime.class));
    }

    // ── findReferral access-control conditions ───────────────────────────

    @Test
    void findReferral_scopedUserIsSendingHospital_allowsAccess() {
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(buildHospital(activeHospId, "Sending Hospital"));
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        // completeReferral calls findReferral internally
        GeneralReferralResponseDTO result = generalReferralService.completeReferral(referralId, "Done", null);

        assertNotNull(result);
        assertEquals(referralId, result.getId());
    }

    @Test
    void findReferral_scopedUserIsReceivingHospital_allowsAccess() {
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();
        UUID otherHospId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(buildHospital(otherHospId, "Sending Hospital"));
        referral.setReceivingHospital(buildHospital(activeHospId, "Receiving Hospital"));
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO result = generalReferralService.completeReferral(referralId, "Done", null);

        assertNotNull(result);
    }

    @Test
    void findReferral_scopedUserIsNeitherHospital_throwsNotFound() {
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();
        UUID sendingHospId = UUID.randomUUID();
        UUID receivingHospId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(buildHospital(sendingHospId, "Sending Hospital"));
        referral.setReceivingHospital(buildHospital(receivingHospId, "Receiving Hospital"));

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        assertThrows(ResourceNotFoundException.class,
            () -> generalReferralService.completeReferral(referralId, "Done", null));
    }

    @Test
    void findReferral_scopedHospitalNullButReceivingMatches_allowsAccess() {
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(null);  // hospital is null — isSendingHospital evaluates to false safely
        referral.setReceivingHospital(buildHospital(activeHospId, "Receiving Hospital"));
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO result = generalReferralService.completeReferral(referralId, "Done", null);

        assertNotNull(result);
    }

    @Test
    void findReferral_scopedBothHospitalsNullThrowsNotFound() {
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();

        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(null);
        referral.setReceivingHospital(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        assertThrows(ResourceNotFoundException.class,
            () -> generalReferralService.completeReferral(referralId, "Done", null));
    }

    @Test
    void getReferralEventsReturnsChronologicalTimeline() {
        // Cover the new getReferralEvents path + the toEventResponse mapper:
        // findReferral validates scope first, then the repository is queried in
        // chronological order, and each row is mapped to a DTO with all fields.
        UUID referralId = UUID.randomUUID();
        UUID activeHospId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setHospital(buildHospital(activeHospId, "Active Hospital"));
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));

        com.example.hms.model.ReferralEvent submit = com.example.hms.model.ReferralEvent.builder()
            .id(UUID.randomUUID())
            .referralId(referralId)
            .eventType(com.example.hms.enums.ReferralEventType.SUBMIT)
            .fromStatus(ReferralStatus.DRAFT)
            .toStatus(ReferralStatus.SUBMITTED)
            .actorUsername("dr.amy@hms.test")
            .actorLabel("USER")
            .note(null)
            .recordedAt(LocalDateTime.of(2026, 5, 1, 10, 0))
            .build();
        com.example.hms.model.ReferralEvent expire = com.example.hms.model.ReferralEvent.builder()
            .id(UUID.randomUUID())
            .referralId(referralId)
            .eventType(com.example.hms.enums.ReferralEventType.EXPIRE)
            .fromStatus(ReferralStatus.SUBMITTED)
            .toStatus(ReferralStatus.EXPIRED)
            .actorUsername(null)
            .actorLabel("SYSTEM:scheduler")
            .note("auto-expired")
            .recordedAt(LocalDateTime.of(2026, 5, 1, 11, 0))
            .build();
        when(eventRepository.findByReferralIdOrderByRecordedAtAsc(referralId))
            .thenReturn(java.util.List.of(submit, expire));

        java.util.List<com.example.hms.payload.dto.referral.ReferralEventResponseDTO> result =
            generalReferralService.getReferralEvents(referralId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventType())
            .isEqualTo(com.example.hms.enums.ReferralEventType.SUBMIT);
        assertThat(result.get(0).getActorLabel()).isEqualTo("USER");
        assertThat(result.get(0).getActorUsername()).isEqualTo("dr.amy@hms.test");
        assertThat(result.get(0).getFromStatus()).isEqualTo(ReferralStatus.DRAFT);
        assertThat(result.get(0).getToStatus()).isEqualTo(ReferralStatus.SUBMITTED);
        assertThat(result.get(1).getEventType())
            .isEqualTo(com.example.hms.enums.ReferralEventType.EXPIRE);
        assertThat(result.get(1).getActorLabel()).isEqualTo("SYSTEM:scheduler");
        assertThat(result.get(1).getActorUsername()).isNull();
        assertThat(result.get(1).getNote()).isEqualTo("auto-expired");
        assertThat(result.get(1).getRecordedAt()).isEqualTo(LocalDateTime.of(2026, 5, 1, 11, 0));
    }

    @Test
    void getReferralEventsThrowsWhenReferralOutOfScope() {
        // The events list must not leak — findReferral runs first, so a missing
        // referral never even hits the events repository.
        UUID referralId = UUID.randomUUID();
        when(referralRepository.findById(referralId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> generalReferralService.getReferralEvents(referralId));

        // Critically: the events repo must NOT be hit when the parent is missing.
        verify(eventRepository, never()).findByReferralIdOrderByRecordedAtAsc(any(UUID.class));
    }

    // ── Referral -> Appointment linkage (P2 #12) ──────────────────────────
    // Scheduling stored scheduledAppointmentAt plus a free-text location and
    // created no Appointment row, so a "booked" referral was invisible to the
    // receiving provider's calendar, reception check-in, the V112 reminder
    // sweep and utilisation reporting. The referral said booked; the schedule
    // disagreed.

    @Test
    void scheduleReferral_createsAndLinksAnAppointmentWhenTheTargetIsKnown() {
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        com.example.hms.model.UserRoleHospitalAssignment assignment =
            com.example.hms.model.UserRoleHospitalAssignment.builder().build();
        assignment.setId(UUID.randomUUID());
        com.example.hms.model.Staff provider = com.example.hms.model.Staff.builder()
            .assignment(assignment)
            .build();
        provider.setId(UUID.randomUUID());
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(UUID.randomUUID());

        referral.setReceivingProvider(provider);
        referral.setTargetDepartment(department);

        LocalDateTime appointmentTime = LocalDateTime.now().plusDays(5).withHour(9).withMinute(0);
        com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO request =
            com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO.builder()
                .appointmentTime(appointmentTime)
                .location("Clinic 7")
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);
        when(appointmentRepository.save(any(com.example.hms.model.Appointment.class)))
            .thenAnswer(invocation -> {
                com.example.hms.model.Appointment saved = invocation.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });

        GeneralReferralResponseDTO response =
            generalReferralService.scheduleReferral(referralId, request);

        org.mockito.ArgumentCaptor<com.example.hms.model.Appointment> captor =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.model.Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        com.example.hms.model.Appointment created = captor.getValue();

        assertEquals(appointmentTime.toLocalDate(), created.getAppointmentDate());
        assertEquals(appointmentTime.toLocalTime(), created.getStartTime());
        assertEquals(com.example.hms.enums.AppointmentStatus.SCHEDULED, created.getStatus());
        assertEquals(provider, created.getStaff());
        assertEquals(department, created.getDepartment());
        assertNotNull(response.getAppointmentId());
        assertEquals(created.getId(), referral.getAppointment().getId());
    }

    @Test
    void scheduleReferral_withoutAReceivingProviderStillSchedules() {
        // The commonest referral there is: out to an external facility, no known
        // provider or department. Appointment requires staff, department AND
        // assignment, all NOT NULL — so refusing to schedule here would break
        // the majority case to serve the minority one.
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);
        referral.setReceivingProvider(null);
        referral.setTargetDepartment(null);

        LocalDateTime appointmentTime = LocalDateTime.now().plusDays(3);
        com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO request =
            com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO.builder()
                .appointmentTime(appointmentTime)
                .location("St Mary's, external")
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);

        GeneralReferralResponseDTO response =
            generalReferralService.scheduleReferral(referralId, request);

        assertEquals(ReferralStatus.SCHEDULED, response.getStatus());
        assertEquals("St Mary's, external", referral.getAppointmentLocation());
        assertNull(response.getAppointmentId());
        verify(appointmentRepository, org.mockito.Mockito.never())
            .save(any(com.example.hms.model.Appointment.class));
    }

    @Test
    void scheduleReferral_appointmentFailureDoesNotBlockTheReferral() {
        // The referral is the clinical record; the appointment is a convenience
        // built on top of it. Losing the convenience must not lose the record.
        UUID referralId = UUID.randomUUID();
        GeneralReferral referral = buildReferral(referralId);
        referral.setStatus(ReferralStatus.ACKNOWLEDGED);

        com.example.hms.model.UserRoleHospitalAssignment assignment =
            com.example.hms.model.UserRoleHospitalAssignment.builder().build();
        assignment.setId(UUID.randomUUID());
        com.example.hms.model.Staff provider = com.example.hms.model.Staff.builder()
            .assignment(assignment)
            .build();
        provider.setId(UUID.randomUUID());
        com.example.hms.model.Department department = new com.example.hms.model.Department();
        department.setId(UUID.randomUUID());
        referral.setReceivingProvider(provider);
        referral.setTargetDepartment(department);

        com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO request =
            com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO.builder()
                .appointmentTime(LocalDateTime.now().plusDays(2))
                .build();

        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralRepository.save(referral)).thenReturn(referral);
        when(appointmentRepository.save(any(com.example.hms.model.Appointment.class)))
            .thenThrow(new RuntimeException("slot conflict"));

        GeneralReferralResponseDTO response =
            generalReferralService.scheduleReferral(referralId, request);

        assertEquals(ReferralStatus.SCHEDULED, response.getStatus());
        assertNull(response.getAppointmentId());
    }
}
