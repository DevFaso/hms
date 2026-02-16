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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneralReferralServiceImplTest {

    @Mock
    private GeneralReferralRepository referralRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private DepartmentRepository departmentRepository;

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

        Patient patient = buildPatient(patientId, "Alice", "Smith");
        Hospital hospital = buildHospital(hospitalId, "Metro Hospital");
        Staff referringProvider = buildStaff(referringProviderId, "Dr. Referrer");
        Staff receivingProvider = buildStaff(receivingProviderId, "Dr. Receiver");
        Department department = buildDepartment(departmentId, "Cardiology");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(referringProviderId)).thenReturn(Optional.of(referringProvider));
        when(staffRepository.findById(receivingProviderId)).thenReturn(Optional.of(receivingProvider));
        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
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

        ArgumentCaptor<GeneralReferral> captor = ArgumentCaptor.forClass(GeneralReferral.class);
        verify(referralRepository).save(captor.capture());
        GeneralReferral saved = captor.getValue();
        assertEquals(ReferralUrgency.EMERGENCY, saved.getUrgency());
        assertEquals("Chest pain evaluation", saved.getReferralReason());
        assertEquals("Cardiac cath", saved.getAnticipatedTreatment());
        assertEquals("AUTH-123", saved.getInsuranceAuthNumber());
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
}
